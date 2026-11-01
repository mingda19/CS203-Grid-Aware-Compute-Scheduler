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

    /** For messages: "id 101", or the quoted name when the job has no id. */
    public String label() {
        return id == null ? "'" + name + "'" : "id " + id;
    }
}
