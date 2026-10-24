package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.CapacityOverrideInput;
import com.gacs.backend.dto.OptimizeRequestDto.ClusterCapacityInput;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.exception.UnprocessableDataException;
import com.gacs.backend.service.PriceSeriesProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 2: request validation (contract section 6.4, request table and Errors). */
class OptimizationProblemFactoryTest {

    private final PriceSeriesProvider provider = mock(PriceSeriesProvider.class);
    private final OptimizationProblemFactory factory = new OptimizationProblemFactory(provider, OptimizerProperties.DEFAULTS);

    /** A valid job: 64 CPU-hours from slot 0, may wait 2 hours, finishes by slot 4. */
    private static JobInput validJob(long id) {
        return new JobInput(id, "job-" + id, 64.0, 16.0, T0, at(4), 2, false);
    }

    private static List<ForecastPointInput> forecast(double... perHour) {
        List<ForecastPointInput> points = new ArrayList<>();
        for (int h = 0; h < perHour.length; h++) {
            points.add(new ForecastPointInput(at(h), perHour[h]));
        }
        return points;
    }

    private static OptimizeRequestDto request(List<JobInput> jobs) {
        return new OptimizeRequestDto(jobs, null, null, null, forecast(10, 20, 30, 40), null);
    }

    private OptimizationProblem build(JobInput job) {
        return factory.build(request(List.of(job)));
    }

