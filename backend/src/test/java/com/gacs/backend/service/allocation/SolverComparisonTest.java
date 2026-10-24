package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static com.gacs.backend.service.allocation.Fixtures.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random small runs, each solved three ways: the exact solver, the brute-force checker (shares in
 * quarters) and the heuristic. The exact solver works with continuous shares, so it can only do as well
 * as or better than the checker; the heuristic can only do as well as or worse than the exact solver.
 */
class SolverComparisonTest {

    private static final int CASES = 120;
    private static final int HOURS = 6;
    private static final double COST_SLACK = 1e-6;

    private record Case(OptimizationProblem problem, int index) {
    }

    @Test
    @DisplayName("Exact solver is never beaten by the brute-force checker, and both follow every rule")
    void exactAtLeastAsGoodAsBruteForce() {
        ExactAllocationSolver exact = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
        int withJobLeftOut = 0;
        int withMultiHourRun = 0;
        for (Case c : cases()) {
            OptimizationProblem problem = c.problem();
            AllocationResult result = exact.solve(problem).orElseThrow();
            if (result.scheduledCount() < problem.jobs().size()) {
                withJobLeftOut++;
            }
            if (result.plans().values().stream().anyMatch(plan -> plan.slotCount() > 1)) {
                withMultiHourRun++;
            }
            BruteForceAllocator.Result reference = BruteForceAllocator.solve(
                    problem.jobs(), problem.prices(), problem.capacity(), COSTS, 4);

            assertThat(result.provenOptimal()).as("case %d proven optimal", c.index()).isTrue();
            assertThat(ContractRules.violations(problem, result)).as("case %d exact rules", c.index()).isEmpty();
            assertThat(ContractRules.violations(problem.jobs(), ContractRules.plansOf(reference), problem.prices(),
                    problem.capacity())).as("case %d checker rules", c.index()).isEmpty();
            assertThat(result.scheduledCount()).as("case %d scheduled", c.index())
                    .isGreaterThanOrEqualTo(reference.placedCount());
            if (result.scheduledCount() == reference.placedCount()) {
                assertThat(result.costUsd(COSTS, problem.prices())).as("case %d cost", c.index())
                        .isLessThanOrEqualTo(reference.costUsd() + COST_SLACK);
            }
        }
        // The random runs must exercise the hard parts, not just easy single-hour placements.
        assertThat(withJobLeftOut).isGreaterThanOrEqualTo(5);
        assertThat(withMultiHourRun).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Heuristic always follows every rule and never beats the proven optimum")
    void heuristicFeasibleAndNoBetterThanExact() {
        ExactAllocationSolver exact = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
        AnnealingAllocationSolver heuristic = new AnnealingAllocationSolver(COSTS, 200);
        int matchedOptimum = 0;
        List<Case> cases = cases();
        for (Case c : cases) {
            OptimizationProblem problem = c.problem();
            AllocationResult best = exact.solve(problem).orElseThrow();
            AllocationResult guess = heuristic.solve(problem);

            assertThat(ContractRules.violations(problem, guess)).as("case %d heuristic rules", c.index()).isEmpty();
            assertThat(guess.scheduledCount()).as("case %d scheduled", c.index())
                    .isLessThanOrEqualTo(best.scheduledCount());
            if (guess.scheduledCount() == best.scheduledCount()) {
                double gap = guess.costUsd(COSTS, problem.prices()) - best.costUsd(COSTS, problem.prices());
                assertThat(gap).as("case %d cost gap", c.index()).isGreaterThanOrEqualTo(-COST_SLACK);
                if (gap < COST_SLACK) {
                    matchedOptimum++;
                }
            }
        }
        // Not a hard requirement, but on runs this small the heuristic should usually find the optimum.
        assertThat(matchedOptimum).isGreaterThan(cases.size() / 2);
    }

    @Test
    @DisplayName("The same problem always gives the same result")
    void deterministic() {
        AnnealingAllocationSolver heuristic = new AnnealingAllocationSolver(COSTS, 200);
        ExactAllocationSolver exact = new ExactAllocationSolver(COSTS, Duration.ofSeconds(10));
        for (Case c : cases().subList(0, 20)) {
            assertThat(heuristic.solve(c.problem())).isEqualTo(heuristic.solve(c.problem()));
            assertThat(exact.solve(c.problem())).isEqualTo(exact.solve(c.problem()));
        }
    }

    /** Valid random runs: every baseline finishes by its deadline and is priced, as the factory requires. */
    private static List<Case> cases() {
        Random random = new Random(20261023L);
        List<Case> cases = new ArrayList<>();
        while (cases.size() < CASES) {
            randomProblem(random).ifPresent(problem -> cases.add(new Case(problem, cases.size())));
        }
        return cases;
    }

    private static Optional<OptimizationProblem> randomProblem(Random random) {
        double[] perHour = new double[HOURS];
        for (int h = 0; h < HOURS; h++) {
            perHour[h] = random.nextInt(10) == 0 ? Double.NaN : 1 + random.nextInt(60);
        }
        PriceSeries prices = prices(perHour);

        CapacityLimit cluster = new CapacityLimit(100.0 * (1 + random.nextInt(3)), 16.0 * (1 + random.nextInt(4)));
        Map<java.time.LocalDateTime, CapacityLimit> overrides = new HashMap<>();
        if (random.nextBoolean()) {
            overrides.put(at(random.nextInt(HOURS)), new CapacityLimit(50.0 * random.nextInt(4), 8.0 * random.nextInt(4)));
        }
        SlotCapacity capacity = new SlotCapacity(cluster, overrides);

        int jobCount = 1 + random.nextInt(3);
        List<OptimizerJob> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            double cpu = 50.0 * random.nextInt(5);
            double gpu = 8.0 * random.nextInt(3);
            if (cpu == 0.0 && gpu == 0.0) {
                cpu = 50.0;
            }
            int start = random.nextInt(4);
            int deadline = start + 1 + random.nextInt(HOURS - start);
            boolean exempt = random.nextInt(6) == 0;
            int flex = exempt ? 0 : random.nextInt(3);
            jobs.add(new OptimizerJob((long) i + 1, "job-" + (i + 1), cpu, gpu, at(start), at(deadline), flex, exempt));
        }

        BaselineCalculator baselines = new BaselineCalculator(capacity);
        for (OptimizerJob job : jobs) {
            Optional<JobPlan> baseline = baselines.baseline(job).plan();
            if (baseline.isEmpty() || EnergyCostModel.firstUnpricedSlot(baseline.get().allocations(), prices).isPresent()) {
                return Optional.empty(); // the factory would reject this run with a 400 or 422
            }
        }
        return Optional.of(problem(jobs, prices, capacity));
    }
}
