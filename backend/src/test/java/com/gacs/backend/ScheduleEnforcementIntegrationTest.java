package com.gacs.backend;

import com.fasterxml.jackson.databind.*;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.*;
import com.gacs.backend.util.ScheduleHashUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleEnforcementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired FacilityRepository facilities;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired WorkloadScheduleRepository schedules;
    @Autowired AuditEventRepository audit;
    @Autowired ScheduleExpiryService expiry;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<String> created = new ArrayList<>();
    private User owner;
    private Job job;
    private LocalDateTime start;

    @BeforeEach void setup() {
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
        start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS);
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Train").cpuHours(4.0).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(3)).flexibilityWindowHours(1).build());
    }
    @AfterEach void cleanup() {
        schedules.deleteAll(schedules.findByOwnerEmailOrderByStartUtcAsc(owner.getEmail()));
        for (String run : created) { plans.deleteById(run); runs.deleteById(run); }
        jobs.deleteAll(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(owner.getEmail()));
        facilities.deleteAll(facilities.findAllByOwnerEmail(owner.getEmail()));
        users.deleteById(owner.getId());
    }
    private Map<String,Object> jobBody() {
        var j = jobs.findById(job.getId()).orElseThrow();
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", j.getId()); m.put("name", j.getName()); m.put("cpuHours", j.getCpuHours());
        m.put("gpuHours", j.getGpuHours()); m.put("originalStartTime", j.getOriginalStartTime().toString());
        m.put("deadline", j.getDeadline().toString()); m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
        m.put("isExempt", j.isExempt()); m.put("priority", j.getPriority().name());
        m.put("workloadCategory", j.getWorkloadCategory().name());
        return m;
    }
    private String optimize() throws Exception {
        var forecast = List.of(Map.of("intervalStartUtc", start.toString(), "predictedPrice", 100),
            Map.of("intervalStartUtc", start.plusHours(1).toString(), "predictedPrice", 10),
            Map.of("intervalStartUtc", start.plusHours(2).toString(), "predictedPrice", 50));
        String result = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("jobs", List.of(jobBody()), "forecast", forecast))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String run = json.readTree(result).path("data").path("runId").asText(); created.add(run); return run;
    }
    private ResultActions decide(String run, String action) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/" + action).with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"));
    }
    private ResultActions apply(String run) throws Exception {
        return mvc.perform(post("/api/schedules/" + run + "/apply").with(user(owner.getEmail())));
    }
    private void update(Map<String,Object> body) throws Exception {
        mvc.perform(put("/api/jobs/" + job.getId()).with(user(owner.getEmail())).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk());
    }
    private ResultActions transition(long id, String state) throws Exception {
        return mvc.perform(patch("/api/workload-schedules/" + id + "/status").with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("status", state))));
    }
    private void refused(ResultActions response, String reason) throws Exception {
        response.andExpect(status().is(reason.equals("NOT_APPROVED") ? 403 : 409))
            .andExpect(jsonPath("$.data.reason").value(reason));
    }

    private ResultActions auditDecision(String run, String action, String email) throws Exception {
        return mvc.perform(post("/api/audit-events").with(user(email))
            .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "runId", run, "actionType", action, "scheduleHash", "0".repeat(64),
                "operatorRationale", "reviewed through audit endpoint"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "REJECT", "REVISE"})
    void auditEndpointCannotChangeAnotherOperatorsApproval(String action) throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        int eventCount = audit.findByRunIdOrderByTimestampDesc(run).size();
        User other = users.save(new User(UUID.randomUUID() + "@test.io", "pw", "Other", "ROLE_USER"));
        try {
            auditDecision(run, action, other.getEmail()).andExpect(status().isNotFound());
            assertThat(audit.findByRunIdOrderByTimestampDesc(run)).hasSize(eventCount);
            assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
            apply(run).andExpect(status().isOk());
        } finally { users.deleteById(other.getId()); }
    }

    @Test void auditEndpointApprovesWithServerHashAndUpdatesReviewState() throws Exception {
        String run = optimize();
        String hash = ScheduleHashUtil.computeSha256(plans.findById(run).orElseThrow().getResultJson());
        auditDecision(run, "APPROVE", owner.getEmail()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.scheduleHash").value(hash))
            .andExpect(jsonPath("$.data.actor").value(owner.getEmail()));
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
        apply(run).andExpect(status().isOk());
    }

    @Test void auditEndpointCannotApproveChangedInputs() throws Exception {
        String run = optimize();
        var body = jobBody(); body.put("cpuHours", 8.0); update(body);
        int eventCount = audit.findByRunIdOrderByTimestampDesc(run).size();
        refused(auditDecision(run, "APPROVE", owner.getEmail()), "INVALIDATED");
        assertThat(audit.findByRunIdOrderByTimestampDesc(run)).hasSize(eventCount);
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.INVALIDATED);
    }

    @ParameterizedTest
    @ValueSource(strings={"cpuHours","gpuHours","originalStartTime","deadline","flexibilityWindowHours",
        "isExempt","priority","cancelled","constraints","confirmation","deleted"})
    void materialChangesInvalidateAndBlockEveryBoundary(String field) throws Exception {
        if (field.equals("confirmation")) {
            job.setRawConstraints("finish on time"); job.setParsedAt(start.minusDays(1));
            job.setConfirmationOutcome(ConfirmationOutcome.CONFIRMED); jobs.save(job);
        }
        String run = optimize(); decide(run,"approve").andExpect(status().isOk()); apply(run).andExpect(status().isOk());
        long entry = schedules.findByRunIdOrderByIdAsc(run).getFirst().getId();
        if (field.equals("deleted")) {
            mvc.perform(delete("/api/jobs/" + job.getId()).with(user(owner.getEmail()))).andExpect(status().isOk());
        } else {
            var body = jobBody();
            switch(field) {
                case "cpuHours" -> body.put(field, 5);
                case "gpuHours" -> body.put(field, 1);
                case "originalStartTime" -> body.put(field, start.plusHours(1).toString());
                case "deadline" -> body.put(field, start.plusHours(4).toString());
                case "flexibilityWindowHours" -> body.put(field, 2);
                case "isExempt" -> body.put(field, true);
                case "priority" -> body.put(field,"HIGH");
                case "cancelled" -> body.put("status","CANCELLED");
                case "constraints" -> body.put("rawConstraints","new constraint");
                case "confirmation" -> body.put("confirmationOutcome","REJECTED");
            }
            update(body);
            assertThat(jobs.findById(job.getId()).orElseThrow().getSchedulingRevision()).isEqualTo(1);
        }
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.INVALIDATED);
        refused(apply(run), "INVALIDATED"); refused(decide(run,"approve"),"INVALIDATED");
        refused(transition(entry,"Running"),"INVALIDATED"); refused(transition(entry,"Scheduled"),"INVALIDATED");
        refused(mvc.perform(get("/api/schedules/"+run+"/export.json").with(user(owner.getEmail()))),"INVALIDATED");
        mvc.perform(get("/api/schedules/"+run).with(user(owner.getEmail()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewState").value("INVALIDATED"))
            .andExpect(jsonPath("$.data.invalidationReason").isNotEmpty());
    }

    @Test void pendingPlanCannotBeApprovedAfterChangeAndRevert() throws Exception {
        String run=optimize(); var original=jobBody(); var changed=jobBody(); changed.put("cpuHours",8);
        update(changed); update(original);
        assertThat(jobs.findById(job.getId()).orElseThrow().getSchedulingRevision()).isEqualTo(2);
        refused(decide(run,"approve"),"INVALIDATED");
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.INVALIDATED);
    }

    @Test void cosmeticNoopAndUnrelatedJobsDoNotInvalidate() throws Exception {
        String run=optimize(); decide(run,"approve").andExpect(status().isOk());
        update(jobBody()); var cosmetic=jobBody(); cosmetic.put("name","Renamed"); cosmetic.put("status","SCHEDULED"); update(cosmetic);
        Job unrelated=jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("Other").cpuHours(1.0)
            .originalStartTime(start).deadline(start.plusHours(2)).build());
        mvc.perform(delete("/api/jobs/"+unrelated.getId()).with(user(owner.getEmail()))).andExpect(status().isOk());
        assertThat(jobs.findById(job.getId()).orElseThrow().getSchedulingRevision()).isZero();
        apply(run).andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings={"maxCpuHours","maxGpuHours","location"})
    void facilityChangesInvalidate(String field) throws Exception {
        String run=optimize(); decide(run,"approve").andExpect(status().isOk());
        Map<String,Object> body=new LinkedHashMap<>(Map.of("name","Renamed", "maxCpuHours",256.0,"maxGpuHours",64.0,"location","LZ_NORTH"));
        mvc.perform(put("/api/facilities/me").with(user(owner.getEmail())).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk());
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
        body.put(field,field.equals("location") ? "LZ_SOUTH" : 128.0);
        mvc.perform(put("/api/facilities/me").with(user(owner.getEmail())).contentType(APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk());
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.INVALIDATED);
        refused(apply(run),"INVALIDATED");
    }

    @Test void applicationIsIdempotentAndRevocationBlocksStarts() throws Exception {
        String run=optimize(); refused(apply(run),"NOT_APPROVED");
        decide(run,"approve").andExpect(status().isOk());
        String first=apply(run).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(apply(run).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).hasSize(1);
        assertThat(audit.findByRunIdOrderByTimestampDesc(run).stream().filter(e->e.getActionType()==AuditActionType.DISPATCH)).hasSize(1);
        var entry=schedules.findByRunIdOrderByIdAsc(run).getFirst();
        transition(entry.getId(),"Running").andExpect(status().isOk());
        // Withdrawing the approval is what halts it; "reject" is a first decision and is refused here.
        refused(decide(run,"reject"),"ALREADY_DECIDED");
        decide(run,"revoke").andExpect(status().isOk()); refused(transition(entry.getId(),"Running"),"NOT_APPROVED");
        transition(entry.getId(),"Completed").andExpect(status().isOk());
    }

    @Test void invalidationDoesNotInterruptRunningButBlocksResume() throws Exception {
        String run=optimize(); decide(run,"approve").andExpect(status().isOk()); apply(run).andExpect(status().isOk());
        long id=schedules.findByRunIdOrderByIdAsc(run).getFirst().getId(); transition(id,"Running").andExpect(status().isOk());
        var changed=jobBody();changed.put("cpuHours",8);update(changed);
        assertThat(schedules.findById(id).orElseThrow().getStatus()).isEqualTo("Running");
        transition(id,"Throttled").andExpect(status().isOk()); refused(transition(id,"Running"),"INVALIDATED");
        transition(id,"Completed").andExpect(status().isOk());
    }

    @Test void legacyEntriesAndDirectCreationCannotExecute() throws Exception {
        refused(mvc.perform(post("/api/workload-schedules").with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content("{}")),"NOT_APPROVED");
        var legacy=new WorkloadSchedule();legacy.setOwnerEmail(owner.getEmail());legacy.setTitle("Legacy");
        legacy.setType("Batch");legacy.setCluster("Cluster");legacy.setStatus("Scheduled");legacy.setStartUtc(start);legacy.setEndUtc(start.plusHours(1));
        legacy=schedules.save(legacy); refused(transition(legacy.getId(),"Running"),"NOT_APPROVED");
        mvc.perform(get("/api/workload-schedules").with(user(owner.getEmail()))).andExpect(status().isOk());
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        jdbc.update("update optimization_plans set input_snapshot=null where run_id=?",run);
        refused(apply(run),"INVALIDATED");
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.INVALIDATED);
    }

    @Test void ownerIsolationAppliesToDetailAndApplication() throws Exception {
        String run=optimize();User other=users.save(new User(UUID.randomUUID()+"@test.io","pw","Other","ROLE_ADMIN"));
        try {
            mvc.perform(get("/api/schedules/"+run).with(user(other.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
            mvc.perform(post("/api/schedules/"+run+"/apply").with(user(other.getEmail()).roles("ADMIN"))).andExpect(status().isNotFound());
            mvc.perform(put("/api/jobs/"+job.getId()).with(user(other.getEmail())).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(jobBody()))).andExpect(status().isNotFound());
            assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
        } finally {users.deleteById(other.getId());}
    }

    @Test void supersessionUsesApprovalOrderAndCannotBeRevived() throws Exception {
        String older=optimize(),newer=optimize();decide(newer,"approve").andExpect(status().isOk());
        decide(older,"approve").andExpect(status().isOk());refused(apply(newer),"STALE");
        decide(older,"revoke").andExpect(status().isOk());refused(decide(newer,"approve"),"STALE");
    }

    @Test void expiryPersistsStaleWithoutAnExportRequest() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        String stored=plans.findById(run).orElseThrow().getResultJson();
        String past=stored.replace(start.toLocalDate().toString(),start.minusDays(4).toLocalDate().toString());
        jdbc.update("update optimization_plans set result_json=? where run_id=?",past,run);
        audit.save(AuditEvent.builder().actor(owner.getEmail()).actionType(AuditActionType.APPROVE).timestamp(Instant.now())
            .runId(run).scheduleHash(ScheduleHashUtil.computeSha256(past)).operatorRationale("Clock advance fixture").build());
        expiry.expire();assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.STALE);
    }

    @Test void applicationRollsBackAsOneTransaction() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try {apply(run).andExpect(status().isOk());} catch(Exception e){throw new RuntimeException(e);}
            tx.setRollbackOnly();
        });
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).isEmpty();
        assertThat(plans.findById(run).orElseThrow().getAppliedAt()).isNull();
        assertThat(audit.findByRunIdOrderByTimestampDesc(run).stream().filter(e->e.getActionType()==AuditActionType.DISPATCH)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"approve","apply","start"})
    void concurrentMaterialEditCannotBeBypassed(String boundary) throws Exception {
        String run=optimize();
        if (!boundary.equals("approve")) decide(run,"approve").andExpect(status().isOk());
        if (boundary.equals("start")) apply(run).andExpect(status().isOk());
        long id=boundary.equals("start") ? schedules.findByRunIdOrderByIdAsc(run).getFirst().getId() : 0;
        CountDownLatch begun=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<ResultActions> future=new TransactionTemplate(manager).execute(tx->{
                users.lockByEmail(owner.getEmail()).orElseThrow();
                var call=executor.submit(()->{begun.countDown();return switch(boundary) {
                    case "approve" -> decide(run,"approve");case "apply" -> apply(run);default -> transition(id,"Running");};});
                try {
                    assertThat(begun.await(5,TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(()->call.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    var body=jobBody();body.put("cpuHours",8);update(body);
                } catch(Exception e){throw new RuntimeException(e);}
                return call;
            });
            refused(future.get(10,TimeUnit.SECONDS),"INVALIDATED");
        }
    }
    @Test void concurrentApplicationsCreateOneDispatch() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        CountDownLatch go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<String>> calls=new ArrayList<>();
            for(int i=0;i<2;i++) calls.add(executor.submit(()->{
                if(!go.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return apply(run).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            }));
            go.countDown();assertThat(calls.get(0).get(10,TimeUnit.SECONDS)).isEqualTo(calls.get(1).get(10,TimeUnit.SECONDS));
        }
        assertThat(schedules.findByRunIdOrderByIdAsc(run)).hasSize(1);
        assertThat(audit.findByRunIdOrderByTimestampDesc(run).stream().filter(e->e.getActionType()==AuditActionType.DISPATCH)).hasSize(1);
    }

    @Test void deletedEntriesAreNotRecreatedByApplicationRetry() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());apply(run).andExpect(status().isOk());
        schedules.deleteAll(schedules.findByRunIdOrderByIdAsc(run));
        apply(run).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertThat(audit.findByRunIdOrderByTimestampDesc(run).stream().filter(e->e.getActionType()==AuditActionType.DISPATCH)).hasSize(1);
    }

    @Test void revokedRunCannotBeAppliedOrStarted() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());apply(run).andExpect(status().isOk());
        long id=schedules.findByRunIdOrderByIdAsc(run).getFirst().getId();
        decide(run,"revoke").andExpect(status().isOk());refused(apply(run),"NOT_APPROVED");
        refused(transition(id,"Running"),"NOT_APPROVED");
    }

    /** Revision is a first decision now, so it is reached from PENDING rather than after approval. */
    @Test void revisionRequestedRunCannotBeApplied() throws Exception {
        String run=optimize();decide(run,"revise").andExpect(status().isOk());
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.REVISION_REQUESTED);
        refused(apply(run),"NOT_APPROVED");
    }

    @Test void failedInputMutationRollsBackRevisionAndInvalidation() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try {var body=jobBody();body.put("cpuHours",8);update(body);} catch(Exception e){throw new RuntimeException(e);}
            tx.setRollbackOnly();
        });
        assertThat(jobs.findById(job.getId()).orElseThrow().getSchedulingRevision()).isZero();
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
    }

    @Test void anotherOperatorsFacilityDoesNotInvalidateOwnerPlans() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());
        User other=users.save(new User(UUID.randomUUID()+"@test.io","pw","Other","ROLE_USER"));
        try {
            mvc.perform(put("/api/facilities/me").with(user(other.getEmail())).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Other\",\"maxCpuHours\":128,\"maxGpuHours\":32,\"location\":\"LZ_SOUTH\"}"))
                .andExpect(status().isOk());
            apply(run).andExpect(status().isOk());
        } finally {facilities.deleteAll(facilities.findAllByOwnerEmail(other.getEmail()));users.deleteById(other.getId());}
    }

    @Test void planDetailDetectsApprovalHashTamperingAndExecutionRefusesIt() throws Exception {
        String run=optimize();decide(run,"approve").andExpect(status().isOk());apply(run).andExpect(status().isOk());
        String stored=plans.findById(run).orElseThrow().getResultJson();
        jdbc.update("update optimization_plans set result_json=? where run_id=?",stored.replace("Train","Tampered"),run);
        mvc.perform(get("/api/schedules/"+run).with(user(owner.getEmail()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewState").value("INVALIDATED"));
        refused(apply(run),"INVALIDATED");
        refused(transition(schedules.findByRunIdOrderByIdAsc(run).getFirst().getId(),"Running"),"INVALIDATED");
    }

    @Test
    @DisplayName("bug 4: re-optimising and re-approving the same job supersedes its calendar entry rather than accumulating one")
    void reapprovingAndApplyingSameJobSupersedesRatherThanAccumulates() throws Exception {
        String run1 = optimize(); decide(run1,"approve").andExpect(status().isOk()); apply(run1).andExpect(status().isOk());
        assertThat(schedules.findByOwnerEmailOrderByStartUtcAsc(owner.getEmail())).hasSize(1);

        String run2 = optimize(); decide(run2,"approve").andExpect(status().isOk()); apply(run2).andExpect(status().isOk());

        var all = schedules.findByOwnerEmailOrderByStartUtcAsc(owner.getEmail());
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getRunId()).isEqualTo(run2);
        assertThat(schedules.findByRunIdOrderByIdAsc(run1)).isEmpty();
        assertThat(plans.findById(run1).orElseThrow().getReviewState()).isEqualTo(ReviewState.STALE);
    }

    @Test
    @DisplayName("bug 6: the applied calendar entry uses the job's own workload category, not a GPU-hours guess")
    void appliedScheduleUsesJobsWorkloadCategoryNotGpuHeuristic() throws Exception {
        var body = jobBody(); body.put("gpuHours", 2.0); body.put("workloadCategory", "CRYPTO_MINING"); update(body);
        String run = optimize(); decide(run,"approve").andExpect(status().isOk()); apply(run).andExpect(status().isOk());
        // The old heuristic (any gpuHours > 0 implies "ML Training") would have mislabeled this entry.
        assertThat(schedules.findByRunIdOrderByIdAsc(run).getFirst().getType()).isEqualTo("Crypto Mining");
    }

    // ------------------------------------------------------- appliedAt on the plan detail (task 2)

    /**
     * The plan detail DTO must carry {@code appliedAt}. The field has existed on
     * {@code OptimizationPlan} since application became gated, but it was exposed on no DTO, so the
     * review UI could not tell an approved plan still waiting to be applied from one applied an hour
     * ago - and re-offered "Add approved schedule to calendar" every time the operator navigated back.
     */
    @Test
    @DisplayName("the plan detail reports appliedAt: null until applied, then the application time")
    void planDetailCarriesAppliedAt() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());

        mvc.perform(get("/api/schedules/" + run).with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reviewState").value("APPROVED"))
            // Present in the payload and explicitly null, not simply absent: a client cannot
            // distinguish "never applied" from "field not implemented" if the key is missing.
            .andExpect(jsonPath("$.data.appliedAt").doesNotExist());

        apply(run).andExpect(status().isOk());
        Instant applied = plans.findById(run).orElseThrow().getAppliedAt();
        assertThat(applied).as("apply() must stamp the plan").isNotNull();

        mvc.perform(get("/api/schedules/" + run).with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.appliedAt").exists());
    }

    /**
     * Applying twice does not move {@code appliedAt} backwards or clear it - the UI reads this field
     * to decide the button is done, so it has to stay set once the schedule is on the calendar.
     */
    @Test
    @DisplayName("appliedAt stays set when an approved schedule is applied again")
    void appliedAtSurvivesReapplication() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        apply(run).andExpect(status().isOk());
        Instant first = plans.findById(run).orElseThrow().getAppliedAt();
        apply(run).andExpect(status().isOk());
        assertThat(plans.findById(run).orElseThrow().getAppliedAt()).isNotNull();
        assertThat(first).isNotNull();
    }

    // ------------------------------------------------- REVOKE through /api/audit-events (task 1)

    /**
     * REVOKE through the compatibility audit endpoint must hit the same gate as
     * {@code POST /api/schedules/{runId}/revoke}. It used to fall to that controller's {@code default}
     * branch and be written straight to the audit log with no ownership or state check at all.
     */
    @Test
    @DisplayName("a REVOKE naming another operator's run records nothing and leaves the approval standing")
    void auditEndpointCannotRevokeAnotherOperatorsApproval() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        int eventCount = audit.findByRunIdOrderByTimestampDesc(run).size();
        User other = users.save(new User(UUID.randomUUID() + "@test.io", "pw", "Other", "ROLE_USER"));
        try {
            auditDecision(run, "REVOKE", other.getEmail()).andExpect(status().isNotFound());
            assertThat(audit.findByRunIdOrderByTimestampDesc(run)).hasSize(eventCount);
            assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.APPROVED);
            // The approval is untouched, so the schedule still applies.
            apply(run).andExpect(status().isOk());
        } finally { users.deleteById(other.getId()); }
    }

    /** A REVOKE on a run that was never approved is refused, and writes no audit event. */
    @Test
    @DisplayName("a REVOKE on a pending run is refused through the audit endpoint too")
    void auditEndpointCannotRevokeAnUnapprovedRun() throws Exception {
        String run = optimize();
        int eventCount = audit.findByRunIdOrderByTimestampDesc(run).size();
        auditDecision(run, "REVOKE", owner.getEmail())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"));
        assertThat(audit.findByRunIdOrderByTimestampDesc(run)).hasSize(eventCount);
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    /** The owner's own revoke through the audit endpoint behaves exactly like the dedicated route. */
    @Test
    @DisplayName("the owner's REVOKE through the audit endpoint halts the schedule")
    void auditEndpointRevokeHaltsTheSchedule() throws Exception {
        String run = optimize();
        decide(run, "approve").andExpect(status().isOk());
        auditDecision(run, "REVOKE", owner.getEmail()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.actionType").value("REVOKE"))
            .andExpect(jsonPath("$.data.actor").value(owner.getEmail()));
        assertThat(plans.findById(run).orElseThrow().getReviewState()).isEqualTo(ReviewState.REJECTED);
        // Every execution gate already refuses anything not APPROVED.
        refused(apply(run), "NOT_APPROVED");
    }
}
