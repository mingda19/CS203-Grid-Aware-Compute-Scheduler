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
        JobPriority priority) {

    public OptimizerJob {
        Objects.requireNonNull(originalStartTime, "originalStartTime");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(priority, "priority");
    }

    /** Convenience for callers that don't need to set priority explicitly: defaults to NORMAL. */
    public OptimizerJob(Long id, String name, double cpuHours, double gpuHours, LocalDateTime originalStartTime,
                        LocalDateTime deadline, int flexibilityWindowHours, boolean exempt) {
        this(id, name, cpuHours, gpuHours, originalStartTime, deadline, flexibilityWindowHours, exempt,
                JobPriority.NORMAL);
    }

    /** The latest slot this job may start in. An exempt job may only start at its original time. */
    public LocalDateTime latestStart() {
        return exempt ? originalStartTime : originalStartTime.plusHours(flexibilityWindowHours);
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
