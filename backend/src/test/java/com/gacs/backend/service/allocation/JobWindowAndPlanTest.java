package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.exempt;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where a job may run (window, deadline, priced slots) and the shape every plan must have. */
class JobWindowAndPlanTest {

    private static final OptimizerJob ANY = job(1, 20.0, 0.0, 0, 0, 5);

    @Test
    @DisplayName("Starts are limited to the flexibility window")
    void startsWithinFlexibilityWindow() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 2, 6), prices(1, 1, 1, 1, 1, 1));
        assertThat(window.startSlots()).containsExactly(T0, at(1), at(2));
    }

    @Test
    @DisplayName("The deadline cuts both the starts and the run")
    void deadlineCutsWindow() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 5, 3), prices(1, 1, 1, 1, 1, 1));
        assertThat(window.lastSlot()).isEqualTo(at(2));
        assertThat(window.startSlots()).containsExactly(T0, at(1), at(2));
        assertThat(window.runFrom(at(1))).containsExactly(at(1), at(2));
    }

    @Test
    @DisplayName("A deadline inside a slot rounds down to the last whole slot")
    void deadlineInsideASlot() {
        OptimizerJob job = new OptimizerJob(1L, "a", 20.0, 0.0, T0, at(1).plusMinutes(59), 3, false);
        assertThat(new JobWindow(job, prices(1, 1, 1)).startSlots()).containsExactly(T0);
    }

    @Test
    @DisplayName("Unpriced hours cannot be used, and a run cannot cross one")
    void unpricedSlotsBlockRuns() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 3, 4), prices(10, Double.NaN, 10, 10));
        assertThat(window.startSlots()).containsExactly(T0, at(2), at(3));
        assertThat(window.runFrom(T0)).containsExactly(T0);
        assertThat(window.runFrom(at(2))).containsExactly(at(2), at(3));
    }

    @Test
    @DisplayName("An exempt job may only start at its original time")
    void exemptJobHasOneStart() {
        assertThat(new JobWindow(exempt(1, 20.0, 0.0, 1, 5), prices(1, 1, 1, 1, 1)).startSlots())
                .containsExactly(at(1));
    }

    @Test
    @DisplayName("A plan reports its start, end and length")
    void planStartAndEnd() {
        JobPlan plan = new JobPlan(ANY, List.of(new SlotAllocation(at(1), 0.25), new SlotAllocation(at(2), 0.75)));
        assertThat(plan.start()).isEqualTo(at(1));
        assertThat(plan.end()).isEqualTo(at(3));
        assertThat(plan.slotCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A plan with a gap, shares not adding to 1, or no slots is rejected")
    void invalidPlansAreRejected() {
        assertThatThrownBy(() -> new JobPlan(ANY, List.of(new SlotAllocation(T0, 0.5), new SlotAllocation(at(2), 0.5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consecutive");
        assertThatThrownBy(() -> new JobPlan(ANY, List.of(new SlotAllocation(T0, 0.5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("add up");
        assertThatThrownBy(() -> new JobPlan(ANY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotAllocation(T0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The window accepts a plan inside it and rejects one past the deadline")
    void windowChecksPlans() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 1, 2), prices(1, 1, 1));
        assertThat(window.allows(new JobPlan(window.job(), List.of(new SlotAllocation(at(1), 1.0))))).isTrue();
        assertThat(window.allows(new JobPlan(window.job(),
                List.of(new SlotAllocation(at(1), 0.5), new SlotAllocation(at(2), 0.5))))).isFalse();
        assertThat(window.allows(new JobPlan(window.job(), List.of(new SlotAllocation(at(2), 1.0))))).isFalse();
    }
}
