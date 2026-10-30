package com.gacs.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.ScheduleDecisionDto;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.security.JwtUtils;
import com.gacs.backend.service.ScheduleReviewService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleDecisionIntegrationTest {
    private static final String REQUEST = """
        {"jobs":[{"id":1,"name":"test","cpuHours":1,"gpuHours":0,
        "originalStartTime":"2026-10-20T10:00:00Z","deadline":"2026-10-20T11:00:00Z",
        "flexibilityWindowHours":0,"isExempt":false}],
        "forecast":[{"intervalStartUtc":"2026-10-20T10:00:00Z","predictedPrice":10}]}
        """;

    @Autowired MockMvc mvc;
    @Autowired OptimizationPlanRepository plans;
    @Autowired ScheduleDecisionRepository decisions;
    @Autowired UserRepository users;
    @Autowired ScheduleReviewService reviews;
    @Autowired JwtUtils jwt;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<UUID> createdPlans = new ArrayList<>();
    private User owner;

    @BeforeEach
    void setup() {
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "test-password", "Operator", "ROLE_USER"));
    }

    @AfterEach
    void cleanup() {
        for (UUID runId : createdPlans) {
            decisions.deleteAll(decisions.findByPlanRunIdOrderByIdAsc(runId));
            plans.deleteById(runId);
        }
        users.deleteById(owner.getId());
    }

    private UUID optimize() throws Exception {
        Instant before = Instant.now();
        String response = mvc.perform(post("/api/schedules/optimize")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(owner))
                .contentType(APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(response).path("data");
        UUID runId = UUID.fromString(result.path("runId").asText());
        createdPlans.add(runId);
        OptimizationPlan plan = plans.findById(runId).orElseThrow();
        assertThat(plan.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(plan.getReviewState()).isEqualTo(ReviewState.PENDING);
        assertThat(plan.getCreatedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(json.readTree(plan.getResultJson())).isEqualTo(result);
        return runId;
    }

    @Test
    void allActionsAppendHistoryAndUseAuthenticatedDatabaseIdentity() throws Exception {
        UUID runId = optimize();
        String snapshot = plans.findById(runId).orElseThrow().getResultJson();
        for (DecisionAction action : DecisionAction.values()) {
            String response = mvc.perform(post("/api/schedules/" + runId + "/" + action.name().toLowerCase(Locale.ROOT))
                    // Even a signed token's numeric claim is not trusted as the database identity.
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(owner.getEmail(), -123L, "ROLE_USER", "Operator"))
                    .contentType(APPLICATION_JSON).content("{\"comment\":\"  reviewed  \",\"operatorId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value(owner.getId()))
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.action").value(action.name()))
                .andExpect(jsonPath("$.data.reviewState").value(action.reviewState().name()))
                .andExpect(jsonPath("$.data.comment").value("reviewed"))
                .andReturn().getResponse().getContentAsString();
            String timestamp = json.readTree(response).path("data").path("decidedAt").asText();
            assertThat(timestamp).endsWith("Z");
            assertThat(Instant.parse(timestamp)).isBeforeOrEqualTo(Instant.now());
        }
        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.comment").doesNotExist());
        var history = decisions.findByPlanRunIdOrderByIdAsc(runId);
        assertThat(history).extracting(ScheduleDecision::getAction)
            .containsExactly(DecisionAction.APPROVE, DecisionAction.REJECT, DecisionAction.REVISE, DecisionAction.APPROVE);
        assertThat(history).allSatisfy(d -> assertThat(d.getOperator().getId()).isEqualTo(owner.getId()));
        OptimizationPlan plan = plans.findById(runId).orElseThrow();
        assertThat(plan.getReviewState()).isEqualTo(ReviewState.APPROVED);
        assertThat(plan.getResultJson()).isEqualTo(snapshot);
    }

    @Test
    void invalidRequestsAndUnauthorizedAccessDoNotWrite() throws Exception {
        UUID runId = optimize();
        String route = "/api/schedules/" + runId + "/approve";
        for (String body : List.of("", "null", "[]", "1", "\"text\"", "{", "{\"comment\":4}",
                "{\"comment\":{}}", "{\"comment\":\"" + "x".repeat(2001) + "\"}")) {
            mvc.perform(post(route).with(user(owner.getEmail())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/schedules/not-a-uuid/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(route).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(route).header("Authorization", "Bearer invalid-token")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(route).with(user("missing@test.io")).contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        User other = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Other", "ROLE_ADMIN"));
        try {
            String hidden = mvc.perform(post(route).with(user(other.getEmail()).roles("ADMIN"))
                    .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String missing = mvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/approve")
                    .with(user(other.getEmail()).roles("ADMIN")).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(hidden).isEqualTo(missing);
        } finally { users.deleteById(other.getId()); }
        assertThat(decisions.findByPlanRunIdOrderByIdAsc(runId)).isEmpty();
        assertThat(plans.findById(runId).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void failedOptimizationDoesNotCreatePlan() throws Exception {
        long before = plans.count();
        mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/schedules/optimize").with(user("missing@test.io"))
                .contentType(APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isUnauthorized());
        assertThat(plans.count()).isEqualTo(before);
    }

    @Test
    void rollbackRevertsBothHistoryAndReviewState() throws Exception {
        UUID runId = optimize();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            reviews.decide(runId, DecisionAction.APPROVE, "reviewed", owner.getEmail());
            throw new IllegalStateException("Force rollback after decision insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(decisions.findByPlanRunIdOrderByIdAsc(runId)).isEmpty();
        assertThat(plans.findById(runId).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void acceptsNullAndMaximumLengthComments() throws Exception {
        UUID runId = optimize();
        for (String commentJson : List.of("null", "\"" + "x".repeat(2000) + "\"")) {
            mvc.perform(post("/api/schedules/" + runId + "/revise").with(user(owner.getEmail()))
                    .contentType(APPLICATION_JSON).content("{\"comment\":" + commentJson + "}"))
                .andExpect(status().isOk());
        }
        var history = decisions.findByPlanRunIdOrderByIdAsc(runId);
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getComment()).isNull();
        assertThat(history.getLast().getComment()).hasSize(2000);
    }

    @Test
    void concurrentDecisionsSerializeAndLatestHistoryMatchesState() throws Exception {
        UUID runId = optimize();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<ScheduleDecisionDto>> calls = new ArrayList<>();
            for (DecisionAction action : List.of(DecisionAction.APPROVE, DecisionAction.REJECT)) {
                calls.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return reviews.decide(runId, action, "concurrent", owner.getEmail());
                }));
            }
            start.countDown();
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).decisionId()).isNotNull();
        }
        var history = decisions.findByPlanRunIdOrderByIdAsc(runId);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(ScheduleDecision::getAction)
            .containsExactlyInAnyOrder(DecisionAction.APPROVE, DecisionAction.REJECT);
        assertThat(plans.findById(runId).orElseThrow().getReviewState())
            .isEqualTo(history.getLast().getAction().reviewState());
    }
}
