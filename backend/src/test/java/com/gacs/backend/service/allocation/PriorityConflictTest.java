package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GRID-160 / GRID-155 AC5: who gets a contested slot. Every case runs through the exact solver, the heuristic
 * and the production hybrid, and all three must agree. Capacity is 256 CPU-hours a slot, so two 200-hour jobs
 * never share a slot.
 */
class PriorityConflictTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS);
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS);
    private static final HybridAllocationSolver HYBRID = new HybridAllocationSolver(COSTS);

    private static OptimizerJob job(long id, JobPriority priority, double cpuHours, int startHour, int flexHours,
                                    int deadlineHour) {
        return new OptimizerJob(id, "job-" + id, cpuHours, 0.0, at(startHour), at(deadlineHour), flexHours, false,
                priority);
    }

    private static OptimizerJob exempt(long id, JobPriority priority, double cpuHours) {
        return new OptimizerJob(id, "exempt-" + id, cpuHours, 0.0, at(0), at(1), 0, true, priority);
    }

    /** Solves with all three solvers, checks the contract rules on each, and returns their results. */
    private static List<AllocationResult> solveAll(List<OptimizerJob> jobs, double... prices) {
        OptimizationProblem problem = problem(jobs, prices(prices), cpuCapacity(256.0));
        List<AllocationResult> results = List.of(
                EXACT.solve(problem, Duration.ofSeconds(10)).orElseThrow(),
                HEURISTIC.solve(problem),
                HYBRID.solve(problem));
        results.forEach(result -> assertThat(ContractRules.violations(problem, result)).isEmpty());
        return results;
    }

    private static JobOutcome outcome(AllocationResult result, long id) {
        return result.outcomes().stream().filter(o -> o.job().id() == id).findFirst().orElseThrow();
    }

    private static Map<Long, PerJobStatus> statuses(AllocationResult result) {
        Map<Long, PerJobStatus> statuses = new TreeMap<>();
        result.outcomes().forEach(o -> statuses.put(o.job().id(), o.status()));
        return statuses;
    }

    @Test
    @DisplayName("Two-job conflict: the higher-priority job gets the slot even though the other is cheaper")
    void twoJobConflict() {
        // Both want only slot 0 and cannot share it (100 + 200 > 256). The LOW job is smaller, so cheaper.
        for (AllocationResult result : solveAll(
                List.of(job(1, JobPriority.LOW, 100.0, 0, 0, 1), job(2, JobPriority.CRITICAL, 200.0, 0, 0, 1)), 10)) {
            assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.OPTIMISED);
            assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        }
    }

    @Test
    @DisplayName("Three-job conflict: the two highest priorities win both slots; the loser is told who took them")
    void threeJobConflict() {
        // Three 200-hour jobs, two slots: only two fit. The LOW job has the lowest id, so an id tie-break would pick it.
        for (AllocationResult result : solveAll(List.of(
                job(1, JobPriority.LOW, 200.0, 0, 1, 2),
                job(2, JobPriority.HIGH, 200.0, 0, 1, 2),
                job(3, JobPriority.CRITICAL, 200.0, 0, 1, 2)), 10, 10)) {
            assertThat(outcome(result, 3).status()).isEqualTo(PerJobStatus.OPTIMISED);
            assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.OPTIMISED);
            JobOutcome loser = outcome(result, 1);
            assertThat(loser.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
            assertThat(loser.reason()).contains("job id 3 (CRITICAL)").contains("1 other(s)");
        }
    }

    @Test
    @DisplayName("Equal priority: the earlier deadline wins, whatever the id")
    void equalPriorityEarlierDeadlineWins() {
        for (AllocationResult result : solveAll(
                List.of(job(1, JobPriority.NORMAL, 200.0, 0, 0, 3), job(2, JobPriority.NORMAL, 200.0, 0, 0, 1)), 10)) {
            assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.OPTIMISED);
            assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        }
    }

    @Test
    @DisplayName("Equal everything: the same inputs give the same schedule, in any input order and on every run")
    void equalPriorityIsDeterministic() {
        List<OptimizerJob> jobs = new ArrayList<>(List.of(
                job(1, JobPriority.NORMAL, 200.0, 0, 1, 2), job(2, JobPriority.NORMAL, 200.0, 0, 1, 2),
                job(3, JobPriority.NORMAL, 200.0, 0, 1, 2), job(4, JobPriority.NORMAL, 200.0, 0, 1, 2)));
        List<AllocationResult> forward = solveAll(jobs, 10, 10);
        List<AllocationResult> again = solveAll(jobs, 10, 10);
        Collections.reverse(jobs);
        List<AllocationResult> reversed = solveAll(jobs, 10, 10);

        for (int solver = 0; solver < forward.size(); solver++) {
            // Four jobs, two slots: the two lowest ids are placed.
            assertThat(statuses(forward.get(solver))).containsExactly(
                    Map.entry(1L, PerJobStatus.OPTIMISED), Map.entry(2L, PerJobStatus.OPTIMISED),
                    Map.entry(3L, PerJobStatus.NO_FEASIBLE_SLOT), Map.entry(4L, PerJobStatus.NO_FEASIBLE_SLOT));
            assertThat(forward.get(solver).plans().values()).containsExactlyInAnyOrderElementsOf(
                    again.get(solver).plans().values());
            assertThat(reversed.get(solver).plans().values()).containsExactlyInAnyOrderElementsOf(
                    forward.get(solver).plans().values());
        }
    }

    @Test
    @DisplayName("Exempt job: priority never moves it or takes its slot; the CRITICAL job loses and is told why")
    void exemptJobIsNeverDisplaced() {
        for (AllocationResult result : solveAll(
                List.of(exempt(9, JobPriority.LOW, 200.0), job(1, JobPriority.CRITICAL, 200.0, 0, 0, 1)), 10)) {
            JobOutcome pinned = outcome(result, 9);
            assertThat(pinned.status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
            assertThat(pinned.plan().allocations()).containsExactly(new SlotAllocation(at(0), 1.0));
            JobOutcome loser = outcome(result, 1);
            assertThat(loser.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
            assertThat(loser.reason()).contains("job id 9 (LOW)");
        }
    }

    @Test
    @DisplayName("Deadline: priority never pushes a job past its deadline, and does not block a lower job that can still fit")
    void priorityNeverOverridesDeadline() {
        // The exempt job fills slot 0. The HIGH job can only run in slot 0 (deadline 1h away), so it cannot be
        // placed; the LOW job may wait until slot 1 and is placed there.
        for (AllocationResult result : solveAll(List.of(
                exempt(9, JobPriority.NORMAL, 200.0),
                job(1, JobPriority.HIGH, 200.0, 0, 0, 1),
                job(2, JobPriority.LOW, 200.0, 0, 1, 2)), 10, 10)) {
            assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
            JobOutcome low = outcome(result, 2);
            assertThat(low.status()).isEqualTo(PerJobStatus.OPTIMISED);
            // It may use the 56 CPU-hours left in slot 0 and finish in slot 1, but never run past its deadline.
            assertThat(low.plan().allocations()).allMatch(a -> a.slotStart().isBefore(at(2)));
        }
    }
}
