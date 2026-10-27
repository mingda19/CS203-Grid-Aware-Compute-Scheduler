package com.gacs.backend.service.allocation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Where and how a job runs: its share of work in each slot it uses. A plan always satisfies the
 * contract's shape rules (section 6.4, Slots and work): at least one slot, consecutive hourly slots
 * with no gaps, and shares that add up to 1. Window, deadline and capacity are checked elsewhere.
 */
public record JobPlan(OptimizerJob job, List<SlotAllocation> allocations) {

    /** Floating-point slack allowed when checking that shares add up to 1. */
    public static final double SHARE_TOLERANCE = 1e-9;

    public JobPlan {
        Objects.requireNonNull(job, "job");
        allocations = List.copyOf(allocations);
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("a plan for job " + job.label() + " needs at least one slot");
        }
        double total = 0.0;
        for (int i = 0; i < allocations.size(); i++) {
            total += allocations.get(i).share();
            if (i > 0 && !allocations.get(i).slotStart().equals(allocations.get(i - 1).slotStart().plusHours(1))) {
                throw new IllegalArgumentException("plan for job " + job.label() + " must use consecutive hourly slots");
            }
        }
        if (Math.abs(total - 1.0) > SHARE_TOLERANCE) {
            throw new IllegalArgumentException("shares for job " + job.label() + " add up to " + total + ", not 1");
        }
    }

    /** Start of the first slot used ({@code scheduledStart}). */
    public LocalDateTime start() {
        return allocations.getFirst().slotStart();
    }

    /** End of the last slot used ({@code scheduledEnd}). */
    public LocalDateTime end() {
        return allocations.getLast().slotStart().plusHours(1);
    }

    /** Number of slots used: the job's duration in hours. */
    public int slotCount() {
        return allocations.size();
    }
}
