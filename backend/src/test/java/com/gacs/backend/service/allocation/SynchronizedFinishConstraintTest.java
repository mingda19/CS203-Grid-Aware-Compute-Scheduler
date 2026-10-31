package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.exempt;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code SynchronizedFinishConstraint} / {@link SynchronizedGroupPlacer}: "every member finishes together." */
class SynchronizedFinishConstraintTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS, 200);

    @Test
    @DisplayName("GreedyAllocator.cheapestPlanEndingAt: cheapest plan reaching exactly the given hour")
    void cheapestPlanEndingAtFindsFixedLengthPlan() {
        // Starts 0 or 1, must end at hour 1 (its lastSlot): stretching back through the pricey hour 0
        // costs strictly more than the single-hour plan starting right at hour 1.
        OptimizerJob theJob = job(1, 200.0, 0.0, 0, 1, 2);
        ResidualCapacity free = new ResidualCapacity(cpuCapacity(256.0));
        var plan = GreedyAllocator.cheapestPlanEndingAt(theJob, at(1), free, prices(100, 1), COSTS);

        assertThat(plan).isPresent();
        assertThat(plan.get().start()).isEqualTo(at(1));
        assertThat(plan.get().end()).isEqualTo(at(2));
    }

    @Test
    @DisplayName("GreedyAllocator.cheapestPlanEndingAt: empty when no run from any start reaches that hour")
    void cheapestPlanEndingAtEmptyWhenUnreachable() {
        OptimizerJob theJob = job(1, 20.0, 0.0, 0, 0, 1); // only candidate start is hour 0, 1-hour window
        ResidualCapacity free = new ResidualCapacity(cpuCapacity(256.0));
        var plan = GreedyAllocator.cheapestPlanEndingAt(theJob, at(3), free, prices(10, 10, 10, 10), COSTS);

        assertThat(plan).isEmpty();
    }

    @Test
    @DisplayName("A group of 3 jobs with different windows all finish at the same, cheapest-common hour")
    void groupFindsCheapestCommonEndHour() {
        SynchronizedFinishConstraint group = new SynchronizedFinishConstraint(List.of(1L, 2L, 3L));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 3, 4), job(2, 20.0, 0.0, 1, 2, 4), job(3, 20.0, 0.0, 0, 4, 5)),
                prices(10, 10, 1, 10, 10), cpuCapacity(256.0), List.of(group));

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(exactResult.scheduledCount()).isEqualTo(3);
        assertThat(endsOf(exactResult, 1, 2, 3)).hasSize(1);

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(heuristicResult.scheduledCount()).isEqualTo(3);
        assertThat(endsOf(heuristicResult, 1, 2, 3)).hasSize(1);
    }

    @Test
    @DisplayName("No common feasible end hour: the whole group fails closed, not partially")
    void groupFailsClosedTogetherWhenNoCommonEndHour() {
        // Job 1 can only ever end at hour 1; job 2 can only ever end at hour 3. No hour works for both,
        // even though each alone is perfectly schedulable.
        SynchronizedFinishConstraint group = new SynchronizedFinishConstraint(List.of(1L, 2L));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 0, 1), job(2, 20.0, 0.0, 2, 0, 3)),
                prices(10, 10, 10), cpuCapacity(256.0), List.of(group));

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(exactResult.scheduledCount()).isEqualTo(0);
        assertThat(outcomeFor(exactResult, 1).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(outcomeFor(exactResult, 2).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(heuristicResult.scheduledCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Capacity the group reserves is visible to the ordinary jobs placed afterwards")
    void groupReservationDisplacesOtherJobs() {
        // The group (jobs 1,2) takes all capacity at hour 0; job 3 (not in the group) wants hour 0 too,
        // and must move to hour 1 instead.
        SynchronizedFinishConstraint group = new SynchronizedFinishConstraint(List.of(1L, 2L));
        OptimizationProblem problem = problem(
                List.of(job(1, 128.0, 0.0, 0, 0, 1), job(2, 128.0, 0.0, 0, 0, 1), job(3, 20.0, 0.0, 0, 1, 2)),
                prices(1, 2), cpuCapacity(256.0), List.of(group));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.scheduledCount()).isEqualTo(3);
        assertThat(planFor(result, 3).start()).isEqualTo(at(1));
    }

    @Test
    @DisplayName("A group with fewer than 2 flexible members (the rest exempt) is not specially handled")
    void groupOfFewerThanTwoFlexibleMembersIsANoOp() {
        SynchronizedFinishConstraint group = new SynchronizedFinishConstraint(List.of(1L, 2L));
        OptimizationProblem problem = problem(
                List.of(job(1, 20.0, 0.0, 0, 2, 3), exempt(2, 20.0, 0.0, 0, 1)),
                prices(1, 9), cpuCapacity(256.0), List.of(group));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(outcomeFor(result, 1).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcomeFor(result, 2).status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
    }

    private static List<LocalDateTime> endsOf(AllocationResult result, long... jobIds) {
        Map<Long, JobPlan> byId = new java.util.HashMap<>();
        result.plans().forEach((job, plan) -> byId.put(job.id(), plan));
        return java.util.Arrays.stream(jobIds).mapToObj(id -> byId.get(id).end()).distinct().toList();
    }

    private static JobPlan planFor(AllocationResult result, long jobId) {
        return result.plans().entrySet().stream()
                .filter(e -> e.getKey().id() == jobId)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static JobOutcome outcomeFor(AllocationResult result, long jobId) {
        return result.outcomes().stream().filter(o -> o.job().id() == jobId).findFirst().orElseThrow();
    }
}
