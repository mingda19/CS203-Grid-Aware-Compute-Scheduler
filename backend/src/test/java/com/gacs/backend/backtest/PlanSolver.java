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
 * (docs/backtest-plan.md section 3.4), so it uses the exact solver with a generous budget and, when that does not
 * prove optimality, the deterministic heuristic on its own. Every day records whether it was proven optimal.
 */
@FunctionalInterface
public interface PlanSolver {

    AllocationResult solve(OptimizationProblem problem);

    static PlanSolver repeatable(EnergyCostModel costs, Duration exactBudget) {
        AnnealingAllocationSolver heuristic = new AnnealingAllocationSolver(costs);
        return problem -> {
            Optional<AllocationResult> exact = new ExactAllocationSolver(costs).solve(problem, exactBudget);
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
    static PlanSolver named(String name, EnergyCostModel costs, Duration exactBudget) {
        return switch (name) {
            case REPEATABLE -> repeatable(costs, exactBudget);
            case HEURISTIC -> heuristicOnly(costs);
            default -> throw new IllegalArgumentException("unknown backtest.solver '" + name
                    + "': use " + REPEATABLE + " or " + HEURISTIC);
        };
    }

    String REPEATABLE = "repeatable";
    String HEURISTIC = "heuristic";
}
