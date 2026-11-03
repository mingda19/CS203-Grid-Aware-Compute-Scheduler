package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GRID-123 acceptance flow, as a client now runs it: create jobs through the job API, read them back,
 * send them to the optimiser with a forecast location, and check the plan against every contract rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScheduleOptimizationAcceptanceTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final String LOCATION = "LZ_NORTH";
    private static final String OWNER = "acceptance@datacenter.io";
    private static final double MAX_CPU = 256.0;
    private static final double MAX_GPU = 64.0;
    private static final DateTimeFormatter UTC_Z = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PredictedPriceRepository predictions;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private com.gacs.backend.repository.UserRepository users;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void clearData() {
        if (!users.existsByEmail(OWNER)) users.save(new com.gacs.backend.model.User(OWNER, "test-password", "Operator", "ROLE_USER"));
        jobs.deleteAll();
        predictions.deleteAll();
    }

    @Test
    void storedJobsProduceACheaperScheduleThatFollowsEveryRule() throws Exception {
        seedPrices();
        createJob("flexible peak job", 80.0, 16.0, DAY.plusHours(17), DAY.plusHours(22), 5, false);
        createJob("tight deadline job", 70.0, 10.0, DAY.plusHours(12), DAY.plusHours(14), 2, false);
        createJob("exempt job", 80.0, 20.0, DAY.plusHours(10), DAY.plusHours(11), 4, true);
        createJob("capacity-bound job", 200.0, 50.0, DAY.plusHours(10), DAY.plusHours(14), 4, false);

        String response = mockMvc.perform(post("/api/schedules/optimize")
                .with(user(OWNER))
                .contentType(APPLICATION_JSON)
                .content(optimiseRequest(storedJobs())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.status", is("SCHEDULED")))
            .andExpect(jsonPath("$.data.items.length()", is(4)))
            .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("baselineCostUsd").asDouble()).isGreaterThan(data.path("optimizedCostUsd").asDouble());
        assertThat(data.path("projectedSavingsUsd").asDouble())
            .isCloseTo(data.path("baselineCostUsd").asDouble() - data.path("optimizedCostUsd").asDouble(), offset(1e-9));

        Map<String, JsonNode> items = new HashMap<>();
        data.path("items").forEach(item -> items.put(item.path("jobName").asText(), item));

        JsonNode exempt = items.get("exempt job");
        assertThat(exempt.path("perJobStatus").asText()).isEqualTo("UNCHANGED_EXEMPT");
        assertThat(exempt.path("scheduledStart").asText()).isEqualTo(exempt.path("baselineStart").asText());

        // The exempt job uses 20 of 64 GPU-hours at 10:00, so the capacity-bound job (50 GPU-hours) cannot
        // run there in full.
        JsonNode capacityBound = items.get("capacity-bound job");
        assertThat(capacityBound.path("perJobStatus").asText()).isEqualTo("OPTIMISED");
        assertThat(capacityBound.path("allocations").get(0).path("gpuHours").asDouble()).isLessThanOrEqualTo(44.0 + 1e-6);

        Map<String, double[]> loadBySlot = new HashMap<>();
        for (JsonNode item : data.path("items")) {
            double cpu = 0.0;
            double gpu = 0.0;
            LocalDateTime previous = null;
            for (JsonNode allocation : item.path("allocations")) {
                LocalDateTime slot = LocalDateTime.parse(allocation.path("intervalStartUtc").asText(), UTC_Z);
                if (previous != null) {
                    assertThat(slot).as("consecutive hours for " + item.path("jobName").asText())
                        .isEqualTo(previous.plusHours(1));
                }
                previous = slot;
                double[] load = loadBySlot.computeIfAbsent(slot.toString(), ignored -> new double[2]);
                load[0] += allocation.path("cpuHours").asDouble();
                load[1] += allocation.path("gpuHours").asDouble();
                cpu += allocation.path("cpuHours").asDouble();
                gpu += allocation.path("gpuHours").asDouble();
            }
            assertThat(cpu).isCloseTo(item.path("allocatedCpuHours").asDouble(), offset(1e-6));
            assertThat(gpu).isCloseTo(item.path("allocatedGpuHours").asDouble(), offset(1e-6));

            LocalDateTime scheduledEnd = LocalDateTime.parse(item.path("scheduledEnd").asText(), UTC_Z);
            LocalDateTime deadline = jobs.findById(item.path("jobId").asLong()).orElseThrow().getDeadline();
            assertThat(scheduledEnd).as("deadline for " + item.path("jobName").asText()).isBeforeOrEqualTo(deadline);
        }
        loadBySlot.forEach((slot, load) -> {
            assertThat(load[0]).as("CPU load at " + slot).isLessThanOrEqualTo(MAX_CPU + 1e-6);
            assertThat(load[1]).as("GPU load at " + slot).isLessThanOrEqualTo(MAX_GPU + 1e-6);
        });
    }

    private void createJob(String name, double cpuHours, double gpuHours, LocalDateTime start,
                           LocalDateTime deadline, int flexibilityHours, boolean exempt) throws Exception {
        String body = """
            {
              "name": "%s", "cpuHours": %s, "gpuHours": %s,
              "originalStartTime": "%s", "deadline": "%s",
              "flexibilityWindowHours": %d, "isExempt": %s, "status": "PENDING"
            }
            """.formatted(name, cpuHours, gpuHours, start, deadline, flexibilityHours, exempt);
        mockMvc.perform(post("/api/jobs").with(user(OWNER)).contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }

    /** The operator's jobs as returned by {@code GET /api/jobs}. */
    private JsonNode storedJobs() throws Exception {
        String response = mockMvc.perform(get("/api/jobs").with(user(OWNER)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    /** Copies the optimiser's job fields from the stored jobs into a request. */
    private String optimiseRequest(JsonNode storedJobs) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode jobArray = request.putArray("jobs");
        for (JsonNode job : storedJobs) {
            ObjectNode input = jobArray.addObject();
            for (String field : new String[] {"id", "name", "cpuHours", "gpuHours", "originalStartTime", "deadline",
                "flexibilityWindowHours", "isExempt"}) {
                input.set(field, job.path(field));
            }
        }
        request.put("forecastLocation", LOCATION);
        return objectMapper.writeValueAsString(request);
    }

    private void seedPrices() {
        Map<Integer, Double> hourlyPrices = Map.ofEntries(
            Map.entry(10, 80.0), Map.entry(11, 20.0), Map.entry(12, 90.0),
            Map.entry(13, 15.0), Map.entry(14, 25.0), Map.entry(15, 30.0),
            Map.entry(16, 40.0), Map.entry(17, 95.0), Map.entry(18, 18.0),
            Map.entry(19, 22.0), Map.entry(20, 28.0), Map.entry(21, 35.0),
            Map.entry(22, 45.0));
        hourlyPrices.forEach((hour, price) -> predictions.save(PredictedPrice.builder()
            .intervalStartUtc(DAY.plusHours(hour))
            .location(LOCATION)
            .modelVersion("acceptance-test-model")
            .generatedAt(DAY.minusHours(1))
            .predictedPrice(price)
            .build()));
    }
}
