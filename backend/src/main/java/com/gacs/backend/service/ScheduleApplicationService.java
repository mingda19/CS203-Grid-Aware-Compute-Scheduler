package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.WorkloadScheduleDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.Facility;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.WorkloadSchedule;
import com.gacs.backend.repository.FacilityRepository;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ScheduleApplicationService {
    private final ScheduleGate gate;
    private final WorkloadScheduleRepository schedules;
    private final FacilityRepository facilities;
    private final AuditEventService audit;
    private final java.time.Clock clock;
    private final StoredScheduleReader reader;
    public ScheduleApplicationService(ScheduleGate gate, WorkloadScheduleRepository schedules,
                                      FacilityRepository facilities, AuditEventService audit, java.time.Clock clock, StoredScheduleReader reader) {
        this.gate = gate;
        this.schedules = schedules;
        this.facilities = facilities;
        this.audit = audit;
        this.clock = clock;
        this.reader = reader;
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public List<WorkloadScheduleDto> apply(UUID runId, String email) {
        OptimizationPlan plan = gate.owned(runId, email);
        var checked = gate.check(plan, email, true);
        var existing = schedules.findByRunIdOrderByIdAsc(plan.getRunId());
        if (plan.getAppliedAt() != null) return existing.stream().map(ScheduleApplicationService::dto).toList();
        String cluster = facilities.findAllByOwnerEmail(email).stream().min(Comparator.comparing(Facility::getId))
            .map(Facility::getName).orElse("Primary Compute Cluster");
        List<WorkloadSchedule> entries = new ArrayList<>();
        for (var item : checked.schedule().items()) {
            if (item.scheduledStart() == null || item.scheduledEnd() == null) continue;
            var entry = new WorkloadSchedule();
            entry.setOwnerEmail(email);
            entry.setRunId(plan.getRunId());
            entry.setJobId(item.jobId());
            entry.setTitle(item.jobName().substring(0, Math.min(200, item.jobName().length())));
            entry.setType(item.allocatedGpuHours() > 0 ? "ML Training" : "HPC Batch");
            entry.setCluster(cluster.substring(0, Math.min(200, cluster.length())));
            // Display estimate retained from the existing calendar integration.
            entry.setPowerKw(Math.round(item.allocatedCpuHours() * 5 + item.allocatedGpuHours() * 25));
            entry.setSavings(item.savingsUsd() == null ? null : String.format(Locale.ROOT, "$%.2f", item.savingsUsd()));
            entry.setStatus("Scheduled");
            entry.setStartUtc(item.scheduledStart());
            entry.setEndUtc(item.scheduledEnd());
            entry.setNotes(item.perJobStatus());
            entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Run has no executable schedule slots");
        schedules.saveAllAndFlush(entries);
        audit.recordEvent(new AuditEventRequestDto(AuditActionType.DISPATCH, plan.getRunId(),
            ScheduleHashUtil.computeSha256(plan.getResultJson()), "Applied approved schedule to calendar"));
        plan.markApplied(clock.instant());
        return entries.stream().map(ScheduleApplicationService::dto).toList();
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public WorkloadScheduleDto status(Long id, String status, String email) {
        if (status == null || !Set.of("Scheduled", "Running", "Throttled", "Completed").contains(status))
            throw new IllegalArgumentException("status must be Scheduled, Running, Throttled, or Completed");
        // Lock the same operator row before reading entries or checking their plan.
        gate.lock(email);
        var entry = schedules.findByIdAndOwnerEmail(id, email)
            .orElseThrow(() -> new ResourceNotFoundException("Workload schedule not found"));
        if (status.equals("Scheduled") || status.equals("Running")) {
            if (entry.getRunId() == null || entry.getJobId() == null)
                throw new ScheduleExportRejectedException(ScheduleExportRejectedException.Reason.NOT_APPROVED,
                    "Legacy calendar entry has no approved run; generate and apply a new recommendation");
            var plan = gate.owned(UUID.fromString(entry.getRunId()), email);
            gate.check(plan, email, true);
        }
        entry.setStatus(status);
        return dto(schedules.save(entry));
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public PlanDetail detail(UUID runId, String email) {
        var plan = gate.owned(runId, email);
        try { gate.check(plan, email, false); }
        catch (ScheduleExportRejectedException ignored) { /* Detail reports the persisted refusal. */ }
        return new PlanDetail(plan.getRunId(), plan.getReviewState(), plan.getInvalidationReason(),
            plan.getInvalidatedAt(), reader.read(plan.getResultJson()).orElse(null));
    }

    public record PlanDetail(String runId, ReviewState reviewState, String invalidationReason,
                             Instant invalidatedAt, OptimizeResponseDto recommendation) {}

    public static WorkloadScheduleDto dto(WorkloadSchedule s) {
        return new WorkloadScheduleDto(s.getId(), s.getTitle(), s.getType(), s.getCluster(), s.getPowerKw(),
            s.getSavings(), s.getStatus(), s.getStartUtc(), s.getEndUtc(), s.getNotes(), s.getRunId(), s.getJobId());
    }
}
