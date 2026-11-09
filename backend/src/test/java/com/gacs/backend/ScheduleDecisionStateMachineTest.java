package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug 3: a plan may carry exactly one terminal decision (APPROVED/REJECTED/REVISION_REQUESTED).
 * {@code ScheduleReviewService#decide} now refuses every further decision for a run once it holds
 * one, rather than letting a later REJECT silently undo an earlier APPROVE (or vice versa) on the
 * same run - see docs/api-contract.md section 6.5, "One terminal decision per run".
 *
 * <p>This intentionally narrows behavior that {@code ScheduleDecisionIntegrationTest} and
 * {@code ScheduleEnforcementIntegrationTest} previously exercised as a feature (repeated decisions,
 * including using REJECT/REVISE after APPROVE to retroactively halt a dispatched run); see
 * REPORT.md for the specific tests left failing by this change and why they were not altered.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleDecisionStateMachineTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired AuditEventRepository auditEvents;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<String> created = new ArrayList<>();
    private User owner;
    private Job job;
    private LocalDateTime start;

    @BeforeEach
    void setup() {
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
        start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS);
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Train").cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(3)).flexibilityWindowHours(1).build());
    }

    @AfterEach
    void cleanup() {
        for (String run : created) { plans.deleteById(run); runs.deleteById(run); }
        jobs.deleteById(job.getId());
        users.deleteById(owner.getId());
    }

    private String optimize() throws Exception {
        var forecast = List.of(Map.of("intervalStartUtc", start.toString(), "predictedPrice", 100),
            Map.of("intervalStartUtc", start.plusHours(1).toString(), "predictedPrice", 10),
            Map.of("intervalStartUtc", start.plusHours(2).toString(), "predictedPrice", 50));
        var jobBody = Map.of("id", job.getId(), "name", job.getName(), "cpuHours", job.getCpuHours(),
            "gpuHours", job.getGpuHours(), "originalStartTime", job.getOriginalStartTime().toString(),
            "deadline", job.getDeadline().toString(), "flexibilityWindowHours", job.getFlexibilityWindowHours(),
            "isExempt", job.isExempt());
        String result = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("jobs", List.of(jobBody), "forecast", forecast))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String run = json.readTree(result).path("data").path("runId").asText();
        created.add(run);
        return run;
    }

    private ResultActions decide(String run, String action) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/" + action).with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "revise", "approve"})
    void reDecidingAnApprovedRunIsRefused(String secondAction) throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        long auditCountAfterApprove = auditEvents.findByRunIdOrderByTimestampDesc(run).size();

        decide(run, secondAction).andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"))
            // The refusal points at the one move that is still open, so an operator who meant to halt
            // the schedule is not left thinking it cannot be stopped at all.
            .andExpect(jsonPath("$.message").value("Schedule run " + run
                + " is already approved and cannot be decided again. Revoke the approval instead to halt it."));

        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
        assertThat(auditEvents.findByRunIdOrderByTimestampDesc(run)).hasSize((int) auditCountAfterApprove);
    }

    @Test
    void revokingAnApprovedRunIsTheOneTransitionLeft() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());

        decide(run, "revoke").andExpect(status().isOk());

        // Revocation lands in REJECTED so every existing execution gate halts the schedule.
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.REJECTED);
        assertThat(auditEvents.findByRunIdOrderByTimestampDesc(run)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "revise"})
    void revokingSomethingNeverApprovedIsRefused(String firstAction) throws Exception {
        String run = optimize();
        decide(run, firstAction).andExpect(status().isOk());

        // A rejected or revision-requested run is final, so it is the terminal guard that refuses
        // this, not the revoke-specific one: there is nothing dispatched from it to halt.
        decide(run, "revoke").andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                "cannot be decided again")));
    }

    @Test
    void revokingAPendingRunIsRefused() throws Exception {
        String run = optimize();

        decide(run, "revoke").andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                "is pending, not approved, so there is no approval to revoke")));
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void anApprovalCannotBeRevokedTwice() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        decide(run, "revoke").andExpect(status().isOk());

        decide(run, "revoke").andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"));
        assertThat(auditEvents.findByRunIdOrderByTimestampDesc(run)).hasSize(2);
    }

    @Test
    void reDecidingARejectedRunIsRefused() throws Exception {
        String run = optimize();
        decide(run, "reject").andExpect(status().isOk());
        decide(run, "approve").andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"));
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.REJECTED);
    }

    @Test
    void reDecidingARevisionRequestedRunIsRefused() throws Exception {
        String run = optimize();
        decide(run, "revise").andExpect(status().isOk());
        decide(run, "approve").andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"));
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.REVISION_REQUESTED);
    }

    @Test
    void aFreshPendingRunCanStillBeDecided() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.action").value(AuditActionType.APPROVE.name()));
    }
}
