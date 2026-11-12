package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
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
import java.util.OptionalLong;
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
 *   <li>maximise the number of scheduled jobs, then (as a lower-order term of the same weight) how many
 *       of them are CRITICAL, then HIGH, and so on, so priority decides who gets a contested slot even
 *       when the lower-priority job would be cheaper;</li>
 *   <li>with that fixed, minimise total cost, plus tiny terms that prefer scheduling jobs earlier
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

    /**
     * Pass-1 weight of scheduling {@code job}: a base large enough that one more job always beats any mix of
     * priorities, plus {@code (n+1)^k} by priority class (LOW 1 ... CRITICAL (n+1)^3), so one more job of a
     * higher class beats any number of a lower class. ponytail: integers up to roughly n^4, fine for the
     * ~100 jobs this solver is ever given (HybridAllocationSolver routes bigger runs to the heuristic);
     * rank-weights below are still linear within a class, so a rare capacity-blocked tie there is arbitrary
     * but deterministic.
     */
    private static double placementWeight(OptimizerJob job, int jobCount) {
        double base = jobCount + 1.0;
        return jobCount * Math.pow(base, 3) + 1.0
                + Math.pow(base, JobPriority.values().length - 1 - job.priority().ordinal());
    }

    public ExactAllocationSolver(EnergyCostModel costs) {
        this.costs = costs;
    }

    /**
     * The best plan found within {@code budget}, or empty if no feasible plan was found in time.
     * {@code provenOptimal} only covers this call's own model: when the problem has a
     * {@link SynchronizedFinishConstraint}, that group's placement comes from a separate,
     * independently-greedy pre-pass ({@link SynchronizedGroupPlacer}), so a proven-optimal result is
     * optimal given the group's placement, not jointly optimal across it and the rest of the run.
     */
    public Optional<AllocationResult> solve(OptimizationProblem problem, Duration budget) {
        return solve(problem, budget, Integer.MAX_VALUE);
    }

    /**
     * As {@link #solve(OptimizationProblem, Duration)}, but each pass also stops after {@code iterationLimit}
     * solver iterations (branch-and-bound nodes). Unlike the time budget, this stops at the same point on
     * every machine, so callers that need repeatable results (the backtest) rely on it and keep the budget
     * out of reach.
     */
    public Optional<AllocationResult> solve(OptimizationProblem problem, Duration budget, int iterationLimit) {
        SynchronizedGroupPlacer.Placement groups = SynchronizedGroupPlacer.place(problem, costs);
        List<OptimizerJob> flexible = problem.flexibleJobsInTieBreakOrder().stream()
                .filter(job -> !groups.groupedJobIds().contains(job.id()))
                .toList();
        if (flexible.isEmpty()) {
            return Optional.of(AllocationResult.of(problem, groups.plans(), true));
        }
        long deadline = System.nanoTime() + budget.toNanos();

        Model countPass = new Model(problem, flexible, OptionalLong.empty(), groups.plans());
        Optimisation.Result counted = countPass.minimise(remainingMillis(deadline), iterationLimit);
        if (!counted.getState().isFeasible()) {
            return Optional.empty();
        }
        boolean countProven = counted.getState().isOptimal();
        long scheduled = Math.round(countPass.scheduledWeight(counted));

        long left = remainingMillis(deadline);
        if (left <= 0) {
            return Optional.of(countPass.extract(counted, false));
        }
        Model costPass = new Model(problem, flexible, OptionalLong.of(scheduled), groups.plans());
        Optimisation.Result costed = costPass.minimise(left, iterationLimit);
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
        private final Map<OptimizerJob, JobPlan> groupPlans;
        private final ExpressionsBasedModel model = new ExpressionsBasedModel();
        private final List<JobVars> vars = new ArrayList<>();
        private final Map<Long, TreeMap<LocalDateTime, Variable>> runningByJob = new HashMap<>();
        private int expressions;

        Model(OptimizationProblem problem, List<OptimizerJob> flexible, OptionalLong fixedCount,
             Map<OptimizerJob, JobPlan> groupPlans) {
            this.problem = problem;
            this.groupPlans = groupPlans;
            // One thread: ojAlgo's branch and bound is multi-threaded by default, which would break determinism.
            model.options.parallelism(1);
            model.options.integer(IntegerStrategy.DEFAULT.withGapTolerance(NumberContext.of(10)));

            boolean costPass = fixedCount.isPresent();
            double costScale = problem.costScale(costs);
            PriceSeries prices = problem.prices();
            ResidualCapacity free = problem.residualAfterExemptJobs();
            groupPlans.values().forEach(free::reserve);
            Map<LocalDateTime, Expression> cpuAt = new TreeMap<>();
            Map<LocalDateTime, Expression> gpuAt = new TreeMap<>();
            Expression count = costPass ? expression().level(fixedCount.getAsLong()) : null;

            for (int rank = 0; rank < flexible.size(); rank++) {
                addJobVariables(flexible.get(rank), rank, flexible.size(), costPass, costScale, prices, free,
                        cpuAt, gpuAt, count);
            }

            applyDeclaredConstraints();
        }

        /**
         * Builds job {@code rank}'s MIP variables and expressions: its "scheduled" binary {@code p},
         * then one {@code s}/{@code e}/{@code a}/{@code x} group per candidate hour, wired into the
         * flow constraint and into the capacity-at-hour ({@code cpuAt}/{@code gpuAt}) and
         * running-variable ({@code runningByJob}) state shared across every job in this pass.
         */
        private void addJobVariables(OptimizerJob job, int rank, int flexibleCount, boolean costPass,
                                     double costScale, PriceSeries prices, ResidualCapacity free,
                                     Map<LocalDateTime, Expression> cpuAt, Map<LocalDateTime, Expression> gpuAt,
                                     Expression count) {
            JobWindow window = problem.windowFor(job);
            for (PlacementConstraint constraint : problem.constraintsFor(job)) {
                window = constraint.restrict(job, window, Map.of());
            }
            Set<LocalDateTime> starts = new HashSet<>(window.startSlots());
            List<LocalDateTime> hours = new ArrayList<>();
            // From the window's first slot, not the job's original start: an hour that has already
            // passed can never be used, so it gets no variables at all rather than variables pinned
            // to zero. Keep this in step with HybridAllocationSolver.size(), which predicts the count.
            for (LocalDateTime t = window.firstSlot(); !t.isAfter(window.lastSlot()); t = t.plusHours(1)) {
                hours.add(t);
            }

            double weight = placementWeight(job, flexibleCount);
            Variable p = model.newVariable("p" + rank).binary()
                    .weight(costPass ? -RANK_WEIGHT * costScale * (flexibleCount - rank) : -weight);
            if (count != null) {
                count.set(p, weight);
            }
            if (hours.isEmpty() || starts.isEmpty()) {
                p.level(0);
                vars.add(new JobVars(job, hours, p, List.of(), List.of()));
                return;
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
                boolean usable = window.isUsable(t);
                String id = rank + "_" + k;
                Variable s = model.newVariable("s" + id).binary();
                Variable e = model.newVariable("e" + id).binary();
                Variable a = model.newVariable("a" + id).binary();
                Variable x = model.newVariable("x" + id).lower(0.0).upper(1.0);

                if (!starts.contains(t)) {
                    s.level(0);
                }
                if (!usable) {
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
                if (usable) {
                    runningByJob.computeIfAbsent(job.id(), key -> new TreeMap<>()).put(t, a);
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

        private void applyDeclaredConstraints() {
            RunningVariableLookup lookup = new RunningVariableLookup() {
                @Override
                public Optional<Variable> runningAt(long jobId, LocalDateTime hour) {
                    return Optional.ofNullable(runningByJob.getOrDefault(jobId, new TreeMap<>()).get(hour));
                }

                @Override
                public List<LocalDateTime> hoursOf(long jobId) {
                    return List.copyOf(runningByJob.getOrDefault(jobId, new TreeMap<>()).keySet());
                }
            };
            for (PlacementConstraint constraint : problem.constraints()) {
                constraint.applyToExactModel(model, lookup);
            }
        }

        private Expression expression() {
            return model.newExpression("c" + expressions++);
        }

        Optimisation.Result minimise(long timeLimitMillis, int iterationLimit) {
            model.options.time_abort = Math.max(1, timeLimitMillis);
            model.options.iterations_abort = Math.max(1, iterationLimit);
            return model.minimise();
        }

        double scheduledWeight(Optimisation.Result result) {
            return vars.stream()
                    .mapToDouble(v -> value(result, v.scheduled()) * placementWeight(v.job(), vars.size()))
                    .sum();
        }

        AllocationResult extract(Optimisation.Result result, boolean provenOptimal) {
            Map<OptimizerJob, JobPlan> plans = new HashMap<>(groupPlans);
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
