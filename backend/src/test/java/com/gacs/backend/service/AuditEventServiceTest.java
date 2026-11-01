package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventDetailDto;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.User;
import com.gacs.backend.util.ScheduleHashUtil;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private OptimizerRunRepository optimizerRunRepository;

    @Mock
    private OptimizationPlanRepository planRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.gacs.backend.repository.ScheduleRunRepository scheduleRunRepository;

    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        auditEventService = new AuditEventService(auditEventRepository, optimizerRunRepository, planRepository,
                userRepository, scheduleRunRepository, new StoredScheduleReader(),
                Clock.fixed(Instant.parse("2026-10-28T09:30:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthenticatedUser(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("AC4.1a — Actor identity extracted strictly from SecurityContextHolder principal")
    void testRecordEvent_ActorExtractedFromSecurityContext() {
        setAuthenticatedUser("operator@gacs.internal");

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Verified sufficient cooling and capacity margin"
        );

        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(invocation -> {
            AuditEvent event = invocation.getArgument(0);
            return AuditEvent.builder()
                    .id(1L)
                    .actor(event.getActor())
                    .actionType(event.getActionType())
                    .timestamp(event.getTimestamp())
                    .runId(event.getRunId())
                    .scheduleHash(event.getScheduleHash())
                    .operatorRationale(event.getOperatorRationale())
                    .build();
        });

        AuditEventResponseDto response = auditEventService.recordEvent(request);

        assertThat(response).isNotNull();
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-10-28T09:30:00Z"));
        assertThat(response.actor()).isEqualTo("operator@gacs.internal");
        assertThat(response.actionType()).isEqualTo(AuditActionType.APPROVE);
        assertThat(response.runId()).isEqualTo("run-777");
        assertThat(response.scheduleHash()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(response.operatorRationale()).isEqualTo("Verified sufficient cooling and capacity margin");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("operator@gacs.internal");
    }

    @Test
    @DisplayName("AC4.1a — Unauthenticated call throws AccessDeniedException")
    void testRecordEvent_UnauthenticatedFails() {
        // No authentication in SecurityContext
        SecurityContextHolder.clearContext();

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Some rationale"
        );

        assertThatThrownBy(() -> auditEventService.recordEvent(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unauthenticated caller");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    @DisplayName("AC4.1a — Anonymous user call throws AccessDeniedException")
    void testRecordEvent_AnonymousUserFails() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        context.setAuthentication(anonymous);
        SecurityContextHolder.setContext(context);

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Some rationale"
        );

        assertThatThrownBy(() -> auditEventService.recordEvent(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unauthenticated caller");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    @DisplayName("Retrieve audit events by runId")
    void testGetEventsByRunId() {
        AuditEvent event = AuditEvent.builder()
                .id(1L)
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.DISPATCH)
                .timestamp(Instant.now())
                .runId("run-999")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Dispatch confirmed")
                .build();

        when(auditEventRepository.findByRunIdOrderByTimestampDesc("run-999"))
                .thenReturn(List.of(event));

        List<AuditEventResponseDto> result = auditEventService.getEventsByRunId("run-999");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).runId()).isEqualTo("run-999");
        assertThat(result.get(0).actionType()).isEqualTo(AuditActionType.DISPATCH);
    }

    @Test
    @DisplayName("Retrieve audit event by ID")
    void testGetEventById() {
        AuditEvent event = AuditEvent.builder()
                .id(42L)
                .actor("admin@gacs.internal")
                .actionType(AuditActionType.OVERRIDE)
                .timestamp(Instant.now())
                .runId("run-override")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Override manual adjustment")
                .build();

        when(auditEventRepository.findById(42L)).thenReturn(Optional.of(event));

        AuditEventResponseDto result = auditEventService.getEventById(42L);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.actor()).isEqualTo("admin@gacs.internal");
        assertThat(result.actionType()).isEqualTo(AuditActionType.OVERRIDE);
    }

    private static final String STORED_RESULT = "{\"runId\":\"run-7\",\"status\":\"SCHEDULED\",\"provenOptimal\":true,"
            + "\"baselineCostUsd\":0.02,\"optimizedCostUsd\":0.002,\"projectedSavingsUsd\":0.018,"
            + "\"projectedSavingsPercent\":90.0,\"plainEnglishExplanation\":null,\"usedSeededFallbackPrices\":false,"
            + "\"items\":[{\"jobId\":5,\"jobName\":\"Train\",\"baselineStart\":\"2026-10-28T09:00:00Z\","
            + "\"scheduledStart\":\"2026-10-28T10:00:00Z\",\"scheduledEnd\":\"2026-10-28T11:00:00Z\","
            + "\"allocatedCpuHours\":4.0,\"allocatedGpuHours\":0.0,\"allocations\":[{\"intervalStartUtc\":"
            + "\"2026-10-28T10:00:00Z\",\"cpuHours\":4.0,\"gpuHours\":0.0}],\"perJobStatus\":\"OPTIMISED\","
            + "\"reason\":null,\"baselineCostUsd\":0.02,\"optimizedCostUsd\":0.002,\"savingsUsd\":0.018}]}";

    private static AuditEvent decision(long id, String runId, String scheduleHash) {
        return AuditEvent.builder()
                .id(id)
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.APPROVE)
                .timestamp(Instant.parse("2026-10-28T09:30:00Z"))
                .runId(runId)
                .scheduleHash(scheduleHash)
                .operatorRationale("Looks good")
                .build();
    }

    private static User user(long id, String email) {
        User user = new User(email, "password", "Operator", "ROLE_USER");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static OptimizerRun run(String id) {
        return OptimizerRun.builder()
                .id(id)
                .createdAt(Instant.parse("2026-10-28T09:00:00Z"))
                .solverName("OR-Tools CP-SAT")
                .solverVersion("9.10")
                .solverConfig("{\"timeLimitSeconds\":10}")
                .build();
    }

    /** Stores run-7 with a plan owned by user 3 and an approval that hashes the stored result. */
    private void storePlanOwnedByUser3(String eventHash) {
        OptimizerRun run = run("run-7");
        OptimizationPlan plan = new OptimizationPlan(run, user(3L, "owner@gacs.internal"), STORED_RESULT, List.of(5L),
                run.getCreatedAt());
        when(auditEventRepository.findById(7L)).thenReturn(Optional.of(decision(7L, "run-7", eventHash)));
        when(optimizerRunRepository.findById("run-7")).thenReturn(Optional.of(run));
        when(planRepository.findById("run-7")).thenReturn(Optional.of(plan));
    }

    @Test
    @DisplayName("AC6.1b — The run's owner sees the run snapshot and the stored schedule, which matches its hash")
    void testGetEventDetail_OwnerSeesStoredSchedule() {
        storePlanOwnedByUser3(ScheduleHashUtil.computeSha256(STORED_RESULT));
        when(userRepository.findByEmail("owner@gacs.internal")).thenReturn(Optional.of(user(3L, "owner@gacs.internal")));

        AuditEventDetailDto result = auditEventService.getEventDetail(7L, "owner@gacs.internal");

        assertThat(result.event().id()).isEqualTo(7L);
        assertThat(result.run().solverName()).isEqualTo("OR-Tools CP-SAT");
        assertThat(result.run().solverConfig()).isEqualTo("{\"timeLimitSeconds\":10}");
        assertThat(result.scheduleAvailability()).isEqualTo(AuditEventDetailDto.ScheduleAvailability.VISIBLE);
        assertThat(result.scheduleHashMatches()).isTrue();
        assertThat(result.schedule()).isNotNull();
        assertThat(result.schedule().items()).extracting(OptimizeResponseDto.ScheduleItem::jobId).containsExactly(5L);
    }

    @Test
    @DisplayName("AC6.1b — Other users see whether the stored schedule matches, but not the schedule")
    void testGetEventDetail_NonOwnerSeesNoSchedule() {
        storePlanOwnedByUser3(ScheduleHashUtil.computeSha256(STORED_RESULT));
        when(userRepository.findByEmail("other@gacs.internal")).thenReturn(Optional.of(user(9L, "other@gacs.internal")));

        AuditEventDetailDto result = auditEventService.getEventDetail(7L, "other@gacs.internal");

        assertThat(result.scheduleAvailability()).isEqualTo(AuditEventDetailDto.ScheduleAvailability.OWNER_ONLY);
        assertThat(result.scheduleHashMatches()).isTrue();
        assertThat(result.schedule()).isNull();
        assertThat(result.run()).isNotNull();
    }

    @Test
    @DisplayName("AC6.1b — A viewer with no database user is not treated as the owner")
    void testGetEventDetail_UnknownViewerSeesNoSchedule() {
        storePlanOwnedByUser3(ScheduleHashUtil.computeSha256(STORED_RESULT));
        when(userRepository.findByEmail("ghost@gacs.internal")).thenReturn(Optional.empty());

        AuditEventDetailDto result = auditEventService.getEventDetail(7L, "ghost@gacs.internal");

        assertThat(result.scheduleAvailability()).isEqualTo(AuditEventDetailDto.ScheduleAvailability.OWNER_ONLY);
        assertThat(result.schedule()).isNull();
    }

    @Test
    @DisplayName("AC6.1b — A stored schedule that no longer matches the event's hash is flagged")
    void testGetEventDetail_HashMismatchFlagged() {
        storePlanOwnedByUser3("0".repeat(64));
        when(userRepository.findByEmail("owner@gacs.internal")).thenReturn(Optional.of(user(3L, "owner@gacs.internal")));

        AuditEventDetailDto result = auditEventService.getEventDetail(7L, "owner@gacs.internal");

        assertThat(result.scheduleHashMatches()).isFalse();
        assertThat(result.schedule()).isNotNull();
    }

    @Test
    @DisplayName("AC6.1b — No stored run or schedule for the event's runId")
    void testGetEventDetail_NothingStored() {
        when(auditEventRepository.findById(8L)).thenReturn(Optional.of(decision(8L, "run-missing", "a".repeat(64))));
        when(optimizerRunRepository.findById("run-missing")).thenReturn(Optional.empty());
        when(planRepository.findById("run-missing")).thenReturn(Optional.empty());

        AuditEventDetailDto result = auditEventService.getEventDetail(8L, "owner@gacs.internal");

        assertThat(result.event().runId()).isEqualTo("run-missing");
        assertThat(result.run()).isNull();
        assertThat(result.scheduleAvailability()).isEqualTo(AuditEventDetailDto.ScheduleAvailability.NOT_STORED);
        assertThat(result.scheduleHashMatches()).isNull();
        assertThat(result.schedule()).isNull();
        verifyNoInteractions(userRepository);
    }
}
