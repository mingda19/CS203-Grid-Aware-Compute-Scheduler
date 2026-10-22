package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Hand-checked cases for the brute-force checker. Once the new solvers exist (Phase 3), they are
 * compared against it on these same cases.
 */
class BruteForceAllocatorTest {

    private static BruteForceAllocator.Result solve(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity) {
        BruteForceAllocator.Result result = BruteForceAllocator.solve(jobs, prices, capacity, COSTS, 4);
        assertThat(ContractRules.violations(jobs, ContractRules.plansOf(result), prices, capacity)).isEmpty();
        return result;
    }

    @Test
    @DisplayName("A job moves to the cheapest hour in its window")
    void movesToCheapestHour() {
        BruteForceAllocator.Result result = solve(List.of(job(1, 20.0, 0.0, 0, 3, 6)),
                prices(10, 5, 8, 2, 9, 7), cpuCapacity(256.0));

        assertThat(result.planFor(1).orElseThrow().allocations()).containsExactly(new SlotAllocation(at(3), 1.0));
        assertThat(result.costUsd()).isCloseTo(2.0 / 1000, within(1e-12));
    }

    @Test
    @DisplayName("Every job is placed even when that costs more than the baseline (negative savings)")
    void placingBeatsSaving() {
        // Each job alone at 10:00 ($20): baseline 0.2 + 0.2. Together they exceed 300 CPU-hours at 10:00,
        // so 100 CPU-hours of work moves to 11:00 ($50).
        List<OptimizerJob> jobs = List.of(job(1, 200.0, 0.0, 0, 1, 2), job(2, 200.0, 0.0, 0, 1, 2));
        PriceSeries prices = prices(20, 50);
        BruteForceAllocator.Result result = solve(jobs, prices, cpuCapacity(300.0));

        assertThat(result.placedCount()).isEqualTo(2);
        double expected = kwhOfCpu(300) * 20 / 1000 + kwhOfCpu(100) * 50 / 1000;
        assertThat(result.costUsd()).isCloseTo(expected, within(1e-12));

        BaselineCalculator baselines = new BaselineCalculator(cpuCapacity(300.0));
        double baseline = jobs.stream()
                .mapToDouble(job -> COSTS.costUsd(baselines.baseline(job).plan().orElseThrow(), prices))
                .sum();
        assertThat(baseline - result.costUsd()).isNegative();
    }

    @Test
    @DisplayName("When only one of two jobs fits, the lower id is placed if both start at the same time")
    void tieBreakByIdWhenStartsMatch() {
        List<OptimizerJob> jobs = List.of(job(2, 200.0, 0.0, 0, 0, 1), job(1, 200.0, 0.0, 0, 0, 1));
        BruteForceAllocator.Result result = solve(jobs, prices(10), cpuCapacity(256.0));

        assertThat(result.placedCount()).isEqualTo(1);
        assertThat(result.planFor(1)).isPresent();
        assertThat(result.planFor(2)).isEmpty();
    }

    @Test
    @DisplayName("When only one of two jobs fits, the earlier originalStartTime is placed, whatever the id")
    void tieBreakByOriginalStart() {
        // Job 2 starts at 10:00 but 10:00 has no price, so both jobs compete for 11:00.
        List<OptimizerJob> jobs = List.of(job(1, 200.0, 0.0, 1, 0, 2), job(2, 200.0, 0.0, 0, 1, 2));
        BruteForceAllocator.Result result = solve(jobs, prices(Double.NaN, 10), cpuCapacity(256.0));

        assertThat(result.planFor(2)).isPresent();
        assertThat(result.planFor(1)).isEmpty();
    }

    @Test
    @DisplayName("Exempt jobs over the limit still run, and that hour has no room for anyone else (decision 4b)")
    void exemptJobsMayExceedTheLimit() {
        List<OptimizerJob> jobs = List.of(
                exempt(1, 200.0, 0.0, 0, 1), exempt(2, 200.0, 0.0, 0, 1), job(3, 100.0, 0.0, 0, 1, 2));
        BruteForceAllocator.Result result = solve(jobs, prices(1, 50), cpuCapacity(256.0));

        assertThat(result.planFor(1).orElseThrow().allocations()).containsExactly(new SlotAllocation(T0, 1.0));
        assertThat(result.planFor(2).orElseThrow().allocations()).containsExactly(new SlotAllocation(T0, 1.0));
        assertThat(result.planFor(3).orElseThrow().allocations()).containsExactly(new SlotAllocation(at(1), 1.0));
    }

    @Test
    @DisplayName("A smaller override forces work into the next hour")
    void overrideForcesASecondHour() {
        SlotCapacity capacity = new SlotCapacity(new CapacityLimit(256.0, 64.0),
                Map.of(T0, new CapacityLimit(100.0, 64.0)));
        BruteForceAllocator.Result result = solve(List.of(job(1, 200.0, 0.0, 0, 0, 3)), prices(10, 30, 40), capacity);

        assertThat(result.planFor(1).orElseThrow().allocations())
                .containsExactly(new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.5));
    }

    @Test
    @DisplayName("A job with no room in any allowed hour is left out")
    void noRoomMeansNotPlaced() {
        List<OptimizerJob> jobs = List.of(exempt(1, 256.0, 0.0, 0, 1), job(2, 100.0, 0.0, 0, 0, 1));
        BruteForceAllocator.Result result = solve(jobs, prices(10), cpuCapacity(256.0));

        assertThat(result.planFor(2)).isEmpty();
        assertThat(result.placedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A run cannot skip an expensive hour in the middle")
    void runsHaveNoGaps() {
        // 400 CPU-hours, 200 per slot: it needs at least two slots. Skipping 11:00 ($100) is not allowed,
        // so the cheapest plan keeps the 11:00 share as small as the 1/4 grid allows, and fills 10:00 ($4) first.
        BruteForceAllocator.Result result = solve(List.of(job(1, 400.0, 0.0, 0, 2, 3)),
                prices(4, 100, 5), cpuCapacity(200.0));

        assertThat(result.planFor(1).orElseThrow().allocations()).containsExactly(
                new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.25), new SlotAllocation(at(2), 0.25));
        double kwh = kwhOfCpu(400);
        assertThat(result.costUsd())
                .isCloseTo((0.5 * kwh * 4 + 0.25 * kwh * 100 + 0.25 * kwh * 5) / 1000, within(1e-12));
    }

    @Test
    @DisplayName("Compositions list every way to split the work")
    void compositionsAreComplete() {
        assertThat(BruteForceAllocator.compositions(4, 1)).containsExactly(new int[] {4});
        assertThat(BruteForceAllocator.compositions(4, 2))
                .containsExactly(new int[] {1, 3}, new int[] {2, 2}, new int[] {3, 1});
        assertThat(BruteForceAllocator.compositions(4, 4)).containsExactly(new int[] {1, 1, 1, 1});
    }
}
