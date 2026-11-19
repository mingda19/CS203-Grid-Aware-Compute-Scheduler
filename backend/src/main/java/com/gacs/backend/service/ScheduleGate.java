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
import java.util.Arrays;
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

    /**
     * The action types that count as "a decision the operator made" when this gate asks the audit log
     * for a run's latest one. REVOKE belongs here with the other three: it is a decision, recorded by
     * the same {@code ScheduleReviewService#decide} path, and leaving it out meant a revoked run's
     * latest decision still read as the APPROVE it withdrew. Nothing reachable today depends on that
     * (a revoked plan is REJECTED, so {@link #requireApproved} refuses it before the audit log is
     * consulted), but an execution gate that would read a withdrawn approval as current is the wrong
     * default to leave in place for whichever caller asks next.
     */
    static final Set<AuditActionType> DECISIONS = EnumSet.of(AuditActionType.APPROVE,
            AuditActionType.REJECT, AuditActionType.REVISE, AuditActionType.REVOKE);
    private static final double HOURS_TOLERANCE = 1e-9;

    private final OptimizationPlanRepository plans;
    private final AuditEventRepository auditEvents;
    private final JobRepository jobs;
    private final ScheduleInputs inputs;
    private final StoredScheduleReader reader;
    private final Clock clock;
    private final CapacityLedger ledger;

    public ScheduleGate(OptimizationPlanRepository plans, AuditEventRepository auditEvents,
                        JobRepository jobs, StoredScheduleReader reader,
                        Clock clock, ScheduleInputs inputs, CapacityLedger ledger) {
        this.ledger = ledger;
        this.plans = plans;
        this.auditEvents = auditEvents;
        this.jobs = jobs;
        this.reader = reader;
        this.clock = clock;
        this.inputs = inputs;
    }

    public User lock(String email) { return inputs.lock(email); }

    public OptimizationPlan owned(UUID runId, String email) {
        User owner = inputs.lock(email);
        return plans.findOwnedForUpdate(runId.toString(), owner.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));
    }

    /**
     * Any plan, for a reviewer who is not its owner. Locks the owner's row, as {@link #owned} does, so a
     * decision and the owner's own changes cannot interleave.
     */
    public OptimizationPlan reviewable(UUID runId) {
        String ownerEmail = plans.findOwnerEmail(runId.toString())
            .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));
        return owned(runId, ownerEmail);
    }

    public record Checked(OptimizeResponseDto schedule, AuditEvent approval) {}

    /** Checks the plan against its owner's jobs and the shared facility, whoever is asking. */
    public Checked check(OptimizationPlan plan, boolean approvalRequired) {
        String email = plan.getOwner().getEmail();
        try {
            if (plan.getReviewState() == ReviewState.INVALIDATED)
                throw new ScheduleExportRejectedException(Reason.INVALIDATED, plan.getInvalidationReason());
            if (plan.getReviewState() == ReviewState.STALE)
                throw new ScheduleExportRejectedException(Reason.STALE, plan.getInvalidationReason());
            if (approvalRequired) requireApproved(plan);
            AuditEvent approval = approvalRequired || plan.getReviewState() == ReviewState.APPROVED
                ? requireMatchingApproval(plan) : null;
            OptimizeResponseDto schedule = withoutRemoved(plan, reader.read(plan.getResultJson())
                .orElseThrow(() -> invalidated(plan.getRunId(), "its stored schedule cannot be read")));
            if (OptimizeResponseDto.STATUS_INFEASIBLE.equals(schedule.status()))
                throw new ScheduleExportRejectedException(Reason.NOT_APPROVED,
                    "Schedule run " + plan.getRunId() + " is INFEASIBLE: no job could be placed");
            requireCurrent(plan, schedule, plan.getOwner());
            requireCapacityCurrent(plan, schedule);
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

    /** The schedule without the jobs that left the plan after approval. Apply, export and every job check use this. */
    private static OptimizeResponseDto withoutRemoved(OptimizationPlan plan, OptimizeResponseDto schedule) {
        if (plan.getRemovedJobIds().isEmpty() || schedule.items() == null) return schedule;
        return new OptimizeResponseDto(schedule.runId(), schedule.status(), schedule.provenOptimal(),
            schedule.baselineCostUsd(), schedule.optimizedCostUsd(), schedule.projectedSavingsUsd(),
            schedule.projectedSavingsPercent(), schedule.plainEnglishExplanation(), schedule.usedSeededFallbackPrices(),
            schedule.items().stream().filter(item -> !plan.getRemovedJobIds().contains(item.jobId())).toList());
    }

    /** A new approval permanently supersedes older overlapping approvals. */
    public void supersede(OptimizationPlan approved) {
        for (var other : plans.findByOwnerIdAndReviewState(approved.getOwner().getId(), ReviewState.APPROVED))
            if (!other.getRunId().equals(approved.getRunId()) &&
                !java.util.Collections.disjoint(other.liveJobIds(), approved.liveJobIds()))
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
            Set<Long> shared = new TreeSet<>(newer.liveJobIds());
            shared.retainAll(jobIds);
            if (!shared.isEmpty()) throw stale(runId, "run " + newer.getRunId()
                + " was approved later and also schedules job(s) " + shared);
        }
    }

    /**
     * A waiting plan is replaced when approved plans changed what it was solved against: it no longer fits,
     * capacity was freed so a cheaper placement may exist, or a start has passed. Capacity taken while the
     * plan still fits changes nothing, because the plan stays valid and was optimal over more choices.
     * Approved plans are not checked here: nothing approved moves.
     */
    private void requireCapacityCurrent(OptimizationPlan plan, OptimizeResponseDto schedule) {
        if (plan.getReviewState() != ReviewState.PENDING) return;
        String runId = plan.getRunId();
        LocalDateTime hour = ledger.currentHour();
        for (ScheduleItem item : items(schedule))
            if (item.scheduledStart() != null && item.scheduledStart().isBefore(hour))
                throw stale(runId, "job " + item.jobId() + " would have started at " + utc(item.scheduledStart())
                    + ", which has passed");

        var now = ledger.reserved();
        var before = inputs.reservedAtSolve(plan);
        LocalDateTime end = inputs.latestDeadline(plan);
        for (var entry : before.entrySet()) {
            LocalDateTime slot = entry.getKey();
            if (slot.isBefore(hour) || (end != null && !slot.isBefore(end))) continue;
            var used = now.get(slot);
            double cpu = used == null ? 0.0 : used.cpuHours(), gpu = used == null ? 0.0 : used.gpuHours();
            if (cpu < entry.getValue().cpuHours() - HOURS_TOLERANCE || gpu < entry.getValue().gpuHours() - HOURS_TOLERANCE)
                throw stale(runId, "capacity was freed at " + utc(slot) + ", so a cheaper placement may exist");
        }

        var limit = inputs.facility();
        Map<LocalDateTime, double[]> planned = CapacityLedger.hoursBySlot(items(schedule));
        for (var entry : planned.entrySet()) {
            var used = now.get(entry.getKey());
            double cpu = entry.getValue()[0] + (used == null ? 0.0 : used.cpuHours());
            double gpu = entry.getValue()[1] + (used == null ? 0.0 : used.gpuHours());
            if (cpu > limit.cpu() * (1 + HOURS_TOLERANCE) + HOURS_TOLERANCE || gpu > limit.gpu() * (1 + HOURS_TOLERANCE) + HOURS_TOLERANCE)
                throw stale(runId, "it no longer fits at " + utc(entry.getKey())
                    + ": an approved plan now uses the capacity it needs");
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
            if (item.scheduledStart().isBefore(earliest)) {
                throw invalidated(runId, "job " + id + " must now start at or after " + utc(earliest)
                        + ", but the schedule starts it at " + utc(item.scheduledStart()));
            }
            // A job the run re-anchored to its deadline was deliberately placed outside the
            // flexibility window the job record still carries: that is the whole point of the
            // operator opting in to rescheduling jobs whose start had passed. Re-deriving
            // latestAllowedStart() from the entity here would therefore invalidate exactly the plans
            // that option exists to produce, at approve/apply/export time, with a message about a
            // window the operator consciously set aside. The lower bound above and the deadline check
            // below are not relaxed, so the two rules that matter still hold: the job does not start
            // before its own original start, and it still finishes by its deadline.
            if (!item.rescheduledFromElapsedStart() && item.scheduledStart().isAfter(latest)) {
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
        String solved = Arrays.stream(snapshot.lines().findFirst().orElse("").split(";"))
            .filter(entry -> !plan.getRemovedJobIds().contains(entryId(entry)))
            .collect(Collectors.joining(";"));
        String current = jobs.findByOwnerEmailAndIdIn(owner.getEmail(), List.copyOf(jobIds)).stream()
            .sorted(Comparator.comparing(Job::getId))
            .map(job -> OptimizationProblem.jobEntry(job.getId(), job.getCpuHours(), job.getGpuHours(),
                job.earliestAllowedStart(), job.getDeadline(), job.getFlexibilityWindowHours(), job.isExempt(),
                job.getPriority()))
            .collect(Collectors.joining(";"));
        if (!solved.equals(current))
            throw invalidated(plan.getRunId(), "its jobs have changed since the schedule was generated");
    }

    /** Each solved entry starts with the job's id and a comma. A blank entry has no id. */
    private static Long entryId(String entry) {
        int comma = entry.indexOf(',');
        try { return comma < 0 ? null : Long.valueOf(entry.substring(0, comma).trim()); }
        catch (NumberFormatException ex) { return null; }
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
