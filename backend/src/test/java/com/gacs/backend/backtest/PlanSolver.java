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
            Optional<AllocationResult> exact = new ExactAllocationSolver(costs, exactBudget).solve(problem);
            if (exact.isPresent() && exact.get().provenOptimal()) {
                return exact.get();
            }
            return heuristic.solve(problem);
        };
    }
}
