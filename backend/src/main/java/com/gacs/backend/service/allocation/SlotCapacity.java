package com.gacs.backend.service.allocation;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The capacity limit of every slot, by the contract's rule (section 6.4, Capacity / AC3): the slot's
 * {@code capacityOverrides[]} entry, else the request's {@code clusterCapacity}, else the configured
 * defaults. The baseline and the optimiser both use this one rule (decision 4a).
 */
public final class SlotCapacity {

    private final CapacityLimit cluster;
    private final TreeMap<LocalDateTime, CapacityLimit> overrides;

    /**
     * @param cluster   capacity for every slot without an override, already resolved against the defaults
     * @param overrides per-slot limits keyed by slot start (UTC, on the hour)
     */
    public SlotCapacity(CapacityLimit cluster, Map<LocalDateTime, CapacityLimit> overrides) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.overrides = new TreeMap<>(overrides);
    }

    /**
     * Resolves the request's capacity fields. Either {@code clusterCpuHours} or
     * {@code clusterGpuHours} may be null, in which case that resource uses the configured default.
     */
    public static SlotCapacity resolve(OptimizerProperties properties, Double clusterCpuHours,
                                       Double clusterGpuHours, Map<LocalDateTime, CapacityLimit> overrides) {
        CapacityLimit defaults = properties.defaultCapacity();
        CapacityLimit cluster = new CapacityLimit(
                clusterCpuHours == null ? defaults.cpuHours() : clusterCpuHours,
                clusterGpuHours == null ? defaults.gpuHours() : clusterGpuHours);
        return new SlotCapacity(cluster, overrides);
    }

    /** The same limit in every slot, with no overrides. */
    public static SlotCapacity uniform(CapacityLimit limit) {
        return new SlotCapacity(limit, Map.of());
    }

    public CapacityLimit limitAt(LocalDateTime slotStart) {
        return overrides.getOrDefault(slotStart, cluster);
    }
}
