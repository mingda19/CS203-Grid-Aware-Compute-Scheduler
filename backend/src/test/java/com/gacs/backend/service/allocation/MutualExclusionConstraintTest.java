package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code MutualExclusionConstraint}: "jobs A and B may never run in the same hour." */
class MutualExclusionConstraintTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS, 200);

    @Test
    @DisplayName("Exact solver: both jobs would pick the same cheap hour unconstrained; exclusion separates them")
    void exactSolverSeparatesOverlap() {
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 3), job(2, 20.0, 0.0, 0, 2, 3)),
                prices(1, 9, 9), cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.provenOptimal()).isTrue();
        assertThat(result.scheduledCount()).isEqualTo(2);
        JobPlan plan1 = planFor(result, 1);
        JobPlan plan2 = planFor(result, 2);
        assertThat(overlaps(plan1, plan2)).isFalse();
        // Exactly one of them gets the cheap hour 0; the other is pushed to a dearer hour.
        assertThat(List.of(plan1.start(), plan2.start())).contains(Fixtures.at(0));
    }

    @Test
    @DisplayName("Greedy/heuristic solver: the same separation holds")
    void heuristicSolverSeparatesOverlap() {
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 3), job(2, 20.0, 0.0, 0, 2, 3)),
                prices(1, 9, 9), cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.scheduledCount()).isEqualTo(2);
        assertThat(overlaps(planFor(result, 1), planFor(result, 2))).isFalse();
    }

    @Test
    @DisplayName("Placement order does not matter: whichever job is placed second avoids the first")
    void orderIndependent() {
        // Same problem, but with job 2 given the earlier id/start so tie-break placement order flips.
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(2, 1);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 3), job(2, 20.0, 0.0, 0, 2, 3)),
                prices(1, 9, 9), cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.scheduledCount()).isEqualTo(2);
        assertThat(overlaps(planFor(result, 1), planFor(result, 2))).isFalse();
    }

    @Test
    @DisplayName("Both jobs forced into the same single free hour: one is NO_FEASIBLE_SLOT")
    void infeasibleWhenOnlyOneSharedHourExists() {
        // Both jobs can only possibly run at hour 0 (single-hour windows); mutual exclusion means only one can.
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 0, 1), job(2, 20.0, 0.0, 0, 0, 1)),
                prices(10), cpuCapacity(256.0), List.of(constraint));

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(exactResult.scheduledCount()).isEqualTo(1);

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(heuristicResult.scheduledCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Random small runs with one exclusion pair: both solvers always keep the two jobs apart")
    void randomCasesRespectExclusion() {
        Random random = new Random(20261029L);
        int checked = 0;
        while (checked < 60) {
            int hours = 6;
            double[] perHour = new double[hours];
            for (int h = 0; h < hours; h++) {
                perHour[h] = 1 + random.nextInt(60);
            }
            OptimizerJob jobA = job(1, 50.0, 0.0, random.nextInt(3), random.nextInt(3), 3 + random.nextInt(3));
            OptimizerJob jobB = job(2, 50.0, 0.0, random.nextInt(3), random.nextInt(3), 3 + random.nextInt(3));
            MutualExclusionConstraint constraint = new MutualExclusionConstraint(1, 2);
            OptimizationProblem randomProblem = problem(List.of(jobA, jobB), prices(perHour), cpuCapacity(500.0),
                    List.of(constraint));

            AllocationResult exactResult = EXACT.solve(randomProblem).orElseThrow();
            assertThat(ContractRules.violations(randomProblem, exactResult)).as("exact case %d", checked).isEmpty();
            AllocationResult heuristicResult = HEURISTIC.solve(randomProblem);
            assertThat(ContractRules.violations(randomProblem, heuristicResult)).as("heuristic case %d", checked)
                    .isEmpty();
            checked++;
        }
    }

    private static boolean overlaps(JobPlan a, JobPlan b) {
        return a.start().isBefore(b.end()) && b.start().isBefore(a.end());
    }

    private static JobPlan planFor(AllocationResult result, long jobId) {
        return result.plans().entrySet().stream()
                .filter(e -> e.getKey().id() == jobId)
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
