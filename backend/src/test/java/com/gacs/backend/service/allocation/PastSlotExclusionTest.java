package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.service.PriceSeries;
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
import static org.mockito.Mockito.mock;

/**
 * Nothing may be scheduled into an hour that has already passed: an operator cannot turn back time to
 * run the work, so a plan that places a job in an elapsed hour is not a plan at all.
 *
 * <p>The bound lives on {@link JobWindow}, which every solver and the plan checker read, so these
 * tests cover the window itself and then confirm the behaviour survives an end-to-end solve.
 */
class PastSlotExclusionTest {

    private static final AllocationSolver SOLVER = new HybridAllocationSolver(COSTS);

    private static JobOutcome outcome(AllocationResult result, long id) {
        return result.outcomes().stream().filter(o -> o.job().id() == id).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ the window itself

    @Test
    @DisplayName("Start slots before the run's earliest hour are dropped")
    void startSlotsDropElapsedHours() {
        OptimizerJob theJob = job(1, 20.0, 0.0, 0, 3, 6);

        assertThat(new JobWindow(theJob, prices(1, 1, 1, 1, 1, 1), NO_HORIZON).startSlots())
                .containsExactly(T0, at(1), at(2), at(3));
        assertThat(new JobWindow(theJob, prices(1, 1, 1, 1, 1, 1), at(2)).startSlots())
                .containsExactly(at(2), at(3));
    }

    @Test
    @DisplayName("firstSlot clamps to the earliest hour, so no variables are built for elapsed ones")
    void firstSlotClampsForward() {
        OptimizerJob theJob = job(1, 20.0, 0.0, 0, 3, 6);

        assertThat(new JobWindow(theJob, prices(1, 1, 1, 1, 1, 1), NO_HORIZON).firstSlot()).isEqualTo(T0);
        assertThat(new JobWindow(theJob, prices(1, 1, 1, 1, 1, 1), at(2)).firstSlot()).isEqualTo(at(2));
        // Never backwards: an earliest hour before the job's own start leaves the start alone.
        assertThat(new JobWindow(theJob, prices(1, 1, 1, 1, 1, 1), T0.minusHours(5)).firstSlot()).isEqualTo(T0);
    }

    @Test
    @DisplayName("An elapsed hour is unusable, which is what pins the exact solver's x to zero")
    void elapsedHoursAreUnusable() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 3, 6), prices(1, 1, 1, 1, 1, 1), at(2));

        assertThat(window.isUsable(T0)).isFalse();
        assertThat(window.isUsable(at(1))).isFalse();
        assertThat(window.isUsable(at(2))).isTrue();
        assertThat(window.earliestStart()).isEqualTo(at(2));
    }

    @Test
    @DisplayName("A run cannot begin in an elapsed hour, even asked for directly")
    void runsCannotStartInThePast() {
        JobWindow window = new JobWindow(job(1, 20.0, 0.0, 0, 3, 6), prices(1, 1, 1, 1, 1, 1), at(2));

        assertThat(window.runFrom(T0)).isEmpty();
        // The deadline at hour 6 makes hour 5 the last usable slot.
        assertThat(window.runFrom(at(2))).containsExactly(at(2), at(3), at(4), at(5));
    }

    @Test
    @DisplayName("An exempt job pinned to an elapsed start has nowhere left to run")
    void exemptJobWithElapsedStartHasNoSlot() {
        OptimizerJob pinned = exempt(1, 20.0, 0.0, 0, 3);

        assertThat(new JobWindow(pinned, prices(1, 1, 1), NO_HORIZON).startSlots()).containsExactly(T0);
        assertThat(new JobWindow(pinned, prices(1, 1, 1), at(1)).startSlots()).isEmpty();
    }

    // ------------------------------------------------------------------ end to end

    @Test
    @DisplayName("The solver takes the cheapest remaining hour, not the cheaper elapsed one")
    void solverIgnoresACheaperElapsedHour() {
        // Hour 0 is by far the cheapest, and is exactly what the solver would pick unbounded.
        List<OptimizerJob> jobs = List.of(job(1, 20.0, 0.0, 0, 3, 6));

        AllocationResult unbounded = SOLVER.solve(
                problemFrom(NO_HORIZON, jobs, cheapestInHourZero(), cpuCapacity(256.0)));
        assertThat(outcome(unbounded, 1).plan().start()).isEqualTo(T0);

        AllocationResult bounded = SOLVER.solve(
                problemFrom(at(2), jobs, cheapestInHourZero(), cpuCapacity(256.0)));
        assertThat(outcome(bounded, 1).plan().start()).isEqualTo(at(3));
        assertThat(outcome(bounded, 1).plan().allocations())
                .allSatisfy(a -> assertThat(a.slotStart()).isAfterOrEqualTo(at(2)));
    }

    @Test
    @DisplayName("No allocation anywhere in a plan starts before the earliest hour")
    void noAllocationFallsBeforeTheEarliestHour() {
        OptimizationProblem problem = problemFrom(at(2),
                List.of(job(1, 200.0, 0.0, 0, 4, 7), job(2, 200.0, 0.0, 0, 4, 7)),
                prices(1, 1, 30, 10, 20, 40, 50), cpuCapacity(256.0));

        AllocationResult result = SOLVER.solve(problem);

        assertThat(ContractRules.violations(problem, result)).isEmpty();
        assertThat(result.plans().values()).isNotEmpty().allSatisfy(plan ->
                assertThat(plan.allocations()).allSatisfy(a ->
                        assertThat(a.slotStart()).isAfterOrEqualTo(at(2))));
    }

    @Test
    @DisplayName("A job whose whole window has elapsed is reported, not rejected, and the rest still plan")
    void wholeWindowElapsedIsReportedPerJob() {
        OptimizationProblem problem = problemFrom(at(4),
                List.of(job(1, 20.0, 0.0, 0, 2, 3), job(2, 20.0, 0.0, 4, 2, 7)),
                prices(1, 1, 1, 1, 10, 5, 20), cpuCapacity(256.0));

        AllocationResult result = SOLVER.solve(problem);

        JobOutcome elapsed = outcome(result, 1);
        assertThat(elapsed.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(elapsed.reason()).contains("has passed").contains("2026-10-20 14:00");

        // The run is still useful: the other job is placed as normal.
        assertThat(outcome(result, 2).status()).isEqualTo(PerJobStatus.OPTIMISED);
        assertThat(outcome(result, 2).plan().start()).isEqualTo(at(5));
    }

    @Test
    @DisplayName("A job that could still run, but whose every start has elapsed, says so")
    void everyStartElapsedSaysSo() {
        // Deadline is comfortably ahead, but with no flexibility the only start is hour 0.
        OptimizationProblem problem = problemFrom(at(2),
                List.of(job(1, 20.0, 0.0, 0, 0, 5)),
                prices(1, 1, 1, 1, 1), cpuCapacity(256.0));

        JobOutcome only = outcome(SOLVER.solve(problem), 1);

        assertThat(only.status()).isEqualTo(PerJobStatus.NO_FEASIBLE_SLOT);
        assertThat(only.reason()).contains("Window elapsed").contains("every hour this job could start in");
    }

    // ------------------------------------------------------------------ where the bound comes from

    @Test
    @DisplayName("The factory takes the earliest hour from the clock, truncated to the hour")
    void factoryReadsTheEarliestHourFromTheClock() {
        OptimizationProblem problem = factoryAt("2026-10-20T09:30:00Z").build(requestFrom(validJob()));

        // 09:30 truncates to 09:00: the hour already under way stays usable, since starts are on the
        // hour and excluding it would leave an operator unable to act on the hour they are in.
        assertThat(problem.earliestStart()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    @Test
    @DisplayName("The earliest hour is left out of the run snapshot, so time passing alone cannot invalidate an approval")
    void snapshotIgnoresTheEarliestHour() {
        List<OptimizerJob> jobs = List.of(job(1, 20.0, 0.0, 0, 3, 6));

        String early = problemFrom(T0, jobs, prices(1, 1, 1, 1, 1, 1), cpuCapacity(256.0)).snapshot();
        String later = problemFrom(at(2), jobs, prices(1, 1, 1, 1, 1, 1), cpuCapacity(256.0)).snapshot();

        assertThat(early).isEqualTo(later);
    }

    // ------------------------------------------------------------------ helpers

    private static OptimizationProblemFactory factoryAt(String instant) {
        return new OptimizationProblemFactory(mock(PriceSeriesProvider.class), OptimizerProperties.DEFAULTS,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static JobInput validJob() {
        return new JobInput(1L, "job-1", 64.0, 16.0, T0, at(4), 2, false);
    }

    private static OptimizeRequestDto requestFrom(JobInput job) {
        List<ForecastPointInput> forecast = new ArrayList<>();
        for (int h = 0; h < 5; h++) {
            forecast.add(new ForecastPointInput(at(h), 10.0 * (h + 1)));
        }
        return new OptimizeRequestDto(List.of(job), null, null, null, forecast, null);
    }

    /** Prices whose cheapest hour is hour 0, so an unbounded solve provably prefers the past. */
    private static PriceSeries cheapestInHourZero() {
        return prices(1, 2, 50, 5, 60, 70);
    }
}
