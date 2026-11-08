package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.dto.OptimizeRequestDto.PairConstraintInput;
import com.gacs.backend.service.PriceSeriesProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Declaring ordering and exclusion rules on a request.
 *
 * <p>The solver has enforced these for a long time - {@link PrecedenceConstraint} and
 * {@link MutualExclusionConstraint} both have their own suites - but nothing could reach them:
 * {@code OptimizationProblem.constraints} was documented as "today always empty". These cover the
 * path that was missing, from the request to a built constraint.
 */
class PairConstraintRequestTest {

    private final OptimizationProblemFactory factory = new OptimizationProblemFactory(
            mock(PriceSeriesProvider.class), OptimizerProperties.DEFAULTS, Fixtures.CLOCK_BEFORE_T0);

    private static JobInput job(long id) {
        return new JobInput(id, "job-" + id, 20.0, 0.0, T0, at(6), 3, false);
    }

    private static OptimizeRequestDto request(List<PairConstraintInput> pairs, long... jobIds) {
        List<JobInput> jobs = new ArrayList<>();
        for (long id : jobIds) {
            jobs.add(job(id));
        }
        List<ForecastPointInput> forecast = new ArrayList<>();
        for (int h = 0; h < 6; h++) {
            forecast.add(new ForecastPointInput(at(h), 10.0));
        }
        return new OptimizeRequestDto(jobs, null, null, null, forecast, null, false, pairs);
    }

    @Test
    @DisplayName("a precedence rule reaches the problem as a PrecedenceConstraint, in the stated order")
    void precedenceIsBuilt() {
        OptimizationProblem problem = factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L)), 1L, 2L));

        assertThat(problem.constraints()).singleElement()
                .isInstanceOfSatisfying(PrecedenceConstraint.class, c -> {
                    assertThat(c.beforeJobId()).isEqualTo(1L);
                    assertThat(c.afterJobId()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("a mutual-exclusion rule reaches the problem as a MutualExclusionConstraint")
    void mutualExclusionIsBuilt() {
        OptimizationProblem problem = factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.MUTUAL_EXCLUSION, 1L, 2L)), 1L, 2L));

        assertThat(problem.constraints()).singleElement().isInstanceOf(MutualExclusionConstraint.class);
    }

    @Test
    @DisplayName("the constraint is attached to both of its jobs, so each is governed by it")
    void bothJobsAreGoverned() {
        OptimizationProblem problem = factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L)), 1L, 2L));

        assertThat(problem.constraintsFor(problem.jobs().get(0))).hasSize(1);
        assertThat(problem.constraintsFor(problem.jobs().get(1))).hasSize(1);
    }

    @Test
    @DisplayName("no rules means no constraints, exactly as before")
    void absentRulesChangeNothing() {
        assertThat(factory.build(request(List.of(), 1L, 2L)).constraints()).isEmpty();
        assertThat(factory.build(request(null, 1L, 2L)).constraints()).isEmpty();
    }

    // ------------------------------------------------------------------ rejections

    @Test
    @DisplayName("a rule naming a job outside the run is rejected, not dropped")
    void ruleNamingAnAbsentJobIsRejected() {
        // Dropping it would produce a plan that looks like it honoured a rule the solver never saw.
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 99L)), 1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("otherJobId 99 is not one of the jobs in this run");
    }

    @Test
    @DisplayName("a rule naming the same job on both sides is rejected")
    void selfReferenceIsRejected() {
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 1L)), 1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be ordered against, or excluded from, itself");
    }

    @Test
    @DisplayName("a missing type or job id is rejected with the field named")
    void missingFieldsAreRejected() {
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(null, 1L, 2L)), 1L, 2L)))
                .hasMessageContaining("pairConstraints[0].type is required");
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, null, 2L)), 1L, 2L)))
                .hasMessageContaining("pairConstraints[0].jobId is required");
    }

    @Test
    @DisplayName("a single-job type sent as a pair rule is rejected")
    void nonPairTypeIsRejected() {
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEADLINE, 1L, 2L)), 1L, 2L)))
                .hasMessageContaining("is not a pair constraint");
    }

    @Test
    @DisplayName("a direct precedence cycle is rejected rather than silently half-honoured")
    void directCycleIsRejected() {
        assertThatThrownBy(() -> factory.build(request(List.of(
                new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L),
                new PairConstraintInput(ConstraintType.PRECEDENCE, 2L, 1L)), 1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precedence cycle involving jobs [1, 2]");
    }

    @Test
    @DisplayName("a longer cycle is rejected too, and names the jobs caught in it")
    void indirectCycleIsRejected() {
        assertThatThrownBy(() -> factory.build(request(List.of(
                new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L),
                new PairConstraintInput(ConstraintType.PRECEDENCE, 2L, 3L),
                new PairConstraintInput(ConstraintType.PRECEDENCE, 3L, 1L)), 1L, 2L, 3L)))
                .hasMessageContaining("precedence cycle involving jobs [1, 2, 3]");
    }

    @Test
    @DisplayName("a chain that is merely long is not a cycle")
    void longChainIsAccepted() {
        OptimizationProblem problem = factory.build(request(List.of(
                new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L),
                new PairConstraintInput(ConstraintType.PRECEDENCE, 2L, 3L)), 1L, 2L, 3L));

        assertThat(problem.constraints()).hasSize(2);
    }

    @Test
    @DisplayName("mutual exclusion both ways is not a cycle: it has no direction to cycle in")
    void mutualExclusionIsNotACycle() {
        OptimizationProblem problem = factory.build(request(List.of(
                new PairConstraintInput(ConstraintType.MUTUAL_EXCLUSION, 1L, 2L),
                new PairConstraintInput(ConstraintType.MUTUAL_EXCLUSION, 2L, 1L)), 1L, 2L));

        assertThat(problem.constraints()).hasSize(2);
    }
}
