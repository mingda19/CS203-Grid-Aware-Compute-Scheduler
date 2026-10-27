package com.gacs.backend.service.allocation;

import java.util.List;
import java.util.Optional;

/**
 * A job's baseline run: started at {@code originalStartTime}, alone on the cluster, taking the most
 * each slot's capacity limit allows until its work is done (contract section 6.4, Cost basis).
 *
 * @param allocations the slots filled, in order. When {@code finishesByDeadline} is false these add
 *                    up to less than 1.
 * @param finishesByDeadline false when the work cannot be finished by the deadline even alone on
 *                           the cluster, or a slot with no capacity stops the run part-way
 */
public record Baseline(OptimizerJob job, List<SlotAllocation> allocations, boolean finishesByDeadline) {

    public Baseline {
        allocations = List.copyOf(allocations);
    }

    /** The baseline as a plan, when it finishes by the deadline. */
    public Optional<JobPlan> plan() {
        return finishesByDeadline ? Optional.of(new JobPlan(job, allocations)) : Optional.empty();
    }
}
