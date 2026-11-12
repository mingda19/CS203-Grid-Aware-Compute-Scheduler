package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.AppSettingsService;
import com.gacs.backend.service.JobPlanningService;
import com.gacs.backend.service.PriceSeriesProvider;
import com.gacs.backend.service.ScheduleExpiryService;
import com.gacs.backend.service.StoredScheduleReader;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.support.MutableClock;
import com.gacs.backend.support.MutableClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Autorun, Recompute, the capacity ledger and the derived job status. The facility is small (10 CPU-hours
 * an hour) so that two plans can compete for it. Plans made by hand use an explicit forecast whose first
 * hour is the cheapest, so every job starts in its first hour. The autorun uses the stored forecast.
 */
@Import(MutableClockConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutorunIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppSettingsService settings;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired FacilityRepository facilities;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired WorkloadScheduleRepository schedules;
    @Autowired JobPlanningService planning;
    @Autowired ScheduleExpiryService expiry;
    @Autowired StoredScheduleReader reader;
    @Autowired MutableClock clock;
    @MockitoSpyBean PriceSeriesProvider prices;
    @MockitoSpyBean AllocationSolver solver;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private User owner;
    private User other;
    private User approver;
    private User admin;
    private LocalDateTime t0;

    @BeforeEach
    void setup() {
        settings.update(false);
        t0 = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        clock.set(Instant.now());
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Owner", "ROLE_USER"));
        other = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Other", "ROLE_USER"));
        approver = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Approver", "ROLE_USER"));
        admin = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Admin", "ROLE_ADMIN"));
        facilities.deleteAll();
        facilities.save(Facility.builder().ownerEmail(owner.getEmail()).maxCpuHours(10.0).maxGpuHours(4.0).build());
    }

    @AfterEach
    void cleanup() {
        Mockito.reset(prices, solver);
        clock.set(Instant.now());
        for (User u : List.of(owner, other, approver, admin)) {
            for (OptimizationPlan plan : ownedPlans(u)) {
                schedules.deleteAll(schedules.findByRunIdOrderByIdAsc(plan.getRunId()));
                plans.deleteById(plan.getRunId());
                runs.deleteById(plan.getRunId());
            }
            jobs.deleteAll(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(u.getEmail()));
            users.deleteById(u.getId());
        }
        facilities.deleteAll(); // one shared facility: reset it for the next test class
        settings.update(false);
    }

    // ------------------------------------------------------------------ helpers

    private static RequestPostProcessor as(User who) {
        return user(who.getEmail()).roles(who.getRole().replace("ROLE_", ""));
    }

    /** A flexible job. Hours are offsets from the top of the current hour. */
    private Job job(User who, String name, double cpu, int startHours, int deadlineHours, int flexHours) {
        return jobs.save(Job.builder().ownerEmail(who.getEmail()).name(name).cpuHours(cpu).gpuHours(0.0)
            .originalStartTime(t0.plusHours(startHours)).deadline(t0.plusHours(deadlineHours))
            .flexibilityWindowHours(flexHours).build());
    }

    private List<OptimizationPlan> ownedPlans(User who) {
        return plans.findAll().stream().filter(p -> p.getOwner().getId().equals(who.getId())).toList();
    }

    private Map<String, Object> body(Job j) {
        j = jobs.findById(j.getId()).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId()); m.put("name", j.getName()); m.put("cpuHours", j.getCpuHours());
        m.put("gpuHours", j.getGpuHours()); m.put("originalStartTime", j.getOriginalStartTime().toString());
        m.put("deadline", j.getDeadline().toString()); m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
        m.put("isExempt", j.isExempt()); m.put("priority", j.getPriority().name());
        return m;
    }

    private ResultActions optimizeRequest(User who, Job... list) throws Exception {
        LocalDateTime from = Arrays.stream(list).map(Job::getOriginalStartTime).min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime to = Arrays.stream(list).map(Job::getDeadline).max(Comparator.naturalOrder()).orElseThrow();
        List<Map<String, Object>> forecast = new ArrayList<>();
        for (LocalDateTime h = from; h.isBefore(to); h = h.plusHours(1))
            forecast.add(Map.of("intervalStartUtc", h.toString(), "predictedPrice", h.equals(from) ? 10 : 100 + forecast.size()));
        return mvc.perform(post("/api/schedules/optimize").with(as(who)).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("jobs", Arrays.stream(list).map(this::body).toList(), "forecast", forecast,
                "clusterCapacity", Map.of("maxConcurrentCpuHours", 10.0, "maxConcurrentGpuHours", 4.0)))));
    }

    private String optimize(User who, Job... list) throws Exception {
        String result = optimizeRequest(who, list).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("runId").asText();
    }

    private ResultActions decide(User who, String run, String action) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/" + action).with(as(who))
            .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"));
    }

    private JsonNode detail(User who, String run) throws Exception {
        return json.readTree(mvc.perform(get("/api/schedules/" + run).with(as(who))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private String jobStatus(User who, Job j) throws Exception {
        return json.readTree(mvc.perform(get("/api/jobs/" + j.getId()).with(as(who))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data").path("status").asText();
    }

    private ReviewState stateOf(String run) {
        return plans.findById(run).orElseThrow().getReviewState();
    }

    private double cpuAt(String run, LocalDateTime slot) {
        double sum = 0;
        for (var item : reader.read(plans.findById(run).orElseThrow().getResultJson()).orElseThrow().items())
            for (var allocation : item.allocations()) if (slot.equals(allocation.intervalStartUtc())) sum += allocation.cpuHours();
        return sum;
    }

    private void changeFacility(double cpu) throws Exception {
        mvc.perform(put("/api/admin/facility").with(as(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"Primary Compute Cluster\",\"maxCpuHours\":" + cpu + ",\"maxGpuHours\":4.0,\"location\":\"LZ_NORTH\"}"))
            .andExpect(status().isOk());
    }

    private LocalDateTime lastSlotEnd(String run) {
        return schedules.findByRunIdOrderByIdAsc(run).stream().map(WorkloadSchedule::getEndUtc).max(Comparator.naturalOrder()).orElseThrow();
    }

    // ------------------------------------------------------------------ section 4: one plan approved while another waits

    @Test
    @DisplayName("B no longer fits after A is approved: B goes stale and a new plan appears without the conflict")
    void aWaitingPlanThatNoLongerFitsIsReplaced() throws Exception {
        String a = optimize(owner, job(owner, "A", 6.0, 3, 4, 0));
        String b = optimize(other, job(other, "B", 6.0, 3, 8, 4));
        assertThat(cpuAt(b, t0.plusHours(3))).isEqualTo(6.0);

        decide(approver, a, "approve").andExpect(status().isOk());

        JsonNode seen = detail(approver, b);
        assertThat(seen.path("reviewState").asText()).isEqualTo("STALE");
        assertThat(seen.path("invalidationReason").asText()).contains("no longer fits");

        var result = planning.plan(other.getEmail(), false);
        assertThat(result.runIds()).hasSize(1);
        String replacement = result.runIds().getFirst();
        assertThat(stateOf(replacement)).isEqualTo(ReviewState.PENDING);
        assertThat(cpuAt(replacement, t0.plusHours(3))).isLessThanOrEqualTo(4.0 + 1e-9); // 10 less A's 6
    }

    @Test
    @DisplayName("B still fits after A is approved: B is left alone and can be approved")
    void aWaitingPlanThatStillFitsIsLeftAlone() throws Exception {
        String a = optimize(owner, job(owner, "A", 4.0, 3, 4, 0));
        String b = optimize(other, job(other, "B", 4.0, 3, 4, 0));

        decide(approver, a, "approve").andExpect(status().isOk());

        assertThat(detail(approver, b).path("reviewState").asText()).isEqualTo("PENDING");
        decide(approver, b, "approve").andExpect(status().isOk());
    }

    @Test
    @DisplayName("A is revoked: a plan that was solved around A goes stale, because a cheaper placement may exist")
    void revokingFreesCapacityAndStalesAPlanSolvedAroundIt() throws Exception {
        String a = optimize(owner, job(owner, "A", 6.0, 3, 4, 0));
        decide(approver, a, "approve").andExpect(status().isOk());
        String b = optimize(other, job(other, "B", 6.0, 3, 8, 4));
        assertThat(cpuAt(b, t0.plusHours(3))).isLessThanOrEqualTo(4.0 + 1e-9);
        assertThat(detail(approver, b).path("reviewState").asText()).isEqualTo("PENDING");

        decide(owner, a, "revoke").andExpect(status().isOk());

        JsonNode seen = detail(approver, b);
        assertThat(seen.path("reviewState").asText()).isEqualTo("STALE");
        assertThat(seen.path("invalidationReason").asText()).contains("capacity was freed");
    }

    @Test
    @DisplayName("two approvals that would overbook the facility: exactly one succeeds")
    void concurrentApprovalsCannotOverbook() throws Exception {
        String a = optimize(owner, job(owner, "A", 6.0, 3, 4, 0));
        String b = optimize(other, job(other, "B", 6.0, 3, 4, 0));

        CountDownLatch go = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> calls = new ArrayList<>();
            for (String run : List.of(a, b)) calls.add(pool.submit(() -> {
                go.await();
                return decide(approver, run, "approve").andReturn().getResponse().getStatus();
            }));
            go.countDown();
            for (var call : calls) codes.add(call.get());
        }

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(List.of(stateOf(a), stateOf(b))).containsExactlyInAnyOrder(ReviewState.APPROVED, ReviewState.STALE);
    }

    @Test
    @DisplayName("a start passes while the plan waits: it goes stale, then the autorun replaces it")
    void aStartThatPassesStalesAWaitingPlan() throws Exception {
        // 14 CPU-hours need two hours, so after the first hour only some of its slots have ended.
        Job j = job(owner, "J", 14.0, 3, 20, 0);
        String run = optimize(owner, j);
        assertThat(jobStatus(owner, j)).isEqualTo("AWAITING_APPROVAL");

        clock.set(t0.plusHours(4).toInstant(ZoneOffset.UTC));

        JsonNode seen = detail(approver, run);
        assertThat(seen.path("reviewState").asText()).isEqualTo("STALE");
        assertThat(seen.path("invalidationReason").asText()).contains("has passed");
        var result = planning.plan(owner.getEmail(), false);
        assertThat(result.runIds()).hasSize(1);
        assertThat(stateOf(result.runIds().getFirst())).isEqualTo(ReviewState.PENDING);
    }

    @Test
    @DisplayName("a run that includes an approved job is refused")
    void anApprovedJobCannotBePlannedAgain() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 4, 0);
        decide(approver, optimize(owner, j), "approve").andExpect(status().isOk());
        optimizeRequest(owner, j).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ the poller

    @Test
    @DisplayName("a new job is planned by the poller and waits for another user; the status follows its plan")
    void statusFollowsThePlan() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 20, 10);
        assertThat(jobStatus(owner, j)).isEqualTo("SCHEDULING");

        String run = planning.plan(owner.getEmail(), false).runIds().getFirst();
        assertThat(jobStatus(owner, j)).isEqualTo("AWAITING_APPROVAL");

        decide(approver, run, "approve").andExpect(status().isOk());
        assertThat(jobStatus(owner, j)).isEqualTo("SCHEDULED");
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).hasSize(1); // approving also applied it

        long entry = schedules.findByRunIdOrderByIdAsc(run).getFirst().getId();
        mvc.perform(patch("/api/workload-schedules/" + entry + "/status").with(as(owner))
            .contentType(APPLICATION_JSON).content("{\"status\":\"Running\"}")).andExpect(status().isOk());
        assertThat(jobStatus(owner, j)).isEqualTo("RUNNING");

        decide(owner, run, "revoke").andExpect(status().isOk());
        assertThat(jobStatus(owner, j)).isEqualTo("REJECTED");
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
    }

    @Test
    @DisplayName("only the owner or an admin can revoke an approval; the approver cannot")
    void onlyTheOwnerOrAnAdminRevokes() throws Exception {
        job(owner, "J", 4.0, 3, 20, 10);
        String run = planning.plan(owner.getEmail(), false).runIds().getFirst();
        decide(approver, run, "approve").andExpect(status().isOk());
        assertThat(detail(approver, run).path("canRevoke").asBoolean()).isFalse();
        assertThat(detail(owner, run).path("canRevoke").asBoolean()).isTrue();

        decide(approver, run, "revoke").andExpect(status().isForbidden());
        decide(other, run, "revoke").andExpect(status().isForbidden());
        assertThat(stateOf(run)).isEqualTo(ReviewState.APPROVED);

        decide(admin, run, "revoke").andExpect(status().isOk());
        assertThat(stateOf(run)).isEqualTo(ReviewState.REJECTED);
    }

    @Test
    @DisplayName("an approved plan stays approved through the expiry check and the next autorun tick")
    void anApprovedPlanStaysApproved() throws Exception {
        Job future = job(owner, "Future", 4.0, 3, 20, 10);
        Job elapsed = job(other, "Elapsed", 4.0, -2, 20, 0); // start already passed: the autorun re-anchors it
        Job offHour = jobs.save(Job.builder().ownerEmail(admin.getEmail()).name("Off hour").cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(t0.plusHours(3).plusMinutes(17).plusSeconds(5)).deadline(t0.plusHours(20).plusMinutes(17))
            .flexibilityWindowHours(10).build());
        Map<Job, User> owners = Map.of(future, owner, elapsed, other, offHour, admin);
        Map<Job, String> runs = new HashMap<>();
        for (var e : owners.entrySet()) {
            String run = planning.plan(e.getValue().getEmail(), false).runIds().getFirst();
            decide(approver, run, "approve").andExpect(status().isOk());
            runs.put(e.getKey(), run);
        }

        clock.set(clock.instant().plusSeconds(90));
        expiry.expire();
        planning.tick();

        for (var e : owners.entrySet()) {
            var plan = plans.findById(runs.get(e.getKey())).orElseThrow();
            assertThat(plan.getReviewState()).as(e.getKey().getName() + ": " + plan.getInvalidationReason())
                .isEqualTo(ReviewState.APPROVED);
            assertThat(jobStatus(e.getValue(), e.getKey())).isEqualTo("SCHEDULED");
        }
    }

    @Test
    @DisplayName("a rejected job is not planned again until it is edited or recomputed")
    void rejectedJobsWaitForAnEditOrRecompute() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 20, 10);
        String first = planning.plan(owner.getEmail(), false).runIds().getFirst();
        decide(approver, first, "reject").andExpect(status().isOk());

        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
        assertThat(ownedPlans(owner)).hasSize(1);

        mvc.perform(post("/api/schedules/recompute").with(as(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runIds.length()").value(1));
        assertThat(ownedPlans(owner)).hasSize(2);
        String second = ownedPlans(owner).stream().filter(p -> p.getReviewState() == ReviewState.PENDING).findFirst().orElseThrow().getRunId();
        decide(approver, second, "reject").andExpect(status().isOk());
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();

        var edited = body(j); edited.put("cpuHours", 5.0);
        mvc.perform(put("/api/jobs/" + j.getId()).with(as(owner)).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(edited))).andExpect(status().isOk());
        assertThat(planning.plan(owner.getEmail(), false).runIds()).hasSize(1);
    }

    @Test
    @DisplayName("a job that cannot be planned does not block the others in its batch")
    void aBadJobDoesNotBlockTheBatch() throws Exception {
        Job good = job(owner, "Good", 4.0, 3, 10, 2);
        Job bad = job(owner, "Bad", 100.0, 3, 4, 0); // cannot finish in an hour, even alone on 10 CPU-hours

        var result = planning.plan(owner.getEmail(), false);

        assertThat(result.runIds()).hasSize(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedJobIds()).containsExactly(bad.getId());
        assertThat(StoredScheduleReader.jobIds(reader.read(plans.findById(result.runIds().getFirst()).orElseThrow().getResultJson()).orElseThrow()))
            .containsExactly(good.getId());
        assertThat(jobs.findById(bad.getId()).orElseThrow().getPlanError()).contains("even alone");
        assertThat(jobStatus(owner, bad)).isEqualTo("NEEDS_ATTENTION");
        assertThat(jobStatus(owner, good)).isEqualTo("AWAITING_APPROVAL");
        // The failure is kept for this revision, so the next tick does not try it again.
        var again = planning.plan(owner.getEmail(), false);
        assertThat(again.runIds()).isEmpty();
        assertThat(again.failed()).isZero();
    }

    @Test
    @DisplayName("a job whose approved slots have ended is Completed and is not planned again")
    void finishedWorkIsNotPlannedAgain() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 6, 2);
        String run = planning.plan(owner.getEmail(), false).runIds().getFirst();
        decide(approver, run, "approve").andExpect(status().isOk());

        clock.set(lastSlotEnd(run).plusHours(1).toInstant(ZoneOffset.UTC));
        assertThat(jobStatus(owner, j)).isEqualTo("COMPLETED");
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();

        expiry.expire(); // the gate marks the plan stale because its slots ended
        assertThat(stateOf(run)).isEqualTo(ReviewState.STALE);
        assertThat(jobStatus(owner, j)).isEqualTo("COMPLETED");
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
        mvc.perform(put("/api/jobs/" + j.getId()).with(as(owner)).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(body(j)))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a facility change after the slots ended does not send finished work back to the poller")
    void aFacilityChangeAfterTheEndKeepsTheJobCompleted() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 6, 2);
        String run = planning.plan(owner.getEmail(), false).runIds().getFirst();
        decide(approver, run, "approve").andExpect(status().isOk());

        clock.set(lastSlotEnd(run).plusHours(1).toInstant(ZoneOffset.UTC));
        changeFacility(11.0);

        assertThat(stateOf(run)).isEqualTo(ReviewState.INVALIDATED);
        assertThat(jobStatus(owner, j)).isEqualTo("COMPLETED");
        assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
    }

    @Test
    @DisplayName("a facility change before the slots end does send the job back to the poller")
    void aFacilityChangeBeforeTheEndReplansTheJob() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 6, 2);
        String run = planning.plan(owner.getEmail(), false).runIds().getFirst();
        decide(approver, run, "approve").andExpect(status().isOk());

        changeFacility(11.0);

        assertThat(jobStatus(owner, j)).isEqualTo("SCHEDULING");
        assertThat(planning.plan(owner.getEmail(), false).runIds()).hasSize(1);
    }

    @Test
    @DisplayName("a job with unconfirmed constraints is not planned, and no run is made on later ticks")
    void unconfirmedConstraintsAreNotPlanned() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 20, 10);
        j.setRawConstraints("finish by tomorrow");
        j.setParsedAt(t0);
        j.setConfirmationOutcome(ConfirmationOutcome.PENDING);
        jobs.save(j);

        for (int tick = 0; tick < 3; tick++) assertThat(planning.plan(owner.getEmail(), false).runIds()).isEmpty();
        assertThat(ownedPlans(owner)).isEmpty();
        assertThat(jobStatus(owner, j)).isEqualTo("NEEDS_ATTENTION");
    }

    @Test
    @DisplayName("a solver crash gives the one failing job a reason and is not retried every tick")
    void aSolverCrashIsNotRetried() throws Exception {
        Job good = job(owner, "Good", 4.0, 3, 10, 2);
        Job crashing = job(owner, "Crashing", 4.0, 3, 10, 2);
        Mockito.doAnswer(call -> {
            var problem = (OptimizationProblem) call.getArgument(0);
            if (problem.jobs().stream().anyMatch(j -> j.id().equals(crashing.getId())))
                throw new IllegalStateException("solver blew up");
            return call.callRealMethod();
        }).when(solver).solve(any(OptimizationProblem.class));

        var result = planning.plan(owner.getEmail(), false);

        assertThat(result.runIds()).hasSize(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedJobIds()).containsExactly(crashing.getId());
        assertThat(jobs.findById(crashing.getId()).orElseThrow().getPlanError()).contains("optimiser failed");
        assertThat(jobStatus(owner, crashing)).isEqualTo("NEEDS_ATTENTION");
        assertThat(jobStatus(owner, good)).isEqualTo("AWAITING_APPROVAL");
        Mockito.clearInvocations(solver);
        var again = planning.plan(owner.getEmail(), false);
        assertThat(again.runIds()).isEmpty();
        assertThat(again.failed()).isZero();
        Mockito.verify(solver, Mockito.never()).solve(any(OptimizationProblem.class));
    }

    @Test
    @DisplayName("a forecast failure records nothing on the job, and the next tick tries again")
    void aForecastFailureIsRetried() throws Exception {
        Job j = job(owner, "J", 4.0, 3, 20, 10);
        Mockito.doThrow(new IllegalStateException("forecast unavailable")).when(prices)
            .load(any(String.class), any(LocalDateTime.class), anyInt());

        planning.tick();

        assertThat(jobs.findById(j.getId()).orElseThrow().getPlanError()).isNull();
        assertThat(jobStatus(owner, j)).isEqualTo("SCHEDULING");
        assertThat(ownedPlans(owner)).isEmpty();

        Mockito.reset(prices);
        planning.tick();
        assertThat(ownedPlans(owner)).hasSize(1);
        assertThat(jobStatus(owner, j)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("a run that places no job never enters the queue and is not repeated")
    void anEmptyRunIsNotRepeated() throws Exception {
        decide(approver, optimize(owner, job(owner, "Fills the hour", 10.0, 3, 4, 0)), "approve").andExpect(status().isOk());
        Job squeezed = job(owner, "Squeezed", 5.0, 3, 4, 0);

        var first = planning.plan(owner.getEmail(), false);
        assertThat(first.runIds()).isEmpty();
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.failedJobIds()).containsExactly(squeezed.getId());
        assertThat(ownedPlans(owner).stream().filter(p -> p.getReviewState() == ReviewState.PENDING)).isEmpty();
        assertThat(jobStatus(owner, squeezed)).isEqualTo("NEEDS_ATTENTION");
        mvc.perform(get("/api/schedules?state=PENDING").with(as(approver))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0));

        int plansBefore = ownedPlans(owner).size();
        assertThat(planning.plan(owner.getEmail(), false).failed()).isZero();
        assertThat(ownedPlans(owner)).hasSize(plansBefore);
    }
}
