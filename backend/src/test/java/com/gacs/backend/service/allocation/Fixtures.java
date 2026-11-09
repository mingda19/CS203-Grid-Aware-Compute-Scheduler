package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test data for the allocation model. Slot {@code h} starts at {@code T0 + h hours}. */
final class Fixtures {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);
    static final EnergyCostModel COSTS = new EnergyCostModel(OptimizerProperties.DEFAULTS);

    /**
     * No horizon bound: every priced hour stays schedulable. The default for fixtures, so tests about
     * capacity, cost, precedence and exclusion are not also implicitly tests of the clock.
     * {@link PastSlotExclusionTest} is where the bound itself is exercised.
     */
    static final LocalDateTime NO_HORIZON = LocalDateTime.MIN;

    /**
     * A clock one hour before {@link #T0}, so a problem the real factory builds from these fixtures has
     * every slot from {@code T0} onwards still ahead of it.
     */
    static final Clock CLOCK_BEFORE_T0 = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    static LocalDateTime at(int hour) {
        return T0.plusHours(hour);
    }

    /** An unbounded window, for tests about a job's own bounds rather than the run's horizon. */
    static JobWindow window(OptimizerJob job, PriceSeries prices) {
        return new JobWindow(job, prices, NO_HORIZON);
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

    /** A problem built the way the factory builds one: every job's baseline must finish by its deadline. */
    static OptimizationProblem problem(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity) {
        return problem(jobs, prices, capacity, List.of(), List.of());
    }

    /** As above, with the run's earliest schedulable hour set - for tests about the horizon bound. */
    static OptimizationProblem problemFrom(LocalDateTime earliestStart, List<OptimizerJob> jobs,
                                           PriceSeries prices, SlotCapacity capacity) {
        return new OptimizationProblem(jobs, prices, capacity, baselinesOf(jobs, capacity), List.of(),
                List.of(), earliestStart);
    }

    /** As above, with placement constraints attached but no synchronized-finish groups. */
    static OptimizationProblem problem(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity,
                                       List<PlacementConstraint> constraints) {
        return problem(jobs, prices, capacity, constraints, List.of());
    }

    /** As above, with both placement constraints and synchronized-finish groups attached. */
    static OptimizationProblem problem(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity,
                                       List<PlacementConstraint> constraints,
                                       List<SynchronizedFinishConstraint> synchronizedGroups) {
        return new OptimizationProblem(jobs, prices, capacity, baselinesOf(jobs, capacity), constraints,
                synchronizedGroups, NO_HORIZON);
    }

    /** Each job's baseline, which the factory requires to finish by the deadline. */
    private static Map<OptimizerJob, JobPlan> baselinesOf(List<OptimizerJob> jobs, SlotCapacity capacity) {
        BaselineCalculator baselines = new BaselineCalculator(capacity);
        Map<OptimizerJob, JobPlan> plans = new HashMap<>();
        for (OptimizerJob job : jobs) {
            plans.put(job, baselines.baseline(job).plan().orElseThrow());
        }
        return plans;
    }

    /** {@code 20 CPU-hours = 1 kWh} under the default 0.05 kWh per CPU-hour. */
    static double kwhOfCpu(double cpuHours) {
        return cpuHours * 0.05;
    }
}
