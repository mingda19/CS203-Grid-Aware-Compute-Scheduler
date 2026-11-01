package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.JobOutcome;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.service.allocation.PerJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** GRID-142 / AC1.3: a failed or infeasible optimiser run is recorded and can never be approved or exported. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleRunFailureIntegrationTest {
    private static final String REQUEST = """
        {"jobs":[{"id":%d,"name":"test","cpuHours":1,"gpuHours":0,
        "originalStartTime":"%s:00","deadline":"%s:00",
        "flexibilityWindowHours":0,"isExempt":false}],
        "forecast":[{"intervalStartUtc":"%s:00","predictedPrice":10}]}
        """;

    @Autowired MockMvc mvc;
    @Autowired JobRepository jobs;
    @Autowired UserRepository users;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired ScheduleRunRepository scheduleRuns;
    @MockitoSpyBean AllocationSolver solver;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private User owner;
    private Job job;
    private String request;
    private String runId;

    @BeforeEach
    void setup() {
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "test-password", "Operator", "ROLE_USER"));
        var start = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(2)
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        job = jobs.save(Job.builder().ownerEmail(owner.getEmail()).name("test").cpuHours(1.0).gpuHours(0.0)
            .originalStartTime(start).deadline(start.plusHours(1)).flexibilityWindowHours(0).build());
        request = REQUEST.formatted(job.getId(), start, start.plusHours(1), start);
    }

    @AfterEach
    void cleanup() {
        if (runId != null) {
            plans.deleteById(runId);
            runs.deleteById(runId);
        }
        jobs.deleteById(job.getId());
        users.deleteById(owner.getId());
    }

    @Test
    void solverFailureIsRecordedAsFailedWithNoPlanToApprove() throws Exception {
        Mockito.doThrow(new IllegalStateException("boom")).when(solver).solve(Mockito.any());

        String body = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isServiceUnavailable())
            .andReturn().getResponse().getContentAsString();
        runId = json.readTree(body).path("data").path("runId").asText();

        assertThat(scheduleRuns.findByRunId(runId).orElseThrow().getStatus()).isEqualTo("FAILED");
        OptimizerRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getInputHash()).isNotBlank();
        assertThat(plans.findById(runId)).isEmpty();
        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"x\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void infeasibleRunIsStoredButCannotBeApprovedOrExported() throws Exception {
        Mockito.doAnswer(call -> {
            OptimizationProblem problem = call.getArgument(0);
            return new AllocationResult(problem.jobs().stream()
                .map(j -> new JobOutcome(j, null, PerJobStatus.NO_FEASIBLE_SLOT, "Cluster full")).toList(), false);
        }).when(solver).solve(Mockito.any());

        String body = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("INFEASIBLE"))
            .andReturn().getResponse().getContentAsString();
        runId = json.readTree(body).path("data").path("runId").asText();

        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"x\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/schedules/" + runId + "/export.csv").with(user(owner.getEmail())))
            .andExpect(status().isForbidden());
        assertThat(plans.findById(runId).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }
}
