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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OptimizationPlanRepository plans;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.gacs.backend.service.AuditEventService audit;
    @Autowired AuditEventRepository decisions;
    @Autowired UserRepository users;
    @Autowired OptimizerRunRepository runs;
    @Autowired ScheduleExplanationRepository explanations;
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
            // Audit records remain append-only; each test uses unique run IDs.
            plans.deleteById(runId.toString());
            runs.deleteById(runId.toString());
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
        OptimizationPlan plan = plans.findById(runId.toString()).orElseThrow();
        assertThat(plan.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(plan.getReviewState()).isEqualTo(ReviewState.PENDING);
        assertThat(plan.getCreatedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(json.readTree(plan.getResultJson())).isEqualTo(result);
        OptimizerRun run = runs.findById(runId.toString()).orElseThrow();
        assertThat(run.getCreatedAt()).isEqualTo(plan.getCreatedAt());
        assertThat(plan.getRun().getId()).isEqualTo(runId.toString());
        assertThat(explanations.findByRunIdAndOwnerEmail(runId.toString(), owner.getEmail())).isPresent();
        assertThat(run.getSolverName()).isEqualTo("HybridAllocationSolver");
        assertThat(run.getSolverVersion()).isEqualTo(com.gacs.backend.service.allocation.AllocationSolver.VERSION);
        assertThat(run.getStatus().name()).isEqualTo(result.path("status").asText());
        assertThat(run.getProvenOptimal()).isEqualTo(result.path("provenOptimal").asBoolean());
        assertThat(json.readTree(run.getSolverConfig()).path("cpuKwhPerHour").asDouble()).isEqualTo(0.05);
        return runId;
    }

    private List<AuditEvent> history(UUID runId) {
        return decisions.findByRunIdOrderByTimestampDesc(runId.toString()).stream()
            .sorted(Comparator.comparing(AuditEvent::getId)).toList();
    }

    private static ReviewState state(AuditActionType action) {
        return switch (action) {
            case APPROVE -> ReviewState.APPROVED;
            case REJECT -> ReviewState.REJECTED;
            case REVISE -> ReviewState.REVISION_REQUESTED;
            default -> throw new IllegalArgumentException();
        };
    }

    private void authenticate() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                owner.getEmail(), "unused", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void allActionsAppendHistoryAndUseAuthenticatedDatabaseIdentity() throws Exception {
        UUID runId = optimize();
        String snapshot = plans.findById(runId.toString()).orElseThrow().getResultJson();
        for (AuditActionType action : List.of(AuditActionType.APPROVE, AuditActionType.REJECT, AuditActionType.REVISE)) {
            String response = mvc.perform(post("/api/schedules/" + runId + "/" + action.name().toLowerCase(Locale.ROOT))
                    // Even a signed token's numeric claim is not trusted as the database identity.
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(owner.getEmail(), -123L, "ROLE_USER", "Operator"))
                    .contentType(APPLICATION_JSON).content("{\"comment\":\"  reviewed  \",\"operatorId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatorId").value(owner.getId()))
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.action").value(action.name()))
                .andExpect(jsonPath("$.data.reviewState").value(state(action).name()))
                .andExpect(jsonPath("$.data.comment").value("reviewed"))
                .andReturn().getResponse().getContentAsString();
            String timestamp = json.readTree(response).path("data").path("decidedAt").asText();
            assertThat(timestamp).endsWith("Z");
            assertThat(Instant.parse(timestamp)).isBeforeOrEqualTo(Instant.now());
            AuditEvent saved = decisions.findById(json.readTree(response).path("data").path("decisionId").asLong()).orElseThrow();
            assertThat(saved.getActionType()).isEqualTo(action);
            assertThat(saved.getTimestamp()).isCloseTo(Instant.parse(timestamp), within(1, java.time.temporal.ChronoUnit.MICROS));
            assertThat(saved.getOperatorRationale()).isEqualTo("reviewed");
        }
        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed again\"}"))
            .andExpect(status().isOk());
        var history = history(runId);
        assertThat(history).extracting(AuditEvent::getActionType)
            .containsExactly(AuditActionType.APPROVE, AuditActionType.REJECT, AuditActionType.REVISE, AuditActionType.APPROVE);
        String expectedHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(history).allSatisfy(d -> {
            assertThat(d.getActor()).isEqualTo(owner.getEmail());
            assertThat(d.getScheduleHash()).isEqualTo(expectedHash);
        });
        OptimizationPlan plan = plans.findById(runId.toString()).orElseThrow();
        assertThat(plan.getReviewState()).isEqualTo(ReviewState.APPROVED);
        assertThat(plan.getResultJson()).isEqualTo(snapshot);
    }

    @Test
    void invalidRequestsAndUnauthorizedAccessDoNotWrite() throws Exception {
        UUID runId = optimize();
        String route = "/api/schedules/" + runId + "/approve";
        org.mockito.Mockito.clearInvocations(plans);
        for (String body : List.of("", "null", "[]", "1", "\"text\"", "{", "{}", "{\"comment\":null}", "{\"comment\":\"   \"}", "{\"comment\":true}", "{\"comment\":4}",
                "{\"comment\":{}}", "{\"comment\":[]}", "{\"comment\":\"\"}", "{\"comment\":\"" + "x".repeat(2001) + "\"}")) {
            mvc.perform(post(route).with(user(owner.getEmail())).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(plans);
        mvc.perform(post("/api/schedules/not-a-uuid/approve").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(route).contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(route).header("Authorization", "Bearer invalid-token")
                .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(route).with(user("missing@test.io")).contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isUnauthorized());
        User other = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Other", "ROLE_ADMIN"));
        try {
            String hidden = mvc.perform(post(route).with(user(other.getEmail()).roles("ADMIN"))
                    .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String missing = mvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/approve")
                    .with(user(other.getEmail()).roles("ADMIN")).contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(hidden).isEqualTo(missing);
        } finally { users.deleteById(other.getId()); }
        assertThat(history(runId)).isEmpty();
        assertThat(plans.findById(runId.toString()).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void failedOptimizationDoesNotCreatePlan() throws Exception {
        long before = plans.count();
        long runsBefore = runs.count();
        mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/schedules/optimize").with(user("missing@test.io"))
                .contentType(APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isUnauthorized());
        assertThat(plans.count()).isEqualTo(before);
        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    void rollbackRevertsBothHistoryAndReviewState() throws Exception {
        UUID runId = optimize();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            authenticate();
            try { reviews.decide(runId, AuditActionType.APPROVE, "reviewed", owner.getEmail()); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            throw new IllegalStateException("Force rollback after decision insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(history(runId)).isEmpty();
        assertThat(plans.findById(runId.toString()).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void acceptsMaximumLengthComments() throws Exception {
        UUID runId = optimize();
        for (String commentJson : List.of("\"" + "x".repeat(2000) + "\"")) {
            mvc.perform(post("/api/schedules/" + runId + "/revise").with(user(owner.getEmail()))
                    .contentType(APPLICATION_JSON).content("{\"comment\":" + commentJson + "}"))
                .andExpect(status().isOk());
        }
        var history = history(runId);
        assertThat(history).hasSize(1);
        assertThat(history.getLast().getOperatorRationale()).hasSize(2000);
    }

    @Test
    void concurrentDecisionsSerializeAndLatestHistoryMatchesState() throws Exception {
        UUID runId = optimize();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<ScheduleDecisionDto>> calls = new ArrayList<>();
            for (AuditActionType action : List.of(AuditActionType.APPROVE, AuditActionType.REJECT)) {
                calls.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    authenticate();
                    try { return reviews.decide(runId, action, "concurrent", owner.getEmail()); }
                    finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
                }));
            }
            start.countDown();
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).decisionId()).isNotNull();
        }
        var history = history(runId);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(AuditEvent::getActionType)
            .containsExactlyInAnyOrder(AuditActionType.APPROVE, AuditActionType.REJECT);
        assertThat(plans.findById(runId.toString()).orElseThrow().getReviewState())
            .isEqualTo(state(history.getLast().getActionType()));
    }
    @Test
    void planPersistenceFailureRollsBackRunAndExplanation() throws Exception {
        long plansBefore = plans.count();
        long runsBefore = runs.count();
        long explanationsBefore = explanations.count();
        org.mockito.Mockito.doAnswer(invocation -> {
            runs.flush();
            throw new IllegalStateException("Plan write failed");
        }).when(plans).save(org.mockito.ArgumentMatchers.any(OptimizationPlan.class));
        try {
            mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                    .contentType(APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError());
        } finally {
            org.mockito.Mockito.reset(plans);
        }
        assertThat(plans.count()).isEqualTo(plansBefore);
        assertThat(runs.count()).isEqualTo(runsBefore);
        assertThat(explanations.count()).isEqualTo(explanationsBefore);
    }

    @Test
    void auditFailureRollsBackReviewState() throws Exception {
        UUID runId = optimize();
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit write failed"))
            .when(audit).recordEvent(org.mockito.ArgumentMatchers.any());
        try {
            mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
                    .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isInternalServerError());
        } finally {
            org.mockito.Mockito.reset(audit);
        }
        assertThat(history(runId)).isEmpty();
        assertThat(plans.findById(runId.toString()).orElseThrow().getReviewState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void foreignKeyPreventsRemovingRunWithExistingPlan() throws Exception {
        UUID runId = optimize();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            runs.deleteById(runId.toString());
            runs.flush();
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(runs.findById(runId.toString())).isPresent();
        assertThat(plans.findById(runId.toString())).isPresent();
    }

}
