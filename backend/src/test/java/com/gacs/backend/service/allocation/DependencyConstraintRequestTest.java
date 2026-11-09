package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.dto.OptimizeRequestDto.PairConstraintInput;
import com.gacs.backend.service.PriceSeriesProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@code DEPENDENCY} on a request: "this job runs after that one".
 *
 * <p>It is the mirror of {@code PRECEDENCE} and reaches the solver as the same
 * {@link PrecedenceConstraint} with its two jobs swapped, so the whole weight of these tests is on
 * the swap happening and happening the right way round. Getting it backwards would not throw, would
 * not fail a solve, and would produce a schedule that looks entirely reasonable while running the
 * operator's jobs in the opposite order to what they asked for - which is why the direction is
 * asserted explicitly rather than via a count of constraints.
 */
class DependencyConstraintRequestTest {

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
        // Hour 1 is far the cheapest, so both jobs want it and nothing but the ordering rule
        // would keep them apart.
        double[] prices = {50.0, 1.0, 40.0, 40.0, 40.0, 40.0};
        List<ForecastPointInput> forecast = new ArrayList<>();
        for (int h = 0; h < 6; h++) {
            forecast.add(new ForecastPointInput(at(h), prices[h]));
        }
        return new OptimizeRequestDto(jobs, null, null, null, forecast, null, false, pairs);
    }

    @Test
    @DisplayName("a dependency puts the OTHER job first: jobId 1 depends on 2, so 2 runs before 1")
    void dependencyIsBuiltWithItsJobsSwapped() {
        OptimizationProblem problem = factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 2L)), 1L, 2L));

        assertThat(problem.constraints()).singleElement()
                .isInstanceOfSatisfying(PrecedenceConstraint.class, c -> {
                    assertThat(c.beforeJobId()).isEqualTo(2L);
                    assertThat(c.afterJobId()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("DEPENDENCY(1,2) and PRECEDENCE(2,1) are the same constraint")
    void dependencyIsExactlyTheMirrorOfPrecedence() {
        PrecedenceConstraint viaDependency = (PrecedenceConstraint) factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 2L)), 1L, 2L))
                .constraints().getFirst();
        PrecedenceConstraint viaPrecedence = (PrecedenceConstraint) factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.PRECEDENCE, 2L, 1L)), 1L, 2L))
                .constraints().getFirst();

        assertThat(viaDependency.beforeJobId()).isEqualTo(viaPrecedence.beforeJobId());
        assertThat(viaDependency.afterJobId()).isEqualTo(viaPrecedence.afterJobId());
    }

    @Test
    @DisplayName("a dependency actually delays the dependent job behind the one it waits for")
    void dependencyIsEnforcedByBothSolvers() {
        // The end-to-end check that the swap means something: job 1 declares it runs after job 2,
        // so however cheap hour 1 is, job 1 must not start before job 2 has finished. Both solvers,
        // because they enforce pair constraints by different mechanisms.
        OptimizationProblem problem = factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 2L)), 1L, 2L));

        AllocationResult exact = new ExactAllocationSolver(
                new EnergyCostModel(OptimizerProperties.DEFAULTS)).solve(problem, Duration.ofSeconds(10))
                .orElseThrow();
        AllocationResult heuristic = new AnnealingAllocationSolver(
                new EnergyCostModel(OptimizerProperties.DEFAULTS), 200).solve(problem);

        assertThat(ContractRules.violations(problem, exact)).isEmpty();
        assertThat(ContractRules.violations(problem, heuristic)).isEmpty();
        for (AllocationResult result : List.of(exact, heuristic)) {
            assertThat(startOf(result, 1L)).isAfterOrEqualTo(endOf(result, 2L));
        }
    }

    private static java.time.LocalDateTime startOf(AllocationResult result, long jobId) {
        return planOf(result, jobId).start();
    }

    private static java.time.LocalDateTime endOf(AllocationResult result, long jobId) {
        return planOf(result, jobId).end();
    }

    private static JobPlan planOf(AllocationResult result, long jobId) {
        return result.plans().entrySet().stream()
                .filter(entry -> entry.getKey().id() == jobId)
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElseThrow(() -> new AssertionError("job " + jobId + " was not placed"));
    }

    @Test
    @DisplayName("a dependency cycle is rejected, same as a precedence cycle")
    void mixedCycleIsRejected() {
        // 1 after 2, and 2 after 1, said in both vocabularies. Normalising DEPENDENCY into
        // PrecedenceConstraint before cycle detection runs is what lets this be caught at all.
        assertThatThrownBy(() -> factory.build(request(List.of(
                new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 2L),
                new PairConstraintInput(ConstraintType.PRECEDENCE, 1L, 2L)), 1L, 2L)))
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("a dependency naming the same job twice is rejected")
    void selfDependencyIsRejected() {
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 1L)), 1L, 2L)))
                .hasMessageContaining("both sides");
    }

    @Test
    @DisplayName("a dependency on a job that is not in the run is rejected, not dropped")
    void dependencyOnAbsentJobIsRejected() {
        assertThatThrownBy(() -> factory.build(request(
                List.of(new PairConstraintInput(ConstraintType.DEPENDENCY, 1L, 99L)), 1L, 2L)))
                .hasMessageContaining("otherJobId");
    }
}
