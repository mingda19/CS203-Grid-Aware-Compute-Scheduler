package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.exception.ScheduleExportRejectedException.Reason;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.gacs.backend.service.ApprovedScheduleExport.utc;

/**
 * Shared approval/provenance/freshness checks. Callers hold the operator lock in a transaction
 * and commit ScheduleExportRejectedException refusals so detected invalidation is retained.
 */
@Service
public class ScheduleGate {

    private static final Set<AuditActionType> DECISIONS =
            EnumSet.of(AuditActionType.APPROVE, AuditActionType.REJECT, AuditActionType.REVISE);
    private static final double HOURS_TOLERANCE = 1e-9;

    private final OptimizationPlanRepository plans;
    private final AuditEventRepository auditEvents;
    private final JobRepository jobs;
    private final ScheduleInputs inputs;
    private final StoredScheduleReader reader;
    private final Clock clock;

    public ScheduleGate(OptimizationPlanRepository plans, AuditEventRepository auditEvents,
                        JobRepository jobs, StoredScheduleReader reader,
                        Clock clock, ScheduleInputs inputs) {
        this.plans = plans;
        this.auditEvents = auditEvents;
        this.jobs = jobs;
        this.reader = reader;
        this.clock = clock;
        this.inputs = inputs;
    }

    public void lock(String email) { inputs.lock(email); }

