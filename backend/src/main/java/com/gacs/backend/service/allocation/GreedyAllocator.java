package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places jobs one at a time, in a given order, each on its cheapest plan in the capacity the earlier
 * jobs left. Every plan it makes meets the window, deadline, price and capacity rules, so any order
 * gives a valid schedule. The heuristic solver searches over orders.
 */
final class GreedyAllocator {

    private static final double DONE_TOLERANCE = 1e-12;

    private GreedyAllocator() {
    }

    /** Plans for the jobs in {@code order} that fit; a job missing from the map did not fit. */
    static Map<OptimizerJob, JobPlan> placeInOrder(List<OptimizerJob> order, OptimizationProblem problem,
                                                   EnergyCostModel costs) {
        ResidualCapacity free = problem.residualAfterExemptJobs();
        Map<OptimizerJob, JobPlan> plans = new LinkedHashMap<>();
        for (OptimizerJob job : order) {
            cheapestPlan(job, free, problem.prices(), costs).ifPresent(plan -> {
                free.reserve(plan);
                plans.put(job, plan);
            });
        }
        return plans;
    }

    /**
     * The cheapest plan for one job given the capacity left. For each allowed start and each run length,
     * every hour gets {@link AllocationSolver#MIN_SHARE} and the rest of the work goes to the cheapest
     * hours first, up to what each hour has free. That is optimal for one job over a fixed set of hours,
     * because cost is linear in the shares. Ties keep the earlier start, then the shorter run.
     */
    static Optional<JobPlan> cheapestPlan(OptimizerJob job, ResidualCapacity free, PriceSeries prices,
                                          EnergyCostModel costs) {
        JobWindow window = new JobWindow(job, prices);
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
                    best = plan(job, hours, shares);
                    bestCost = cost;
                }
            }
        }
        return Optional.ofNullable(best);
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
