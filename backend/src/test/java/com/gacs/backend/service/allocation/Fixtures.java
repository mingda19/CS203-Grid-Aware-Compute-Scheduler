package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared test data for the allocation model. Slot {@code h} starts at {@code T0 + h hours}. */
final class Fixtures {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);
    static final EnergyCostModel COSTS = new EnergyCostModel(OptimizerProperties.DEFAULTS);

    private Fixtures() {
    }

    static LocalDateTime at(int hour) {
        return T0.plusHours(hour);
    }

    /** Hourly prices in $/MWh from T0. {@code Double.NaN} leaves that hour without a price. */
    static PriceSeries prices(double... perHour) {
        Map<LocalDateTime, Double> map = new LinkedHashMap<>();
        for (int h = 0; h < perHour.length; h++) {
            if (!Double.isNaN(perHour[h])) {
                map.put(at(h), perHour[h]);
            }
        }
        return new PriceSeries(map, false);
    }

    /** A flexible job starting at slot {@code startHour}, finishing by slot boundary {@code deadlineHour}. */
    static OptimizerJob job(long id, double cpuHours, double gpuHours, int startHour, int flexHours, int deadlineHour) {
        return new OptimizerJob(id, "job-" + id, cpuHours, gpuHours, at(startHour), at(deadlineHour), flexHours, false);
    }

    static OptimizerJob exempt(long id, double cpuHours, double gpuHours, int startHour, int deadlineHour) {
        return new OptimizerJob(id, "exempt-" + id, cpuHours, gpuHours, at(startHour), at(deadlineHour), 0, true);
    }

    static SlotCapacity cpuCapacity(double cpuHoursPerSlot) {
        return SlotCapacity.uniform(new CapacityLimit(cpuHoursPerSlot, 64.0));
    }

    /** {@code 20 CPU-hours = 1 kWh} under the default 0.05 kWh per CPU-hour. */
    static double kwhOfCpu(double cpuHours) {
        return cpuHours * 0.05;
    }
}
