package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link OptimizationProblem#TIE_BREAK_ORDER}: priority, then earlier deadline, then fewer resource-hours, then
 *  the originalStartTime/id fallback (GRID-155 AC3 / GRID-124). */
class OptimizationProblemTest {

    private static OptimizerJob job(long id, JobPriority priority, int startHour, int deadlineHour) {
        return new OptimizerJob(id, "job-" + id, 10.0, 0.0, at(startHour), at(deadlineHour), 2, false, priority);
    }

    private static OptimizerJob job(long id, JobPriority priority, int startHour, int deadlineHour, double cpuHours) {
        return new OptimizerJob(id, "job-" + id, cpuHours, 0.0, at(startHour), at(deadlineHour), 2, false, priority);
    }

    private static List<Long> order(OptimizerJob... jobs) {
        return List.of(jobs).stream().sorted(OptimizationProblem.TIE_BREAK_ORDER).map(OptimizerJob::id).toList();
    }

    @Test
    @DisplayName("A higher-priority job is placed before a lower-priority one, whatever its deadline")
    void higherPriorityWins() {
        assertThat(order(job(2, JobPriority.LOW, 0, 3), job(1, JobPriority.CRITICAL, 0, 9))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("All four priority levels sort CRITICAL, HIGH, NORMAL, LOW")
    void allPriorityLevelsSortInDeclaredOrder() {
        assertThat(order(job(1, JobPriority.LOW, 0, 5), job(2, JobPriority.NORMAL, 0, 5),
                job(3, JobPriority.CRITICAL, 0, 5), job(4, JobPriority.HIGH, 0, 5)))
                .containsExactly(3L, 4L, 2L, 1L);
    }

    @Test
    @DisplayName("Equal priority: the earlier deadline is placed first, whatever the id")
    void equalPriorityEarlierDeadlineWins() {
        assertThat(order(job(1, JobPriority.HIGH, 0, 9), job(2, JobPriority.HIGH, 0, 4))).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("Equal priority and deadline: fewer resource-hours first, whatever the start or id; deadline still outranks size")
    void equalPriorityAndDeadlineSmallerJobWins() {
        assertThat(order(job(1, JobPriority.NORMAL, 0, 5, 50.0), job(2, JobPriority.NORMAL, 1, 5, 10.0))).containsExactly(2L, 1L);
        assertThat(order(job(1, JobPriority.NORMAL, 0, 4, 50.0), job(2, JobPriority.NORMAL, 0, 5, 10.0))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Equal priority, deadline and resource-hours: earlier originalStartTime, then lower id")
    void fallsBackToOriginalStartTimeThenId() {
        assertThat(order(job(1, JobPriority.NORMAL, 1, 5), job(2, JobPriority.NORMAL, 0, 5))).containsExactly(2L, 1L);
        assertThat(order(job(2, JobPriority.NORMAL, 0, 5), job(1, JobPriority.NORMAL, 0, 5))).containsExactly(1L, 2L);
    }
}
