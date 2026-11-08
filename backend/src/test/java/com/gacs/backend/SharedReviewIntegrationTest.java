package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.AppSettingsService;
import com.gacs.backend.service.ScheduleApplicationService;
import com.gacs.backend.service.ScheduleGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Shared review (anyone but the submitter approves, an admin can allow self-approval) and the rules for
 * approved jobs (delete leaves the rest of the plan approved, edit is refused).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharedReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppSettingsService settings;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired FacilityRepository facilities;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired WorkloadScheduleRepository schedules;
    @Autowired AuditEventRepository audit;
    @Autowired ScheduleApplicationService applications;
    @Autowired ScheduleGate gate;
    @Autowired PlatformTransactionManager manager;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdRuns = new ArrayList<>();
    private User owner;
    private User approver;
    private User admin;
    private LocalDateTime start;

    @BeforeEach
    void setup() {
        settings.update(false);
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
        approver = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Approver", "ROLE_USER"));
        admin = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Admin", "ROLE_ADMIN"));
        start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS);
    }

    @AfterEach
    void cleanup() {
        for (String run : createdRuns) {
            schedules.deleteAll(schedules.findByRunIdOrderByIdAsc(run));
            plans.deleteById(run);
            runs.deleteById(run);
        }
        for (User u : List.of(owner, approver, admin)) {
            jobs.deleteAll(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(u.getEmail()));
            users.deleteById(u.getId());
        }
        facilities.deleteAll(); // one shared facility: reset it for the next test class
        settings.update(false);
    }

    // ------------------------------------------------------------------ helpers

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(User who) {
        return user(who.getEmail()).roles(who.getRole().replace("ROLE_", ""));
    }

    private Job job(User who, String name, double cpu) {
        return jobs.save(Job.builder().ownerEmail(who.getEmail()).name(name).cpuHours(cpu).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(3)).flexibilityWindowHours(1).build());
    }

    private Map<String, Object> body(Job j) {
        j = jobs.findById(j.getId()).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId()); m.put("name", j.getName()); m.put("cpuHours", j.getCpuHours());
        m.put("gpuHours", j.getGpuHours()); m.put("originalStartTime", j.getOriginalStartTime().toString());
        m.put("deadline", j.getDeadline().toString()); m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
        m.put("isExempt", j.isExempt()); m.put("priority", j.getPriority().name());
        m.put("workloadCategory", j.getWorkloadCategory().name());
        return m;
    }

    private String optimize(User who, Job... list) throws Exception {
        var forecast = List.of(Map.of("intervalStartUtc", start.toString(), "predictedPrice", 100),
            Map.of("intervalStartUtc", start.plusHours(1).toString(), "predictedPrice", 10),
            Map.of("intervalStartUtc", start.plusHours(2).toString(), "predictedPrice", 50));
        String result = mvc.perform(post("/api/schedules/optimize").with(as(who)).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("jobs", Arrays.stream(list).map(this::body).toList(), "forecast", forecast))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String run = json.readTree(result).path("data").path("runId").asText();
        createdRuns.add(run);
        return run;
    }

    private ResultActions decide(User who, String run, String action) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/" + action).with(as(who))
            .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"));
    }

    private ResultActions apply(User who, String run) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/apply").with(as(who)));
    }

    private ResultActions edit(User who, Job j, Map<String, Object> changed) throws Exception {
        return mvc.perform(put("/api/jobs/" + j.getId()).with(as(who)).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(changed)));
    }

    private ReviewState stateOf(String run) {
        return plans.findById(run).orElseThrow().getReviewState();
    }

    private JsonNode detail(User who, String run) throws Exception {
        return json.readTree(mvc.perform(get("/api/schedules/" + run).with(as(who))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode item(JsonNode detail, long jobId) {
        for (JsonNode item : detail.path("recommendation").path("items"))
            if (item.path("jobId").asLong() == jobId) return item;
        throw new AssertionError("job " + jobId + " is not in the plan");
    }

    // ------------------------------------------------------------------ who may approve

    @Test
    @DisplayName("another user can approve a plan, and the decision records them, not the submitter")
    void anotherUserCanApprove() throws Exception {
        String run = optimize(owner, job(owner, "Train", 4.0));
        decide(approver, run, "approve").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operatorId").value(approver.getId()))
            .andExpect(jsonPath("$.data.reviewState").value("APPROVED"));
        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);
        assertThat(audit.findByRunIdOrderByTimestampDesc(run)).extracting(AuditEvent::getActor)
            .containsExactly(approver.getEmail());
    }

    @Test
    @DisplayName("the submitter cannot approve their own plan, and nothing is recorded")
    void theSubmitterCannotApproveTheirOwnPlan() throws Exception {
        String run = optimize(owner, job(owner, "Train", 4.0));
        decide(owner, run, "approve").andExpect(status().isForbidden());
        assertThat(stateOf(run)).isEqualTo(ReviewState.PENDING);
        assertThat(audit.findByRunIdOrderByTimestampDesc(run)).isEmpty();
    }

    @Test
    @DisplayName("an admin can approve their own plan")
    void anAdminCanApproveTheirOwnPlan() throws Exception {
        String run = optimize(admin, job(admin, "Train", 4.0));
        decide(admin, run, "approve").andExpect(status().isOk());
        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);
    }

    @Test
    @DisplayName("with self-approval allowed the submitter can approve their own plan")
    void theSettingLetsTheSubmitterApprove() throws Exception {
        mvc.perform(put("/api/admin/settings").with(as(admin)).contentType(APPLICATION_JSON)
                .content("{\"allowSelfApproval\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowSelfApproval").value(true));
        String run = optimize(owner, job(owner, "Train", 4.0));
        decide(owner, run, "approve").andExpect(status().isOk());
        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);
    }

    @Test
    @DisplayName("the settings are admin only")
    void settingsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/admin/settings").with(as(admin))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allowSelfApproval").value(false));
        mvc.perform(get("/api/admin/settings").with(as(owner))).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/settings").with(as(owner)).contentType(APPLICATION_JSON)
            .content("{\"allowSelfApproval\":true}")).andExpect(status().isForbidden());
        assertThat(settings.get().isAllowSelfApproval()).isFalse();
    }

    @Test
    @DisplayName("the submitter can still reject, ask for a revision and revoke")
    void theSelfApprovalRuleBlocksApproveOnly() throws Exception {
        String rejected = optimize(owner, job(owner, "Train", 4.0));
        decide(owner, rejected, "reject").andExpect(status().isOk());
        assertThat(stateOf(rejected)).isEqualTo(ReviewState.REJECTED);
        String revise = optimize(owner, job(owner, "Eval", 2.0));
        decide(owner, revise, "revise").andExpect(status().isOk());
        String approved = optimize(owner, job(owner, "Score", 2.0));
        decide(approver, approved, "approve").andExpect(status().isOk());
        decide(owner, approved, "revoke").andExpect(status().isOk());
        assertThat(stateOf(approved)).isEqualTo(ReviewState.REJECTED);
    }

    @Test
    @DisplayName("an approver reading or approving a plan does not invalidate it")
    void anApproversCheckDoesNotInvalidateAGoodPlan() throws Exception {
        job(approver, "Approver's own job", 1.0);
        String run = optimize(owner, job(owner, "Train", 4.0));
        JsonNode read = detail(approver, run);
        assertThat(read.path("reviewState").asText()).isEqualTo("PENDING");
        assertThat(read.path("invalidationReason").isNull()).isTrue();
        decide(approver, run, "approve").andExpect(status().isOk());
        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);
    }

    @Test
    @DisplayName("the detail names the owner and says whether the caller may approve")
    void detailShowsOwnerAndCanApprove() throws Exception {
        String run = optimize(owner, job(owner, "Train", 4.0));
        assertThat(detail(owner, run).path("canApprove").asBoolean()).isFalse();
        assertThat(detail(approver, run).path("canApprove").asBoolean()).isTrue();
        assertThat(detail(approver, run).path("ownerName").asText()).isEqualTo("Operator");
        assertThat(detail(approver, run).path("removedJobIds")).isEmpty();
    }

    @Test
    @DisplayName("calendar rows belong to the plan's owner, whoever approved it")
    void calendarRowsBelongToTheOwner() throws Exception {
        String run = optimize(owner, job(owner, "Train", 4.0));
        decide(approver, run, "approve").andExpect(status().isOk());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(approver.getEmail(), null, List.of()));
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var plan = gate.reviewable(UUID.fromString(run));
                applications.applyChecked(plan, gate.check(plan, true).schedule());
            });
        } finally { SecurityContextHolder.clearContext(); }
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).isNotEmpty()
            .allSatisfy(row -> assertThat(row.getOwnerEmail()).isEqualTo(owner.getEmail()));
    }

    // ------------------------------------------------------------------ audit visibility

    @Test
    @DisplayName("the submitter sees who approved, the approver sees the plan's schedule, strangers see nothing")
    void auditVisibility() throws Exception {
        String run = optimize(owner, job(owner, "Train", 4.0));
        decide(approver, run, "approve").andExpect(status().isOk());
        long eventId = audit.findByRunIdOrderByTimestampDesc(run).getFirst().getId();

        for (User who : List.of(owner, approver)) {
            JsonNode page = json.readTree(mvc.perform(get("/api/audit-events?size=50").with(as(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
            assertThat(page).anySatisfy(e -> {
                assertThat(e.path("runId").asText()).isEqualTo(run);
                assertThat(e.path("actor").asText()).isEqualTo(approver.getEmail());
            });
            mvc.perform(get("/api/audit-events/" + eventId).with(as(who))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleAvailability").value("VISIBLE"));
            mvc.perform(get("/api/audit-events/run/" + run).with(as(who))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        }
        JsonNode strangers = json.readTree(mvc.perform(get("/api/audit-events?size=50").with(as(admin)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("content");
        assertThat(strangers).noneSatisfy(e -> assertThat(e.path("runId").asText()).isEqualTo(run));
        mvc.perform(get("/api/audit-events/" + eventId).with(as(admin))).andExpect(status().isNotFound());
        mvc.perform(get("/api/audit-events/run/" + run).with(as(admin))).andExpect(jsonPath("$.data").isEmpty());
    }

    // ------------------------------------------------------------------ deleting a job in an approved plan

    @Test
    @DisplayName("deleting a job leaves the other jobs approved with the same times, and the approval still matches")
    void deletingAJobLeavesTheOthersApproved() throws Exception {
        Job train = job(owner, "Train", 4.0);
        Job evaluate = job(owner, "Evaluate", 2.0);
        String run = optimize(owner, train, evaluate);
        decide(approver, run, "approve").andExpect(status().isOk());
        apply(owner, run).andExpect(status().isOk());
        JsonNode before = item(detail(owner, run), evaluate.getId());

        mvc.perform(delete("/api/jobs/" + train.getId()).with(as(owner))).andExpect(status().isOk());

        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);
        JsonNode after = detail(owner, run);
        assertThat(after.path("reviewState").asText()).isEqualTo("APPROVED");
        assertThat(after.path("removedJobIds")).extracting(JsonNode::asLong).containsExactly(train.getId());
        assertThat(item(after, evaluate.getId()).path("scheduledStart")).isEqualTo(before.path("scheduledStart"));
        assertThat(item(after, evaluate.getId()).path("scheduledEnd")).isEqualTo(before.path("scheduledEnd"));
        // The stored plan is unchanged, so the approval hash recorded when it was approved still matches.
        assertThat(plans.findById(run).orElseThrow().getInvalidationReason()).isNull();
        // The deleted job is gone from the calendar and the export; the other job is not.
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).extracting(WorkloadSchedule::getJobId)
            .containsExactly(evaluate.getId());
        String export = mvc.perform(get("/api/schedules/" + run + "/export.csv").with(as(owner)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(export).contains("Evaluate").doesNotContain("Train");
        // It is recorded who removed it.
        assertThat(audit.findByRunIdOrderByTimestampDesc(run)).anySatisfy(e -> {
            assertThat(e.getActionType()).isEqualTo(AuditActionType.REMOVE_JOB);
            assertThat(e.getActor()).isEqualTo(owner.getEmail());
            assertThat(e.getOperatorRationale()).contains("Train");
        });
        // The latest decision on the plan is still its approval.
        apply(owner, run).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an admin can delete another user's job; a user who is not the owner gets 404")
    void whoCanDeleteAJob() throws Exception {
        Job theirs = job(owner, "Train", 4.0);
        mvc.perform(delete("/api/jobs/" + theirs.getId()).with(as(approver))).andExpect(status().isNotFound());
        assertThat(jobs.findById(theirs.getId())).isPresent();
        mvc.perform(delete("/api/jobs/" + theirs.getId()).with(as(admin))).andExpect(status().isOk());
        assertThat(jobs.findById(theirs.getId())).isEmpty();
    }

    @Test
    @DisplayName("an admin sees every user's jobs with the owner's name, others cannot")
    void adminJobList() throws Exception {
        Job theirs = job(owner, "Train", 4.0);
        JsonNode all = json.readTree(mvc.perform(get("/api/admin/jobs").with(as(admin))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(all).anySatisfy(j -> {
            assertThat(j.path("id").asLong()).isEqualTo(theirs.getId());
            assertThat(j.path("ownerName").asText()).isEqualTo("Operator");
        });
        mvc.perform(get("/api/admin/jobs").with(as(owner))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ editing a job in an approved plan

    @Test
    @DisplayName("an approved job says which plan it is in, is refused edits, and can be edited again after a revoke")
    void anApprovedJobIsLockedUntilRevoked() throws Exception {
        Job train = job(owner, "Train", 4.0);
        String run = optimize(owner, train);
        decide(approver, run, "approve").andExpect(status().isOk());

        JsonNode listed = json.readTree(mvc.perform(get("/api/jobs").with(as(owner))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(listed.get(0).path("approvedRunId").asText()).isEqualTo(run);

        var renamed = body(train); renamed.put("name", "Renamed");
        edit(owner, train, renamed).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This job is approved and cannot be edited. You can delete it."));
        assertThat(jobs.findById(train.getId()).orElseThrow().getName()).isEqualTo("Train");

        decide(approver, run, "revoke").andExpect(status().isOk());
        edit(owner, train, renamed).andExpect(status().isOk());
        assertThat(jobs.findById(train.getId()).orElseThrow().getName()).isEqualTo("Renamed");
        assertThat(json.readTree(mvc.perform(get("/api/jobs/" + train.getId()).with(as(owner))).andReturn()
            .getResponse().getContentAsString()).path("data").path("approvedRunId").isNull()).isTrue();
    }

    @Test
    @DisplayName("a job with no slot is not approved: it can be edited and re-planned without disturbing the rest of its plan")
    void aJobWithNoSlotCanStillBeEdited() throws Exception {
        // The first job fills the whole cluster for the hour, so the second has nowhere to go.
        Job filler = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Filler").cpuHours(256.0).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(1)).flexibilityWindowHours(0).isExempt(true).build());
        Job squeezed = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Squeezed").cpuHours(100.0).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(1)).flexibilityWindowHours(0).build());
        String first = optimize(owner, filler, squeezed);
        assertThat(item(detail(owner, first), squeezed.getId()).path("perJobStatus").asText()).isEqualTo("NO_FEASIBLE_SLOT");
        decide(approver, first, "approve").andExpect(status().isOk());

        assertThat(detail(owner, first).path("removedJobIds")).extracting(JsonNode::asLong).containsExactly(squeezed.getId());
        var smaller = body(squeezed); smaller.put("cpuHours", 50.0);
        edit(owner, squeezed, smaller).andExpect(status().isOk());
        edit(owner, filler, body(filler)).andExpect(status().isConflict());

        String second = optimize(owner, squeezed);
        decide(approver, second, "approve").andExpect(status().isOk());
        assertThat(stateOf(first)).isEqualTo(ReviewState.APPROVED);
        assertThat(stateOf(second)).isEqualTo(ReviewState.APPROVED);
    }
}
