package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.service.PriceSeriesProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.NO_HORIZON;
import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.exempt;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problemFrom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A job whose {@code originalStartTime} has passed should not simply be discarded.
 *
 * <p>Eleven jobs were submitted and seven came back {@code NO_FEASIBLE_SLOT} purely because their
 * start times had gone by, while their deadlines were still hours away. The cause is that the
 * flexibility window is anchored to the start: {@code latestStart() = originalStartTime +
 * flexibilityWindowHours} can be entirely behind the current hour, so {@code startSlots()} is empty
 * and the job is reported infeasible on an anchor that no longer exists.
 *
 * <p>The remedy is opt-in per request ({@code rescheduleElapsedJobs}), because it genuinely widens
 * placement past what the operator specified: such a job may then start anywhere from the current
 * hour up to the last slot that still lets it finish by its deadline. These tests cover the window
 * arithmetic, the one invariant that must survive the flag (nothing is ever placed before the current
 * hour), and the cases the flag deliberately does <em>not</em> rescue.
 *
 * @see PastSlotExclusionTest for the bound this must not weaken
 */
class ElapsedStartRescheduleTest {

    private static final AllocationSolver SOLVER = new HybridAllocationSolver(COSTS);

    private static JobOutcome outcome(AllocationResult result, long id) {
        return result.outcomes().stream().filter(o -> o.job().id() == id).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ the anchor itself

    @Test
    @DisplayName("Re-anchoring moves latestStart from the flexibility window to the deadline")
    void reanchoringMovesLatestStartToTheDeadline() {
        OptimizerJob original = job(1, 20.0, 0.0, 0, 3, 6);
        assertThat(original.latestStart()).isEqualTo(at(3));          // start + flexibility
        assertThat(original.rescheduledFromElapsedStart()).isFalse();

        OptimizerJob reanchored = original.reanchoredToDeadline();
        assertThat(reanchored.latestStart()).isEqualTo(at(6));         // the deadline itself
        assertThat(reanchored.rescheduledFromElapsedStart()).isTrue();
        // Nothing else about the job changes - above all not flexibilityWindowHours, which the run
        // snapshot hashes and the export gate rebuilds from the stored job.
        assertThat(reanchored.flexibilityWindowHours()).isEqualTo(original.flexibilityWindowHours());
        assertThat(reanchored.originalStartTime()).isEqualTo(original.originalStartTime());
        assertThat(reanchored.deadline()).isEqualTo(original.deadline());
    }

    @Test
    @DisplayName("An exempt job is never re-anchored: it runs at its original time by definition")
    void exemptJobsAreNeverReanchored() {
        OptimizerJob pinned = exempt(1, 20.0, 0.0, 0, 3);

        assertThat(pinned.reanchoredToDeadline()).isSameAs(pinned);
        assertThat(pinned.reanchoredToDeadline().latestStart()).isEqualTo(T0);
        // And the combination cannot be constructed by hand either.
        assertThatThrownBy(() -> new OptimizerJob(1L, "x", 20.0, 0.0, T0, at(3), 0, true,
                com.gacs.backend.model.JobPriority.NORMAL, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be rescheduled from an elapsed start");
    }

    @Test
    @DisplayName("Re-anchoring is idempotent, so a job cannot be widened twice")
    void reanchoringIsIdempotent() {
        OptimizerJob once = job(1, 20.0, 0.0, 0, 3, 6).reanchoredToDeadline();
        assertThat(once.reanchoredToDeadline()).isSameAs(once);
    }

    // ------------------------------------------------------------------ the window

    @Test
    @DisplayName("A re-anchored job's start slots run from the current hour to the last slot that finishes in time")
    void reanchoredWindowRunsFromNowToTheLastFeasibleSlot() {
        // Start hour 0 with 1 hour of flexibility, deadline hour 6, and we are now at hour 3.
        OptimizerJob elapsed = job(1, 20.0, 0.0, 0, 1, 6);

        // Without the opt-in: every start it is allowed (hours 0 and 1) is behind hour 3.
        assertThat(new JobWindow(elapsed, prices(1, 1, 1, 1, 1, 1), at(3)).startSlots()).isEmpty();

        // With it: hour 3 up to hour 5, the last slot that still ends by the hour-6 deadline.
        assertThat(new JobWindow(elapsed.reanchoredToDeadline(), prices(1, 1, 1, 1, 1, 1), at(3)).startSlots())
                .containsExactly(at(3), at(4), at(5));
    }

    @Test
    @DisplayName("Re-anchoring never reaches past the deadline, however far away the deadline is")
    void reanchoredWindowStillEndsByTheDeadline() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 1, 4).reanchoredToDeadline(),
                prices(1, 1, 1, 1, 1, 1), at(1));

        // lastSlot is hour 3: a run starting there ends at hour 4, exactly the deadline.
        assertThat(window.lastSlot()).isEqualTo(at(3));
        assertThat(window.startSlots()).containsExactly(at(1), at(2), at(3));
        assertThat(window.runFrom(at(3))).containsExactly(at(3));
    }

