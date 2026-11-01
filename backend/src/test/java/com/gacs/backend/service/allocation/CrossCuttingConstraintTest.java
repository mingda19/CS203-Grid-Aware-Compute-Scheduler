package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/** Two different constraint types attached to overlapping jobs, enforced together. */
class CrossCuttingConstraintTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS, 200);

    /**
     * Job 1 (flex 0) can only ever run at hour 3, ending hour 4 - so job 2's precedence lower bound is
     * always exactly hour 4. Job 2's absolute window alone would allow hour 3 or 4; precedence rules
     * hour 3 out, leaving only hour 4 - the priciest hour (9, vs 1 elsewhere) - as feasible. Job 2 must
     * land there despite it being worse than every hour its window alone would have allowed.
     */
    @Test
    @DisplayName("Precedence and an absolute time window on the same job are both enforced together")
    void precedenceAndAbsoluteWindowBothBind() {
        List<Constraint> constraints = List.of(
                new PrecedenceConstraint(1, 2),
                new AbsoluteTimeWindowConstraint(2, LocalTime.of(13, 0), LocalTime.of(15, 0)));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 3, 0, 4), job(2, 20.0, 0.0, 0, 4, 5)),
                prices(1, 1, 1, 1, 9), cpuCapacity(256.0), constraints);

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(exactResult.scheduledCount()).isEqualTo(2);
        assertThat(planFor(exactResult, 1).end()).isEqualTo(at(4));
        assertThat(planFor(exactResult, 2).start()).isEqualTo(at(4));

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(heuristicResult.scheduledCount()).isEqualTo(2);
        assertThat(planFor(heuristicResult, 2).start()).isEqualTo(at(4));
    }

    @Test
    @DisplayName("Same problem, multiple constraint types attached: both solvers are deterministic")
    void deterministicWithConstraintsAttached() {
        List<Constraint> constraints = List.of(
                new PrecedenceConstraint(1, 2),
                new AbsoluteTimeWindowConstraint(2, LocalTime.of(13, 0), LocalTime.of(15, 0)),
                new MutualExclusionConstraint(2, 3));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 3, 0, 4), job(2, 20.0, 0.0, 0, 4, 5), job(3, 20.0, 0.0, 0, 4, 5)),
                prices(1, 1, 1, 1, 9), cpuCapacity(256.0), constraints);

        assertThat(EXACT.solve(problem)).isEqualTo(EXACT.solve(problem));
        assertThat(HEURISTIC.solve(problem)).isEqualTo(HEURISTIC.solve(problem));
    }

    private static JobPlan planFor(AllocationResult result, long jobId) {
        return result.plans().entrySet().stream()
                .filter(e -> e.getKey().id() == jobId)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }
}
