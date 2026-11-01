package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AbsoluteTimeWindowConstraint} end to end: {@link #at}'s T0 is 2026-10-20T10:00, so slot
 * {@code h} is at wall-clock hour {@code (10 + h) % 24}.
 */
class AbsoluteTimeWindowConstraintTest {

    private static final ExactAllocationSolver EXACT = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
    private static final AnnealingAllocationSolver HEURISTIC = new AnnealingAllocationSolver(COSTS, 200);

    @Test
    @DisplayName("Exact solver: a job is pushed out of its cheapest hour when that hour falls outside the window")
    void exactSolverPushesOutOfWindowHour() {
        // Cheapest unconstrained hour is slot 1 (11:00, price 1); window forbids 11:00-12:00, forcing slot 2 (12:00).
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(1, LocalTime.of(12, 0), LocalTime.of(18, 0));
        OptimizationProblem problem = problem(List.of(job(1, 20.0, 0.0, 0, 3, 4)), prices(10, 1, 2, 9),
                cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.provenOptimal()).isTrue();
        assertThat(outcome(result).plan().start()).isEqualTo(at(2));
    }

    @Test
    @DisplayName("Greedy/heuristic solver: the same window is respected")
    void heuristicSolverRespectsWindow() {
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(1, LocalTime.of(12, 0), LocalTime.of(18, 0));
        OptimizationProblem problem = problem(List.of(job(1, 20.0, 0.0, 0, 3, 4)), prices(10, 1, 2, 9),
                cpuCapacity(256.0), List.of(constraint));

        AllocationResult result = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(outcome(result).plan().start()).isEqualTo(at(2));
    }

    @Test
    @DisplayName("An overnight window (wraps past midnight) is supported")
    void overnightWindowWraps() {
        // T0 is 10:00. Window 22:00-06:00 means only hours >=22 or <6 are usable: slot 13 (23:00) qualifies,
        // slot 0 (10:00, cheapest) does not.
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(1, LocalTime.of(22, 0), LocalTime.of(6, 0));
        double[] perHour = new double[14];
        java.util.Arrays.fill(perHour, 10.0);
        perHour[0] = 1.0; // slot 0 (10:00) is cheapest but outside the overnight window
        perHour[13] = 2.0; // slot 13 (23:00) is inside it
        PriceSeries priceSeries = prices(perHour);
        OptimizationProblem problem = problem(List.of(job(1, 20.0, 0.0, 0, 13, 14)), priceSeries, cpuCapacity(256.0),
                List.of(constraint));

        AllocationResult result = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(outcome(result).plan().start()).isEqualTo(at(13));
    }

    @Test
    @DisplayName("A job with no in-window hour before its deadline is NO_FEASIBLE_SLOT, not silently violated")
    void infeasibleWhenNoHourFallsInWindow() {
        // Window is 00:00-01:00; T0 (10:00) through its only candidate hours never reach that window.
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(1, LocalTime.of(0, 0), LocalTime.of(1, 0));
        OptimizationProblem problem = problem(List.of(job(1, 20.0, 0.0, 0, 2, 3)), prices(10, 10, 10),
                cpuCapacity(256.0), List.of(constraint));

        AllocationResult exactResult = EXACT.solve(problem).orElseThrow();
        assertThat(ContractRules.violations(problem, exactResult)).isEmpty();
        assertThat(outcome(exactResult).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);

        AllocationResult heuristicResult = HEURISTIC.solve(problem);
        assertThat(ContractRules.violations(problem, heuristicResult)).isEmpty();
        assertThat(outcome(heuristicResult).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
    }

    @Test
    @DisplayName("Random small runs with an absolute window: exact >= brute-force-equivalent rules hold")
    void randomCasesObeyTheWindow() {
        Random random = new Random(20261029L);
        int checked = 0;
        while (checked < 40) {
            int hours = 6;
            double[] perHour = new double[hours];
            for (int h = 0; h < hours; h++) {
                perHour[h] = 1 + random.nextInt(60);
            }
            int startHour = random.nextInt(3);
            int deadlineHour = startHour + 2 + random.nextInt(hours - startHour - 1);
            int flex = random.nextInt(3);
            OptimizerJob theJob = job(1, 20.0 * (1 + random.nextInt(3)), 0.0, startHour, flex, deadlineHour);
            LocalTime dailyStart = LocalTime.of(random.nextInt(24), 0);
            LocalTime dailyEnd = LocalTime.of(random.nextInt(24), 0);
            if (dailyStart.equals(dailyEnd)) {
                continue;
            }
            AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(1, dailyStart, dailyEnd);
            OptimizationProblem randomProblem = problem(List.of(theJob), prices(perHour), cpuCapacity(256.0),
                    List.of(constraint));

            AllocationResult exactResult = EXACT.solve(randomProblem).orElseThrow();
            assertThat(ContractRules.violations(randomProblem, exactResult)).as("case %d", checked).isEmpty();
            AllocationResult heuristicResult = HEURISTIC.solve(randomProblem);
            assertThat(ContractRules.violations(randomProblem, heuristicResult)).as("case %d", checked).isEmpty();
            checked++;
        }
    }

    private static JobOutcome outcome(AllocationResult result) {
        return result.outcomes().getFirst();
    }
}
