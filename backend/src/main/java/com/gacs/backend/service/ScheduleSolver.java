package com.gacs.backend.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Placement engine for an optimization run.
 *
 * <p>Deliberately narrow and free of Spring, persistence and HTTP concerns: the real
 * solver (GRID-125) replaces {@link GreedyStubScheduleSolver} behind this interface
 * without any change to {@link ScheduleOptimizationService}, the endpoint, or the
 * tests written against the contract.
 *
 * <p>Implementations must be deterministic. The same {@link SolverInput} has to yield
 * the same {@link SolverResult} on every call, or the test suites covering GRID-123
 * and GRID-124 cannot assert anything stable.
 */
public interface ScheduleSolver {

    SolverResult solve(SolverInput input);

    /**
     * Per-job outcome, mirroring the "Per-Job Status Values" list in the contract.
     */
    enum PerJobStatus {
        /** Moved into a cheaper slot inside its flexibility window. */
        RESCHEDULED,
        /** Left at its original start because the original slot was already cheapest. */
        UNCHANGED_ALREADY_OPTIMAL,
        /** Left at its original start because {@code isExempt} is true. */
        UNCHANGED_EXEMPT,
        /** Left at its original start because no slot met deadline and capacity constraints. */
        UNCHANGED_NO_FEASIBLE_SLOT
    }

    /**
     * Per-slot ceiling on concurrent load.
     *
     * <p>Because every job occupies exactly one one-hour slot, a job's {@code cpuHours}
     * and {@code gpuHours} are also its load within that slot, so these ceilings are
     * compared against the sum of the hours of all jobs placed in the same slot.
     */
    record ClusterCapacity(double maxConcurrentCpuHours, double maxConcurrentGpuHours) {

        public static final double DEFAULT_MAX_CPU_HOURS = 256.0;
        public static final double DEFAULT_MAX_GPU_HOURS = 64.0;

        public static ClusterCapacity defaults() {
            return new ClusterCapacity(DEFAULT_MAX_CPU_HOURS, DEFAULT_MAX_GPU_HOURS);
        }
    }

    /**
     * One job as the solver sees it, decoupled from the JPA entity and the wire DTO.
     */
    record JobSpec(
        Long id,
        String name,
        double cpuHours,
        double gpuHours,
        LocalDateTime originalStartTime,
        LocalDateTime deadline,
        int flexibilityWindowHours,
        boolean exempt
    ) {
        /** Energy draw for this job, per the contract's conversion factors. */
        public double energyKwh() {
            return CostModel.energyKwh(cpuHours, gpuHours);
        }

        /** The slot this job occupies under the fixed-schedule baseline. */
        public LocalDateTime baselineSlot() {
            return originalStartTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        }
    }

    /**
     * Everything the solver needs, supplied explicitly rather than read from ambient state.
     */
    record SolverInput(
        List<JobSpec> jobs,
        PriceSeries prices,
        ClusterCapacity capacity,
        LocalDateTime horizonStart,
        int horizonHours
    ) {
        /** Exclusive end of the optimization window. */
        public LocalDateTime horizonEnd() {
            return horizonStart.plusHours(horizonHours);
        }
    }

    /**
     * Where one job ended up. {@code slotStart} is always a priced slot inside the horizon.
     */
    record SolverAssignment(JobSpec job, LocalDateTime slotStart, PerJobStatus status) {

        /** Every job occupies exactly one one-hour slot. */
        public LocalDateTime slotEnd() {
            return slotStart.plusHours(1);
        }
    }

    /**
     * @param certifiedOptimal true when {@code assignments} is a proven global minimum
     *     (an exact solver reported {@code OPTIMAL}); false for a heuristic result that is
     *     feasible but not proven optimal (the greedy stub, or a time-boxed fallback).
     */
    record SolverResult(List<SolverAssignment> assignments, boolean certifiedOptimal) {
    }
}
