package com.gacs.backend.service;

import com.gacs.backend.service.ExactScheduleSolver.Outcome;
import com.gacs.backend.service.ScheduleSolver.ClusterCapacity;
import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.PerJobStatus;
import com.gacs.backend.service.ScheduleSolver.SolverAssignment;
import com.gacs.backend.service.ScheduleSolver.SolverInput;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness tests for {@link ExactScheduleSolver} (GRID-125 / GRID-129): small cases must
 * match {@link BruteForceScheduleOracle}'s true minimum exactly, deadlines and capacity are
 * hard constraints, and the solver is deterministic.
 */
class ExactScheduleSolverTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);
    // Generous relative to the tiny instances here, so CI noise never flips OPTIMAL -> FEASIBLE.
    private static final Duration TEST_BUDGET = Duration.ofSeconds(30);

    private final ExactScheduleSolver solver = new ExactScheduleSolver(TEST_BUDGET);

    @Test
    @DisplayName("matches the brute-force optimum over many small random instances")
    void matchesBruteForceOnRandomInstances() {
        Random rng = new Random(42);
        for (int trial = 0; trial < 200; trial++) {
            SolverInput input = randomInstance(rng);

            Outcome outcome = solver.solve(input);
            assertThat(outcome.certifiedOptimal()).as("trial %d should certify optimal on an instance this small", trial).isTrue();

            double exactCost = flexibleRawCost(outcome.result(), input);
            double bruteForceCost = BruteForceScheduleOracle.solve(input).totalRawCost();

            assertThat(exactCost)
                .as("trial %d: exact solver cost vs brute-force optimum\ninput=%s", trial, input)
                .isCloseTo(bruteForceCost, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Test
    @DisplayName("the true optimum can require moving a job above its own baseline price")
    void trueOptimumCanRaiseAnIndividualJobsCost() {
        // A and B both start at T0 (price 100). Capacity only admits one 10-cpu-hour job per
        // slot. The only other reachable slot, T1, is cheap (price 10) but also only fits
        // one job. If the solver enforced "never move into an hour dearer than my own
        // baseline" (the greedy stub's rule, not a real constraint) it could still reach the
        // same total here by symmetry - so this case alone wouldn't catch a reintroduced
        // ceiling. The random sweep above is what actually exercises that failure mode across
        // many asymmetric instances; this case just pins a readable, hand-checkable example of
        // the aggregate invariant holding when baseline itself is jointly infeasible for both.
        LocalDateTime t0 = HORIZON_START.plusHours(5);
        LocalDateTime t1 = t0.plusHours(1);
        JobSpec a = flexibleJob(1L, t0, t0.plusHours(5), 1, 10.0);
        JobSpec b = flexibleJob(2L, t0, t0.plusHours(5), 1, 10.0);

        ClusterCapacity capacity = new ClusterCapacity(10.0, 10.0);
        PriceSeries prices = priceSeries(Map.of(t0, 100.0, t1, 10.0));
        SolverInput input = input(List.of(a, b), prices, capacity);

        Outcome outcome = solver.solve(input);
        assertThat(outcome.certifiedOptimal()).isTrue();

        double exactCost = flexibleRawCost(outcome.result(), input);
        double bruteForceCost = BruteForceScheduleOracle.solve(input).totalRawCost();
        assertThat(exactCost).isCloseTo(bruteForceCost, org.assertj.core.data.Offset.offset(1e-6));
        // One job at 100, the other at 10 (10 cpu-hours = 0.5 kWh each) - strictly better
        // than both unplaced-at-baseline (2 * CostModel.cost(0.5, 100) = 0.1).
        double expected = CostModel.cost(0.5, 100.0) + CostModel.cost(0.5, 10.0);
        assertThat(exactCost).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("never places a job past its deadline")
    void respectsDeadline() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        LocalDateTime deadline = baseline.plusHours(1); // only baseline itself can finish in time
        JobSpec job = flexibleJob(1L, baseline, deadline, 4, 10.0);

        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0, baseline.plusHours(1), 5.0, baseline.plusHours(2), 5.0
        ));
        SolverResult result = solver.solve(input(List.of(job), prices, ClusterCapacity.defaults())).result();

        SolverAssignment assignment = result.assignments().get(0);
        assertThat(assignment.slotStart().plusHours(1)).isBeforeOrEqualTo(deadline);
        assertThat(assignment.slotStart()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("never exceeds per-slot capacity across the jobs it actually places")
    void respectsCapacity() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        LocalDateTime deadline = baseline.plusHours(1);
        // Three jobs, all pinned to the same single candidate slot by a tight deadline;
        // capacity only admits two of them.
        JobSpec a = flexibleJob(1L, baseline, deadline, 0, 10.0);
        JobSpec b = flexibleJob(2L, baseline, deadline, 0, 10.0);
        JobSpec c = flexibleJob(3L, baseline, deadline, 0, 10.0);

        ClusterCapacity capacity = new ClusterCapacity(20.0, 20.0);
        PriceSeries prices = priceSeries(Map.of(baseline, 10.0));
        SolverResult result = solver.solve(input(List.of(a, b, c), prices, capacity)).result();

        double placedCpu = result.assignments().stream()
            .filter(x -> x.status() != PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT)
            .filter(x -> x.slotStart().equals(baseline))
            .mapToDouble(x -> x.job().cpuHours())
            .sum();
        assertThat(placedCpu).isLessThanOrEqualTo(20.0);

        long unplaced = result.assignments().stream()
            .filter(x -> x.status() == PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT).count();
        assertThat(unplaced).isEqualTo(1);
    }

    @Test
    @DisplayName("exempt jobs stay at their original slot and reserve capacity first")
    void exemptJobsArePinned() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec exempt = new JobSpec(1L, "exempt", 10.0, 0.0, baseline, baseline.plusHours(5), 0, true);
        JobSpec flexible = flexibleJob(2L, baseline, baseline.plusHours(5), 2, 10.0);

        ClusterCapacity capacity = new ClusterCapacity(10.0, 10.0);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 10.0, baseline.plusHours(1), 10.0, baseline.plusHours(2), 10.0
        ));
        SolverResult result = solver.solve(input(List.of(exempt, flexible), prices, capacity)).result();

        assertThat(result.assignments().get(0).status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
        assertThat(result.assignments().get(0).slotStart()).isEqualTo(baseline);
        // Capacity at baseline is fully claimed by the exempt job, so the flexible job must move.
        assertThat(result.assignments().get(1).slotStart()).isNotEqualTo(baseline);
    }

    @Test
    @DisplayName("same input always yields the same result")
    void deterministic() {
        Random rng = new Random(7);
        SolverInput input = randomInstance(rng);

        SolverResult first = solver.solve(input).result();
        SolverResult second = solver.solve(input).result();

        assertThat(first).isEqualTo(second);
    }

    // ---------------------------------------------------------------- fixtures

    private static double flexibleRawCost(SolverResult result, SolverInput input) {
        double total = 0.0;
        for (SolverAssignment a : result.assignments()) {
            if (a.job().exempt()) {
                continue;
            }
            LocalDateTime priced = a.status() == PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT
                ? a.job().baselineSlot() : a.slotStart();
            double price = input.prices().priceAt(priced).orElseThrow();
            total += CostModel.cost(a.job().energyKwh(), price);
        }
        return total;
    }

    /** Small enough for brute force to be instant: <=4 jobs, <=6 priced hours, window <=3. */
    private static SolverInput randomInstance(Random rng) {
        int horizonHours = 6;
        int n = 2 + rng.nextInt(3); // 2-4 jobs

        Map<LocalDateTime, Double> prices = new LinkedHashMap<>();
        for (int h = 0; h < horizonHours; h++) {
            prices.put(HORIZON_START.plusHours(h), 5.0 + rng.nextInt(96));
        }

        List<JobSpec> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDateTime baseline = HORIZON_START.plusHours(rng.nextInt(3));
            int window = rng.nextInt(4); // 0-3
            LocalDateTime deadline = baseline.plusHours(1 + rng.nextInt(5));
            double cpu = rng.nextBoolean() ? 5.0 : 10.0;
            double gpu = rng.nextBoolean() ? 0.0 : 5.0;
            boolean exempt = rng.nextInt(5) == 0; // 20%
            jobs.add(new JobSpec((long) i, "job-" + i, cpu, gpu, baseline, deadline, window, exempt));
        }

        ClusterCapacity capacity = new ClusterCapacity(10.0 + rng.nextInt(11), 10.0 + rng.nextInt(11));
        return new SolverInput(jobs, new PriceSeries(prices, false), capacity, HORIZON_START, horizonHours);
    }

    private static JobSpec flexibleJob(long id, LocalDateTime start, LocalDateTime deadline, int windowHours, double cpuHours) {
        return new JobSpec(id, "job-" + id, cpuHours, 0.0, start, deadline, windowHours, false);
    }

    private static PriceSeries priceSeries(Map<LocalDateTime, Double> prices) {
        return new PriceSeries(new LinkedHashMap<>(prices), false);
    }

    private static SolverInput input(List<JobSpec> jobs, PriceSeries prices, ClusterCapacity capacity) {
        return new SolverInput(jobs, prices, capacity, HORIZON_START, 48);
    }
}