    /**
     * The invariant the whole elapsed-job feature has to preserve: no usable hour, and therefore no
     * scheduled start, is ever before the run's earliest schedulable hour. Re-anchoring moves only the
     * window's <em>upper</em> bound; the lower bound stays in {@code JobWindow.usable()}, which every
     * solver and the plan checker read.
     */
    @Test
    @DisplayName("Re-anchoring does not weaken the rule that nothing may start before the current hour")
    void reanchoringNeverAllowsAnElapsedHour() {
        OptimizerJob reanchored = job(1, 20.0, 0.0, 0, 1, 6).reanchoredToDeadline();
        JobWindow window = new JobWindow(reanchored, prices(1, 1, 1, 1, 1, 1), at(3));

        for (int hour = 0; hour < 3; hour++) {
            assertThat(window.isUsable(at(hour))).as("hour %d is elapsed", hour).isFalse();
            assertThat(window.runFrom(at(hour))).as("no run may begin in elapsed hour %d", hour).isEmpty();
        }
        assertThat(window.startSlots()).allSatisfy(slot -> assertThat(slot).isAfterOrEqualTo(at(3)));
        assertThat(window.firstSlot()).isEqualTo(at(3));
    }

    @Test
    @DisplayName("A job whose deadline has also passed gains nothing from re-anchoring")
    void aPassedDeadlineIsStillInfeasible() {
        // Deadline at hour 3, and we are now at hour 4: there is no window left to widen.
        OptimizerJob gone = job(1, 20.0, 0.0, 0, 2, 3).reanchoredToDeadline();

        assertThat(new JobWindow(gone, prices(1, 1, 1, 1, 1, 1), at(4)).startSlots()).isEmpty();
    }

    // ------------------------------------------------------------------ end to end through a solver

