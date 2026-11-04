package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One job as the optimiser sees it (contract section 6.4, request {@code jobs[]}). Times are UTC.
 * Request validation (Phase 2) guarantees {@code originalStartTime} is on the hour,
 * {@code deadline >= originalStartTime}, hours are non-negative and at least one is above 0.
 *
 * @param cpuHours total CPU-hours of work, not a duration
 * @param gpuHours total GPU-hours of work, not a duration
 * @param priority used to break ties when jobs compete for the same capacity - see
 *                 {@link OptimizationProblem#TIE_BREAK_ORDER}
 * @param rescheduledFromElapsedStart set by {@link OptimizationProblemFactory} for a job whose
 *                 {@code originalStartTime} is already in the past and which the request explicitly
 *                 opted in to rescheduling ({@code rescheduleElapsedJobs}). It changes what {@link
 *                 #latestStart()} returns - see there - and nothing else. Never true for an exempt
 *                 job, which is pinned to its original start by definition.
 */
public record OptimizerJob(
        Long id,
        String name,
        double cpuHours,
        double gpuHours,
        LocalDateTime originalStartTime,
        LocalDateTime deadline,
        int flexibilityWindowHours,
        boolean exempt,
        JobPriority priority,
        boolean rescheduledFromElapsedStart) {

    public OptimizerJob {
        Objects.requireNonNull(originalStartTime, "originalStartTime");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(priority, "priority");
        if (rescheduledFromElapsedStart && exempt) {
            throw new IllegalArgumentException("job " + (id == null ? name : id)
                    + ": an exempt job runs at its original time and cannot be rescheduled from an elapsed start");
        }
    }

    /**
     * Convenience for the ordinary case: a job placed within the flexibility window the operator
     * actually specified, which is every job unless the request opted into rescheduling elapsed ones.
     */
    public OptimizerJob(Long id, String name, double cpuHours, double gpuHours, LocalDateTime originalStartTime,
                        LocalDateTime deadline, int flexibilityWindowHours, boolean exempt,
                        JobPriority priority) {
        this(id, name, cpuHours, gpuHours, originalStartTime, deadline, flexibilityWindowHours, exempt,
                priority, false);
    }

    /** Convenience for callers that don't need to set priority explicitly: defaults to NORMAL. */
    public OptimizerJob(Long id, String name, double cpuHours, double gpuHours, LocalDateTime originalStartTime,
                        LocalDateTime deadline, int flexibilityWindowHours, boolean exempt) {
        this(id, name, cpuHours, gpuHours, originalStartTime, deadline, flexibilityWindowHours, exempt,
                JobPriority.NORMAL);
    }

    /**
     * The latest slot this job may start in. An exempt job may only start at its original time.
     *
     * <p>Normally this is {@code originalStartTime + flexibilityWindowHours}: the window the operator
     * specified, anchored to the start they gave. For a job whose start has already passed that
     * anchor is a time that no longer exists, and the whole window can sit behind the current hour -
     * which is how eleven submitted jobs came back with seven reported {@code NO_FEASIBLE_SLOT}
     * merely because their start time had gone by. Nothing was wrong with those jobs; their deadlines
     * were still hours away.
     *
     * <p>So when {@code rescheduledFromElapsedStart} is set, the anchor moves to the deadline
     * instead: the job may start as late as its deadline still permits, and {@link JobWindow} clamps
     * that to the last slot that actually ends by the deadline ({@link JobWindow#lastSlot()}), while
     * {@link JobWindow#firstSlot()} keeps the lower bound at the current hour. The window becomes
     * {@code [now, last slot that still finishes by the deadline]}.
     *
     * <p>This genuinely widens placement past what the operator asked for, which is why it is opt-in
     * per request and set per job only where the start has actually elapsed - never inferred, and
     * never silent. Returning the deadline itself (rather than a slot computed here) keeps the
     * "ends by the deadline" rule in the one place that owns it, {@link JobWindow}.
     */
    public LocalDateTime latestStart() {
        if (exempt) {
            return originalStartTime;
        }
        return rescheduledFromElapsedStart ? deadline : originalStartTime.plusHours(flexibilityWindowHours);
    }

    /**
     * This job with its start window re-anchored to its deadline; see {@link #latestStart()}.
     * Returns {@code this} for an exempt job, which can never be moved off its original start, so a
     * caller can apply it to a whole list without filtering exempt jobs out first.
     */
    public OptimizerJob reanchoredToDeadline() {
        return rescheduledFromElapsedStart || exempt ? this
                : new OptimizerJob(id, name, cpuHours, gpuHours, originalStartTime, deadline,
                        flexibilityWindowHours, exempt, priority, true);
    }

    /** Total work requested, used as the tie-break's resource-footprint measure. */
    public double totalResourceHours() {
        return cpuHours + gpuHours;
    }

    /** For messages: "id 101", or the quoted name when the job has no id. */
    public String label() {
        return id == null ? "'" + name + "'" : "id " + id;
    }

    /**
     * Bug 2: like {@link #label()} but also carries the job's name, so an operator looking at an
     * error message can tell which of their jobs - possibly several sharing a name - it refers to
     * without having to guess from a bare numeric id. {@link #label()} itself is left unchanged: its
     * exact "id N" wording is asserted by {@code PriorityConflictTest} and documented verbatim in
     * docs/api-contract.md ("Explaining an infeasible plan"), so this is a separate method rather
     * than a change to label()'s format.
     */
    public String identity() {
        return id == null ? "'" + name + "'" : "id " + id + " ('" + name + "')";
    }
}
