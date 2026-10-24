package com.gacs.backend.service.allocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Heuristic fallback for runs too large to solve exactly in time. It searches over the order in which
 * {@link GreedyAllocator} places jobs, using simulated annealing (swap two jobs, accept worse orders
 * with a probability that shrinks over time), and keeps the best result by the contract's objective.
 *
 * <p>Every order gives a valid schedule, so every result meets all the rules; it is just not proven
 * optimal. The search starts from {@link OptimizationProblem#TIE_BREAK_ORDER}, so earlier jobs get
 * first pick of capacity. It is deterministic: the random generator is seeded from the problem itself
 * and the number of iterations depends only on the number of jobs, never on a wall-clock limit.
 *
 * <p>After a swap, only the jobs from the earlier swapped position onward are placed again: the jobs
 * before it see the same capacity, so their plans cannot change.
 */
public final class AnnealingAllocationSolver implements AllocationSolver {

    static final int DEFAULT_ITERATIONS = 5_000;

    /**
     * Caps iterations x jobs so large runs stay fast: 60 jobs over 72 hours get 2,500 iterations, measured
     * at well under a second. A starting point, not a tuned value.
     */
    static final int WORK_LIMIT = 150_000;
    private static final int MIN_ITERATIONS = 100;

    /** Energy weight per scheduled job; larger than any cost term, which is scaled to [-1, 1]. */
    private static final double SCHEDULED_WEIGHT = 4.0;
    private static final double START_TEMPERATURE = 1.0;
    private static final double COOLING_RATE = 0.995;

    private final EnergyCostModel costs;
    private final int iterations;

    public AnnealingAllocationSolver(EnergyCostModel costs) {
        this(costs, DEFAULT_ITERATIONS);
    }

    public AnnealingAllocationSolver(EnergyCostModel costs, int iterations) {
        this.costs = costs;
        this.iterations = iterations;
    }

    @Override
    public AllocationResult solve(OptimizationProblem problem) {
        List<OptimizerJob> order = problem.flexibleJobsInTieBreakOrder();
        Placement current = Placement.decode(order, 0, null, problem, costs);
        AllocationResult best = current.result(problem);
        if (order.size() < 2) {
            return best;
        }
        double costScale = problem.costScale(costs);
        Random random = new Random(seed(problem));
        double currentEnergy = energy(best, problem, costScale);
        double temperature = START_TEMPERATURE;
        int rounds = Math.min(iterations, Math.max(MIN_ITERATIONS, WORK_LIMIT / order.size()));

        for (int i = 0; i < rounds; i++) {
            List<OptimizerJob> swapped = new ArrayList<>(current.order());
            int first = random.nextInt(swapped.size());
            int second = random.nextInt(swapped.size() - 1);
            if (second >= first) {
                second++;
            }
            Collections.swap(swapped, first, second);

            Placement candidate = Placement.decode(swapped, Math.min(first, second), current, problem, costs);
            AllocationResult result = candidate.result(problem);
            double energy = energy(result, problem, costScale);
            if (energy < currentEnergy || random.nextDouble() < Math.exp((currentEnergy - energy) / temperature)) {
                current = candidate;
                currentEnergy = energy;
            }
            if (AllocationResult.isBetter(result, best, problem, costs)) {
                best = result;
            }
            temperature *= COOLING_RATE;
        }
        return best;
    }

    /**
     * Jobs placed greedily in {@code order}, with the capacity left before each position kept so a later
     * decode can restart part-way through.
     */
    private record Placement(List<OptimizerJob> order, List<ResidualCapacity> freeBefore, List<JobPlan> plans) {

        static Placement decode(List<OptimizerJob> order, int from, Placement previous, OptimizationProblem problem,
                                EnergyCostModel costs) {
            List<ResidualCapacity> freeBefore = new ArrayList<>();
            List<JobPlan> plans = new ArrayList<>();
            ResidualCapacity free;
            if (from == 0) {
                free = problem.residualAfterExemptJobs();
            } else {
                freeBefore.addAll(previous.freeBefore().subList(0, from));
                plans.addAll(previous.plans().subList(0, from));
                free = previous.freeBefore().get(from).copy();
            }
            for (int k = from; k < order.size(); k++) {
                freeBefore.add(free.copy());
                JobPlan plan = GreedyAllocator.cheapestPlan(order.get(k), free, problem.prices(), costs).orElse(null);
                if (plan != null) {
                    free.reserve(plan);
                }
                plans.add(plan);
            }
            return new Placement(List.copyOf(order), freeBefore, plans);
        }

        AllocationResult result(OptimizationProblem problem) {
            Map<OptimizerJob, JobPlan> placed = new HashMap<>();
            for (int k = 0; k < order.size(); k++) {
                if (plans.get(k) != null) {
                    placed.put(order.get(k), plans.get(k));
                }
            }
            return AllocationResult.of(problem, placed, order.isEmpty());
        }
    }

    private double energy(AllocationResult result, OptimizationProblem problem, double costScale) {
        return -SCHEDULED_WEIGHT * result.scheduledCount() + result.costUsd(costs, problem.prices()) / costScale;
    }

    /** Stable across runs and JVMs: built only from the problem's values. */
    private static long seed(OptimizationProblem problem) {
        return Objects.hash(problem.jobs(), problem.prices().asMap());
    }
}