    @Test
    @DisplayName("An elapsed job is left out without the opt-in and placed with it, in the cheapest remaining hour")
    void solverPlacesAReanchoredJobInTheCheapestRemainingHour() {
        OptimizerJob elapsed = job(1, 20.0, 0.0, 0, 1, 6);
        // Hours 0-1 are cheapest but elapsed; of the hours left, hour 4 is cheapest.
        var curve = prices(1, 1, 50, 40, 5, 60);

        JobOutcome without = outcome(SOLVER.solve(
                problemFrom(at(2), List.of(elapsed), curve, cpuCapacity(256.0))), 1);
        assertThat(without.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(without.reason()).contains("Window elapsed");

        JobOutcome with = outcome(SOLVER.solve(
                problemFrom(at(2), List.of(elapsed.reanchoredToDeadline()), curve, cpuCapacity(256.0))), 1);
        assertThat(with.status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(with.plan().start()).isEqualTo(at(4));
        assertThat(with.job().rescheduledFromElapsedStart()).isTrue();
    }

    @Test
    @DisplayName("A re-anchored job competes for capacity like any other, without displacing on-time jobs")
    void reanchoredJobsRespectCapacityAndTheContractRules() {
        OptimizationProblem problem = problemFrom(at(2),
                List.of(job(1, 200.0, 0.0, 0, 1, 7).reanchoredToDeadline(), job(2, 200.0, 0.0, 2, 4, 7)),
                prices(1, 1, 30, 10, 20, 40, 50), cpuCapacity(256.0));

        AllocationResult result = SOLVER.solve(problem);

        // The plan checker reads the same JobWindow, so it accepts re-anchored placements and still
        // rejects anything that breaks a window, deadline or capacity rule.
        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.plans().values()).isNotEmpty().allSatisfy(plan ->
                assertThat(plan.allocations()).allSatisfy(a ->
                        assertThat(a.slotStart()).isAfterOrEqualTo(at(2))));
    }

    /**
     * The new third reason. Admission only proves a job fits alone between its <em>original</em> start
     * and its deadline; once that start has passed, fewer hours remain and the job can be unplaceable
     * with the cluster completely idle. Calling that "cluster full" would send the operator to free up
     * capacity that is not the problem.
     */
    @Test
    @DisplayName("Too few hours left before the deadline is reported as time, not as capacity")
    void notEnoughTimeLeftIsNotReportedAsClusterFull() {
        // 100 CPU-hours at 40 per slot needs 3 slots, but only hours 4 and 5 are left before the
        // hour-6 deadline. The job is alone on the cluster, so nothing is competing with it.
        OptimizerJob tooLate = job(1, 100.0, 0.0, 0, 5, 6).reanchoredToDeadline();

        JobOutcome only = outcome(SOLVER.solve(
                problemFrom(at(4), List.of(tooLate), prices(1, 1, 1, 1, 1, 1), cpuCapacity(40.0))), 1);

        assertThat(only.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(only.reason()).contains("Not enough time left").doesNotContain("Cluster full");
    }

    @Test
    @DisplayName("The window-elapsed reason names the opt-in as a remedy while the deadline is still ahead")
    void windowElapsedReasonNamesTheOptIn() {
        JobOutcome only = outcome(SOLVER.solve(
                problemFrom(at(2), List.of(job(1, 20.0, 0.0, 0, 0, 5)),
                        prices(1, 1, 1, 1, 1), cpuCapacity(256.0))), 1);

        assertThat(only.reason())
                .contains("Window elapsed")
                .contains("reschedule jobs whose start time has passed");
    }

    // ------------------------------------------------------------------ the request flag

    @Test
    @DisplayName("The flag defaults to false, and false keeps each job's own window exactly")
    void theFlagDefaultsToFalse() {
        assertThat(new OptimizeRequestDto(List.of(), null, null, null, null, null).reschedulesElapsedJobs())
                .isFalse();
        assertThat(new OptimizeRequestDto(List.of(), null, null, null, null, null, null)
                .reschedulesElapsedJobs()).as("absent means false").isFalse();

        // Now is hour 2, so the job's start (hour 0) has elapsed; without the flag it keeps its own
        // window and is reported rather than moved.
        OptimizationProblem kept = factoryAt("2026-10-20T12:00:00Z").build(request(elapsedJobInput(), false));
        assertThat(kept.jobs()).allSatisfy(job ->
                assertThat(job.rescheduledFromElapsedStart()).isFalse());
        assertThat(kept.jobs().getFirst().latestStart()).isEqualTo(at(1));
    }

    @Test
    @DisplayName("The flag re-anchors only the jobs whose start has actually passed")
    void theFlagAppliesPerJobAndOnlyToElapsedStarts() {
        // Now is hour 2. Job 1 starts at hour 0 (elapsed); job 2 starts at hour 2 (not elapsed).
        OptimizationProblem problem = factoryAt("2026-10-20T12:00:00Z")
                .build(request(List.of(elapsedJobInput(), onTimeJobInput()), true));

        OptimizerJob first = problem.jobs().get(0);
        OptimizerJob second = problem.jobs().get(1);
        assertThat(first.rescheduledFromElapsedStart()).as("start elapsed").isTrue();
        assertThat(first.latestStart()).isEqualTo(at(6));
        assertThat(second.rescheduledFromElapsedStart()).as("start has not elapsed").isFalse();
        assertThat(second.latestStart()).isEqualTo(at(3));
    }

    @Test
    @DisplayName("The flag stays out of the run snapshot, so it cannot invalidate a pending approval")
    void theFlagIsNotPartOfTheSnapshot() {
        // The snapshot records the inputs a later change is detected against, and the export gate
        // rebuilds its job line from the stored job's own fields. Putting the flag (or the widened
        // window it implies) in there would make the line disagree with the job record and invalidate
        // exactly the plans this option exists to produce.
        String without = factoryAt("2026-10-20T12:00:00Z").build(request(elapsedJobInput(), false)).snapshot();
        String with = factoryAt("2026-10-20T12:00:00Z").build(request(elapsedJobInput(), true)).snapshot();

        assertThat(with).isEqualTo(without);
    }

    // ------------------------------------------------------------------ helpers

    private static OptimizationProblemFactory factoryAt(String instant) {
        return new OptimizationProblemFactory(mock(PriceSeriesProvider.class), OptimizerProperties.DEFAULTS,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    /** Starts at hour 0 with 1 hour of flexibility, deadline hour 6. */
    private static JobInput elapsedJobInput() {
        return new JobInput(1L, "elapsed", 20.0, 0.0, T0, at(6), 1, false);
    }

    /** Starts at hour 2 with 1 hour of flexibility, deadline hour 6. */
    private static JobInput onTimeJobInput() {
        return new JobInput(2L, "on-time", 20.0, 0.0, at(2), at(6), 1, false);
    }

    private static OptimizeRequestDto request(JobInput job, boolean rescheduleElapsed) {
        return request(List.of(job), rescheduleElapsed);
    }

    private static OptimizeRequestDto request(List<JobInput> jobs, boolean rescheduleElapsed) {
        List<ForecastPointInput> forecast = new ArrayList<>();
        for (int h = 0; h < 7; h++) {
            forecast.add(new ForecastPointInput(at(h), 10.0 * (h + 1)));
        }
        return new OptimizeRequestDto(jobs, null, null, null, forecast, null, rescheduleElapsed);
    }

    /** Guards the assumption the window tests rest on: slot h starts at T0 + h hours. */
    @Test
    void fixtureHoursAreOnTheHourFromT0() {
        assertThat(at(3)).isEqualTo(LocalDateTime.of(2026, 10, 20, 13, 0));
        assertThat(NO_HORIZON).isEqualTo(LocalDateTime.MIN);
    }
}
