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

/** {@code PrecedenceConstraint}: "job A must finish before job B starts." */
class PrecedenceConstraintTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS, 200);

    @Test
    @DisplayName("Exact solver: the after-job is forced to start once the before-job actually finishes")
    void exactSolverEnforcesGap() {
        // Unconstrained, both jobs would want the cheap hour 1. With A->B precedence, B must wait for A's end.
        PrecedenceConstraint constraint = new PrecedenceConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 2), job(2, 20.0, 0.0, 0, 3, 4)),
                prices(10, 1, 1, 9), cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.provenOptimal()).isTrue();
        JobPlan beforePlan = planFor(result, 1);
        JobPlan afterPlan = planFor(result, 2);
        assertThat(afterPlan.start()).isAfterOrEqualTo(beforePlan.end());
    }

    @Test
    @DisplayName("Greedy/heuristic solver: the same gap is enforced, with the right placement order")
    void heuristicSolverEnforcesGap() {
        PrecedenceConstraint constraint = new PrecedenceConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 2), job(2, 20.0, 0.0, 0, 3, 4)),
                prices(10, 1, 1, 9), cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        JobPlan beforePlan = planFor(result, 1);
        JobPlan afterPlan = planFor(result, 2);
        assertThat(afterPlan.start()).isAfterOrEqualTo(beforePlan.end());
    }

    @Test
    @DisplayName("Infeasible precedence: the after-job's window cannot accommodate waiting for the before-job")
    void infeasibleWhenAfterJobCannotWait() {
        // Both jobs' only possible hour is hour 0 - scheduling both is impossible under precedence (job 2
        // would have to start before job 1 even finishes). Same originalStartTime, so the tie-break (lower
        // id first) unambiguously keeps job 1 and drops job 2, rather than leaving it to the solver's own
        // preference between two otherwise-equal choices.
        PrecedenceConstraint constraint = new PrecedenceConstraint(1, 2);
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 0, 1), job(2, 20.0, 0.0, 0, 0, 1)),
                prices(10), cpuCapacity(256.0), List.of(constraint));

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(outcomeFor(exactResult, 1).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcomeFor(exactResult, 2).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(outcomeFor(heuristicResult, 1).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcomeFor(heuristicResult, 2).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
    }

    @Test
    @DisplayName("Chained precedence (A -> B -> C) resolves correctly in both solvers")
    void chainedPrecedenceResolves() {
        List<Constraint> constraints = List.of(new PrecedenceConstraint(1, 2), new PrecedenceConstraint(2, 3));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 5, 1), job(2, 20.0, 0.0, 0, 5, 2), job(3, 20.0, 0.0, 0, 5, 3)),
                prices(1, 1, 1, 1, 1, 1), cpuCapacity(256.0), constraints);

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(exactResult.scheduledCount()).as("all 3 chained jobs scheduled, exact").isEqualTo(3);
        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(heuristicResult.scheduledCount()).as("all 3 chained jobs scheduled, heuristic").isEqualTo(3);
    }

    @Test
    @DisplayName("Random small runs with one precedence edge: both solvers always respect it")
    void randomCasesRespectPrecedence() {
        Random random = new Random(20261029L);
        int checked = 0;
        int withGenuineWait = 0;
        while (checked < 60) {
            int hours = 6;
            double[] perHour = new double[hours];
            for (int h = 0; h < hours; h++) {
                perHour[h] = 1 + random.nextInt(60);
            }
            OptimizerJob beforeJob = job(1, 50.0, 0.0, random.nextInt(2), random.nextInt(2), 2 + random.nextInt(3));
            OptimizerJob afterJob = job(2, 50.0, 0.0, random.nextInt(2), random.nextInt(3), 3 + random.nextInt(3));
            PrecedenceConstraint constraint = new PrecedenceConstraint(1, 2);
            OptimizationProblem randomProblem = problem(List.of(beforeJob, afterJob), prices(perHour),
                    cpuCapacity(500.0), List.of(constraint));

            AllocationResult exactResult = EXACT.solve(randomProblem).orElseThrow();
            assertThat(ContractRules.violations(randomProblem, exactResult)).as("exact case %d", checked).isEmpty();
            AllocationResult heuristicResult = HEURISTIC.solve(randomProblem);
            assertThat(ContractRules.violations(randomProblem, heuristicResult)).as("heuristic case %d", checked)
                    .isEmpty();
            if (planFor(exactResult, 1) != null && planFor(exactResult, 2) != null
                    && planFor(exactResult, 2).start().isAfter(afterJob.originalStartTime())) {
                withGenuineWait++;
            }
            checked++;
        }
        // Not just coincidentally-already-ordered cases: some runs must actually force the after-job to wait.
        assertThat(withGenuineWait).isGreaterThan(5);
    }

    private static JobPlan planFor(AllocationResult result, long jobId) {
        return result.plans().entrySet().stream()
                .filter(e -> e.getKey().id() == jobId)
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static JobOutcome outcomeFor(AllocationResult result, long jobId) {
        return result.outcomes().stream().filter(o -> o.job().id() == jobId).findFirst().orElseThrow();
    }
}
