package com.gacs.backend;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.service.GreedyStubScheduleSolver;
import com.gacs.backend.service.ScheduleSolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for POST /api/schedules/optimize (GRID-124 AC5.1-AC5.6).
 *
 * <p>These tests use the application context and H2 so the security filter chain, Jackson,
 * forecast repository query, price provider, solver and response assembly are exercised
 * together. Forecast rows are persisted rather than mocked because the latest-forecast
 * correlated subquery is part of the endpoint contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = ScheduleOptimizationIntegrationTest.FixedClockConfig.class)
@Transactional
class ScheduleOptimizationIntegrationTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final String LOCATION = "LZ_NORTH";
    private static final LocalDateTime GENERATED_AT = HORIZON_START.minusHours(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PredictedPriceRepository predictions;

    @BeforeEach
    void clearForecasts() {
        predictions.deleteAll();
    }

    // AC5.1 and AC5.3: the authenticated endpoint returns the documented fields and
    // totals reconcile with per-item costs calculated from the same price series.
    @Test
    @WithMockUser(username = "contract-tester@datacenter.io")
    void authenticatedRequestReturnsScheduleAndReconciledCostFields() throws Exception {
        LocalDateTime baseline = HORIZON_START.plusHours(10);
        seedPrice(baseline, 90.0);
        seedPrice(baseline.plusHours(1), 10.0);

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJob(baseline, baseline.plusHours(4), 2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.message", is("Optimized schedule generated")))
            .andExpect(jsonPath("$.data.runId", notNullValue()))
            .andExpect(jsonPath("$.data.status", is("FEASIBLE")))
            .andExpect(jsonPath("$.data.baselineCostUsd", is(2.30)))
            .andExpect(jsonPath("$.data.optimizedCostUsd", is(0.26)))
            .andExpect(jsonPath("$.data.projectedSavingsUsd", is(2.04)))
            .andExpect(jsonPath("$.data.projectedSavingsPercent", is(88.70)))
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(false)))
            .andExpect(jsonPath("$.data.usedHeuristicFallback", is(true)))
            .andExpect(jsonPath("$.data.items.length()", is(1)))
            .andExpect(jsonPath("$.data.items[0].jobId", is(101)))
            .andExpect(jsonPath("$.data.items[0].jobName", is("contract job")))
            .andExpect(jsonPath("$.data.items[0].baselineStart", is("2026-10-20T10:00:00")))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T11:00:00")))
            .andExpect(jsonPath("$.data.items[0].scheduledEnd", is("2026-10-20T12:00:00")))
            .andExpect(jsonPath("$.data.items[0].perJobStatus", is("RESCHEDULED")))
            .andExpect(jsonPath("$.data.items[0].baselineCostUsd", is(2.30)))
            .andExpect(jsonPath("$.data.items[0].optimizedCostUsd", is(0.26)))
            .andExpect(jsonPath("$.data.items[0].savingsUsd", is(2.04)));
    }

    // AC5.2: stored forecast rows are selected by the real latest-forecast query.
    @Test
    @WithMockUser
    void latestForecastRowsDrivePriceMapping() throws Exception {
        LocalDateTime baseline = HORIZON_START.plusHours(8);
        seedPrice(baseline, 70.0);
        seedPrice(baseline.plusHours(1), 20.0);
        predictions.save(PredictedPrice.builder()
            .intervalStartUtc(baseline)
            .location(LOCATION)
            .modelVersion("older-model")
            .generatedAt(GENERATED_AT.minusHours(1))
            .predictedPrice(5.0)
            .build());

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJob(baseline, baseline.plusHours(3), 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(false)))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T09:00:00")))
            .andExpect(jsonPath("$.data.items[0].baselineCostUsd", is(1.79)))
            .andExpect(jsonPath("$.data.items[0].optimizedCostUsd", is(0.51)));
    }

    // AC5.2: no stored forecast uses the documented seeded curve rather than failing.
    @Test
    @WithMockUser
    void missingForecastUsesSeededFallbackPrices() throws Exception {
        LocalDateTime baseline = HORIZON_START.plusHours(17);

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJob(baseline, baseline.plusHours(3), 2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(true)))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T19:00:00")))
            .andExpect(jsonPath("$.data.items[0].baselineCostUsd", is(2.43)))
            .andExpect(jsonPath("$.data.items[0].optimizedCostUsd", is(1.64)))
            .andExpect(jsonPath("$.data.plainEnglishExplanation", containsString("seeded fallback")));
    }

    // AC5.4: invalid input is a clear 400 and contains no success-shaped schedule.
    @Test
    @WithMockUser
    void invalidInputReturnsDocumentedErrorWithoutSchedule() throws Exception {
        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content("{\"jobs\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.message", containsString("jobs")))
            .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJob(HORIZON_START.plusHours(4), HORIZON_START.plusHours(3), 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.message", containsString("deadline")))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    // AC5.5: a job with no one-hour slot before its deadline is returned unchanged.
    @Test
    @WithMockUser
    void infeasibleJobIsReturnedAtOriginalTimeWithoutError() throws Exception {
        LocalDateTime baseline = HORIZON_START.plusHours(6);
        seedPrice(baseline, 30.0);

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJob(baseline, baseline, 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.items.length()", is(1)))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T06:00:00")))
            .andExpect(jsonPath("$.data.items[0].scheduledEnd", is("2026-10-20T07:00:00")))
            .andExpect(jsonPath("$.data.items[0].perJobStatus", is("UNCHANGED_NO_FEASIBLE_SLOT")));

        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content(requestWithJobAndCapacity(
                    baseline, baseline.plusHours(2), 1, 256.0, 1.0
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T06:00:00")))
            .andExpect(jsonPath("$.data.items[0].perJobStatus", is("UNCHANGED_NO_FEASIBLE_SLOT")));
    }

    // AC5.4: the optimiser endpoint is protected by the application security contract.
    @Test
    void unauthenticatedRequestIsForbidden() throws Exception {
        mockMvc.perform(post("/api/schedules/optimize")
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    private void seedPrice(LocalDateTime intervalStart, double price) {
        predictions.save(PredictedPrice.builder()
            .intervalStartUtc(intervalStart)
            .location(LOCATION)
            .modelVersion("contract-test-model")
            .generatedAt(GENERATED_AT)
            .predictedPrice(price)
            .build());
    }

    private static String requestWithJob(
        LocalDateTime baseline, LocalDateTime deadline, int flexibilityWindowHours
    ) {
        return requestWithJobAndCapacity(baseline, deadline, flexibilityWindowHours, null, null);
    }

    private static String requestWithJobAndCapacity(
        LocalDateTime baseline, LocalDateTime deadline, int flexibilityWindowHours,
        Double maxCpuHours, Double maxGpuHours
    ) {
        String capacity = maxCpuHours == null && maxGpuHours == null
            ? ""
            : """
              "clusterCapacity": {
                "maxConcurrentCpuHours": %s,
                "maxConcurrentGpuHours": %s
              },
            """.formatted(maxCpuHours, maxGpuHours);
        return """
            {
              %s
              "jobs": [{
                "id": 101,
                "name": "contract job",
                "cpuHours": 0.0,
                "gpuHours": 64.0,
                "originalStartTime": "%s",
                "deadline": "%s",
                "flexibilityWindowHours": %d,
                "isExempt": false
              }],
              "forecastLocation": "%s",
              "horizonHours": 24
            }
            """.formatted(capacity, baseline, deadline, flexibilityWindowHours, LOCATION);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(HORIZON_START.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ScheduleSolver stubScheduleSolver() {
            return new GreedyStubScheduleSolver();
        }
    }
}
