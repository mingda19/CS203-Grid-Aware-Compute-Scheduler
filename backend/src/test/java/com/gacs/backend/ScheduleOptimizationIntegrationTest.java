package com.gacs.backend;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for {@code POST /api/schedules/optimize} (docs/api-contract.md section 6.4) through the
 * full application: security, JSON, the forecast query, validation, solver and response. Forecast rows are
 * stored in H2 rather than mocked, because the latest-forecast query is part of the contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScheduleOptimizationIntegrationTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final String LOCATION = "LZ_NORTH";
    private static final LocalDateTime GENERATED_AT = DAY.minusHours(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PredictedPriceRepository predictions;

    @BeforeEach
    void clearForecasts() {
        predictions.deleteAll();
    }

    @Test
    @WithMockUser(username = "contract-tester@datacenter.io")
    void contractExampleReturnsDocumentedResponse() throws Exception {
        // The contract's example, sent with Z timestamps and an explicit forecast.
        String body = """
            {
              "jobs": [{
                "id": 101, "name": "LLM Fine-Tuning Run #42", "cpuHours": 64.0, "gpuHours": 16.0,
                "originalStartTime": "2026-10-20T10:00:00Z", "deadline": "2026-10-21T18:00:00Z",
                "flexibilityWindowHours": 15, "isExempt": false
              }],
              "forecast": [
                { "intervalStartUtc": "2026-10-20T10:00:00Z", "predictedPrice": 112.0 },
                { "intervalStartUtc": "2026-10-20T11:00:00Z", "predictedPrice": 100.0 },
                { "intervalStartUtc": "2026-10-21T01:00:00Z", "predictedPrice": 24.0 }
              ],
              "clusterCapacity": { "maxConcurrentCpuHours": 256.0, "maxConcurrentGpuHours": 64.0 }
            }
            """;

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.message", is("Optimized schedule generated")))
            .andExpect(jsonPath("$.data.runId", notNullValue()))
            .andExpect(jsonPath("$.data.status", is("SCHEDULED")))
            .andExpect(jsonPath("$.data.provenOptimal", is(true)))
            .andExpect(jsonPath("$.data.baselineCostUsd", is(1.0752)))
            .andExpect(jsonPath("$.data.optimizedCostUsd", is(0.2304)))
            .andExpect(jsonPath("$.data.projectedSavingsUsd", is(0.8448)))
            .andExpect(jsonPath("$.data.projectedSavingsPercent", is(78.57)))
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(false)))
            .andExpect(jsonPath("$.data.items.length()", is(1)))
            .andExpect(jsonPath("$.data.items[0].jobId", is(101)))
            .andExpect(jsonPath("$.data.items[0].jobName", is("LLM Fine-Tuning Run #42")))
            .andExpect(jsonPath("$.data.items[0].baselineStart", is("2026-10-20T10:00:00Z")))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-21T01:00:00Z")))
            .andExpect(jsonPath("$.data.items[0].scheduledEnd", is("2026-10-21T02:00:00Z")))
            .andExpect(jsonPath("$.data.items[0].allocatedCpuHours", is(64.0)))
            .andExpect(jsonPath("$.data.items[0].allocatedGpuHours", is(16.0)))
            .andExpect(jsonPath("$.data.items[0].allocations.length()", is(1)))
            .andExpect(jsonPath("$.data.items[0].allocations[0].intervalStartUtc", is("2026-10-21T01:00:00Z")))
            .andExpect(jsonPath("$.data.items[0].allocations[0].cpuHours", closeTo(64.0, 1e-6)))
            .andExpect(jsonPath("$.data.items[0].perJobStatus", is("OPTIMISED")))
            .andExpect(jsonPath("$.data.items[0].reason", nullValue()))
            .andExpect(jsonPath("$.data.items[0].baselineCostUsd", is(1.0752)))
            .andExpect(jsonPath("$.data.items[0].optimizedCostUsd", is(0.2304)))
            .andExpect(jsonPath("$.data.items[0].savingsUsd", is(0.8448)));
    }

    @Test
    @WithMockUser
    void forecastLocationUsesTheLatestStoredForecast() throws Exception {
        LocalDateTime baseline = DAY.plusHours(8);
        seedPrice(baseline, 70.0, GENERATED_AT);
        seedPrice(baseline.plusHours(1), 20.0, GENERATED_AT);
        seedPrice(baseline, 5.0, GENERATED_AT.minusHours(1)); // older run, must be ignored

        // 64 GPU-hours = 25.6 kWh: $70 at 08:00 -> 1.792; $20 at 09:00 -> 0.512.
        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON)
                .content(locationRequest(baseline, baseline.plusHours(3), 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(false)))
            .andExpect(jsonPath("$.data.items[0].scheduledStart", is("2026-10-20T09:00:00Z")))
            .andExpect(jsonPath("$.data.items[0].baselineCostUsd", is(1.792)))
            .andExpect(jsonPath("$.data.items[0].optimizedCostUsd", is(0.512)));
    }

    @Test
    @WithMockUser
    void noStoredForecastUsesTheFallbackCurve() throws Exception {
        LocalDateTime baseline = DAY.plusHours(17);

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON)
                .content(locationRequest(baseline, baseline.plusHours(3), 2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usedSeededFallbackPrices", is(true)))
            .andExpect(jsonPath("$.data.plainEnglishExplanation", containsString("seeded fallback")));
    }

    @Test
    @WithMockUser
    void invalidInputIsA400WithoutSchedule() throws Exception {
        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.message", is("jobs is required")))
            .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON)
                .content(locationRequest(DAY.plusHours(4), DAY.plusHours(3), 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("deadline")));

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON)
                .content(locationRequest(DAY.plusHours(4).plusMinutes(30), DAY.plusHours(8), 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("on the hour")));

        // 64 GPU-hours at 64 per hour needs a full hour; the deadline leaves 30 minutes.
        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON)
                .content(locationRequest(DAY.plusHours(4), DAY.plusHours(4).plusMinutes(30), 0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("even alone on the cluster")));
    }

    @Test
    @WithMockUser
    void unpricedBaselineIsA422() throws Exception {
        String body = """
            {
              "jobs": [{
                "id": 1, "name": "a", "cpuHours": 20.0, "gpuHours": 0.0,
                "originalStartTime": "2026-10-20T10:00:00", "deadline": "2026-10-20T13:00:00",
                "flexibilityWindowHours": 1, "isExempt": false
              }],
              "forecast": [ { "intervalStartUtc": "2026-10-20T11:00:00", "predictedPrice": 10.0 } ]
            }
            """;

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.message", containsString("2026-10-20T10:00")))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser
    void jobWithNoRoomIsReportedNotRejected() throws Exception {
        // The exempt job fills 10:00; the other job may only run at 10:00.
        String body = """
            {
              "jobs": [
                { "id": 1, "name": "exempt", "cpuHours": 256.0, "gpuHours": 0.0,
                  "originalStartTime": "2026-10-20T10:00:00", "deadline": "2026-10-20T11:00:00",
                  "flexibilityWindowHours": 0, "isExempt": true },
                { "id": 2, "name": "squeezed", "cpuHours": 100.0, "gpuHours": 0.0,
                  "originalStartTime": "2026-10-20T10:00:00", "deadline": "2026-10-20T11:00:00",
                  "flexibilityWindowHours": 0, "isExempt": false }
              ],
              "forecast": [ { "intervalStartUtc": "2026-10-20T10:00:00", "predictedPrice": 10.0 } ]
            }
            """;

        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status", is("NEEDS_ATTENTION")))
            .andExpect(jsonPath("$.data.items[0].perJobStatus", is("UNCHANGED_EXEMPT")))
            .andExpect(jsonPath("$.data.items[1].perJobStatus", is("NO_FEASIBLE_SLOT")))
            .andExpect(jsonPath("$.data.items[1].scheduledStart", nullValue()))
            .andExpect(jsonPath("$.data.items[1].scheduledEnd", nullValue()))
            .andExpect(jsonPath("$.data.items[1].allocations.length()", is(0)))
            .andExpect(jsonPath("$.data.items[1].baselineCostUsd", nullValue()))
            .andExpect(jsonPath("$.data.items[1].reason", containsString("Cluster full")))
            .andExpect(jsonPath("$.data.baselineCostUsd", is(0.128)));
    }

    @Test
    void unauthenticatedRequestIsForbidden() throws Exception {
        mockMvc.perform(post("/api/schedules/optimize").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    private void seedPrice(LocalDateTime intervalStart, double price, LocalDateTime generatedAt) {
        predictions.save(PredictedPrice.builder()
            .intervalStartUtc(intervalStart)
            .location(LOCATION)
            .modelVersion("contract-test-model")
            .generatedAt(generatedAt)
            .predictedPrice(price)
            .build());
    }

    /** One 64 GPU-hour job priced from the stored forecast for {@link #LOCATION}. */
    private static String locationRequest(LocalDateTime start, LocalDateTime deadline, int flexibilityWindowHours) {
        return """
            {
              "jobs": [{
                "id": 101, "name": "contract job", "cpuHours": 0.0, "gpuHours": 64.0,
                "originalStartTime": "%s", "deadline": "%s",
                "flexibilityWindowHours": %d, "isExempt": false
              }],
              "forecastLocation": "%s",
              "horizonHours": 24
            }
            """.formatted(start, deadline, flexibilityWindowHours, LOCATION);
    }
}
