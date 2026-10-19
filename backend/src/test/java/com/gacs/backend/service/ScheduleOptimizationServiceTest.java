package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.ScheduleSolver.PerJobStatus;
import com.gacs.backend.service.ScheduleSolver.SolverAssignment;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link ScheduleOptimizationService}'s own orchestration logic - validation,
 * baseline/optimized pricing, and (GRID-125) the {@code status}/{@code usedHeuristicFallback}
 * wiring - against a mocked {@link ScheduleSolver}, independent of which solver is actually
 * wired in production.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleOptimizationServiceTest {

    private static final String OWNER = "operator@datacenter.io";
    private static final LocalDateTime HORIZON_START = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Mock
    private JobRepository jobRepository;
    @Mock
    private PriceSeriesProvider priceSeriesProvider;
    @Mock
    private ScheduleSolver solver;

    private ScheduleOptimizationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(HORIZON_START.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new ScheduleOptimizationService(jobRepository, priceSeriesProvider, solver, clock);
    }

    @Test
    @DisplayName("a certified-optimal solver result reports status OPTIMAL and no heuristic fallback")
    void certifiedOptimalIsReportedAsOptimal() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        OptimizeRequestDto.JobInput jobInput = jobInput(1L, baseline, baseline.plusHours(10), 2, false);
        when(priceSeriesProvider.load(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new PriceSeries(Map.of(baseline, 50.0, baseline.plusHours(1), 10.0), false));

        ScheduleSolver.JobSpec spec = new ScheduleSolver.JobSpec(
            1L, "job-1", 10.0, 0.0, baseline, baseline.plusHours(10), 2, false);
        SolverResult result = new SolverResult(
            List.of(new SolverAssignment(spec, baseline.plusHours(1), PerJobStatus.RESCHEDULED)), true);
        when(solver.solve(any())).thenReturn(result);

        OptimizeResponseDto response = service.optimize(
            new OptimizeRequestDto(List.of(jobInput), null, null, null), OWNER);

        assertThat(response.status()).isEqualTo(OptimizeResponseDto.STATUS_OPTIMAL);
        assertThat(response.usedHeuristicFallback()).isFalse();
        assertThat(response.items()).hasSize(1);
    }

    @Test
    @DisplayName("a non-certified solver result reports status FEASIBLE and usedHeuristicFallback=true")
    void nonCertifiedIsReportedAsFeasible() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        OptimizeRequestDto.JobInput jobInput = jobInput(1L, baseline, baseline.plusHours(10), 2, false);
        when(priceSeriesProvider.load(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new PriceSeries(Map.of(baseline, 50.0, baseline.plusHours(1), 10.0), false));

        ScheduleSolver.JobSpec spec = new ScheduleSolver.JobSpec(
            1L, "job-1", 10.0, 0.0, baseline, baseline.plusHours(10), 2, false);
        SolverResult result = new SolverResult(
            List.of(new SolverAssignment(spec, baseline, PerJobStatus.UNCHANGED_ALREADY_OPTIMAL)), false);
        when(solver.solve(any())).thenReturn(result);

        OptimizeResponseDto response = service.optimize(
            new OptimizeRequestDto(List.of(jobInput), null, null, null), OWNER);

        assertThat(response.status()).isEqualTo(OptimizeResponseDto.STATUS_FEASIBLE);
        assertThat(response.usedHeuristicFallback()).isTrue();
    }

    @Test
    @DisplayName("an explicitly empty jobs array is rejected before the solver is ever called")
    void emptyJobsArrayIsRejected() {
        assertThatThrownBy(() -> service.optimize(new OptimizeRequestDto(List.of(), null, null, null), OWNER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jobs");
    }

    @Test
    @DisplayName("omitting jobs optimizes the operator's own stored, eligible jobs")
    void omittedJobsUsesStoredJobs() {
        LocalDateTime baseline = HORIZON_START.plusHours(5);
        Job stored = Job.builder()
            .id(9L).ownerEmail(OWNER).name("stored job")
            .cpuHours(10.0).gpuHours(0.0)
            .originalStartTime(baseline).deadline(baseline.plusHours(10))
            .flexibilityWindowHours(0).isExempt(false).status("PENDING")
            .build();
        when(jobRepository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER)).thenReturn(List.of(stored));
        when(priceSeriesProvider.load(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new PriceSeries(Map.of(baseline, 20.0), false));

        ScheduleSolver.JobSpec spec = new ScheduleSolver.JobSpec(
            9L, "stored job", 10.0, 0.0, baseline, baseline.plusHours(10), 0, false);
        SolverResult result = new SolverResult(
            List.of(new SolverAssignment(spec, baseline, PerJobStatus.UNCHANGED_ALREADY_OPTIMAL)), true);
        when(solver.solve(any())).thenReturn(result);

        OptimizeResponseDto response = service.optimize(new OptimizeRequestDto(null, null, null, null), OWNER);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).jobId()).isEqualTo(9L);
    }

    private static OptimizeRequestDto.JobInput jobInput(
        long id, LocalDateTime start, LocalDateTime deadline, int windowHours, boolean exempt
    ) {
        return new OptimizeRequestDto.JobInput(id, "job-" + id, 10.0, 0.0, start, deadline, windowHours, exempt);
    }
}
