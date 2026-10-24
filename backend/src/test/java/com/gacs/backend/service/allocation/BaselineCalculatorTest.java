package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The baseline: started at originalStartTime, alone on the cluster, as much per slot as the limit allows. */
class BaselineCalculatorTest {

    private static final CapacityLimit CLUSTER = new CapacityLimit(256.0, 64.0);

    @Test
    @DisplayName("A job that fits in one slot runs there in full")
    void smallJobUsesOneSlot() {
        Baseline baseline = new BaselineCalculator(cpuCapacity(256.0)).baseline(job(1, 200.0, 0.0, 0, 0, 5));

        assertThat(baseline.finishesByDeadline()).isTrue();
        assertThat(baseline.allocations()).containsExactly(new SlotAllocation(T0, 1.0));
        assertThat(baseline.plan().orElseThrow().end()).isEqualTo(at(1));
    }

    @Test
    @DisplayName("A smaller override in the first slot pushes the rest of the work into the next slot (decision 4a)")
    void overrideSpillsIntoNextSlot() {
        SlotCapacity capacity = new SlotCapacity(CLUSTER, Map.of(T0, new CapacityLimit(100.0, 64.0)));
        Baseline baseline = new BaselineCalculator(capacity).baseline(job(1, 200.0, 0.0, 0, 0, 5));

        assertThat(baseline.allocations())
                .containsExactly(new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.5));
        // 10 kWh: half at $20, half at $80
        assertThat(COSTS.costUsd(baseline.plan().orElseThrow(), prices(20.0, 80.0)))
                .isCloseTo(0.5 * 10 * 20 / 1000 + 0.5 * 10 * 80 / 1000, within(1e-12));
    }

    @Test
    @DisplayName("A job bigger than one slot runs over consecutive slots, and its shares add up to 1")
    void largeJobSpansSlots() {
        Baseline baseline = new BaselineCalculator(cpuCapacity(256.0)).baseline(job(1, 600.0, 0.0, 0, 0, 5));

        assertThat(baseline.allocations()).hasSize(3);
        assertThat(baseline.allocations().get(0).share()).isCloseTo(256.0 / 600, within(1e-12));
        assertThat(baseline.allocations().get(1).share()).isCloseTo(256.0 / 600, within(1e-12));
        assertThat(baseline.allocations().get(2).share()).isCloseTo(88.0 / 600, within(1e-12));
        assertThat(baseline.plan()).isPresent(); // JobPlan checks the shares add up to 1
    }

    @Test
    @DisplayName("GPU can be the limit: 128 GPU-hours with 64 per slot takes two slots")
    void gpuBoundJob() {
        Baseline baseline = new BaselineCalculator(SlotCapacity.uniform(CLUSTER)).baseline(job(1, 64.0, 128.0, 0, 0, 5));

        assertThat(baseline.allocations())
                .containsExactly(new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.5));
    }

    @Test
    @DisplayName("Work that cannot finish by the deadline, even alone, gives an unfinished baseline")
    void tooLittleTimeBeforeDeadline() {
        Baseline baseline = new BaselineCalculator(cpuCapacity(256.0)).baseline(job(1, 600.0, 0.0, 0, 0, 2));

        assertThat(baseline.finishesByDeadline()).isFalse();
        assertThat(baseline.allocations()).hasSize(2);
        assertThat(baseline.plan()).isEmpty();
    }

    @Test
    @DisplayName("A deadline inside a slot makes that slot unusable")
    void deadlineInsideASlot() {
        OptimizerJob job = new OptimizerJob(1L, "a", 200.0, 0.0, T0, T0.plusMinutes(30), 0, false);
        Baseline baseline = new BaselineCalculator(cpuCapacity(256.0)).baseline(job);

        assertThat(baseline.finishesByDeadline()).isFalse();
        assertThat(baseline.allocations()).isEmpty();
    }

    @Test
    @DisplayName("A slot with no capacity stops the baseline, since a run cannot pause")
    void zeroCapacitySlotStopsTheRun() {
        SlotCapacity capacity = new SlotCapacity(CLUSTER, Map.of(at(1), new CapacityLimit(0.0, 0.0)));
        Baseline baseline = new BaselineCalculator(capacity).baseline(job(1, 600.0, 0.0, 0, 0, 5));

        assertThat(baseline.finishesByDeadline()).isFalse();
        assertThat(baseline.allocations()).hasSize(1);
    }

    @Test
    @DisplayName("Flexibility and exemption do not change the baseline")
    void flexibilityDoesNotChangeBaseline() {
        BaselineCalculator calculator = new BaselineCalculator(cpuCapacity(256.0));
        OptimizerJob flexible = job(1, 200.0, 0.0, 0, 4, 5);
        OptimizerJob exempt = Fixtures.exempt(2, 200.0, 0.0, 0, 5);

        assertThat(calculator.baseline(flexible).allocations()).isEqualTo(calculator.baseline(exempt).allocations());
    }
}
