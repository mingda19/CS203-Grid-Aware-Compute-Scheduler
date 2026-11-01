package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventFilter;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.OptimizerRunStatus;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GRID-134 AC6.1a — filtered audit queries run against the database. Audit rows are append-only, so each test
 * tags its own rows with a unique ID and scopes its queries to them instead of clearing the table.
 */
@SpringBootTest
@Transactional
class AuditEventQueryTest {

    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Autowired private AuditEventService auditEventService;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private UserRepository users;
    @Autowired private OptimizerRunRepository runs;
    @Autowired private OptimizationPlanRepository plans;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private String runId(String name) {
        return "run-" + tag + "-" + name;
    }

    private String actor(String name) {
        return name + "-" + tag + "@gacs.internal";
    }

    @BeforeEach
    void seed() {
        auditEventRepository.saveAll(List.of(
                event(actor("alice"), AuditActionType.APPROVE, "2026-10-27T10:00:00Z", runId("aaa-111")),
                event(actor("bob"), AuditActionType.REJECT, "2026-10-28T09:00:00Z", runId("bbb-222")),
                event(actor("Alice"), AuditActionType.REJECT, "2026-10-28T23:59:59Z", runId("aaa-333")),
                event(actor("carol_ops"), AuditActionType.DISPATCH, "2026-10-29T00:00:00Z", runId("ccc-444"))
        ));
    }

    private static AuditEvent event(String actor, AuditActionType action, String timestamp, String runId) {
        return AuditEvent.builder()
                .actor(actor)
                .actionType(action)
                .timestamp(Instant.parse(timestamp))
                .runId(runId)
                .scheduleHash(HASH)
                .operatorRationale("rationale for " + runId)
                .build();
    }

    private static AuditEventFilter filter(Instant from, Instant to, String actor, AuditActionType action,
                                           String runId, Long jobId) {
        return new AuditEventFilter(from, to, actor, action, runId, jobId);
    }

    private List<String> runIds(AuditEventFilter filter) {
        return auditEventService.getEvents(filter, PageRequest.of(0, 20)).getContent().stream()
                .map(AuditEventResponseDto::runId)
                .toList();
    }

    /** Stores a plan for the run that lists the given jobs, as ScheduleReviewService does when optimising. */
    private void storePlan(String runId, User owner, Long... jobIds) {
        OptimizerRun run = runs.save(OptimizerRun.builder().id(runId).createdAt(Instant.parse("2026-10-27T09:00:00Z"))
                .solverName("HybridAllocationSolver").solverVersion("1").solverConfig("{}")
                .status(OptimizerRunStatus.SCHEDULED).provenOptimal(true).build());
        plans.save(new OptimizationPlan(run, owner, "{}", List.of(jobIds), run.getCreatedAt()));
    }

    @Test
    @DisplayName("Results come back newest first")
    void newestFirst() {
        assertThat(runIds(filter(null, null, null, null, tag, null)))
                .containsExactly(runId("ccc-444"), runId("aaa-333"), runId("bbb-222"), runId("aaa-111"));
    }

    @Test
    @DisplayName("Date range includes 'from' and excludes 'to'")
    void dateRange_FromInclusiveToExclusive() {
        AuditEventFilter range = filter(Instant.parse("2026-10-28T09:00:00Z"), Instant.parse("2026-10-29T00:00:00Z"),
                null, null, tag, null);
        assertThat(runIds(range)).containsExactly(runId("aaa-333"), runId("bbb-222"));
    }

    @Test
    @DisplayName("Operator filter is a case-insensitive substring match")
    void actor_CaseInsensitiveSubstring() {
        assertThat(runIds(filter(null, null, ("ALICE-" + tag).toUpperCase(Locale.ROOT), null, null, null)))
                .containsExactly(runId("aaa-333"), runId("aaa-111"));
    }

    @Test
    @DisplayName("Operator filter treats LIKE wildcards literally")
    void actor_WildcardsEscaped() {
        assertThat(runIds(filter(null, null, "_", null, tag, null))).containsExactly(runId("ccc-444"));
        assertThat(runIds(filter(null, null, "%", null, tag, null))).isEmpty();
    }

    @Test
    @DisplayName("Decision outcome filter matches the action type exactly")
    void actionType_Exact() {
        assertThat(runIds(filter(null, null, null, AuditActionType.REJECT, tag, null)))
                .containsExactly(runId("aaa-333"), runId("bbb-222"));
    }

    @Test
    @DisplayName("Target run filter is a trimmed, case-insensitive substring match")
    void runId_Substring() {
        assertThat(runIds(filter(null, null, null, null, "  " + tag.toUpperCase(Locale.ROOT) + "-AAA ", null)))
                .containsExactly(runId("aaa-333"), runId("aaa-111"));
    }

    @Test
    @DisplayName("AC6.1a — Target job filter finds decisions on runs whose stored plan lists the job")
    void jobId_MatchesRunsWhosePlanListsTheJob() {
        User owner = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
        long jobA = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        long jobB = jobA + 1;
        storePlan(runId("aaa-111"), owner, jobA, jobB);
        storePlan(runId("bbb-222"), owner, jobB);

        assertThat(runIds(filter(null, null, null, null, null, jobA))).containsExactly(runId("aaa-111"));
        assertThat(runIds(filter(null, null, null, null, null, jobB))).containsExactly(runId("bbb-222"), runId("aaa-111"));
        assertThat(runIds(filter(null, null, null, AuditActionType.REJECT, null, jobB))).containsExactly(runId("bbb-222"));
        assertThat(runIds(filter(null, null, null, null, null, jobB + 1))).isEmpty();
    }

    @Test
    @DisplayName("Filters combine with AND")
    void filters_Combine() {
        AuditEventFilter combined = filter(Instant.parse("2026-10-28T00:00:00Z"), null, "alice-" + tag,
                AuditActionType.REJECT, "aaa", null);
        assertThat(runIds(combined)).containsExactly(runId("aaa-333"));
    }

    @Test
    @DisplayName("Results are paginated with total counts")
    void pagination() {
        Page<AuditEventResponseDto> page = auditEventService.getEvents(filter(null, null, null, null, tag, null),
                PageRequest.of(1, 3));
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AuditEventResponseDto::runId).containsExactly(runId("aaa-111"));
    }

    @Test
    @DisplayName("'from' not before 'to' is rejected")
    void invertedRange_Rejected() {
        Instant t = Instant.parse("2026-10-28T00:00:00Z");
        assertThatThrownBy(() -> auditEventService.getEvents(filter(t, t, null, null, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
