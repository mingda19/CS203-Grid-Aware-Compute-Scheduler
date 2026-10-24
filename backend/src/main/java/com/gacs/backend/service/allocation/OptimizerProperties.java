package com.gacs.backend.service.allocation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurable constants of the optimiser contract (docs/api-contract.md section 6.4), bound from
 * {@code gacs.optimiser.*}. The defaults are the contract's seeded values.
 *
 * @param cpuKwhPerHour              kWh drawn per CPU-hour of work (AC2)
 * @param gpuKwhPerHour              kWh drawn per GPU-hour of work (AC2)
 * @param defaultMaxCpuHoursPerSlot  CPU-hours per slot when the request gives no capacity (AC3)
 * @param defaultMaxGpuHoursPerSlot  GPU-hours per slot when the request gives no capacity (AC3)
 */
@ConfigurationProperties(prefix = "gacs.optimiser")
public record OptimizerProperties(
        @DefaultValue("0.05") double cpuKwhPerHour,
        @DefaultValue("0.40") double gpuKwhPerHour,
        @DefaultValue("256.0") double defaultMaxCpuHoursPerSlot,
        @DefaultValue("64.0") double defaultMaxGpuHoursPerSlot) {

    /** The contract's seeded values, for code and tests that run without Spring. */
    public static final OptimizerProperties DEFAULTS = new OptimizerProperties(0.05, 0.40, 256.0, 64.0);

    public OptimizerProperties {
        requireNonNegative("cpu-kwh-per-hour", cpuKwhPerHour);
        requireNonNegative("gpu-kwh-per-hour", gpuKwhPerHour);
        requireNonNegative("default-max-cpu-hours-per-slot", defaultMaxCpuHoursPerSlot);
        requireNonNegative("default-max-gpu-hours-per-slot", defaultMaxGpuHoursPerSlot);
    }

    /** Capacity applied to every slot that has neither an override nor a request-level capacity. */
    public CapacityLimit defaultCapacity() {
        return new CapacityLimit(defaultMaxCpuHoursPerSlot, defaultMaxGpuHoursPerSlot);
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("gacs.optimiser." + name + " must be a finite value >= 0");
        }
    }
}