    private void assertRejected(JobInput job, String message) {
        assertThatThrownBy(() -> build(job)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static JobInput with(UnaryOperator<JobInput> change) {
        return change.apply(validJob(1));
    }

    // ------------------------------------------------------------------ valid requests

    @Test
    @DisplayName("A valid request with forecast[] becomes a problem with baselines, without calling the forecast service")
    void validRequestWithExplicitForecast() {
        OptimizationProblem problem = factory.build(request(List.of(validJob(1), validJob(2))));

        assertThat(problem.jobs()).extracting(OptimizerJob::id).containsExactly(1L, 2L);
        assertThat(problem.prices().priceAt(at(2))).contains(30.0);
        assertThat(problem.baselineOf(problem.jobs().getFirst()).allocations())
                .containsExactly(new SlotAllocation(T0, 1.0));
        verify(provider, never()).load(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("forecastLocation loads prices from the earliest originalStartTime, default horizon 48")
    void forecastLocationLoadsFromEarliestStart() {
        when(provider.load("LZ_NORTH", T0, 48)).thenReturn(prices(10, 20, 30, 40, 50));
        JobInput later = new JobInput(2L, "later", 20.0, 0.0, at(2), at(4), 0, false);
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(later, validJob(1)), null, "LZ_NORTH", null, null, null);

        OptimizationProblem problem = factory.build(request);

        verify(provider).load("LZ_NORTH", T0, 48);
        assertThat(problem.prices().priceAt(at(4))).contains(50.0);
    }

    @Test
    @DisplayName("forecast wins when both forecast and forecastLocation are sent; horizonHours is then ignored")
    void forecastWinsOverLocation() {
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(validJob(1)), null, "LZ_NORTH", 500,
                forecast(10, 20), null);

        assertThat(factory.build(request).prices().priceAt(T0)).contains(10.0);
        verify(provider, never()).load(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("Capacity: overrides per slot, cluster capacity elsewhere, defaults for a missing field")
    void capacityIsResolved() {
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(validJob(1)),
                new ClusterCapacityInput(200.0, null), null, null, forecast(10, 20, 30, 40),
                List.of(new CapacityOverrideInput(at(1), 32.0, 8.0)));

        SlotCapacity capacity = factory.build(request).capacity();
        assertThat(capacity.limitAt(at(1))).isEqualTo(new CapacityLimit(32.0, 8.0));
        assertThat(capacity.limitAt(at(2))).isEqualTo(new CapacityLimit(200.0, 64.0));
    }

    @Test
    @DisplayName("The baseline uses the slot's override, so a small override spreads it over two hours")
    void baselineUsesOverrides() {
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(validJob(1)), null, null, null,
                forecast(10, 20, 30, 40), List.of(new CapacityOverrideInput(T0, 32.0, 64.0)));

        OptimizationProblem problem = factory.build(request);
        assertThat(problem.baselineOf(problem.jobs().getFirst()).allocations())
                .containsExactly(new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.5));
    }

    // ------------------------------------------------------------------ 400: request shape

    @Test
    @DisplayName("400: missing body, missing or empty jobs")
    void missingJobs() {
        assertThatThrownBy(() -> factory.build(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.build(request(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("jobs is required");
        assertThatThrownBy(() -> factory.build(request(List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one job");
    }

    @Test
    @DisplayName("400: each required job field")
    void requiredJobFields() {
        assertRejected(with(j -> new JobInput(null, j.name(), 1.0, 0.0, T0, at(4), 0, false)), "jobs[0].id is required");
        assertRejected(with(j -> new JobInput(1L, " ", 1.0, 0.0, T0, at(4), 0, false)), "jobs[0].name must not be blank");
        assertRejected(with(j -> new JobInput(1L, "x".repeat(256), 1.0, 0.0, T0, at(4), 0, false)), "at most 255");
        assertRejected(with(j -> new JobInput(1L, "a", null, 0.0, T0, at(4), 0, false)), "jobs[0].cpuHours is required");
        assertRejected(with(j -> new JobInput(1L, "a", 1.0, null, T0, at(4), 0, false)), "jobs[0].gpuHours is required");
        assertRejected(with(j -> new JobInput(1L, "a", 1.0, 0.0, null, at(4), 0, false)), "originalStartTime is required");
        assertRejected(with(j -> new JobInput(1L, "a", 1.0, 0.0, T0, null, 0, false)), "deadline is required");
        assertRejected(with(j -> new JobInput(1L, "a", 1.0, 0.0, T0, at(4), null, false)), "flexibilityWindowHours is required");
        assertRejected(with(j -> new JobInput(1L, "a", 1.0, 0.0, T0, at(4), 0, null)), "isExempt is required");
    }

    @Test
    @DisplayName("400: negative work, no work, start off the hour, deadline before start, negative flexibility")
    void invalidJobValues() {
        assertRejected(new JobInput(1L, "a", -1.0, 1.0, T0, at(4), 0, false), "cpuHours must be a finite number of 0 or more");
        assertRejected(new JobInput(1L, "a", 0.0, 0.0, T0, at(4), 0, false), "cpuHours or gpuHours greater than 0");
        assertRejected(new JobInput(1L, "a", 1.0, 0.0, T0.plusMinutes(30), at(4), 0, false), "must be on the hour");
        assertRejected(new JobInput(1L, "a", 1.0, 0.0, at(2), at(1), 0, false), "deadline must be at or after");
        assertRejected(new JobInput(1L, "a", 1.0, 0.0, T0, at(4), -1, false), "flexibilityWindowHours must be 0 or more");
    }

    @Test
    @DisplayName("400: duplicate job ids")
    void duplicateIds() {
        assertThatThrownBy(() -> factory.build(request(List.of(validJob(7), validJob(7)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jobs[1].id 7 is used by more than one job");
    }

    @Test
    @DisplayName("400: neither forecast nor forecastLocation, blank location, horizon outside 1-168")
    void priceSourceRules() {
        List<JobInput> jobs = List.of(validJob(1));
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Either forecast or forecastLocation is required");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, " ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, "LZ_NORTH", 0, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 1 and 168");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, "LZ_NORTH", 169, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 1 and 168");
    }

    @Test
    @DisplayName("400: bad forecast entries and bad capacity values")
    void badForecastAndCapacity() {
        List<JobInput> jobs = List.of(validJob(1));
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, null, null, List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one price");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, null, null,
                List.of(new ForecastPointInput(T0, 1.0), new ForecastPointInput(T0, 2.0)), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("appears more than once");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, null, null,
                List.of(new ForecastPointInput(T0.plusMinutes(15), 1.0)), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be on the hour");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, new ClusterCapacityInput(-1.0, 1.0), null,
                null, forecast(10), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrentCpuHours");
        assertThatThrownBy(() -> factory.build(new OptimizeRequestDto(jobs, null, null, null, forecast(10),
                List.of(new CapacityOverrideInput(T0, 1.0, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacityOverrides[0].maxGpuHours is required");
    }

    @Test
    @DisplayName("400: a job that cannot finish by its deadline even alone on the cluster")
    void cannotFinishAlone() {
        // 600 CPU-hours at 256 per hour needs 3 hours; the deadline allows 2.
        JobInput tooBig = new JobInput(1L, "big", 600.0, 0.0, T0, at(2), 0, false);
        assertThatThrownBy(() -> build(tooBig))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("even alone on the cluster");
    }

    // ------------------------------------------------------------------ 422: missing data

    @Test
    @DisplayName("422: a baseline hour without a price names the job and the hour")
    void unpricedBaselineHour() {
        // Override halves capacity at 10:00, so the baseline also needs 11:00, which has no price.
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(validJob(1)), null, null, null,
                List.of(new ForecastPointInput(T0, 10.0)), List.of(new CapacityOverrideInput(T0, 32.0, 64.0)));

        assertThatThrownBy(() -> factory.build(request))
                .isInstanceOf(UnprocessableDataException.class)
                .hasMessageContaining(at(1).toString())
                .hasMessageContaining("job id 1");
    }

    @Test
    @DisplayName("422 is returned with the standard error envelope")
    void unprocessableMapsTo422() {
        var response = new GlobalExceptionHandler().handleUnprocessableData(new UnprocessableDataException("no price"));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().getMessage()).isEqualTo("no price");
    }

    @Test
    @DisplayName("A request with an invalid job is a 400 even when prices are also missing")
    void inputErrorsComeBeforeDataErrors() {
        JobInput tooBig = new JobInput(1L, "big", 600.0, 0.0, T0, at(2), 0, false);
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(tooBig), null, null, null,
                List.of(new ForecastPointInput(at(5), 10.0)), null);

        assertThatThrownBy(() -> factory.build(request))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(UnprocessableDataException.class);
    }

    @Test
    @DisplayName("Times in the past are fine when prices are given for them")
    void pastStartWithExplicitForecast() {
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
        JobInput job = new JobInput(1L, "past", 20.0, 0.0, past, past.plusHours(1), 0, false);
        OptimizeRequestDto request = new OptimizeRequestDto(List.of(job), null, null, null,
                List.of(new ForecastPointInput(past, 10.0)), null);

        assertThat(factory.build(request).jobs()).hasSize(1);
    }
}
