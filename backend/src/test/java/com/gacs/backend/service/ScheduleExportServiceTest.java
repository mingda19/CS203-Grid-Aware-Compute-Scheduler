package com.gacs.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.exception.ScheduleExportRejectedException.Reason;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.OptimizerRunStatus;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.ScheduleExportService.ExportFile;
import com.gacs.backend.service.ScheduleExportService.Format;
import com.gacs.backend.util.ScheduleHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GRID-135 AC3 — approval-gate boundaries that need a fixed clock (docs/api-contract.md section 6.6).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleExportServiceTest {

    private static final UUID RUN_ID = UUID.fromString("7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44");
    private static final String EMAIL = "operator@gacs.internal";
    private static final LocalDateTime SLOT = LocalDateTime.parse("2026-11-10T04:00:00");

    @Mock private OptimizationPlanRepository plans;
    @Mock private AuditEventRepository auditEvents;
    @Mock private JobRepository jobs;
    @Mock private UserRepository users;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private User owner;
    private OptimizerRun storedRun;
    private AuditEvent storedApproval;

    @BeforeEach
    void setUp() {
        owner = new User(EMAIL, "password", "Operator", "ROLE_USER");
        ReflectionTestUtils.setField(owner, "id", 7L);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
        when(plans.findOwnedInStateCreatedAfter(anyLong(), any(), any())).thenReturn(List.of());
    }

    private ScheduleExportService service(String now) {
        return new ScheduleExportService(plans, auditEvents, jobs, users, new ApprovedScheduleCsvSerializer(),
                new ApprovedScheduleJsonSerializer(), new StoredScheduleReader(),
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static ScheduleItem scheduled(long jobId, double cpuHours) {
        return new ScheduleItem(jobId, "job " + jobId, SLOT, SLOT, SLOT.plusHours(1), cpuHours, 0.0,
                List.of(new Allocation(SLOT, cpuHours, 0.0)), "OPTIMISED", null, 0.1, 0.1, 0.0);
    }

    private static ScheduleItem unscheduled(long jobId) {
        return new ScheduleItem(jobId, "job " + jobId, SLOT, null, null, 0.0, 0.0, List.of(),
                "NO_FEASIBLE_SLOT", "cluster full", null, null, null);
    }

    private static Job job(long id, double cpuHours) {
        Job job = Job.builder().ownerEmail(EMAIL).name("job " + id).cpuHours(cpuHours).gpuHours(0.0)
                .originalStartTime(SLOT).deadline(SLOT.plusHours(2)).flexibilityWindowHours(0).isExempt(false).build();
        job.setId(id);
        return job;
    }

    /** Stores an approved plan with a matching approval record, as ScheduleReviewService would. */
    private void storeApproved(String resultJson) {
        OptimizerRun run = OptimizerRun.builder().id(RUN_ID.toString()).createdAt(Instant.parse("2026-11-01T00:00:00Z"))
                .solverName("HybridAllocationSolver").solverVersion("1").solverConfig("{\"cpuKwhPerHour\":0.05}")
                .status(OptimizerRunStatus.SCHEDULED).provenOptimal(true).build();
        OptimizationPlan plan = new OptimizationPlan(run, owner, resultJson, List.of(), run.getCreatedAt());
        storedRun = run;
        ReflectionTestUtils.setField(plan, "runId", RUN_ID.toString());
        plan.recordDecision(AuditActionType.APPROVE);
        assertThat(plan.getReviewState()).isEqualTo(ReviewState.APPROVED);
        when(plans.findOwned(RUN_ID.toString(), 7L)).thenReturn(Optional.of(plan));
        storedApproval = AuditEvent.builder().id(1L).actor(EMAIL).actionType(AuditActionType.APPROVE)
                .timestamp(Instant.parse("2026-11-01T00:00:00Z")).runId(RUN_ID.toString())
                .scheduleHash(ScheduleHashUtil.computeSha256(resultJson)).operatorRationale("ok").build();
        when(auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(eq(RUN_ID.toString()), anyCollection()))
                .thenReturn(Optional.of(storedApproval));
    }

    private OptimizeResponseDto storeApproved(ScheduleItem... items) throws Exception {
        OptimizeResponseDto schedule = new OptimizeResponseDto(RUN_ID.toString(), "SCHEDULED", true, 0.1, 0.1, 0.0,
                0.0, null, false, List.of(items));
        storeApproved(mapper.writeValueAsString(schedule));
        return schedule;
    }

    @Test
    @DisplayName("A plan turns stale exactly when its last slot ends")
    void elapsed_BoundaryIsLastSlotEnd() throws Exception {
        storeApproved(scheduled(1, 4.0));
        when(jobs.findByOwnerEmailAndIdIn(anyString(), anyCollection())).thenReturn(List.of(job(1, 4.0)));

        assertThat(service("2026-11-10T04:59:59Z").export(RUN_ID, Format.CSV, EMAIL).content()).isNotEmpty();
        assertThatThrownBy(() -> service("2026-11-10T05:00:00Z").export(RUN_ID, Format.CSV, EMAIL))
                .isInstanceOfSatisfying(ScheduleExportRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.STALE));
    }

    @Test
    @DisplayName("Staleness is reported before job changes")
    void staleCheckedBeforeJobChanges() throws Exception {
        storeApproved(scheduled(1, 4.0));
        when(jobs.findByOwnerEmailAndIdIn(anyString(), anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> service("2026-11-11T00:00:00Z").export(RUN_ID, Format.CSV, EMAIL))
                .isInstanceOfSatisfying(ScheduleExportRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.STALE));
        assertThatThrownBy(() -> service("2026-11-09T00:00:00Z").export(RUN_ID, Format.CSV, EMAIL))
                .isInstanceOfSatisfying(ScheduleExportRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALIDATED));
    }

    @Test
    @DisplayName("Unscheduled jobs are not checked against the job list and never make a plan stale")
    void unscheduledJobs_NotChecked() throws Exception {
        storeApproved(unscheduled(99));

        assertThat(service("2030-01-01T00:00:00Z").export(RUN_ID, Format.JSON, EMAIL).content()).isNotEmpty();
        verifyNoInteractions(jobs);
    }

    @Test
    @DisplayName("Work hours are compared with a tolerance for floating-point noise")
    void hoursComparedWithTolerance() throws Exception {
        storeApproved(scheduled(1, 0.3));
        when(jobs.findByOwnerEmailAndIdIn(anyString(), anyCollection())).thenReturn(List.of(job(1, 0.1 + 0.2)));

        assertThat(service("2026-11-09T00:00:00Z").export(RUN_ID, Format.CSV, EMAIL).content()).isNotEmpty();
    }

    @Test
    @DisplayName("A stored plan that cannot be read is invalidated, even when its hash matches")
    void unreadablePlan_Invalidated() {
        storeApproved("not json");

        assertThatThrownBy(() -> service("2026-11-09T00:00:00Z").export(RUN_ID, Format.CSV, EMAIL))
                .isInstanceOfSatisfying(ScheduleExportRejectedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.INVALIDATED);
                    assertThat(e.getMessage()).contains("cannot be read");
                });
    }

    @Test
    @DisplayName("Files are named schedule-run-<id> with the format's extension and content type")
    void exportFile_NameTypeAndContent() throws Exception {
        OptimizeResponseDto schedule = storeApproved(scheduled(1, 4.0));
        when(jobs.findByOwnerEmailAndIdIn(anyString(), anyCollection())).thenReturn(List.of(job(1, 4.0)));
        ScheduleExportService service = service("2026-11-09T00:00:00Z");

        ExportFile csv = service.export(RUN_ID, Format.CSV, EMAIL);
        assertThat(csv.filename()).isEqualTo("schedule-run-" + RUN_ID + ".csv");
        assertThat(csv.contentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .isEqualTo(new ApprovedScheduleCsvSerializer().serialize(schedule));

        ExportFile json = service.export(RUN_ID, Format.JSON, EMAIL);
        assertThat(json.filename()).isEqualTo("schedule-run-" + RUN_ID + ".json");
        assertThat(json.contentType().toString()).isEqualTo("application/json");
        assertThat(new String(json.content(), StandardCharsets.UTF_8))
                .isEqualTo(new ApprovedScheduleJsonSerializer().serialize(schedule, storedRun, storedApproval))
                .contains("\"solver\":{\"name\":\"HybridAllocationSolver\"")
                .contains("\"approval\":{\"decisionId\":1,\"approvedBy\":\"" + EMAIL + "\"");
    }
}
