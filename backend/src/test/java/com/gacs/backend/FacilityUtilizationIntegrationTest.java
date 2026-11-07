package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.Facility;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.FacilityRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.support.MutableClock;
import com.gacs.backend.support.MutableClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/facilities/me/utilization}: scheduled CPU and GPU use in the current hour, summed over
 * every live approved plan. Real jobs, optimiser runs and decisions; the clock is moved into the hour the
 * plans use so an hour boundary cannot change the result.
 */
@Import(MutableClockConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FacilityUtilizationIntegrationTest {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired FacilityRepository facilities;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired MutableClock clock;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<String> createdRuns = new ArrayList<>();
    private final List<Long> createdJobs = new ArrayList<>();
    private User owner;

    /** Two days ahead, on the hour, so no plan is stale by time before the clock moves. */
    private final LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).plusDays(2);
    /** The cheapest hour: every flexible job below moves here, and locked jobs start here. */
    private final LocalDateTime usedHour = start.plusHours(1);

    @BeforeEach
    void setup() {
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "test-password", "Operator", "ROLE_USER"));
        saveFacility(256.0, 64.0);
    }

    @AfterEach
    void cleanup() {
        clock.set(Instant.now());
        for (String runId : createdRuns) {
            plans.deleteById(runId);
            runs.deleteById(runId);
        }
        jobs.deleteAllById(createdJobs);
        facilities.deleteAll(facilities.findAllByOwnerEmail(owner.getEmail()));
        users.deleteById(owner.getId());
    }

    private void saveFacility(double cpu, double gpu) {
        facilities.deleteAll(facilities.findAllByOwnerEmail(owner.getEmail()));
        facilities.save(Facility.builder().ownerEmail(owner.getEmail()).maxCpuHours(cpu).maxGpuHours(gpu).build());
    }

    private Job flexibleJob(double cpu, double gpu) {
        return save(Job.builder().ownerEmail(owner.getEmail()).name("Flexible").cpuHours(cpu).gpuHours(gpu)
                .originalStartTime(start).deadline(start.plusHours(3)).flexibilityWindowHours(1).isExempt(false).build());
    }

    private Job lockedJob(double cpu, double gpu) {
        return save(Job.builder().ownerEmail(owner.getEmail()).name("Locked").cpuHours(cpu).gpuHours(gpu)
                .originalStartTime(usedHour).deadline(usedHour.plusHours(2)).flexibilityWindowHours(0).isExempt(true).build());
    }

    private Job save(Job job) {
        Job saved = jobs.save(job);
        createdJobs.add(saved.getId());
        return saved;
    }

    /** Optimises against prices that make {@link #usedHour} the cheapest, under the facility's own limits. */
    private String optimize(Job... jobList) throws Exception {
        Facility facility = facilities.findAllByOwnerEmail(owner.getEmail()).get(0);
        List<Map<String, Object>> jobsJson = new ArrayList<>();
        for (Job j : jobList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId());
            m.put("name", j.getName());
            m.put("cpuHours", j.getCpuHours());
            m.put("gpuHours", j.getGpuHours());
            m.put("originalStartTime", UTC.format(j.getOriginalStartTime().truncatedTo(ChronoUnit.HOURS)));
            m.put("deadline", UTC.format(j.getDeadline()));
            m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
            m.put("isExempt", j.isExempt());
            jobsJson.add(m);
        }
        List<Map<String, Object>> forecast = List.of(
                Map.of("intervalStartUtc", UTC.format(start), "predictedPrice", 100),
                Map.of("intervalStartUtc", UTC.format(usedHour), "predictedPrice", 10),
                Map.of("intervalStartUtc", UTC.format(start.plusHours(2)), "predictedPrice", 50));
        Map<String, Object> capacity = Map.of("maxConcurrentCpuHours", facility.getMaxCpuHours(),
                "maxConcurrentGpuHours", facility.getMaxGpuHours());
        String response = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("jobs", jobsJson, "forecast", forecast, "clusterCapacity", capacity))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(response).path("data").path("runId").asText();
        createdRuns.add(runId);
        return runId;
    }

    private String approvedPlan(Job... jobList) throws Exception {
        String runId = optimize(jobList);
        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isOk());
        return runId;
    }

    private void clockAt(LocalDateTime time) {
        clock.set(time.toInstant(ZoneOffset.UTC));
    }

    private ResultActions utilization() throws Exception {
        return mvc.perform(get("/api/facilities/me/utilization").with(user(owner.getEmail())));
    }

    private ReviewState stateOf(String runId) {
        return plans.findById(runId).orElseThrow().getReviewState();
    }

    @Test
    @DisplayName("A flexible job and a locked job with allocations in the current hour are summed together")
    void flexibleAndLockedJobsAreSummed() throws Exception {
        String runId = approvedPlan(flexibleJob(4.0, 0.0), lockedJob(6.0, 2.0));
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LIVE"))
                .andExpect(jsonPath("$.data.runIds[0]").value(runId))
                .andExpect(jsonPath("$.data.hourStartUtc").value(UTC.format(usedHour)))
                .andExpect(jsonPath("$.data.cpu.used").value(10.0))
                .andExpect(jsonPath("$.data.cpu.limit").value(256.0))
                .andExpect(jsonPath("$.data.cpu.percent").value(10.0 / 256.0 * 100))
                .andExpect(jsonPath("$.data.gpu.used").value(2.0))
                .andExpect(jsonPath("$.data.gpu.limit").value(64.0))
                .andExpect(jsonPath("$.data.gpu.percent").value(2.0 / 64.0 * 100));
    }

    @Test
    @DisplayName("Two approved plans with different job sets are both counted")
    void twoApprovedPlansWithDifferentJobsAreBothCounted() throws Exception {
        String first = approvedPlan(flexibleJob(4.0, 0.0));
        String second = approvedPlan(flexibleJob(6.0, 0.0));
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LIVE"))
                .andExpect(jsonPath("$.data.runIds.length()").value(2))
                .andExpect(jsonPath("$.data.runIds[?(@ == '" + first + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.runIds[?(@ == '" + second + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.cpu.used").value(10.0));
    }

    @Test
    @DisplayName("An allocation in another hour is not counted, but 0 is a valid LIVE value")
    void allocationInAnotherHourIsNotCounted() throws Exception {
        approvedPlan(flexibleJob(4.0, 0.0));
        clockAt(start.plusMinutes(10)); // the hour before the plan's slot

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LIVE"))
                .andExpect(jsonPath("$.data.hourStartUtc").value(UTC.format(start)))
                .andExpect(jsonPath("$.data.cpu.used").value(0.0))
                .andExpect(jsonPath("$.data.cpu.percent").value(0.0));
    }

    @Test
    @DisplayName("A plan whose job changed after approval is not counted and its state is stored as invalid")
    void changedJobPlanIsNotCountedAndIsInvalidated() throws Exception {
        Job job = flexibleJob(4.0, 0.0);
        String runId = approvedPlan(job);
        job.setCpuHours(5.0);
        jobs.save(job);
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NO_APPROVED_PLAN"))
                .andExpect(jsonPath("$.data.runIds.length()").value(0));
        assertThat(stateOf(runId)).isIn(ReviewState.STALE, ReviewState.INVALIDATED);
    }

    @Test
    @DisplayName("A STALE plan, superseded by a later approval of the same job, is not counted")
    void stalePlanIsNotCounted() throws Exception {
        Job job = flexibleJob(4.0, 0.0);
        String older = approvedPlan(job);
        String newer = approvedPlan(job);
        assertThat(stateOf(older)).isEqualTo(ReviewState.STALE);
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LIVE"))
                .andExpect(jsonPath("$.data.runIds.length()").value(1))
                .andExpect(jsonPath("$.data.runIds[0]").value(newer))
                .andExpect(jsonPath("$.data.cpu.used").value(4.0));
    }

    @Test
    @DisplayName("With no approved plan the state is NO_APPROVED_PLAN: used is null and the limits stay filled")
    void noApprovedPlan() throws Exception {
        optimize(flexibleJob(4.0, 0.0)); // a plan that is still PENDING does not count
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NO_APPROVED_PLAN"))
                .andExpect(jsonPath("$.data.runIds.length()").value(0))
                .andExpect(jsonPath("$.data.cpu.used").value(nullValue()))
                .andExpect(jsonPath("$.data.cpu.percent").value(nullValue()))
                .andExpect(jsonPath("$.data.cpu.limit").value(256.0))
                .andExpect(jsonPath("$.data.gpu.used").value(nullValue()))
                .andExpect(jsonPath("$.data.gpu.limit").value(64.0));
    }

    @Test
    @DisplayName("A GPU limit of 0 gives a null GPU percent")
    void zeroGpuLimitGivesNullPercent() throws Exception {
        saveFacility(256.0, 0.0);
        approvedPlan(flexibleJob(4.0, 0.0));
        clockAt(usedHour.plusMinutes(10));

        utilization().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LIVE"))
                .andExpect(jsonPath("$.data.cpu.percent").value(4.0 / 256.0 * 100))
                .andExpect(jsonPath("$.data.gpu.used").value(0.0))
                .andExpect(jsonPath("$.data.gpu.limit").value(0.0))
                .andExpect(jsonPath("$.data.gpu.percent").value(nullValue()));
    }
}
