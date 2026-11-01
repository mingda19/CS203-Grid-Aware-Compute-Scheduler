package com.gacs.backend.service.allocation;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Job {@code jobId} may only run between {@code dailyStartTime} and {@code dailyEndTime} UTC,
 * every day" - an absolute, recurring daily wall-clock window, distinct from
 * {@link ConstraintType#FLEXIBILITY_WINDOW}, which is relative to the job's own baseline start.
 *
 * <p>Single-job, order-independent, so {@link #restrict} alone is enough for both solvers: the
 * exact solver applies it once per job while building its model (docs/schedule-explanation-design.md
 * section 2.4 - "an input-side change, no new model constraints needed at all").
 *
 * <p>{@code dailyStartTime.equals(dailyEndTime)} would mean an empty window every day; callers
 * should not declare that. {@code dailyStartTime.isAfter(dailyEndTime)} is a window that wraps
 * past midnight (e.g. 22:00-06:00), supported directly.
 */
public final class AbsoluteTimeWindowConstraint extends Constraint {

    private final long jobId;
    private final LocalTime dailyStartTime;
    private final LocalTime dailyEndTime;

    public AbsoluteTimeWindowConstraint(long jobId, LocalTime dailyStartTime, LocalTime dailyEndTime) {
        this.jobId = jobId;
        this.dailyStartTime = Objects.requireNonNull(dailyStartTime, "dailyStartTime");
        this.dailyEndTime = Objects.requireNonNull(dailyEndTime, "dailyEndTime");
    }

    public LocalTime dailyStartTime() {
        return dailyStartTime;
    }

    public LocalTime dailyEndTime() {
        return dailyEndTime;
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.ABSOLUTE_TIME_WINDOW;
    }

    @Override
    public List<Long> jobIds() {
        return List.of(jobId);
    }

    @Override
    public JobWindow restrict(OptimizerJob job, JobWindow window, Map<Long, JobPlan> placedSoFar) {
        if (job.id() != jobId) {
            return window;
        }
        return window.restrictedTo(hour -> withinDailyWindow(hour.toLocalTime()));
    }

    private boolean withinDailyWindow(LocalTime time) {
        if (!dailyStartTime.isAfter(dailyEndTime)) {
            return !time.isBefore(dailyStartTime) && time.isBefore(dailyEndTime);
        }
        return !time.isBefore(dailyStartTime) || time.isBefore(dailyEndTime); // wraps past midnight
    }
}
