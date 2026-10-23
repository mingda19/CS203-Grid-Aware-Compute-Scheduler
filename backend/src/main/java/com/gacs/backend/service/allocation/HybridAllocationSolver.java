package com.gacs.backend.service.allocation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * The production solver: tries {@link ExactAllocationSolver} first and returns its plan when it is proven
 * optimal. Otherwise (run too large, time budget used up, or a solver error) it runs
 * {@link AnnealingAllocationSolver} and returns whichever of the two plans is better.
 */
@Service
public class HybridAllocationSolver implements AllocationSolver {

    /**
     * Runs with more (flexible job, hour) pairs than this skip the exact solver. Measured on 2026-10-23
     * (-Xmx384m, 48-72 hour windows): about 10 jobs (~100 pairs) solve and prove optimal in under 0.2 s;
     * about 20 jobs (~200 pairs) use the whole budget without a proof; 40+ jobs find nothing in time.
     * The heuristic takes about 0.6 s at 60 jobs over 72 hours, so larger runs go straight to it.
     */
    static final int DEFAULT_SIZE_THRESHOLD = 300;

    /**
     * Time budget for the exact solver's two passes together. ojAlgo can overrun it somewhat, since it
     * checks the limit between steps.
     */
    static final Duration DEFAULT_TIME_BUDGET = Duration.ofSeconds(2);

    private final EnergyCostModel costs;
    private final int sizeThreshold;
    private final Duration timeBudget;
    private final AnnealingAllocationSolver heuristic;

    @Autowired
    public HybridAllocationSolver(EnergyCostModel costs) {
        this(costs, DEFAULT_SIZE_THRESHOLD, DEFAULT_TIME_BUDGET, new AnnealingAllocationSolver(costs));
    }

    /** For tests: force the routing without building a large input or waiting out a real budget. */
    HybridAllocationSolver(EnergyCostModel costs, int sizeThreshold, Duration timeBudget,
                           AnnealingAllocationSolver heuristic) {
        this.costs = costs;
        this.sizeThreshold = sizeThreshold;
        this.timeBudget = timeBudget;
        this.heuristic = heuristic;
    }

    @Override
    public AllocationResult solve(OptimizationProblem problem) {
        Optional<AllocationResult> exact = Optional.empty();
        if (size(problem) <= sizeThreshold) {
            try {
                exact = new ExactAllocationSolver(costs, timeBudget).solve(problem);
            } catch (RuntimeException solverFailure) {
                // Fall through to the heuristic: a valid plan beats a 500.
            }
            if (exact.isPresent() && exact.get().provenOptimal()) {
                return exact.get();
            }
        }
        AllocationResult fallback = heuristic.solve(problem);
        if (exact.isPresent() && AllocationResult.isBetter(exact.get(), fallback, problem, costs)) {
            return exact.get();
        }
        return fallback;
    }

    /** Number of (flexible job, hour) pairs the exact model would have. */
    static int size(OptimizationProblem problem) {
        int pairs = 0;
        for (OptimizerJob job : problem.flexibleJobsInTieBreakOrder()) {
            JobWindow window = new JobWindow(job, problem.prices());
            long hours = java.time.Duration.between(job.originalStartTime(), window.lastSlot()).toHours() + 1;
            pairs += (int) Math.max(0, hours);
        }
        return pairs;
    }
}
