package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.service.GreedyStubScheduleSolver;
import com.gacs.backend.service.ScheduleSolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GRID-123 acceptance flow: create jobs through the job API, then optimize the stored set.
 * The test intentionally uses the day-2 stub solver so it remains valid before GRID-125.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(classes = ScheduleOptimizationAcceptanceTest.FixedClockConfig.class)
@Transactional
class ScheduleOptimizationAcceptanceTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final String LOCATION = "LZ_NORTH";
    private static final String OWNER = "acceptance@datacenter.io";
    private static final LocalDateTime GENERATED_AT = HORIZON_START.minusHours(1);
    private static final double MAX_CPU = 256.0;
    private static final double MAX_GPU = 64.0;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PredictedPriceRepository predictions;

    @Autowired
    private JobRepository jobs;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void clearData() {
        jobs.deleteAll();
        predictions.deleteAll();
    }

    // AC1 and AC2: create the demo jobs through /api/jobs, then run the authenticated optimiser.
    @Test
    void seededJobsProduceACheaperSchedule() throws Exception {
        seedPrices();

        createJob("flexible peak job", 80.0, 16.0,
            HORIZON_START.plusHours(17), HORIZON_START.plusHours(22), 5, false);
        createJob("tight deadline job", 70.0, 10.0,
            HORIZON_START.plusHours(12), HORIZON_START.plusHours(14), 2, false);
        createJob("exempt job", 80.0, 20.0,
            HORIZON_START.plusHours(10), HORIZON_START.plusHours(11), 4, true);
        createJob("capacity-bound job", 200.0, 50.0,
            HORIZON_START.plusHours(10), HORIZON_START.plusHours(14), 4, false);

        String response = mockMvc.perform(post("/api/schedules/optimize")
                .with(user(OWNER))
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.status", is("FEASIBLE")))
            .andExpect(jsonPath("$.data.items.length()", is(4)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("baselineCostUsd").asDouble())
            .isGreaterThan(data.path("optimizedCostUsd").asDouble());
        assertThat(data.path("projectedSavingsUsd").asDouble())
            .isCloseTo(data.path("baselineCostUsd").asDouble()
                - data.path("optimizedCostUsd").asDouble(), offset(0.001));

        Map<String, JsonNode> items = new HashMap<>();
        data.path("items").forEach(item -> items.put(item.path("jobName").asText(), item));

        JsonNode exempt = items.get("exempt job");
        assertThat(exempt.path("scheduledStart").asText())
            .isEqualTo(exempt.path("baselineStart").asText());
        assertThat(exempt.path("perJobStatus").asText()).isEqualTo("UNCHANGED_EXEMPT");

        JsonNode capacityBound = items.get("capacity-bound job");
        assertThat(capacityBound.path("scheduledStart").asText())
            .isNotEqualTo(capacityBound.path("baselineStart").asText());

        Map<String, double[]> loadBySlot = new HashMap<>();
        for (JsonNode item : data.path("items")) {
            if ("UNCHANGED_NO_FEASIBLE_SLOT".equals(item.path("perJobStatus").asText())) {
                continue;
            }
            double[] load = loadBySlot.computeIfAbsent(
                item.path("scheduledStart").asText(), ignored -> new double[2]
            );
            load[0] += item.path("allocatedCpuHours").asDouble();
            load[1] += item.path("allocatedGpuHours").asDouble();
        }
        for (Map.Entry<String, double[]> entry : loadBySlot.entrySet()) {
            assertThat(entry.getValue()[0])
                .as("CPU load at " + entry.getKey())
                .isLessThanOrEqualTo(MAX_CPU);
            assertThat(entry.getValue()[1])
                .as("GPU load at " + entry.getKey())
                .isLessThanOrEqualTo(MAX_GPU);
        }

        for (JsonNode item : data.path("items")) {
            String status = item.path("perJobStatus").asText();
            if ("RESCHEDULED".equals(status) || "UNCHANGED_ALREADY_OPTIMAL".equals(status)) {
                LocalDateTime scheduledEnd = LocalDateTime.parse(item.path("scheduledEnd").asText());
                LocalDateTime deadline = jobs.findById(item.path("jobId").asLong())
                    .orElseThrow().getDeadline();
                assertThat(!scheduledEnd.isAfter(deadline))
                    .as("deadline for job " + item.path("jobId").asLong())
                    .isTrue();
            }
        }
    }

    private void createJob(
        String name, double cpuHours, double gpuHours, LocalDateTime start,
        LocalDateTime deadline, int flexibilityHours, boolean exempt
    ) throws Exception {
        String body = """
            {
              "name": "%s",
              "cpuHours": %s,
              "gpuHours": %s,
              "originalStartTime": "%s",
              "deadline": "%s",
              "flexibilityWindowHours": %d,
              "isExempt": %s,
              "status": "PENDING"
            }
            """.formatted(name, cpuHours, gpuHours, start, deadline, flexibilityHours, exempt);

        mockMvc.perform(post("/api/jobs")
                .with(user(OWNER))
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success", is(true)));
    }

    private void seedPrices() {
        Map<Integer, Double> hourlyPrices = Map.ofEntries(
            Map.entry(10, 80.0), Map.entry(11, 20.0), Map.entry(12, 90.0),
            Map.entry(13, 15.0), Map.entry(14, 25.0), Map.entry(15, 30.0),
            Map.entry(16, 40.0), Map.entry(17, 95.0), Map.entry(18, 18.0),
            Map.entry(19, 22.0), Map.entry(20, 28.0), Map.entry(21, 35.0),
            Map.entry(22, 45.0)
        );
        for (Map.Entry<Integer, Double> entry : hourlyPrices.entrySet()) {
            predictions.save(PredictedPrice.builder()
                .intervalStartUtc(HORIZON_START.plusHours(entry.getKey()))
                .location(LOCATION)
                .modelVersion("acceptance-test-model")
                .generatedAt(GENERATED_AT)
                .predictedPrice(entry.getValue())
                .build());
        }
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
