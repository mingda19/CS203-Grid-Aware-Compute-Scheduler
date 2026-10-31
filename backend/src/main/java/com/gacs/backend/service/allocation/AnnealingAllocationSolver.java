package com.gacs.backend.service.allocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

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
        SynchronizedGroupPlacer.Placement groups = SynchronizedGroupPlacer.place(problem, costs);
        ResidualCapacity baseFree = problem.residualAfterExemptJobs();
        groups.plans().values().forEach(baseFree::reserve);
        Map<Long, JobPlan> basePlacedSoFar = new HashMap<>();
        groups.plans().forEach((job, plan) -> basePlacedSoFar.put(job.id(), plan));

        List<OptimizerJob> order = problem.flexibleJobsInPlacementOrder().stream()
                .filter(job -> !groups.groupedJobIds().contains(job.id()))
                .toList();
        List<PrecedenceConstraint> precedenceEdges = problem.constraints().stream()
                .filter(c -> c instanceof PrecedenceConstraint)
                .map(c -> (PrecedenceConstraint) c)
                .toList();

        Placement current = Placement.decode(order, 0, null, problem, costs, baseFree, basePlacedSoFar);
        AllocationResult currentResult = current.result(problem);
        AllocationResult best = mergeGroups(currentResult, groups.plans(), problem);
        if (order.size() < 2) {
            return best;
        }
        double costScale = problem.costScale(costs);
        Random random = new Random(seed(problem));
        double currentEnergy = energy(currentResult, problem, costScale);
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

            if (respectsPrecedence(swapped, precedenceEdges)) {
                Placement candidate = Placement.decode(swapped, Math.min(first, second), current, problem, costs,
                        baseFree, basePlacedSoFar);
                AllocationResult result = candidate.result(problem);
                double energy = energy(result, problem, costScale);
                if (energy < currentEnergy
                        || random.nextDouble() < Math.exp((currentEnergy - energy) / temperature)) {
                    current = candidate;
                    currentEnergy = energy;
                }
                AllocationResult merged = mergeGroups(result, groups.plans(), problem);
                if (AllocationResult.isBetter(merged, best, problem, costs)) {
                    best = merged;
                }
            }
            temperature *= COOLING_RATE;
        }
        return best;
    }

    /**
     * False when {@code order} would place a {@link PrecedenceConstraint}'s after-job at or before
     * its before-job - such an order is never evaluated, let alone accepted: once placed, the
     * after-job's {@link PrecedenceConstraint#restrict} only has anything to narrow against once the
     * before-job is already in {@code placedSoFar}, so the before-job must always come first.
     */
    private static boolean respectsPrecedence(List<OptimizerJob> order, List<PrecedenceConstraint> edges) {
        if (edges.isEmpty()) {
            return true;
        }
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i).id(), i);
        }
        for (PrecedenceConstraint edge : edges) {
            Integer beforeIndex = position.get(edge.beforeJobId());
            Integer afterIndex = position.get(edge.afterJobId());
            if (beforeIndex != null && afterIndex != null && beforeIndex >= afterIndex) {
                return false;
            }
        }
        return true;
    }

    /** {@code individual}'s plans plus the fixed {@link SynchronizedGroupPlacer} group plans. */
    private static AllocationResult mergeGroups(AllocationResult individual, Map<OptimizerJob, JobPlan> groupPlans,
                                                 OptimizationProblem problem) {
        if (groupPlans.isEmpty()) {
            return individual;
        }
        Map<OptimizerJob, JobPlan> merged = new HashMap<>(groupPlans);
        merged.putAll(individual.plans());
        return AllocationResult.of(problem, merged, individual.provenOptimal());
    }

    /**
     * Jobs placed greedily in {@code order}, with the capacity and constraint state before each
     * position kept so a later decode can restart part-way through.
     */
    private record Placement(List<OptimizerJob> order, List<ResidualCapacity> freeBefore, List<JobPlan> plans) {

        static Placement decode(List<OptimizerJob> order, int from, Placement previous, OptimizationProblem problem,
                                EnergyCostModel costs, ResidualCapacity baseFree, Map<Long, JobPlan> basePlacedSoFar) {
            List<ResidualCapacity> freeBefore = new ArrayList<>();
            List<JobPlan> plans = new ArrayList<>();
            ResidualCapacity free;
            Map<Long, JobPlan> placedSoFar = new HashMap<>(basePlacedSoFar);
            if (from == 0) {
                free = baseFree.copy();
            } else {
                freeBefore.addAll(previous.freeBefore().subList(0, from));
                plans.addAll(previous.plans().subList(0, from));
                free = previous.freeBefore().get(from).copy();
                for (int k = 0; k < from; k++) {
                    if (plans.get(k) != null) {
                        placedSoFar.put(order.get(k).id(), plans.get(k));
                    }
                }
            }
            for (int k = from; k < order.size(); k++) {
                OptimizerJob job = order.get(k);
                freeBefore.add(free.copy());
                JobPlan plan = GreedyAllocator.cheapestPlan(job, free, problem.prices(), costs,
                        problem.constraintsFor(job), placedSoFar).orElse(null);
                if (plan != null) {
                    free.reserve(plan);
                    placedSoFar.put(job.id(), plan);
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
