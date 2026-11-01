package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.OptimizationPlanJobBackfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GRID-134 AC6.1a / AC6.1b — the audit trail end to end: real optimiser runs and decisions, then the job filter
 * and the detail view's stored schedule (docs/api-contract.md section 6.7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditTrailIntegrationTest {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired OptimizationPlanJobBackfill backfill;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> createdRuns = new ArrayList<>();
    private final List<Long> createdJobs = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();
    private final LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).plusDays(2);
    private User owner;

    @BeforeEach
    void setup() {
        owner = newUser();
    }

    @AfterEach
    void cleanup() {
        // Audit records remain append-only; each test uses fresh run IDs.
        for (String runId : createdRuns) {
            plans.deleteById(runId);
            runs.deleteById(runId);
        }
        jobs.deleteAllById(createdJobs);
        users.deleteAllById(createdUsers);
    }

    private User newUser() {
        User user = users.save(new User(UUID.randomUUID() + "@test.io", "test-password", "Operator", "ROLE_USER"));
        createdUsers.add(user.getId());
        return user;
    }

    private Job job(String name) {
        Job saved = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name(name).cpuHours(4.0).gpuHours(0.0)
                .originalStartTime(start).deadline(start.plusHours(3)).flexibilityWindowHours(1).isExempt(false).build());
        createdJobs.add(saved.getId());
        return saved;
    }

    /** Optimises the jobs, approves the run, and returns {runId, approval audit event ID}. */
    private String[] optimizeAndApprove(Job... jobList) throws Exception {
        List<Map<String, Object>> jobsJson = new ArrayList<>();
        for (Job j : jobList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId());
            m.put("name", j.getName());
            m.put("cpuHours", j.getCpuHours());
            m.put("gpuHours", j.getGpuHours());
            m.put("originalStartTime", UTC.format(j.getOriginalStartTime()));
            m.put("deadline", UTC.format(j.getDeadline()));
            m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
            m.put("isExempt", j.isExempt());
            jobsJson.add(m);
        }
        List<Map<String, Object>> forecast = List.of(
                Map.of("intervalStartUtc", UTC.format(start), "predictedPrice", 100),
                Map.of("intervalStartUtc", UTC.format(start.plusHours(1)), "predictedPrice", 10),
                Map.of("intervalStartUtc", UTC.format(start.plusHours(2)), "predictedPrice", 50));
        String optimized = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("jobs", jobsJson, "forecast", forecast))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String runId = json.readTree(optimized).path("data").path("runId").asText();
        createdRuns.add(runId);
        String decided = mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON).content("{\"comment\":\"approved for audit test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new String[] {runId, json.readTree(decided).path("data").path("decisionId").asText()};
    }

    private List<String> runIdsForJob(long jobId) throws Exception {
        String body = mvc.perform(get("/api/audit-events").param("jobId", String.valueOf(jobId)).with(user(owner.getEmail())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> runIds = new ArrayList<>();
        for (JsonNode event : json.readTree(body).path("data").path("content")) {
            runIds.add(event.path("runId").asText());
        }
        return runIds;
    }

    @Test
    @DisplayName("AC6.1a — filtering by job ID finds the decisions on runs that scheduled that job")
    void jobFilter_FindsDecisionsOnRunsWithTheJob() throws Exception {
        Job shared = job("Shared");
        Job solo = job("Solo");
        String runWithBoth = optimizeAndApprove(shared, solo)[0];
        String runWithShared = optimizeAndApprove(shared)[0];

        assertThat(runIdsForJob(solo.getId())).containsExactly(runWithBoth);
        assertThat(runIdsForJob(shared.getId())).containsExactlyInAnyOrder(runWithBoth, runWithShared);
        assertThat(jdbc.queryForList("select job_id from optimization_plan_jobs where run_id = ?", Long.class, runWithBoth))
                .containsExactlyInAnyOrder(shared.getId(), solo.getId());
    }

    @Test
    @DisplayName("AC6.1b — the run's owner sees the stored schedule; other users only see that it matches")
    void detail_StoredScheduleOnlyForOwner() throws Exception {
        Job train = job("Train");
        String[] approved = optimizeAndApprove(train);
        String eventUrl = "/api/audit-events/" + approved[1];

        mvc.perform(get(eventUrl).with(user(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.runId").value(approved[0]))
                .andExpect(jsonPath("$.data.run.solverName").value("HybridAllocationSolver"))
                .andExpect(jsonPath("$.data.scheduleAvailability").value("VISIBLE"))
                .andExpect(jsonPath("$.data.scheduleHashMatches").value(true))
                .andExpect(jsonPath("$.data.schedule.runId").value(approved[0]))
                .andExpect(jsonPath("$.data.schedule.items[0].jobId").value(train.getId()))
                .andExpect(jsonPath("$.data.schedule.items[0].scheduledStart").value(UTC.format(start.plusHours(1))));

        User other = newUser();
        mvc.perform(get(eventUrl).with(user(other.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleAvailability").value("OWNER_ONLY"))
                .andExpect(jsonPath("$.data.scheduleHashMatches").value(true))
                .andExpect(jsonPath("$.data.schedule").doesNotExist())
                .andExpect(jsonPath("$.data.run.solverName").value("HybridAllocationSolver"));
    }

    @Test
    @DisplayName("AC6.1b — an event whose run has no stored plan reports NOT_STORED")
    void detail_NoStoredPlan() throws Exception {
        String recorded = mvc.perform(post("/api/audit-events").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("actionType", "OVERRIDE", "runId", "legacy-" + UUID.randomUUID(),
                                "scheduleHash", "a".repeat(64), "operatorRationale", "manual override before plans were stored"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/audit-events/" + json.readTree(recorded).path("data").path("id").asText()).with(user(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleAvailability").value("NOT_STORED"))
                .andExpect(jsonPath("$.data.scheduleHashMatches").doesNotExist())
                .andExpect(jsonPath("$.data.run").doesNotExist());
    }

    @Test
    @DisplayName("AC6.1b — a stored schedule changed after the decision is flagged as not matching")
    void detail_TamperedPlanFlagged() throws Exception {
        String[] approved = optimizeAndApprove(job("Train"));
        String stored = plans.findById(approved[0]).orElseThrow().getResultJson();
        jdbc.update("update optimization_plans set result_json = ? where run_id = ?",
                stored.replace("\"jobName\":\"Train\"", "\"jobName\":\"Tampered\""), approved[0]);

        mvc.perform(get("/api/audit-events/" + approved[1]).with(user(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleHashMatches").value(false))
                .andExpect(jsonPath("$.data.schedule.items[0].jobName").value("Tampered"));
    }

    @Test
    @DisplayName("AC6.1a — plans stored before job IDs were recorded get them from the backfill")
    void backfill_RestoresJobIdsForOlderPlans() throws Exception {
        Job train = job("Train");
        String runId = optimizeAndApprove(train)[0];
        jdbc.update("delete from optimization_plan_jobs where run_id = ?", runId);
        assertThat(runIdsForJob(train.getId())).isEmpty();

        assertThat(backfill.backfill()).isGreaterThanOrEqualTo(1);

        assertThat(runIdsForJob(train.getId())).containsExactly(runId);
        assertThat(backfill.backfill()).isZero();
    }
}
