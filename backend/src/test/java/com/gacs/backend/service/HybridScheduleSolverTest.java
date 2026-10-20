package com.gacs.backend.service;

import com.gacs.backend.service.ScheduleSolver.ClusterCapacity;
import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.SolverInput;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing tests for {@link HybridScheduleSolver}: it should certify an optimal result for a
 * small input via {@link ExactScheduleSolver}, and fall back to the simulated-annealing path
 * when a cheap upfront size estimate (or an unmet time budget) says the exact path isn't
 * worth attempting - without ever constructing a genuinely large input or waiting on a real
 * timeout to prove it.
 */
class HybridScheduleSolverTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Test
    @DisplayName("certifies optimal for a small input via the exact solver")
    void smallInputIsSolvedExactly() {
        HybridScheduleSolver hybrid = new HybridScheduleSolver(
            HybridScheduleSolver.DEFAULT_SIZE_THRESHOLD_PAIRS, HybridScheduleSolver.DEFAULT_TIME_BUDGET);

        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = new JobSpec(1L, "job-1", 10.0, 0.0, baseline, baseline.plusHours(5), 2, false);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0, baseline.plusHours(1), 10.0, baseline.plusHours(2), 50.0));
        SolverInput input = new SolverInput(List.of(job), prices, ClusterCapacity.defaults(), HORIZON_START, 48);

        SolverResult result = hybrid.solve(input);

        assertThat(result.certifiedOptimal()).isTrue();
        assertThat(result.assignments().get(0).slotStart()).isEqualTo(baseline.plusHours(1));
    }

    @Test
    @DisplayName("falls back to the heuristic path when the size threshold is set below the input")
    void oversizedInputSkipsStraightToHeuristic() {
        // Threshold 0 forces every input with any flexible candidate at all past the gate,
        // without needing to construct a genuinely large one.
        HybridScheduleSolver hybrid = new HybridScheduleSolver(0, HybridScheduleSolver.DEFAULT_TIME_BUDGET);

        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = new JobSpec(1L, "job-1", 10.0, 0.0, baseline, baseline.plusHours(5), 2, false);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0, baseline.plusHours(1), 10.0, baseline.plusHours(2), 50.0));
        SolverInput input = new SolverInput(List.of(job), prices, ClusterCapacity.defaults(), HORIZON_START, 48);

        SolverResult result = hybrid.solve(input);

        assertThat(result.certifiedOptimal()).isFalse();
        // The heuristic path still finds the same answer on an input this trivially small.
        assertThat(result.assignments().get(0).slotStart()).isEqualTo(baseline.plusHours(1));
    }

    @Test
    @DisplayName("falls back to the heuristic path when the exact solver can't certify optimal in time")
    void unmetTimeBudgetFallsBackToHeuristic() {
        // A zero-length budget: the exact solver is attempted (the size gate passes) but
        // aborts immediately without proving optimality, forcing the fallback deterministically
        // and fast rather than constructing an input large enough to time out for real.
        HybridScheduleSolver hybrid = new HybridScheduleSolver(
            HybridScheduleSolver.DEFAULT_SIZE_THRESHOLD_PAIRS, Duration.ZERO);

        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = new JobSpec(1L, "job-1", 10.0, 0.0, baseline, baseline.plusHours(5), 2, false);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0, baseline.plusHours(1), 10.0, baseline.plusHours(2), 50.0));
        SolverInput input = new SolverInput(List.of(job), prices, ClusterCapacity.defaults(), HORIZON_START, 48);

        SolverResult result = hybrid.solve(input);

        assertThat(result.certifiedOptimal()).isFalse();
    }

    private static PriceSeries priceSeries(Map<LocalDateTime, Double> prices) {
        return new PriceSeries(new LinkedHashMap<>(prices), false);
    }
}
