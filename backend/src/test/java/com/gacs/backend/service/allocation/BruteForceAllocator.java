package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only reference solver for the work-based allocation model. It tries every plan for every job
 * and keeps the best, by the contract's objective (section 6.4, Objective):
 * <ol>
 *   <li>place as many jobs as possible;</li>
 *   <li>then the lowest total cost;</li>
 *   <li>then, between plans that leave out different jobs, place the job with the earlier
 *       {@code originalStartTime} (then lower {@code id}).</li>
 * </ol>
 *
 * <p>Continuous shares cannot be enumerated, so shares are multiples of {@code 1 / quanta}. Every
 * hand-checked case is built so its true optimum lies on that grid (capacities that are whole
 * quanta of each job's work). Exponential in the number of jobs: small cases only.
 *
 * <p>Exempt jobs are fixed at their baseline and may exceed a slot's limit together; that slot then
 * has no room for other jobs (decision 4b).
 */
final class BruteForceAllocator {

    private static final double TOLERANCE = 1e-9;

    record Outcome(OptimizerJob job, Optional<JobPlan> plan) {
    }

    /**
     * @param outcomes    one per input job, in input order
     * @param placedCount scheduled jobs, exempt ones included
     * @param costUsd     raw total cost of every scheduled job, exempt ones included
     */
    record Result(List<Outcome> outcomes, int placedCount, double costUsd) {

        Optional<JobPlan> planFor(long jobId) {
            return outcomes.stream()
                    .filter(outcome -> outcome.job().id() == jobId)
                    .findFirst()
                    .orElseThrow()
                    .plan();
        }
    }

    private record Option(JobPlan plan, double cost) {
    }

    private BruteForceAllocator() {
    }

    static Result solve(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity, EnergyCostModel costs,
                        int quanta) {
        BaselineCalculator baselines = new BaselineCalculator(capacity);
        Map<Long, JobPlan> exemptPlans = new HashMap<>();
        Map<LocalDateTime, double[]> exemptUse = new HashMap<>();
        double exemptCost = 0.0;
        for (OptimizerJob job : jobs) {
            if (job.exempt()) {
                JobPlan plan = baselines.baseline(job).plan().orElseThrow(() ->
                        new IllegalArgumentException("exempt job " + job.label() + " cannot finish by its deadline"));
                exemptPlans.put(job.id(), plan);
                exemptCost += costs.costUsd(plan, prices);
                add(exemptUse, plan, 1.0);
            }
        }

        // Flexible jobs in tie-break order: earlier originalStartTime first, then lower id.
        List<OptimizerJob> flexible = jobs.stream()
                .filter(job -> !job.exempt())
                .sorted(Comparator.comparing(OptimizerJob::originalStartTime).thenComparing(OptimizerJob::id))
                .toList();
        List<List<Option>> options = new ArrayList<>();
        for (OptimizerJob job : flexible) {
            options.add(optionsFor(job, prices, costs, quanta));
        }

        Search search = new Search(flexible, options, capacity, exemptUse);
        search.run(0, new Option[flexible.size()], 0, 0.0);

        Map<Long, JobPlan> chosen = new HashMap<>(exemptPlans);
        for (int i = 0; i < flexible.size(); i++) {
            if (search.best[i] != null) {
                chosen.put(flexible.get(i).id(), search.best[i].plan());
            }
        }
        List<Outcome> outcomes = jobs.stream()
                .map(job -> new Outcome(job, Optional.ofNullable(chosen.get(job.id()))))
                .toList();
        return new Result(outcomes, exemptPlans.size() + search.bestPlaced, exemptCost + search.bestCost);
    }

    /** Every plan the job's window allows, with shares in steps of 1 / quanta. */
    private static List<Option> optionsFor(OptimizerJob job, PriceSeries prices, EnergyCostModel costs, int quanta) {
        JobWindow window = new JobWindow(job, prices);
        List<Option> result = new ArrayList<>();
        for (LocalDateTime start : window.startSlots()) {
            List<LocalDateTime> run = window.runFrom(start);
            for (int length = 1; length <= Math.min(quanta, run.size()); length++) {
                for (int[] parts : compositions(quanta, length)) {
                    List<SlotAllocation> allocations = new ArrayList<>();
                    for (int k = 0; k < length; k++) {
                        allocations.add(new SlotAllocation(run.get(k), (double) parts[k] / quanta));
                    }
                    JobPlan plan = new JobPlan(job, allocations);
                    result.add(new Option(plan, costs.costUsd(plan, prices)));
                }
            }
        }
        return result;
    }

    /** Every way to write {@code total} as {@code parts} positive integers, in order. */
    static List<int[]> compositions(int total, int parts) {
        List<int[]> result = new ArrayList<>();
        compose(total, parts, new int[parts], 0, result);
        return result;
    }

    private static void compose(int left, int parts, int[] current, int index, List<int[]> out) {
        if (index == parts - 1) {
            current[index] = left;
            out.add(current.clone());
            return;
        }
        for (int value = 1; value <= left - (parts - index - 1); value++) {
            current[index] = value;
            compose(left - value, parts, current, index + 1, out);
        }
    }

    private static void add(Map<LocalDateTime, double[]> use, JobPlan plan, double sign) {
        for (SlotAllocation allocation : plan.allocations()) {
            double[] used = use.computeIfAbsent(allocation.slotStart(), slot -> new double[2]);
            used[0] += sign * allocation.cpuHours(plan.job());
            used[1] += sign * allocation.gpuHours(plan.job());
        }
    }

    private static final class Search {
        private final List<OptimizerJob> jobs;
        private final List<List<Option>> options;
        private final SlotCapacity capacity;
        private final Map<LocalDateTime, double[]> exemptUse;
        private final Map<LocalDateTime, double[]> use = new HashMap<>();

        private Option[] best;
        private int bestPlaced = -1;
        private double bestCost;

        Search(List<OptimizerJob> jobs, List<List<Option>> options, SlotCapacity capacity,
               Map<LocalDateTime, double[]> exemptUse) {
            this.jobs = jobs;
            this.options = options;
            this.capacity = capacity;
            this.exemptUse = exemptUse;
        }

        void run(int index, Option[] chosen, int placed, double cost) {
            if (index == jobs.size()) {
                if (isBetter(chosen, placed, cost)) {
                    best = chosen.clone();
                    bestPlaced = placed;
                    bestCost = cost;
                }
                return;
            }
            chosen[index] = null; // leave this job out
            run(index + 1, chosen, placed, cost);
            for (Option option : options.get(index)) {
                if (fits(option.plan())) {
                    add(use, option.plan(), 1.0);
                    chosen[index] = option;
                    run(index + 1, chosen, placed + 1, cost + option.cost());
                    add(use, option.plan(), -1.0);
                }
            }
            chosen[index] = null;
        }

        private boolean fits(JobPlan plan) {
            for (SlotAllocation allocation : plan.allocations()) {
                LocalDateTime slot = allocation.slotStart();
                double[] reserved = exemptUse.getOrDefault(slot, new double[2]);
                CapacityLimit free = capacity.limitAt(slot).minus(reserved[0], reserved[1]);
                double[] used = use.getOrDefault(slot, new double[2]);
                if (used[0] + allocation.cpuHours(plan.job()) > free.cpuHours() + TOLERANCE
                        || used[1] + allocation.gpuHours(plan.job()) > free.gpuHours() + TOLERANCE) {
                    return false;
                }
            }
            return true;
        }

        private boolean isBetter(Option[] chosen, int placed, double cost) {
            if (placed != bestPlaced) {
                return placed > bestPlaced;
            }
            if (Math.abs(cost - bestCost) > TOLERANCE) {
                return cost < bestCost;
            }
            for (int i = 0; i < chosen.length; i++) {
                boolean mine = chosen[i] != null;
                boolean theirs = best[i] != null;
                if (mine != theirs) {
                    return mine; // the earlier job in tie-break order is placed
                }
            }
            return false; // equal on every rule: keep the first plan found
        }
    }
}
