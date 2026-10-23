package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.optimisation.integer.IntegerStrategy;
import org.ojalgo.type.context.NumberContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact solver: models a run as a mixed-integer program and solves it with ojAlgo.
 *
 * <p>For each flexible job {@code j} and each hour {@code t} from its originalStartTime to the last hour
 * ending by its deadline:
 * <ul>
 *   <li>{@code p[j]} binary: the job is scheduled;</li>
 *   <li>{@code s[j,t]}, {@code e[j,t]} binary: the job starts / ends in {@code t};</li>
 *   <li>{@code a[j,t]} binary: the job is running in {@code t};</li>
 *   <li>{@code x[j,t]} in [0, 1]: the share of its work done in {@code t}.</li>
 * </ul>
 * Constraints: one start and one end if scheduled ({@code sum s = sum e = p}); all work done
 * ({@code sum x = p}); consecutive hours ({@code a[t] = a[t-1] + s[t] - e[t-1]}, {@code e <= a}, running
 * at the last hour means ending there); {@code MIN_SHARE * a <= x <= a}; starts only in the
 * flexibility window; unpriced hours unusable; and per hour, the sum of {@code x * cpuHours} (and GPU)
 * within the capacity left after exempt jobs.
 *
 * <p>The objective is solved in two passes so a huge "placement" weight never shares one objective
 * with cent-sized costs (the solver's gap tolerance could otherwise hide real cost differences):
 * <ol>
 *   <li>maximise the number of scheduled jobs;</li>
 *   <li>with that number fixed, minimise total cost, plus tiny terms that prefer scheduling jobs earlier
 *       in {@link OptimizationProblem#TIE_BREAK_ORDER} and then earlier starts.</li>
 * </ol>
 * Each pass is time-boxed. The result is proven optimal only when both passes finish optimal.
 */
public final class ExactAllocationSolver {

    /** Tie-break weight per rank step, as a fraction of the run's cost scale. */
    private static final double RANK_WEIGHT = 1e-7;
    /** Weight per hour of start offset, as a fraction of the run's cost scale. Below one rank step. */
    private static final double START_WEIGHT = 1e-11;

    private final EnergyCostModel costs;
    private final Duration timeBudget;

    public ExactAllocationSolver(EnergyCostModel costs, Duration timeBudget) {
        this.costs = costs;
        this.timeBudget = timeBudget;
    }

    /** The best plan found within the time budget, or empty if no feasible plan was found in time. */
    public Optional<AllocationResult> solve(OptimizationProblem problem) {
        List<OptimizerJob> flexible = problem.flexibleJobsInTieBreakOrder();
        if (flexible.isEmpty()) {
            return Optional.of(AllocationResult.of(problem, Map.of(), true));
        }
        long deadline = System.nanoTime() + timeBudget.toNanos();

        Model countPass = new Model(problem, flexible, OptionalInt.empty());
        Optimisation.Result counted = countPass.minimise(remainingMillis(deadline));
        if (!counted.getState().isFeasible()) {
            return Optional.empty();
        }
        boolean countProven = counted.getState().isOptimal();
        int scheduled = (int) Math.round(countPass.scheduledCount(counted));

        long left = remainingMillis(deadline);
        if (left <= 0) {
            return Optional.of(countPass.extract(counted, false));
        }
        Model costPass = new Model(problem, flexible, OptionalInt.of(scheduled));
        Optimisation.Result costed = costPass.minimise(left);
        if (!costed.getState().isFeasible()) {
            return Optional.of(countPass.extract(counted, false));
        }
        return Optional.of(costPass.extract(costed, countProven && costed.getState().isOptimal()));
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
    }

    private record JobVars(OptimizerJob job, List<LocalDateTime> hours, Variable scheduled,
                           List<Variable> running, List<Variable> shares) {
    }

    /** One pass's model. With {@code fixedCount} empty it maximises scheduled jobs; otherwise it minimises cost. */
    private final class Model {
        private final OptimizationProblem problem;
        private final ExpressionsBasedModel model = new ExpressionsBasedModel();
        private final List<JobVars> vars = new ArrayList<>();
        private int expressions;

        Model(OptimizationProblem problem, List<OptimizerJob> flexible, OptionalInt fixedCount) {
            this.problem = problem;
            // One thread: ojAlgo's branch and bound is multi-threaded by default, which would break determinism.
            model.options.parallelism(1);
            model.options.integer(IntegerStrategy.DEFAULT.withGapTolerance(NumberContext.of(10)));

            boolean costPass = fixedCount.isPresent();
            double costScale = problem.costScale(costs);
            PriceSeries prices = problem.prices();
            ResidualCapacity free = problem.residualAfterExemptJobs();
            Map<LocalDateTime, Expression> cpuAt = new TreeMap<>();
            Map<LocalDateTime, Expression> gpuAt = new TreeMap<>();
            Expression count = costPass ? expression().level(fixedCount.getAsInt()) : null;

            for (int rank = 0; rank < flexible.size(); rank++) {
                OptimizerJob job = flexible.get(rank);
                JobWindow window = new JobWindow(job, prices);
                Set<LocalDateTime> starts = new HashSet<>(window.startSlots());
                List<LocalDateTime> hours = new ArrayList<>();
                for (LocalDateTime t = job.originalStartTime(); !t.isAfter(window.lastSlot()); t = t.plusHours(1)) {
                    hours.add(t);
                }

                Variable p = model.newVariable("p" + rank).binary()
                        .weight(costPass ? -RANK_WEIGHT * costScale * (flexible.size() - rank) : -1.0);
                if (count != null) {
                    count.set(p, 1);
                }
                if (hours.isEmpty() || starts.isEmpty()) {
                    p.level(0);
                    vars.add(new JobVars(job, hours, p, List.of(), List.of()));
                    continue;
                }

                Expression startSum = expression().level(0).set(p, -1);
                Expression endSum = expression().level(0).set(p, -1);
                Expression shareSum = expression().level(0).set(p, -1);
                List<Variable> running = new ArrayList<>();
                List<Variable> shares = new ArrayList<>();
                Variable previousRunning = null;
                Variable previousEnd = null;

                for (int k = 0; k < hours.size(); k++) {
                    LocalDateTime t = hours.get(k);
                    Optional<Double> price = prices.priceAt(t);
                    String id = rank + "_" + k;
                    Variable s = model.newVariable("s" + id).binary();
                    Variable e = model.newVariable("e" + id).binary();
                    Variable a = model.newVariable("a" + id).binary();
                    Variable x = model.newVariable("x" + id).lower(0.0).upper(1.0);

                    if (!starts.contains(t)) {
                        s.level(0);
                    }
                    if (price.isEmpty()) {
                        a.level(0);
                        e.level(0);
                        x.level(0);
                    } else if (costPass) {
                        x.weight(costs.slotCostUsd(job, 1.0, price.get()));
                        s.weight(START_WEIGHT * costScale * k);
                    }

                    // a[t] = a[t-1] + s[t] - e[t-1]: a run is one consecutive block.
                    Expression flow = expression().level(0).set(a, 1).set(s, -1);
                    if (previousRunning != null) {
                        flow.set(previousRunning, -1).set(previousEnd, 1);
                    }
                    expression().upper(0).set(e, 1).set(a, -1);                                  // e <= a
                    expression().upper(0).set(x, 1).set(a, -1);                                  // x <= a
                    expression().lower(0).set(x, 1).set(a, -AllocationSolver.MIN_SHARE);         // x >= MIN_SHARE * a

                    startSum.set(s, 1);
                    endSum.set(e, 1);
                    shareSum.set(x, 1);
                    if (price.isPresent()) {
                        CapacityLimit limit = free.freeAt(t);
                        if (job.cpuHours() > 0.0) {
                            cpuAt.computeIfAbsent(t, slot -> expression().upper(limit.cpuHours())).set(x, job.cpuHours());
                        }
                        if (job.gpuHours() > 0.0) {
                            gpuAt.computeIfAbsent(t, slot -> expression().upper(limit.gpuHours())).set(x, job.gpuHours());
                        }
                    }
                    running.add(a);
                    shares.add(x);
                    previousRunning = a;
                    previousEnd = e;
                }
                // Still running in the last allowed hour means it must end there, not run past the deadline.
                expression().level(0).set(previousRunning, 1).set(previousEnd, -1);
                vars.add(new JobVars(job, hours, p, running, shares));
            }
        }

        private Expression expression() {
            return model.newExpression("c" + expressions++);
        }

        Optimisation.Result minimise(long timeLimitMillis) {
            model.options.time_abort = Math.max(1, timeLimitMillis);
            return model.minimise();
        }

        double scheduledCount(Optimisation.Result result) {
            return vars.stream().mapToDouble(v -> value(result, v.scheduled())).sum();
        }

        AllocationResult extract(Optimisation.Result result, boolean provenOptimal) {
            Map<OptimizerJob, JobPlan> plans = new HashMap<>();
            for (JobVars v : vars) {
                if (value(result, v.scheduled()) < 0.5) {
                    continue;
                }
                List<LocalDateTime> slots = new ArrayList<>();
                List<Double> raw = new ArrayList<>();
                double total = 0.0;
                for (int k = 0; k < v.running().size(); k++) {
                    if (value(result, v.running().get(k)) > 0.5) {
                        double share = Math.max(value(result, v.shares().get(k)), AllocationSolver.MIN_SHARE / 2);
                        slots.add(v.hours().get(k));
                        raw.add(share);
                        total += share;
                    }
                }
                List<SlotAllocation> allocations = new ArrayList<>();
                for (int k = 0; k < slots.size(); k++) {
                    allocations.add(new SlotAllocation(slots.get(k), raw.get(k) / total)); // remove solver float dust
                }
                plans.put(v.job(), new JobPlan(v.job(), allocations));
            }
            return AllocationResult.of(problem, plans, provenOptimal);
        }

        private static double value(Optimisation.Result result, Variable variable) {
            return result.doubleValue(variable.getIndex().index);
        }
    }
}
