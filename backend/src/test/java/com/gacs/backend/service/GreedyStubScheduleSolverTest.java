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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the greedy stub's current, documented behaviour before any solver swap, so a
 * regression in the refactor onto {@link CandidateSlots} (or later changes) is caught
 * immediately rather than silently inherited by {@link HybridScheduleSolver}'s fallback path.
 */
class GreedyStubScheduleSolverTest {

    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);

    private final GreedyStubScheduleSolver solver = new GreedyStubScheduleSolver();

    @Test
    @DisplayName("moves a flexible job to the cheapest priced slot in its window")
    void movesToCheapestSlot() {
        LocalDateTime baseline = HORIZON_START.plusHours(10);
        JobSpec job = flexibleJob(1L, baseline, baseline.plusHours(6), 4);

        PriceSeries prices = priceSeries(Map.of(
            baseline, 80.0,
            baseline.plusHours(1), 20.0,
            baseline.plusHours(2), 50.0
        ));
        SolverResult result = solver.solve(input(List.of(job), prices, ClusterCapacity.defaults()));

        SolverAssignment a = result.assignments().get(0);
        assertThat(a.slotStart()).isEqualTo(baseline.plusHours(1));
        assertThat(a.status()).isEqualTo(PerJobStatus.RESCHEDULED);
    }

    @Test
    @DisplayName("reports UNCHANGED_ALREADY_OPTIMAL when the baseline slot is already cheapest")
    void alreadyOptimal() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = flexibleJob(1L, baseline, baseline.plusHours(10), 3);

        PriceSeries prices = priceSeries(Map.of(
            baseline, 10.0,
            baseline.plusHours(1), 50.0,
            baseline.plusHours(2), 50.0
        ));
        SolverResult result = solver.solve(input(List.of(job), prices, ClusterCapacity.defaults()));

        assertThat(result.assignments().get(0).status()).isEqualTo(PerJobStatus.UNCHANGED_ALREADY_OPTIMAL);
        assertThat(result.assignments().get(0).slotStart()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("exempt jobs stay at their original slot and reserve its capacity")
    void exemptJobPinned() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec exempt = exemptJob(1L, baseline, baseline.plusHours(10));
        JobSpec flexible = flexibleJob(2L, baseline, baseline.plusHours(10), 2);

        // Each job needs 10 cpu-hours; a 15-cpu-hour ceiling leaves room for only one of
        // them per slot, so whichever claims `baseline` first forces the other to move.
        ClusterCapacity capacity = new ClusterCapacity(15.0, 15.0);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 10.0,
            baseline.plusHours(1), 10.0,
            baseline.plusHours(2), 10.0
        ));
        SolverResult result = solver.solve(input(List.of(exempt, flexible), prices, capacity));

        SolverAssignment exemptAssignment = result.assignments().get(0);
        assertThat(exemptAssignment.status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
        assertThat(exemptAssignment.slotStart()).isEqualTo(baseline);

        // The flexible job cannot also land on `baseline` (capacity already claimed), so it
        // must move even though every candidate slot is priced identically.
        SolverAssignment flexibleAssignment = result.assignments().get(1);
        assertThat(flexibleAssignment.slotStart()).isNotEqualTo(baseline);
        assertThat(flexibleAssignment.status()).isEqualTo(PerJobStatus.RESCHEDULED);
    }

    @Test
    @DisplayName("falls back to UNCHANGED_NO_FEASIBLE_SLOT when every alternative is dearer than baseline")
    void noFeasibleSlotWhenAllAlternativesAreDearer() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        LocalDateTime deadline = baseline.plusHours(2);
        // Blocker has no flexibility and fully saturates baseline's capacity on its own
        // (each job needs 10 cpu-hours, the ceiling is exactly 10). The second job shares
        // that same baseline, so it has no room there; the only other slot in its window
        // is dearer than its own baseline, so the stub's never-move-to-a-dearer-hour rule
        // excludes that too, leaving no feasible slot at all.
        JobSpec blocker = flexibleJob(1L, baseline, deadline, 0);
        JobSpec job = flexibleJob(2L, baseline, deadline, 1);

        ClusterCapacity capacity = new ClusterCapacity(10.0, 10.0);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 10.0,
            baseline.plusHours(1), 50.0
        ));
        SolverResult result = solver.solve(input(List.of(blocker, job), prices, capacity));

        SolverAssignment jobAssignment = result.assignments().get(1);
        assertThat(jobAssignment.status()).isEqualTo(PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT);
        assertThat(jobAssignment.slotStart()).isEqualTo(baseline);
    }

    @Test
    @DisplayName("ties break to the earliest slot")
    void tiesBreakEarliest() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = flexibleJob(1L, baseline, baseline.plusHours(3), 2);

        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0,
            baseline.plusHours(1), 10.0,
            baseline.plusHours(2), 10.0
        ));
        SolverResult result = solver.solve(input(List.of(job), prices, ClusterCapacity.defaults()));

        assertThat(result.assignments().get(0).slotStart()).isEqualTo(baseline.plusHours(1));
    }

    @Test
    @DisplayName("same input always yields the same result")
    void deterministic() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        JobSpec job = flexibleJob(1L, baseline, baseline.plusHours(5), 2);
        PriceSeries prices = priceSeries(Map.of(
            baseline, 50.0, baseline.plusHours(1), 30.0, baseline.plusHours(2), 10.0
        ));
        SolverInput solverInput = input(List.of(job), prices, ClusterCapacity.defaults());

        SolverResult first = solver.solve(solverInput);
        SolverResult second = solver.solve(solverInput);

        assertThat(first).isEqualTo(second);
    }

    // ---------------------------------------------------------------- fixtures

    private static JobSpec flexibleJob(long id, LocalDateTime start, LocalDateTime deadline, int windowHours) {
        return new JobSpec(id, "job-" + id, 10.0, 0.0, start, deadline, windowHours, false);
    }

    private static JobSpec exemptJob(long id, LocalDateTime start, LocalDateTime deadline) {
        return new JobSpec(id, "job-" + id, 10.0, 0.0, start, deadline, 0, true);
    }

    private static PriceSeries priceSeries(Map<LocalDateTime, Double> prices) {
        return new PriceSeries(new LinkedHashMap<>(prices), false);
    }

    private static SolverInput input(List<JobSpec> jobs, PriceSeries prices, ClusterCapacity capacity) {
        return new SolverInput(jobs, prices, capacity, HORIZON_START, 48);
    }
}
