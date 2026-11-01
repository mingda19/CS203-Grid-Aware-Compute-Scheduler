package com.gacs.backend.service.allocation;

import java.time.Duration;
import java.util.Optional;

/**
 * A solver that may legitimately decline to answer within a budget: given more time it might
 * still find or rule out a feasible plan, so {@link Optional#empty()} means "inconclusive within
 * budget", never "no feasible plan exists". That is a genuinely different contract from
 * {@link AllocationSolver}, which always returns an answer - conflating the two is what used to
 * leave {@link ExactAllocationSolver} outside any shared interface at all. {@link
 * HybridAllocationSolver} is the adapter between them, falling back to an {@link AllocationSolver}
 * when this one runs out of time.
 */
public interface BoundedAllocationSolver {

    Optional<AllocationResult> solve(OptimizationProblem problem, Duration budget);
}
