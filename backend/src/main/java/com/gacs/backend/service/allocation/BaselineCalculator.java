package com.gacs.backend.service.allocation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds each job's baseline run (contract section 6.4, Cost basis). The same run is used to place
 * exempt jobs, which "run from {@code originalStartTime} exactly as in their baseline" (Capacity / AC3).
 *
 * <p>The baseline ignores every other job, so the baselines of several jobs together may exceed a
 * slot's capacity. That is intended: it is a reference cost, not a schedule.
 */
public final class BaselineCalculator {

    /** Work left below this is treated as done, so float rounding cannot add an extra slot. */
    private static final double DONE_TOLERANCE = 1e-12;

    private final SlotCapacity capacity;

    public BaselineCalculator(SlotCapacity capacity) {
        this.capacity = capacity;
    }

    /**
     * Fills consecutive slots from {@code originalStartTime}, each with
     * {@code min(work left, the share the slot's limit allows)}, stopping when the work is done.
     * It also stops, unfinished, at the last slot that ends by the deadline, or at a slot whose
     * limit allows no work at all (the run would have a gap, which the contract does not allow).
     * Prices are not needed here.
     */
    public Baseline baseline(OptimizerJob job) {
        return runFrom(job, job.originalStartTime());
    }

    /**
     * The same fill as {@link #baseline}, but starting at {@code from} instead of the job's original
     * start - so a caller can ask "could this job still finish by its deadline if it started here,
     * alone on the cluster?" without duplicating the fill rule.
     *
     * <p>Used by {@link AllocationResult} to tell two unscheduled-job causes apart that otherwise
     * look identical: not enough hours left before the deadline, versus other jobs holding the
     * capacity. {@code build()} admits a job on the strength of its baseline fitting from its
     * <em>original</em> start; once that start has passed, fewer hours remain, and the job can become
     * unplaceable for reasons that have nothing to do with contention. Blaming capacity for that sends
     * the operator to the wrong remedy.
     *
     * @return a {@link Baseline} whose {@code finishesByDeadline} answers that question
     */
    public Baseline runFrom(OptimizerJob job, LocalDateTime from) {
        List<SlotAllocation> allocations = new ArrayList<>();
        double remaining = 1.0;
        LocalDateTime slot = from;

        while (remaining > DONE_TOLERANCE && !slot.plusHours(1).isAfter(job.deadline())) {
            double share = Math.min(remaining, capacity.limitAt(slot).maxShare(job));
            if (share <= DONE_TOLERANCE) {
                break;
            }
            remaining -= share;
            if (remaining <= DONE_TOLERANCE) {
                share += remaining; // absorb float dust so the shares add up to exactly 1
                remaining = 0.0;
            }
            allocations.add(new SlotAllocation(slot, share));
            slot = slot.plusHours(1);
        }
        return new Baseline(job, allocations, remaining == 0.0);
    }
}
