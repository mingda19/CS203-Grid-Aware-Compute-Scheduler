package com.gacs.backend.service.allocation;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The share of a job's work done in one slot (contract section 6.4, Slots and work). The job uses
 * {@code share * cpuHours} CPU-hours and {@code share * gpuHours} GPU-hours in that slot.
 */
public record SlotAllocation(LocalDateTime slotStart, double share) {

    public SlotAllocation {
        Objects.requireNonNull(slotStart, "slotStart");
        if (!(share > 0.0 && share <= 1.0 + JobPlan.SHARE_TOLERANCE)) {
            throw new IllegalArgumentException("share must be in (0, 1], was " + share);
        }
    }

    public double cpuHours(OptimizerJob job) {
        return share * job.cpuHours();
    }

    public double gpuHours(OptimizerJob job) {
        return share * job.gpuHours();
    }
}
