package com.gacs.backend.service;

import com.gacs.backend.service.ScheduleSolver.ClusterCapacity;
import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.PerJobStatus;
import com.gacs.backend.service.ScheduleSolver.SolverAssignment;
import com.gacs.backend.service.ScheduleSolver.SolverInput;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness tests for the simulated-annealing fallback (GRID-125): it must always be
 * feasible (capacity/deadline), deterministic, and never worse than its own starting point
 * (the greedy stub's result) on the same input.
 */
class SimulatedAnnealingScheduleSolverTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);

    private final SimulatedAnnealingScheduleSolver solver = new SimulatedAnnealingScheduleSolver();

    @Test
    @DisplayName("every returned schedule respects deadlines, and flexible jobs never push a slot over capacity")
    void alwaysFeasible() {
        // Per the contract (point 11) exempt jobs are pinned regardless of capacity and can
        // jointly oversubscribe a slot on their own - that is a documented exception, not a
        // bug, and GreedyStubScheduleSolver (this solver's own seed) exhibits it too. The real
        // invariant to check is that FLEXIBLE jobs never make that worse: their own demand at
        // a slot must fit within whatever capacity remains after exempt reservations there.
        Random rng = new Random(123);
        for (int trial = 0; trial < 30; trial++) {
            SolverInput input = randomInstance(rng, 8, 24);
            SolverResult result = solver.solve(input);

            for (SolverAssignment a : result.assignments()) {
                if (a.status() != PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT) {
                    assertThat(a.slotEnd()).isBeforeOrEqualTo(a.job().deadline());
                }
            }

            Map<LocalDateTime, double[]> exemptUsage = new java.util.TreeMap<>();
            Map<LocalDateTime, double[]> flexibleUsage = new java.util.TreeMap<>();
            for (SolverAssignment a : result.assignments()) {
                if (a.status() == PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT) {
                    continue;
                }
                Map<LocalDateTime, double[]> bucket = a.job().exempt() ? exemptUsage : flexibleUsage;
                double[] u = bucket.computeIfAbsent(a.slotStart(), k -> new double[] {0.0, 0.0});
                u[0] += a.job().cpuHours();
                u[1] += a.job().gpuHours();
            }
            for (var entry : flexibleUsage.entrySet()) {
                double[] exempt = exemptUsage.getOrDefault(entry.getKey(), new double[] {0.0, 0.0});
                double cpuCeiling = Math.max(0.0, input.capacity().maxConcurrentCpuHours() - exempt[0]);
                double gpuCeiling = Math.max(0.0, input.capacity().maxConcurrentGpuHours() - exempt[1]);
                assertThat(entry.getValue()[0]).as("trial %d cpu capacity at %s", trial, entry.getKey())
                    .isLessThanOrEqualTo(cpuCeiling + 1e-9);
                assertThat(entry.getValue()[1]).as("trial %d gpu capacity at %s", trial, entry.getKey())
                    .isLessThanOrEqualTo(gpuCeiling + 1e-9);
            }
        }
    }

    @Test
    @DisplayName("same input always yields the same result")
    void deterministic() {
        SolverInput input = randomInstance(new Random(99), 10, 30);

        SolverResult first = solver.solve(input);
        SolverResult second = solver.solve(input);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("never costs more than its own starting point (the greedy stub)")
    void noWorseThanGreedyStub() {
        Random rng = new Random(55);
        GreedyStubScheduleSolver stub = new GreedyStubScheduleSolver();
        for (int trial = 0; trial < 20; trial++) {
            SolverInput input = randomInstance(rng, 12, 36);

            double stubCost = flexibleRawCost(stub.solve(input), input);
            double saCost = flexibleRawCost(solver.solve(input), input);

            assertThat(saCost).as("trial %d", trial).isLessThanOrEqualTo(stubCost + 1e-9);
        }
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

    private static SolverInput randomInstance(Random rng, int jobCount, int horizonHours) {
        Map<LocalDateTime, Double> prices = new LinkedHashMap<>();
        for (int h = 0; h < horizonHours; h++) {
            prices.put(HORIZON_START.plusHours(h), 5.0 + rng.nextInt(96));
        }

        List<JobSpec> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            LocalDateTime baseline = HORIZON_START.plusHours(rng.nextInt(horizonHours - 6));
            int window = rng.nextInt(6);
            LocalDateTime deadline = baseline.plusHours(1 + rng.nextInt(8));
            double cpu = rng.nextBoolean() ? 5.0 : 10.0;
            double gpu = rng.nextBoolean() ? 0.0 : 5.0;
            boolean exempt = rng.nextInt(6) == 0;
            jobs.add(new JobSpec((long) i, "job-" + i, cpu, gpu, baseline, deadline, window, exempt));
        }

        ClusterCapacity capacity = new ClusterCapacity(10.0 + rng.nextInt(16), 10.0 + rng.nextInt(16));
        return new SolverInput(jobs, new PriceSeries(prices, false), capacity, HORIZON_START, horizonHours);
    }
}
