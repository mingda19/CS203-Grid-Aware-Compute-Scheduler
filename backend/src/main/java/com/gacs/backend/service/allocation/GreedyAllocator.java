package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places one job at a time on its cheapest plan in the capacity (and, with {@link Constraint}s
 * attached, the placement) earlier jobs left. Every plan it makes meets the window, deadline,
 * price, capacity and constraint rules, so any order gives a valid schedule. The heuristic solver
 * ({@link AnnealingAllocationSolver}) searches over orders by calling {@link #cheapestPlan}
 * directly; {@link SynchronizedGroupPlacer} calls {@link #cheapestPlanEndingAt} for its dedicated
 * pre-pass.
 */
final class GreedyAllocator {

    private static final double DONE_TOLERANCE = 1e-12;

    private GreedyAllocator() {
    }

    /**
     * The cheapest plan for one job given the capacity left and the jobs already placed for any
     * {@link Constraint} this job carries. For each allowed start and each run length, every hour
     * gets {@link AllocationSolver#MIN_SHARE} and the rest of the work goes to the cheapest hours
     * first, up to what each hour has free. That is optimal for one job over a fixed set of hours,
     * because cost is linear in the shares. Ties keep the earlier start, then the shorter run.
     *
     * @param constraints this job's own constraints ({@code problem.constraintsFor(job)}), not every
     *                    constraint in the problem
     * @param placedSoFar every other job already placed, keyed by job id, for the constraints above
     *                    to consult
     */
    static Optional<JobPlan> cheapestPlan(OptimizerJob job, ResidualCapacity free, PriceSeries prices,
                                          EnergyCostModel costs, List<Constraint> constraints,
                                          Map<Long, JobPlan> placedSoFar) {
        JobWindow window = new JobWindow(job, prices);
        for (Constraint constraint : constraints) {
            window = constraint.restrict(job, window, placedSoFar);
        }
        double kwh = costs.slotCostUsd(job, 1.0, 1.0); // cost of all the work at $1/MWh
        JobPlan best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (LocalDateTime start : window.startSlots()) {
            List<LocalDateTime> hours = new ArrayList<>();
            List<Double> caps = new ArrayList<>();
            List<Double> hourPrices = new ArrayList<>();
            List<Integer> cheapestFirst = new ArrayList<>(); // indices into hours, by price then time
            double totalCap = 0.0;
            for (LocalDateTime hour : window.runFrom(start)) {
                double cap = Math.min(1.0, free.freeAt(hour).maxShare(job));
                if (cap < AllocationSolver.MIN_SHARE || (hours.size() + 1) * AllocationSolver.MIN_SHARE > 1.0) {
                    break; // a run cannot pass through a full hour
                }
                int index = hours.size();
                double price = prices.priceAt(hour).orElseThrow();
                hours.add(hour);
                caps.add(cap);
                hourPrices.add(price);
                insertByPrice(cheapestFirst, hourPrices, index);
                totalCap += cap;
                if (totalCap < 1.0 - DONE_TOLERANCE) {
                    continue; // not enough room in these hours yet
                }
                double[] shares = fill(caps, cheapestFirst);
                if (shares == null) {
                    continue;
                }
                double cost = 0.0;
                for (int i = 0; i < shares.length; i++) {
                    cost += shares[i] * kwh * hourPrices.get(i);
                }
                if (cost < bestCost - AllocationResult.COST_TOLERANCE) {
                    JobPlan candidate = plan(job, hours, shares);
                    if (satisfiesAll(constraints, job, candidate, placedSoFar)) {
                        best = candidate;
                        bestCost = cost;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The cheapest plan for {@code job} whose last slot starts exactly at {@code endHour} - the
     * building block {@link SynchronizedGroupPlacer} tries one common end hour at a time with.
     * Ignores this job's own {@link Constraint}s: a synchronized-finish group is placed before any
     * other constraint on its members would have anything to consult yet.
     */
    static Optional<JobPlan> cheapestPlanEndingAt(OptimizerJob job, LocalDateTime endHour, ResidualCapacity free,
                                                   PriceSeries prices, EnergyCostModel costs) {
        JobWindow window = new JobWindow(job, prices);
        double kwh = costs.slotCostUsd(job, 1.0, 1.0);
        JobPlan best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (LocalDateTime start : window.startSlots()) {
            if (start.isAfter(endHour)) {
                continue;
            }
            List<LocalDateTime> run = window.runFrom(start);
            long length = java.time.Duration.between(start, endHour).toHours() + 1;
            if (length > run.size() || !run.get((int) length - 1).equals(endHour)) {
                continue; // the run from this start does not reach endHour, or overshoots past it
            }
            List<LocalDateTime> hours = run.subList(0, (int) length);
            List<Double> caps = new ArrayList<>();
            List<Double> hourPrices = new ArrayList<>();
            List<Integer> cheapestFirst = new ArrayList<>();
            boolean fits = true;
            for (int i = 0; i < hours.size(); i++) {
                LocalDateTime hour = hours.get(i);
                double cap = Math.min(1.0, free.freeAt(hour).maxShare(job));
                if (cap < AllocationSolver.MIN_SHARE || (i + 1) * AllocationSolver.MIN_SHARE > 1.0) {
                    fits = false;
                    break;
                }
                double price = prices.priceAt(hour).orElseThrow();
                caps.add(cap);
                hourPrices.add(price);
                insertByPrice(cheapestFirst, hourPrices, i);
            }
            if (!fits) {
                continue;
            }
            double[] shares = fill(caps, cheapestFirst);
            if (shares == null) {
                continue;
            }
            double cost = 0.0;
            for (int i = 0; i < shares.length; i++) {
                cost += shares[i] * kwh * hourPrices.get(i);
            }
            if (cost < bestCost - AllocationResult.COST_TOLERANCE) {
                best = plan(job, hours, shares);
                bestCost = cost;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean satisfiesAll(List<Constraint> constraints, OptimizerJob job, JobPlan candidate,
                                        Map<Long, JobPlan> placedSoFar) {
        for (Constraint constraint : constraints) {
            if (!constraint.isSatisfiedBy(job, candidate, placedSoFar)) {
                return false;
            }
        }
        return true;
    }

    /** Inserts {@code index} into {@code order}, which is sorted by price, then by index. */
    private static void insertByPrice(List<Integer> order, List<Double> prices, int index) {
        double price = prices.get(index);
        int position = order.size();
        while (position > 0 && prices.get(order.get(position - 1)) > price) {
            position--;
        }
        order.add(position, index);
    }

    /** Shares for the hours: MIN_SHARE each, the rest to the cheapest first. Null if the work does not fit. */
    private static double[] fill(List<Double> caps, List<Integer> cheapestFirst) {
        int n = caps.size();
        double[] shares = new double[n];
        double remaining = 1.0 - n * AllocationSolver.MIN_SHARE;
        java.util.Arrays.fill(shares, AllocationSolver.MIN_SHARE);
        for (int i : cheapestFirst) {
            double add = Math.min(caps.get(i) - AllocationSolver.MIN_SHARE, remaining);
            shares[i] += add;
            remaining -= add;
            if (remaining <= 0.0) {
                break;
            }
        }
        return remaining > DONE_TOLERANCE ? null : shares;
    }

    private static JobPlan plan(OptimizerJob job, List<LocalDateTime> hours, double[] shares) {
        double total = 0.0;
        for (double share : shares) {
            total += share;
        }
        List<SlotAllocation> allocations = new ArrayList<>();
        for (int i = 0; i < shares.length; i++) {
            allocations.add(new SlotAllocation(hours.get(i), shares[i] / total));
        }
        return new JobPlan(job, allocations);
    }
}
