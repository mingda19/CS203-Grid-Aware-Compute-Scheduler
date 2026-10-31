package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link JobWindow#restrictedTo} and {@link Constraint}'s no-op defaults. */
class ConstraintTest {

    private static final class NoOpConstraint extends Constraint {
        @Override
        public ConstraintType type() {
            return ConstraintType.ABSOLUTE_TIME_WINDOW;
        }

        @Override
        public List<Long> jobIds() {
            return List.of(1L);
        }
    }

    @Test
    @DisplayName("restrictedTo narrows starts and runs to hours matching the predicate")
    void restrictedToNarrowsWindow() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 5, 6), prices(1, 1, 1, 1, 1, 1));
        JobWindow restricted = window.restrictedTo(hour -> hour.equals(at(2)) || hour.equals(at(3)));

        assertThat(restricted.startSlots()).containsExactly(at(2), at(3));
        assertThat(restricted.runFrom(at(2))).containsExactly(at(2), at(3));
    }

    @Test
    @DisplayName("restrictedTo never widens what the window already allows")
    void restrictedToNeverWidens() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 1, 6), prices(1, 1, 1, 1, 1, 1));
        JobWindow restricted = window.restrictedTo(hour -> true); // an always-true predicate still can't widen
        assertThat(restricted.startSlots()).isEqualTo(window.startSlots());
    }

    @Test
    @DisplayName("Repeated restrictedTo calls compose as an AND, only narrowing further")
    void restrictedToComposes() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 5, 6), prices(1, 1, 1, 1, 1, 1));
        JobWindow restricted = window.restrictedTo(hour -> !hour.equals(at(1)))
                .restrictedTo(hour -> !hour.equals(at(3)));

        assertThat(restricted.startSlots()).containsExactly(at(0), at(2), at(4), at(5));
    }

    @Test
    @DisplayName("A bare Constraint subclass changes nothing: every hook keeps its default")
    void defaultsAreNoOps() {
        Constraint constraint = new NoOpConstraint();
        OptimizerJob theJob = job(1, 20.0, 0.0, 0, 0, 1);
        JobWindow window = new JobWindow(theJob, prices(1));
        JobPlan plan = new JobPlan(theJob, List.of(new SlotAllocation(at(0), 1.0)));

        assertThat(constraint.restrict(theJob, window, Map.of())).isEqualTo(window);
        assertThat(constraint.isSatisfiedBy(theJob, plan, Map.of())).isTrue();
    }
}
