package com.gacs.backend.service;

import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.PerJobStatus;
import com.gacs.backend.service.ScheduleSolver.SolverAssignment;
import com.gacs.backend.service.ScheduleSolver.SolverInput;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Exact solver for GRID-125: formulates one optimization run as a 0/1 MILP and solves it
 * with ojAlgo, which takes continuous demand/capacity coefficients natively (no fixed-point
 * scaling, unlike a finite-integer-domain CP solver).
 *
 * <p>Model: each flexible job gets a binary variable per candidate slot ({@link
 * CandidateSlots}) plus one "unplaced" slack variable; exactly one of those must be 1
 * ({@code sum_s x[j,s] + unplaced[j] == 1}). The slack is what lets one genuinely infeasible
 * job fall back to {@link PerJobStatus#UNCHANGED_NO_FEASIBLE_SLOT} without making the whole
 * model infeasible - a bare "every job needs a real slot" constraint has no way to express
 * that gracefully. Per-slot capacity is {@code sum x[j,s]*demand[j] <= max(0, capacity -
 * exemptReservedAtSlot(s))}; the {@code max(0, ...)} clamp matters because exempt jobs alone
 * can oversubscribe a slot (they are pinned regardless), and a negative upper bound would
 * make even the all-zero solution infeasible, breaking the whole model rather than just that
 * slot. The objective minimizes raw (unrounded) total cost, with the unplaced slack weighted
 * at the job's own baseline cost - "every flexible job takes its slack" is therefore always a
 * feasible point costing exactly the baseline total, which is why the true minimum can never
 * exceed it: this is what keeps {@code optimizedCostUsd <= baselineCostUsd} true for the real
 * global optimum, not just for the greedy stub's self-imposed per-job rule.
 *
 * <p>Two tiny tie-break terms are layered on top of the real costs so the result is
 * deterministic even when multiple assignments cost exactly the same: {@link #OFFSET_EPSILON}
 * prefers the earliest of equally-priced candidate slots (matching the stub's own documented
 * convention), and {@link #UNPLACED_EPSILON} (an order of magnitude larger, so it can never be
 * outweighed by the offset term) prefers a real placement over the unplaced slack whenever
 * both would cost the same - otherwise {@code UNCHANGED_NO_FEASIBLE_SLOT} could mean "the
 * solver happened to break a tie this way" instead of "truly infeasible." Both are far below
 * {@link CostModel}'s cent rounding, so neither ever surfaces in a reported dollar figure.
 */
public final class ExactScheduleSolver {

    private static final double UNPLACED_EPSILON = 1e-3;
    private static final double OFFSET_EPSILON = 1e-6;

    private final Duration timeBudget;

    public ExactScheduleSolver(Duration timeBudget) {
        this.timeBudget = timeBudget;
    }

    public record Outcome(SolverResult result, boolean certifiedOptimal) {
    }

    private record FlexibleJobVars(
        int jobIndex, List<LocalDateTime> slots, List<Variable> placementVars, Variable unplaced
    ) {
    }

    public Outcome solve(SolverInput input) {
        List<JobSpec> jobs = input.jobs();
        int n = jobs.size();
        SolverAssignment[] assignmentsByIndex = new SolverAssignment[n];

        // Exempt jobs are pinned and reserve capacity before anything flexible is modeled.
        java.util.Map<LocalDateTime, double[]> exemptReserved = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            JobSpec job = jobs.get(i);
            if (job.exempt()) {
                LocalDateTime slot = job.baselineSlot();
                double[] used = exemptReserved.computeIfAbsent(slot, k -> new double[] {0.0, 0.0});
                used[0] += job.cpuHours();
                used[1] += job.gpuHours();
                assignmentsByIndex[i] = new SolverAssignment(job, slot, PerJobStatus.UNCHANGED_EXEMPT);
            }
        }

        ExpressionsBasedModel model = new ExpressionsBasedModel();
        // Pinned to 1 thread: ojAlgo's branch-and-bound is multi-threaded by default, which
        // would make the "same input -> same output" guarantee ScheduleSolver requires
        // unreliable across runs.
        model.options.parallelism(1);
        model.options.time_abort = timeBudget.toMillis();

        java.util.Map<LocalDateTime, Expression> cpuExprAtSlot = new TreeMap<>();
        java.util.Map<LocalDateTime, Expression> gpuExprAtSlot = new TreeMap<>();
        List<FlexibleJobVars> flexibleVars = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            JobSpec job = jobs.get(i);
            if (job.exempt()) {
                continue;
            }
            List<LocalDateTime> slots = CandidateSlots.forJob(job, input);
            double baselinePrice = input.prices().priceAt(job.baselineSlot()).orElse(0.0);
            double baselineRawCost = CostModel.cost(job.energyKwh(), baselinePrice);

            Expression placement = model.newExpression("placement_" + i).level(1.0);
            List<Variable> placementVars = new ArrayList<>(slots.size());

            for (int k = 0; k < slots.size(); k++) {
                LocalDateTime slot = slots.get(k);
                double price = input.prices().priceAt(slot).orElseThrow();
                double rawCost = CostModel.cost(job.energyKwh(), price);
                long offsetHours = Duration.between(job.baselineSlot(), slot).toHours();

                Variable x = model.newVariable("x_" + i + "_" + k).binary()
                    .weight(rawCost + OFFSET_EPSILON * offsetHours);
                placementVars.add(x);
                placement.set(x, 1.0);

                double[] reserved = exemptReserved.getOrDefault(slot, new double[] {0.0, 0.0});
                cpuExprAtSlot.computeIfAbsent(slot, s -> model.newExpression("cpu_" + s)
                    .upper(Math.max(0.0, input.capacity().maxConcurrentCpuHours() - reserved[0])));
                gpuExprAtSlot.computeIfAbsent(slot, s -> model.newExpression("gpu_" + s)
                    .upper(Math.max(0.0, input.capacity().maxConcurrentGpuHours() - reserved[1])));
                cpuExprAtSlot.get(slot).set(x, job.cpuHours());
                gpuExprAtSlot.get(slot).set(x, job.gpuHours());
            }

            Variable unplaced = model.newVariable("u_" + i).binary()
                .weight(baselineRawCost + UNPLACED_EPSILON);
            placement.set(unplaced, 1.0);

            flexibleVars.add(new FlexibleJobVars(i, slots, placementVars, unplaced));
        }

        if (flexibleVars.isEmpty()) {
            // Nothing to optimize (every job is exempt, or there are no jobs at all) -
            // trivially optimal without involving ojAlgo at all.
            return new Outcome(new SolverResult(List.of(assignmentsByIndex), true), true);
        }

        Optimisation.Result result = model.minimise();
        if (!result.getState().isFeasible()) {
            // Should not happen by construction (the unplaced slack is always available and
            // consumes no capacity), but fail loudly rather than report a bogus schedule if
            // it ever does - the caller falls back to the heuristic path.
            throw new IllegalStateException("Exact solver returned " + result.getState()
                + " for a model that should always have a feasible point (the unplaced slack).");
        }

        for (FlexibleJobVars fv : flexibleVars) {
            JobSpec job = jobs.get(fv.jobIndex());
            LocalDateTime chosen = null;
            for (int k = 0; k < fv.placementVars().size(); k++) {
                if (result.doubleValue(fv.placementVars().get(k).getIndex().index) > 0.5) {
                    chosen = fv.slots().get(k);
                    break;
                }
            }
            PerJobStatus status;
            LocalDateTime slotStart;
            if (chosen == null) {
                slotStart = job.baselineSlot();
                status = PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT;
            } else {
                slotStart = chosen;
                status = chosen.equals(job.baselineSlot())
                    ? PerJobStatus.UNCHANGED_ALREADY_OPTIMAL
                    : PerJobStatus.RESCHEDULED;
            }
            assignmentsByIndex[fv.jobIndex()] = new SolverAssignment(job, slotStart, status);
        }

        boolean certifiedOptimal = result.getState().isOptimal();
        return new Outcome(new SolverResult(List.of(assignmentsByIndex), certifiedOptimal), certifiedOptimal);
    }
}
