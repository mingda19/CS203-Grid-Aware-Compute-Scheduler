package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.cpuCapacity;
import static com.gacs.backend.service.allocation.Fixtures.exempt;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/** Routing between the exact solver and the heuristic. */
class HybridAllocationSolverTest {

    private static final OptimizationProblem SMALL = problem(
            List.of(job(1, 200.0, 0.0, 0, 2, 4), job(2, 200.0, 0.0, 0, 2, 4), exempt(3, 100.0, 0.0, 1, 2)),
            prices(30, 10, 20, 40), cpuCapacity(256.0));

    @Test
    @DisplayName("Small runs go to the exact solver and come back proven optimal")
    void smallRunUsesExactSolver() {
        AllocationResult result = new HybridAllocationSolver(COSTS).solve(SMALL);

        assertThat(result.provenOptimal()).isTrue();
        assertThat(ContractRules.violations(SMALL, result)).isEmpty();
    }

    @Test
    @DisplayName("Runs above the size threshold skip the exact solver and are not marked proven")
    void largeRunUsesHeuristic() {
        HybridAllocationSolver hybrid = new HybridAllocationSolver(COSTS, 0, Duration.ofSeconds(3),
                new AnnealingAllocationSolver(COSTS, 100));
        AllocationResult result = hybrid.solve(SMALL);

        assertThat(result.provenOptimal()).isFalse();
        assertThat(ContractRules.violations(SMALL, result)).isEmpty();
        assertThat(result.scheduledCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Size counts each flexible job's hours from its start to its deadline")
    void sizeCountsFlexibleHours() {
        assertThat(HybridAllocationSolver.size(SMALL)).isEqualTo(4 + 4); // the exempt job does not count
    }
}
