package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.CapacityOverrideInput;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.service.PriceSeries;
import com.gacs.backend.service.PriceSeriesProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.exempt;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.kwhOfCpu;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/** The exact solver on the hand-checked cases from {@link BruteForceAllocatorTest}. */
class ExactAllocationSolverTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final ExactAllocationSolver SOLVER = new ExactAllocationSolver(COSTS);

    private static AllocationResult solve(OptimizationProblem problem) {
        AllocationResult result = SOLVER.solve(problem, BUDGET).orElseThrow();
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.provenOptimal()).isTrue();
        return result;
    }

    private static JobOutcome outcome(AllocationResult result, long id) {
        return result.outcomes().stream().filter(o -> o.job().id() == id).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("A job moves to the cheapest hour in its window")
    void movesToCheapestHour() {
        AllocationResult result = solve(problem(List.of(job(1, 20.0, 0.0, 0, 3, 6)),
                prices(10, 5, 8, 2, 9, 7), cpuCapacity(256.0)));

        JobOutcome outcome = outcome(result, 1);
        assertThat(outcome.status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcome.plan().allocations()).containsExactly(new SlotAllocation(at(3), 1.0));
        assertThat(outcome.reason()).isNull();
    }

    @Test
    @DisplayName("Every job is placed even when that costs more than the baseline")
    void placingBeatsSaving() {
        OptimizationProblem problem = problem(List.of(job(1, 200.0, 0.0, 0, 1, 2), job(2, 200.0, 0.0, 0, 1, 2)),
                prices(20, 50), cpuCapacity(300.0));
        AllocationResult result = solve(problem);

        assertThat(result.scheduledCount()).isEqualTo(2);
        assertThat(result.costUsd(COSTS, problem.prices()))
                .isCloseTo(kwhOfCpu(300) * 20 / 1000 + kwhOfCpu(100) * 50 / 1000, within(1e-6));
    }

    @Test
    @DisplayName("When only one of two jobs fits, the lower id is placed if both start at the same time")
    void tieBreakById() {
        AllocationResult result = solve(problem(
                List.of(job(2, 200.0, 0.0, 0, 0, 1), job(1, 200.0, 0.0, 0, 0, 1)), prices(10), cpuCapacity(256.0)));

        assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
    }

    @Test
    @DisplayName("When only one of two jobs fits, the earlier originalStartTime is placed, whatever the id")
    void tieBreakByOriginalStart() {
        // An exempt job fills 10:00, so job 2 (starts 10:00, may wait an hour) and job 1 (starts 11:00)
        // both need 11:00, which fits only one of them.
        AllocationResult result = solve(problem(
                List.of(exempt(9, 256.0, 0.0, 0, 1), job(1, 200.0, 0.0, 1, 0, 2), job(2, 200.0, 0.0, 0, 1, 2)),
                prices(10, 10), cpuCapacity(256.0)));

        assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
    }

    @Test
    @DisplayName("Exempt jobs over the limit still run, and that hour has no room for anyone else")
    void exemptJobsMayExceedTheLimit() {
        AllocationResult result = solve(problem(List.of(
                exempt(1, 200.0, 0.0, 0, 1), exempt(2, 200.0, 0.0, 0, 1), job(3, 100.0, 0.0, 0, 1, 2)),
                prices(1, 50), cpuCapacity(256.0)));

        assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
        assertThat(outcome(result, 1).plan().allocations()).containsExactly(new SlotAllocation(T0, 1.0));
        assertThat(outcome(result, 3).plan().allocations()).containsExactly(new SlotAllocation(at(1), 1.0));
    }

    @Test
    @DisplayName("A smaller override forces work into the next hour")
    void overrideForcesASecondHour() {
        SlotCapacity capacity = new SlotCapacity(new CapacityLimit(256.0, 64.0),
                Map.of(T0, new CapacityLimit(100.0, 64.0)));
        AllocationResult result = solve(problem(List.of(job(1, 200.0, 0.0, 0, 0, 3)), prices(10, 30, 40), capacity));

        List<SlotAllocation> allocations = outcome(result, 1).plan().allocations();
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).share()).isCloseTo(0.5, within(1e-6));
        assertThat(allocations.get(1).slotStart()).isEqualTo(at(1));
    }

    @Test
    @DisplayName("A job with no room is NO_FEASIBLE_SLOT, with a reason and no plan")
    void noRoomMeansNotPlaced() {
        AllocationResult result = solve(problem(List.of(exempt(1, 256.0, 0.0, 0, 1), job(2, 100.0, 0.0, 0, 0, 1)),
                prices(10), cpuCapacity(256.0)));

        JobOutcome outcome = outcome(result, 2);
        assertThat(outcome.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(outcome.plan()).isNull();
        assertThat(outcome.reason()).startsWith("Cluster full between 2026-10-20 10:00 and 2026-10-20 11:00 UTC");
    }

    /** The API path: the problem comes from the factory, which requires every baseline hour to have a price. */
    private static AllocationResult solveRequest(OptimizeRequestDto request) {
        OptimizationProblem problem = new OptimizationProblemFactory(mock(PriceSeriesProvider.class),
                OptimizerProperties.DEFAULTS, Fixtures.CLOCK_BEFORE_T0).build(request);
        return solve(problem);
    }

    /** Only hour 0 has a price. An exempt job fills it; the flexible job could only use hours 1-2, which have none. */
    private static OptimizeRequestDto blockedBaselineRequest(List<CapacityOverrideInput> overrides) {
        return new OptimizeRequestDto(
                List.of(new JobInput(1L, "exempt", 256.0, 0.0, T0, at(1), 0, true),
                        new JobInput(2L, "squeezed", 100.0, 0.0, T0, at(3), 2, false)),
                null, null, null, List.of(new ForecastPointInput(T0, 10.0)), overrides);
    }

    @Test
    @DisplayName("Free capacity only in unpriced hours says there is no forecast price, not that the cluster is full")
    void unpricedFreeHoursAreNamedInReason() {
        JobOutcome outcome = outcome(solveRequest(blockedBaselineRequest(null)), 2);

        assertThat(outcome.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(outcome.reason()).startsWith("No forecast price between 2026-10-20 11:00 and 2026-10-20 12:00 UTC");
    }

    @Test
    @DisplayName("Unpriced hours that are also full still say the cluster is full")
    void fullUnpricedHoursStayClusterFull() {
        List<CapacityOverrideInput> closed = List.of(new CapacityOverrideInput(at(1), 0.0, 0.0),
                new CapacityOverrideInput(at(2), 0.0, 0.0));

        JobOutcome outcome = outcome(solveRequest(blockedBaselineRequest(closed)), 2);

        assertThat(outcome.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(outcome.reason()).startsWith("Cluster full between 2026-10-20 10:00 and 2026-10-20 13:00 UTC");
    }

    @Test
    @DisplayName("A window where every hour has a price and other jobs fill it says the cluster is full")
    void fullyPricedWindowIsClusterFull() {
        OptimizeRequestDto request = new OptimizeRequestDto(
                List.of(new JobInput(1L, "exempt", 256.0, 0.0, T0, at(1), 0, true),
                        new JobInput(2L, "squeezed", 100.0, 0.0, T0, at(1), 0, false)),
                null, null, null, List.of(new ForecastPointInput(T0, 10.0)), null);

        JobOutcome outcome = outcome(solveRequest(request), 2);

        assertThat(outcome.reason()).startsWith("Cluster full between 2026-10-20 10:00 and 2026-10-20 11:00 UTC");
    }

    @Test
    @DisplayName("A run cannot skip an expensive hour in the middle; it keeps that hour's share tiny")
    void runsHaveNoGaps() {
        AllocationResult result = solve(problem(List.of(job(1, 400.0, 0.0, 0, 2, 3)),
                prices(4, 100, 5), cpuCapacity(200.0)));

        List<SlotAllocation> allocations = outcome(result, 1).plan().allocations();
        assertThat(allocations).extracting(SlotAllocation::slotStart).containsExactly(T0, at(1), at(2));
        assertThat(allocations.get(1).share()).isLessThan(0.001);
    }

    @Test
    @DisplayName("An iteration limit stops the search short of a proof, whatever the time budget")
    void iterationLimitStopsBeforeAProof() {
        OptimizationProblem contested = problem(List.of(job(1, 200.0, 0.0, 0, 3, 6), job(2, 200.0, 0.0, 0, 3, 6),
                job(3, 200.0, 0.0, 1, 3, 6), job(4, 200.0, 0.0, 1, 3, 6)), prices(30, 20, 50, 10, 40, 25), cpuCapacity(300.0));

        assertThat(SOLVER.solve(contested, BUDGET).orElseThrow().provenOptimal()).isTrue();
        assertThat(SOLVER.solve(contested, BUDGET, 1).filter(AllocationResult::provenOptimal)).isEmpty();
    }

    @Test
    @DisplayName("A run with only exempt jobs is trivially optimal")
    void onlyExemptJobs() {
        AllocationResult result = solve(problem(List.of(exempt(1, 20.0, 0.0, 0, 1)), prices(10), cpuCapacity(256.0)));
        assertThat(outcome(result, 1).status()).isEqualTo(PerJobStatus.UNCHANGED_EXEMPT);
    }

    @Test
    @DisplayName("Outcomes are listed in request order")
    void outcomesKeepRequestOrder() {
        PriceSeries prices = prices(10, 10, 10);
        AllocationResult result = solve(problem(
                List.of(job(3, 20.0, 0.0, 1, 0, 3), job(1, 20.0, 0.0, 0, 0, 3), exempt(2, 20.0, 0.0, 2, 3)),
                prices, cpuCapacity(256.0)));
        assertThat(result.outcomes()).extracting(o -> o.job().id()).containsExactly(3L, 1L, 2L);
    }
}