    public OptimizationPlan owned(UUID runId, String email) {
        User owner = inputs.lock(email);
        return plans.findOwnedForUpdate(runId.toString(), owner.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));
    }

    public record Checked(OptimizeResponseDto schedule, AuditEvent approval) {}

    public Checked check(OptimizationPlan plan, String email, boolean approvalRequired) {
        try {
            if (plan.getReviewState() == ReviewState.INVALIDATED)
                throw new ScheduleExportRejectedException(Reason.INVALIDATED, plan.getInvalidationReason());
            if (plan.getReviewState() == ReviewState.STALE)
                throw new ScheduleExportRejectedException(Reason.STALE, plan.getInvalidationReason());
            if (approvalRequired) requireApproved(plan);
            AuditEvent approval = approvalRequired || plan.getReviewState() == ReviewState.APPROVED
                ? requireMatchingApproval(plan) : null;
            OptimizeResponseDto schedule = reader.read(plan.getResultJson())
                .orElseThrow(() -> invalidated(plan.getRunId(), "its stored schedule cannot be read"));
            if (OptimizeResponseDto.STATUS_INFEASIBLE.equals(schedule.status()))
                throw new ScheduleExportRejectedException(Reason.NOT_APPROVED,
                    "Schedule run " + plan.getRunId() + " is INFEASIBLE: no job could be placed");
            requireCurrent(plan, schedule, plan.getOwner());
            String mismatch = inputs.mismatch(plan, email);
            if (mismatch != null) throw invalidated(plan.getRunId(), mismatch);
            requireJobsUnchanged(plan.getRunId(), schedule, plan.getOwner());
            requireInputsUnchanged(plan, StoredScheduleReader.jobIds(schedule), plan.getOwner());
            return new Checked(schedule, approval);
        } catch (ScheduleExportRejectedException ex) {
            if (ex.getReason() != Reason.NOT_APPROVED)
                plan.invalidate(ex.getReason() == Reason.STALE ? ReviewState.STALE : ReviewState.INVALIDATED,
                    ex.getMessage(), clock.instant());
            throw ex;
        }
    }

    /** A new approval permanently supersedes older overlapping approvals. */
    public void supersede(OptimizationPlan approved) {
        for (var other : plans.findByOwnerIdAndReviewState(approved.getOwner().getId(), ReviewState.APPROVED))
            if (!other.getRunId().equals(approved.getRunId()) &&
                !java.util.Collections.disjoint(other.getJobIds(), approved.getJobIds()))
                other.invalidate(ReviewState.STALE, "Superseded by approved run " + approved.getRunId(), clock.instant());
    }

    private static void requireApproved(OptimizationPlan plan) {
        if (plan.getReviewState() != ReviewState.APPROVED) {
            throw new ScheduleExportRejectedException(Reason.NOT_APPROVED, "Schedule run " + plan.getRunId()
                    + " is " + plan.getReviewState() + ", not APPROVED, so it cannot be applied, executed, or exported");
        }
    }

    /**
     * The audit log is the record of approval: its latest decision must approve exactly the stored plan.
     *
     * @return that approval event
     */
    private AuditEvent requireMatchingApproval(OptimizationPlan plan) {
        String runId = plan.getRunId();
        AuditEvent latest = auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(runId, DECISIONS).orElse(null);
        if (latest == null || latest.getActionType() != AuditActionType.APPROVE) {
            throw invalidated(runId, "its review state is APPROVED but its latest recorded decision is "
                    + (latest == null ? "missing" : latest.getActionType()));
        }
        if (!ScheduleHashUtil.computeSha256(plan.getResultJson()).equals(latest.getScheduleHash())) {
            throw invalidated(runId, "the stored schedule no longer matches the hash recorded when it was approved");
        }
        return latest;
    }

    private void requireCurrent(OptimizationPlan plan, OptimizeResponseDto schedule, User owner) {
        String runId = plan.getRunId();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Optional<LocalDateTime> lastEnd = items(schedule).stream()
                .map(ScheduleItem::scheduledEnd)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (lastEnd.isPresent() && !now.isBefore(lastEnd.get())) {
            throw stale(runId, "all of its slots ended by " + utc(lastEnd.get()));
        }

        Set<Long> jobIds = StoredScheduleReader.jobIds(schedule);
        if (plan.getReviewState() != ReviewState.APPROVED) return;
        AuditEvent ownDecision = auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(runId, DECISIONS).orElse(null);
        for (OptimizationPlan newer : plans.findByOwnerIdAndReviewState(owner.getId(), ReviewState.APPROVED)) {
            if (newer.getRunId().equals(runId)) continue;
            AuditEvent decision = auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(newer.getRunId(), DECISIONS).orElse(null);
            if (decision == null || decision.getActionType() != AuditActionType.APPROVE ||
                (ownDecision != null && decision.getId() <= ownDecision.getId())) continue;
            Set<Long> shared = new TreeSet<>(newer.getJobIds());
            shared.retainAll(jobIds);
            if (!shared.isEmpty()) throw stale(runId, "run " + newer.getRunId()
                + " was approved later and also schedules job(s) " + shared);
        }
    }

    /** Applies the optimiser's per-job rules (contract section 6.4) to the jobs as they are now. */
    private void requireJobsUnchanged(String runId, OptimizeResponseDto schedule, User owner) {
        List<ScheduleItem> scheduled = items(schedule).stream()
                .filter(item -> item.scheduledStart() != null && item.scheduledEnd() != null)
                .sorted(Comparator.comparing(ScheduleItem::jobId))
                .toList();
        if (scheduled.isEmpty()) {
            return;
        }
        Map<Long, Job> current = jobs.findByOwnerEmailAndIdIn(owner.getEmail(),
                        scheduled.stream().map(ScheduleItem::jobId).toList()).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));

        for (ScheduleItem item : scheduled) {
            Long id = item.jobId();
            Job job = current.get(id);
            if (job == null) {
                throw invalidated(runId, "job " + id + " is no longer in your jobs");
            }
            if (!sameHours(job.getCpuHours(), item.allocatedCpuHours())
                    || !sameHours(job.getGpuHours(), item.allocatedGpuHours())) {
                throw invalidated(runId, "job " + id + "'s CPU or GPU hours changed after the schedule was generated");
            }
            LocalDateTime earliest = job.earliestAllowedStart();
            LocalDateTime latest = job.latestAllowedStart();
            if (item.scheduledStart().isBefore(earliest) || item.scheduledStart().isAfter(latest)) {
                throw invalidated(runId, "job " + id + " must now start between " + utc(earliest) + " and "
                        + utc(latest) + ", but the schedule starts it at " + utc(item.scheduledStart()));
            }
            // The optimiser only uses slots that end by the deadline's hour.
            if (item.scheduledEnd().isAfter(job.getDeadline().truncatedTo(ChronoUnit.HOURS))) {
                throw invalidated(runId, "job " + id + "'s deadline " + utc(job.getDeadline())
                        + " is now before its scheduled end " + utc(item.scheduledEnd()));
            }
        }
    }

    /** Compare the solver's canonical jobs with current jobs, independently of plan provenance. */
    private void requireInputsUnchanged(OptimizationPlan plan, Set<Long> jobIds, User owner) {
        String snapshot = plan.getRun().getInputSnapshot();
        if (snapshot == null) return; // Runs created before GRID-141 have no solver snapshot.
        String solved = snapshot.lines().findFirst().orElse("");
        String current = jobs.findByOwnerEmailAndIdIn(owner.getEmail(), List.copyOf(jobIds)).stream()
            .sorted(Comparator.comparing(Job::getId))
            .map(job -> OptimizationProblem.jobEntry(job.getId(), job.getCpuHours(), job.getGpuHours(),
                job.earliestAllowedStart(), job.getDeadline(), job.getFlexibilityWindowHours(), job.isExempt(),
                job.getPriority()))
            .collect(Collectors.joining(";"));
        if (!solved.equals(current))
            throw invalidated(plan.getRunId(), "its jobs have changed since the schedule was generated");
    }

    private static List<ScheduleItem> items(OptimizeResponseDto schedule) {
        return schedule.items() == null ? List.of() : schedule.items();
    }

    private static boolean sameHours(Double current, double planned) {
        double value = current == null ? 0.0 : current;
        return Math.abs(value - planned) <= HOURS_TOLERANCE * Math.max(1.0, Math.abs(planned));
    }

    private static ScheduleExportRejectedException stale(String runId, String why) {
        return new ScheduleExportRejectedException(Reason.STALE, "Schedule run " + runId + " is stale: " + why);
    }

    private static ScheduleExportRejectedException invalidated(String runId, String why) {
        return new ScheduleExportRejectedException(Reason.INVALIDATED,
                "Schedule run " + runId + " is invalidated: " + why);
    }
}
