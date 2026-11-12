package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.allocation.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedJobConstraintsTest {
    private final JobRepository jobs = mock(JobRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    private final ScheduleInputs inputs = new ScheduleInputs(mock(UserRepository.class), jobs,
            mock(FacilityRepository.class), mock(OptimizationPlanRepository.class), clock, OptimizerProperties.DEFAULTS);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 12, 0, 0);

    private OptimizeRequestDto request(Long... ids) {
        var submitted = java.util.Arrays.stream(ids).map(id -> new OptimizeRequestDto.JobInput(id, "job " + id,
                1.0, 0.0, start, start.plusHours(4), 3, false, "NORMAL")).toList();
        var forecast = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new OptimizeRequestDto.ForecastPointInput(start.plusHours(i), 10.0 + i)).toList();
        return new OptimizeRequestDto(submitted, null, null, null, forecast, null);
    }

    private Job savedFirst() {
        return Job.builder().id(1L).ownerEmail("owner").confirmationOutcome(ConfirmationOutcome.CONFIRMED)
                .pairConstraints(List.of(new JobConstraint(ConstraintType.PRECEDENCE, 2L))).build();
    }

    @Test void savedRulesReachTheSolverEvenWhenTheCallerOmitsThem() {
        when(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc("owner"))
                .thenReturn(List.of(savedFirst(), Job.builder().id(2L).build()));
        var enriched = inputs.withStoredConstraints(request(1L, 2L), "owner");
        assertThat(enriched.pairConstraints()).containsExactly(
                new OptimizeRequestDto.PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L));
        var problem = new OptimizationProblemFactory(mock(PriceSeriesProvider.class), OptimizerProperties.DEFAULTS, clock)
                .build(enriched);
        assertThat(problem.constraints()).singleElement().isInstanceOf(PrecedenceConstraint.class);
        var result = new AnnealingAllocationSolver(new EnergyCostModel(OptimizerProperties.DEFAULTS)).solve(problem);
        var before = result.outcomes().stream().filter(o -> o.job().id() == 1).findFirst().orElseThrow();
        var after = result.outcomes().stream().filter(o -> o.job().id() == 2).findFirst().orElseThrow();
        assertThat(after.plan().start()).isAfterOrEqualTo(before.plan().end());
    }

    @Test void neitherEndOfASavedRelationshipCanBeScheduledAlone() {
        when(jobs.findByOwnerEmailOrderByOriginalStartTimeAsc("owner")).thenReturn(List.of(savedFirst()));
        assertThatThrownBy(() -> inputs.withStoredConstraints(request(1L), "owner")).hasMessageContaining("planned together");
        assertThatThrownBy(() -> inputs.withStoredConstraints(request(2L), "owner")).hasMessageContaining("planned together");
    }

    @Test void relationshipChangesAreSchedulingInputsAndJsonRoundTrips() {
        Job first = savedFirst();
        var before = ScheduleInputs.state(first);
        var converter = new JobConstraintsConverter();
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(first.getPairConstraints())))
                .isEqualTo(first.getPairConstraints());
        first.setPairConstraints(List.of());
        assertThat(ScheduleInputs.state(first)).isNotEqualTo(before);
    }
}
