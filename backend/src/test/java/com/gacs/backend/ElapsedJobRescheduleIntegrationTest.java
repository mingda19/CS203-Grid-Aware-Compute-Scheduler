package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.FacilityRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.LinkedHashMap;
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
 * The elapsed-job option end to end: optimise, approve, apply.
 *
 * <p>This is the half that unit tests of {@link com.gacs.backend.service.allocation.JobWindow} cannot
 * reach. Re-anchoring places a job outside the flexibility window its {@code jobs} row still carries,
 * and {@code ScheduleGate.requireJobsUnchanged} re-derives {@code latestAllowedStart()} from that row
 * every time a plan is approved, applied or exported. Without teaching the gate about the marker on
 * the item, the feature would appear to work - the optimiser would return a plan - and then every such
 * plan would be refused as invalidated at the moment the operator tried to use it, citing a window
 * they had explicitly chosen to set aside. So the thing worth testing is not that a plan comes back;
 * it is that the plan can be approved and applied.
 *
 * <p>Deliberately on the real clock, like {@code ScheduleExportIntegrationTest}: the whole subject is
 * a start time that has passed relative to now, and the fixtures are derived from the clock rather
 * than from absolute dates, so there is nothing for a pinned clock to make truer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ElapsedJobRescheduleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired com.gacs.backend.service.AppSettingsService appSettings;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired FacilityRepository facilities;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired WorkloadScheduleRepository schedules;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<String> created = new ArrayList<>();
    private User owner;
    private Job job;
    /** The current hour, truncated: exactly what the optimiser treats as its earliest usable slot. */
    private LocalDateTime now;

    @BeforeEach
    void setup() {
        appSettings.update(true);
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
        now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        // Saved through the repository on purpose: JobService refuses a start time in the past, which
        // is right for new jobs, but this job's start passed while it sat in the queue - which is the
        // situation being tested and exactly how the operator's eleven jobs got into this state.
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Overdue training")
            .cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(now.minusHours(2))
            .deadline(now.plusHours(5))
            .flexibilityWindowHours(1)          // so [start, start + flex] ends an hour before now
            .build());
    }

    @AfterEach
    void cleanup() {
        schedules.deleteAll(schedules.findByOwnerEmailOrderByStartUtcAsc(owner.getEmail()));
        for (String run : created) { plans.deleteById(run); runs.deleteById(run); }
        jobs.deleteAll(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(owner.getEmail()));
        facilities.deleteAll(); // one shared facility: reset it for the next test class
        users.deleteById(owner.getId());
    }

    @Test
    @DisplayName("Without the opt-in an elapsed job is still reported NO_FEASIBLE_SLOT, as before")
    void withoutTheOptInTheJobIsStillLeftOut() throws Exception {
        String body = optimize(false).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].perJobStatus").value("NO_FEASIBLE_SLOT"))
            .andExpect(jsonPath("$.data.items[0].scheduledStart").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].rescheduledFromElapsedStart").value(false))
            .andReturn().getResponse().getContentAsString();
        created.add(json.readTree(body).path("data").path("runId").asText());

        assertThat(json.readTree(body).path("data").path("items").get(0).path("reason").asText())
            .contains("Window elapsed");
    }

    @Test
    @DisplayName("With the opt-in the job is placed from the current hour, marked, and can be approved and applied")
    void withTheOptInTheJobIsPlacedApprovedAndApplied() throws Exception {
        String body = optimize(true).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.data.items[0].perJobStatus").value("OPTIMISED"))
            // The marker the UI needs to say this job moved because its start had passed, rather
            // than to find a cheaper hour.
            .andExpect(jsonPath("$.data.items[0].rescheduledFromElapsedStart").value(true))
            .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).path("data");
        String run = data.path("runId").asText();
        created.add(run);

        LocalDateTime scheduledStart = LocalDateTime.parse(
            data.path("items").get(0).path("scheduledStart").asText().replace("Z", ""));
        // The invariant, asserted on a real response and not only on a JobWindow: nothing is placed
        // before the current hour, even though the window was widened.
        assertThat(scheduledStart).isAfterOrEqualTo(now);
        // And it is genuinely outside the window the job row still carries, which is the point.
        assertThat(scheduledStart).isAfter(job.latestAllowedStart());
        assertThat(LocalDateTime.parse(data.path("items").get(0).path("scheduledEnd").asText().replace("Z", "")))
            .isBeforeOrEqualTo(job.getDeadline());

        // The gate must accept it, or the feature produces plans nobody can use.
        mvc.perform(post("/api/schedules/" + run + "/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"Start had passed; rescheduling confirmed\"}"))
            .andExpect(status().isOk());
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);

        mvc.perform(post("/api/schedules/" + run + "/apply").with(user(owner.getEmail())))
            .andExpect(status().isOk());
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).hasSize(1);
        assertThat(plans.findById(run).orElseThrow().getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("The explanation says these jobs moved because their start had passed, and caveats their savings")
    void theExplanationNamesTheRescheduledJobs() throws Exception {
        String body = optimize(true).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        created.add(json.readTree(body).path("data").path("runId").asText());

        assertThat(json.readTree(body).path("data").path("plainEnglishExplanation").asText())
            .contains("1 job(s) had a start time that has already passed")
            .contains("savings are measured against a baseline that itself already partly elapsed");
    }

    /**
     * A job whose deadline has also gone stays infeasible whatever the flag says: there is no window
     * left to widen, so the opt-in must not be able to resurrect it.
     */
    @Test
    @DisplayName("A job whose deadline has also passed stays infeasible with the opt-in on")
    void aPassedDeadlineIsNotRescued() throws Exception {
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Long overdue")
            .cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(now.minusHours(6))
            .deadline(now.minusHours(2))
            .flexibilityWindowHours(1)
            .build());

        String body = optimize(true).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("INFEASIBLE"))
            .andExpect(jsonPath("$.data.items[0].rescheduledFromElapsedStart").value(false))
            .andReturn().getResponse().getContentAsString();
        created.add(json.readTree(body).path("data").path("runId").asText());

        assertThat(json.readTree(body).path("data").path("items").get(0).path("reason").asText())
            .contains("has passed");
    }

    /**
     * An exempt job is pinned to its original start, so the opt-in must leave it alone.
     *
     * <p>Note what this does <em>not</em> assert. An exempt job keeps {@code UNCHANGED_EXEMPT} and its
     * baseline plan even when that baseline sits in the past: {@code AllocationResult.of} gives every
     * exempt job its baseline unconditionally, which is pre-existing behaviour and out of scope here.
     * What matters for the opt-in is only that the job was not moved - {@code
     * rescheduledFromElapsedStart} is false and the schedule still starts it at its original time.
     */
    @Test
    @DisplayName("An exempt job with an elapsed start is not rescheduled by the opt-in")
    void anExemptJobIsNotRescheduled() throws Exception {
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Pinned nightly ETL")
            .cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(now.minusHours(2))
            .deadline(now.plusHours(5))
            .flexibilityWindowHours(1)
            .isExempt(true)
            .build());

        String body = optimize(true).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].perJobStatus").value("UNCHANGED_EXEMPT"))
            .andExpect(jsonPath("$.data.items[0].rescheduledFromElapsedStart").value(false))
            .andReturn().getResponse().getContentAsString();
        created.add(json.readTree(body).path("data").path("runId").asText());

        // Still at its original time, untouched by the widening.
        assertThat(json.readTree(body).path("data").path("items").get(0).path("scheduledStart").asText())
            .startsWith(now.minusHours(2).toString());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions optimize(boolean rescheduleElapsedJobs) throws Exception {
        var current = jobs.findById(job.getId()).orElseThrow();
        Map<String, Object> jobBody = new LinkedHashMap<>();
        jobBody.put("id", current.getId());
        jobBody.put("name", current.getName());
        jobBody.put("cpuHours", current.getCpuHours());
        jobBody.put("gpuHours", current.getGpuHours());
        jobBody.put("originalStartTime", current.getOriginalStartTime().toString());
        jobBody.put("deadline", current.getDeadline().toString());
        jobBody.put("flexibilityWindowHours", current.getFlexibilityWindowHours());
        jobBody.put("isExempt", current.isExempt());
        jobBody.put("priority", current.getPriority().name());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jobs", List.of(jobBody));
        // Every hour from the job's own start (its baseline must be priceable, and that baseline
        // legitimately covers elapsed hours) to its deadline. The hour two after `now` is cheapest,
        // so a placed job provably chose a remaining hour rather than falling on the first one free.
        request.put("forecast", forecast(current.getOriginalStartTime(), current.getDeadline()));
        request.put("rescheduleElapsedJobs", rescheduleElapsedJobs);

        return mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private List<Map<String, Object>> forecast(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDateTime slot = from.truncatedTo(ChronoUnit.HOURS); !slot.isAfter(to); slot = slot.plusHours(1)) {
            points.add(Map.of("intervalStartUtc", slot.toString(),
                "predictedPrice", slot.equals(now.plusHours(2)) ? 1.0 : 100.0));
        }
        return points;
    }
}
