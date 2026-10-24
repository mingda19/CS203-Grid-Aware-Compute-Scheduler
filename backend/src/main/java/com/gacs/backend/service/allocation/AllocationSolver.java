package com.gacs.backend.service.allocation;

/**
 * Places jobs under the contract's rules (section 6.4) and objective: schedule as many jobs as
 * possible, then minimise total cost, then break ties by {@link OptimizationProblem#TIE_BREAK_ORDER}.
 * Implementations must be deterministic: the same problem always gives the same result.
 */
public interface AllocationSolver {

    /**
     * Smallest share solvers put in an hour a job runs in. The contract only needs shares above 0; a floor
     * keeps "running" hours from silently carrying no work, which would hide a gap in the run.
     */
    double MIN_SHARE = 1e-4;

    AllocationResult solve(OptimizationProblem problem);
}
