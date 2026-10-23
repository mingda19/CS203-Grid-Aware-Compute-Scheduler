package com.gacs.backend.service.allocation;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Capacity left in each slot after some plans have been placed. Never below 0 in any slot. */
final class ResidualCapacity {

    private final SlotCapacity capacity;
    private final Map<LocalDateTime, double[]> used;

    ResidualCapacity(SlotCapacity capacity) {
        this(capacity, new HashMap<>());
    }

    private ResidualCapacity(SlotCapacity capacity, Map<LocalDateTime, double[]> used) {
        this.capacity = capacity;
        this.used = used;
    }

    ResidualCapacity copy() {
        Map<LocalDateTime, double[]> clone = new HashMap<>();
        used.forEach((slot, hours) -> clone.put(slot, hours.clone()));
        return new ResidualCapacity(capacity, clone);
    }

    void reserve(JobPlan plan) {
        for (SlotAllocation allocation : plan.allocations()) {
            double[] hours = used.computeIfAbsent(allocation.slotStart(), slot -> new double[2]);
            hours[0] += allocation.cpuHours(plan.job());
            hours[1] += allocation.gpuHours(plan.job());
        }
    }

    CapacityLimit freeAt(LocalDateTime slot) {
        double[] hours = used.getOrDefault(slot, new double[2]);
        return capacity.limitAt(slot).minus(hours[0], hours[1]);
    }
}
