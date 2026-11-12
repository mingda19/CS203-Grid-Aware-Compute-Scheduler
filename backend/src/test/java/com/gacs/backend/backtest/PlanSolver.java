package com.gacs.backend.backtest;

import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.AnnealingAllocationSolver;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.ExactAllocationSolver;
import com.gacs.backend.service.allocation.OptimizationProblem;

import java.time.Duration;
import java.util.Optional;

/**
 * How the backtest plans one day. The production {@code HybridAllocationSolver} returns the better of a time-boxed
 * exact plan and the heuristic, so its answer can depend on machine speed. The backtest must be repeatable
 * (docs/backtest-plan.md section 3.4), so it limits the exact solver by iterations, not by time, and, when that does
 * not prove optimality, uses the deterministic heuristic on its own. Every day records whether it was proven optimal.
 */
@FunctionalInterface
public interface PlanSolver {

    AllocationResult solve(OptimizationProblem problem);

    /** Default {@code backtest.exactIterations}: exact-solver iterations per pass before the heuristic is used. */
    int DEFAULT_EXACT_ITERATIONS = 100_000;

    /** Only so a broken solver cannot hang a run; a plan that gets near it is no longer repeatable. */
    Duration NO_TIME_LIMIT = Duration.ofDays(1);

    static PlanSolver repeatable(EnergyCostModel costs, int exactIterations) {
        AnnealingAllocationSolver heuristic = new AnnealingAllocationSolver(costs);
        return problem -> {
            Optional<AllocationResult> exact = new ExactAllocationSolver(costs).solve(problem, NO_TIME_LIMIT, exactIterations);
            if (exact.isPresent() && exact.get().provenOptimal()) {
                return exact.get();
            }
            return heuristic.solve(problem);
        };
    }

    /** The deterministic heuristic alone, never proven optimal. Used to measure its gap to the exact plans (A8). */
    static PlanSolver heuristicOnly(EnergyCostModel costs) {
        return new AnnealingAllocationSolver(costs)::solve;
    }

    /** By name, as given in {@code backtest.solver}: {@code repeatable} (the default) or {@code heuristic}. */
    static PlanSolver named(String name, EnergyCostModel costs, int exactIterations) {
        return switch (name) {
            case REPEATABLE -> repeatable(costs, exactIterations);
            case HEURISTIC -> heuristicOnly(costs);
            default -> throw new IllegalArgumentException("unknown backtest.solver '" + name
                    + "': use " + REPEATABLE + " or " + HEURISTIC);
        };
    }

    String REPEATABLE = "repeatable";
    String HEURISTIC = "heuristic";
}
