package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link OptimizationProblem#TIE_BREAK_ORDER}: priority first, then resource footprint, then the
 *  original originalStartTime/id fallback (GRID-155 / GRID-124). */
class OptimizationProblemTest {

    private static OptimizerJob job(long id, JobPriority priority, double cpuHours) {
        return new OptimizerJob(id, "job-" + id, cpuHours, 0.0, at(0), at(5), 2, false, priority);
    }

    @Test
    @DisplayName("A higher-priority job is placed before a lower-priority one, regardless of size")
    void higherPriorityWinsRegardlessOfSize() {
        OptimizerJob critical = job(1, JobPriority.CRITICAL, 500.0);
        OptimizerJob low = job(2, JobPriority.LOW, 1.0);

        assertThat(List.of(low, critical).stream().sorted(OptimizationProblem.TIE_BREAK_ORDER).toList())
                .containsExactly(critical, low);
    }

    @Test
    @DisplayName("Equal priority: the job asking for fewer total resource-hours is placed first")
    void equalPrioritySmallerResourceFootprintWinsTies() {
        OptimizerJob small = job(1, JobPriority.HIGH, 10.0);
        OptimizerJob large = job(2, JobPriority.HIGH, 100.0);

        assertThat(List.of(large, small).stream().sorted(OptimizationProblem.TIE_BREAK_ORDER).toList())
                .containsExactly(small, large);
    }

    @Test
    @DisplayName("All four priority levels sort CRITICAL, HIGH, NORMAL, LOW")
    void allPriorityLevelsSortInDeclaredOrder() {
        List<OptimizerJob> jobs = List.of(
                job(1, JobPriority.LOW, 1.0),
                job(2, JobPriority.NORMAL, 1.0),
                job(3, JobPriority.CRITICAL, 1.0),
                job(4, JobPriority.HIGH, 1.0));

        assertThat(jobs.stream().sorted(OptimizationProblem.TIE_BREAK_ORDER).map(OptimizerJob::id).toList())
                .containsExactly(3L, 4L, 2L, 1L);
    }

    @Test
    @DisplayName("Equal priority and resource footprint: falls back to earlier originalStartTime, then lower id")
    void fallsBackToOriginalStartTimeThenId() {
        OptimizerJob earlier = new OptimizerJob(2L, "earlier", 10.0, 0.0, at(0), at(5), 2, false, JobPriority.NORMAL);
        OptimizerJob later = new OptimizerJob(1L, "later", 10.0, 0.0, at(1), at(5), 2, false, JobPriority.NORMAL);

        assertThat(List.of(later, earlier).stream().sorted(OptimizationProblem.TIE_BREAK_ORDER).toList())
                .containsExactly(earlier, later);
    }
}
