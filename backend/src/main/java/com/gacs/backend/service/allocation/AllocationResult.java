package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one run: a {@link JobOutcome} per input job, in input order.
 *
 * @param provenOptimal true when the exact solver proved no better plan exists; false for a heuristic plan,
 *                      which still meets every window, deadline and capacity rule
 */
public record AllocationResult(List<JobOutcome> outcomes, boolean provenOptimal) {

    /** Costs closer than this are treated as equal when ranking plans. Far below the 4 dp shown to users. */
    static final double COST_TOLERANCE = 1e-7;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public AllocationResult {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * Builds the result from the plans chosen for flexible jobs. Exempt jobs run as their baseline; a
     * flexible job without a plan is {@link PerJobStatus#NO_FEASIBLE_SLOT} with a reason.
     */
    static AllocationResult of(OptimizationProblem problem, Map<OptimizerJob, JobPlan> flexiblePlans,
                               boolean provenOptimal) {
        List<JobOutcome> outcomes = new ArrayList<>();
        Map<OptimizerJob, JobPlan> scheduled = new HashMap<>(flexiblePlans);
        problem.jobs().stream().filter(OptimizerJob::exempt).forEach(job -> scheduled.put(job, problem.baselineOf(job)));
        // Only built when a job has no plan: the heuristic solver calls this once per round.
        ResidualCapacity free = problem.jobs().stream().anyMatch(job -> !job.exempt() && !flexiblePlans.containsKey(job))
                ? residualAfter(problem, scheduled) : null;
        for (OptimizerJob job : problem.jobs()) {
            if (job.exempt()) {
                outcomes.add(new JobOutcome(job, problem.baselineOf(job), PerJobStatus.UNCHANGED_EXEMPT, null));
            } else if (flexiblePlans.containsKey(job)) {
                outcomes.add(new JobOutcome(job, flexiblePlans.get(job), PerJobStatus.OPTIMISED, null));
            } else {
                outcomes.add(new JobOutcome(job, null, PerJobStatus.NO_FEASIBLE_SLOT, noRoomReason(job, problem, scheduled, free)));
            }
        }
        return new AllocationResult(outcomes, provenOptimal);
    }

    /** Every scheduled job (exempt included) and its plan, in input order. */
    public Map<OptimizerJob, JobPlan> plans() {
        Map<OptimizerJob, JobPlan> plans = new LinkedHashMap<>();
        outcomes.forEach(outcome -> outcome.planIfScheduled().ifPresent(plan -> plans.put(outcome.job(), plan)));
        return plans;
    }

    public int scheduledCount() {
        return (int) outcomes.stream().filter(JobOutcome::scheduled).count();
    }

    /** Raw (unrounded) total cost of every scheduled job, exempt included. */
    public double costUsd(EnergyCostModel costs, PriceSeries prices) {
        return plans().values().stream().mapToDouble(plan -> costs.costUsd(plan, prices)).sum();
    }

    /**
     * True when {@code candidate} is better than {@code incumbent} under the contract's objective: more jobs
     * scheduled, then the plan that serves higher priorities (more CRITICAL jobs, then more HIGH, ...), then
     * lower cost, then (all equal) the plan that schedules the job that comes first in
     * {@link OptimizationProblem#TIE_BREAK_ORDER} where the two differ.
     */
    static boolean isBetter(AllocationResult candidate, AllocationResult incumbent, OptimizationProblem problem,
                            EnergyCostModel costs) {
        if (incumbent == null) {
            return true;
        }
        int count = Integer.compare(candidate.scheduledCount(), incumbent.scheduledCount());
        if (count != 0) {
            return count > 0;
        }
        int priority = Arrays.compare(OptimizationProblem.priorityProfile(candidate.plans().keySet()),
                OptimizationProblem.priorityProfile(incumbent.plans().keySet()));
        if (priority != 0) {
            return priority > 0;
        }
        double difference = candidate.costUsd(costs, problem.prices()) - incumbent.costUsd(costs, problem.prices());
        if (Math.abs(difference) > COST_TOLERANCE) {
            return difference < 0;
        }
        Map<OptimizerJob, JobPlan> mine = candidate.plans();
        Map<OptimizerJob, JobPlan> theirs = incumbent.plans();
        for (OptimizerJob job : problem.flexibleJobsInTieBreakOrder()) {
            if (mine.containsKey(job) != theirs.containsKey(job)) {
                return mine.containsKey(job);
            }
        }
        return false;
    }

    private static ResidualCapacity residualAfter(OptimizationProblem problem, Map<OptimizerJob, JobPlan> scheduled) {
        ResidualCapacity free = new ResidualCapacity(problem.capacity());
        scheduled.values().forEach(free::reserve);
        return free;
    }

    /**
     * Why a job got no plan. Five causes, in the order checked:
     * <ol>
     *   <li>the job's whole window has elapsed, so there is no hour left it may use at all - request
     *       validation permits this, because a job's baseline is not bounded by the current hour
     *       (see {@link OptimizationProblem#earliestStart()});</li>
     *   <li>it has no start slot: either every hour it could start in is behind the current hour, or
     *       its window lies beyond where the price forecast reaches;</li>
     *   <li>it has start slots, but too few hours remain before its deadline for the job to finish
     *       even alone on the cluster;</li>
     *   <li>the hours it may use are full, but free capacity exists in hours it cannot use for want of a
     *       forecast price (unpriced, or priced only by an estimate), so a forecast would unblock it;</li>
     *   <li>otherwise capacity taken by other jobs within the hours it was allowed to use.</li>
     * </ol>
     *
     * <p>Cause 3 has to be checked before the capacity causes. Admission ({@code OptimizationProblemFactory.build})
     * only guarantees a job fits alone between its <em>original</em> start and its deadline. Once that
     * start has passed - whether the job was re-anchored to its deadline or simply has a flexibility
     * window long enough to still reach the current hour - fewer hours are left, and the job can be
     * unplaceable with the cluster completely idle. Reporting that as "cluster full" would send the
     * operator to free up capacity that is not the problem.
     */
    private static String noRoomReason(OptimizerJob job, OptimizationProblem problem,
                                       Map<OptimizerJob, JobPlan> scheduled, ResidualCapacity free) {
        JobWindow window = problem.windowFor(job);
        LocalDateTime lastEnd = window.lastSlot().plusHours(1);
        if (window.firstSlot().isAfter(window.lastSlot())) {
            return "Deadline " + lastEnd.format(HOUR) + " UTC has passed: this job's whole window is "
                    + "earlier than " + window.earliestStart().format(HOUR)
                    + " UTC, the first hour that can still be scheduled. Update its start and deadline, "
                    + "or retire it.";
        }
        if (window.startSlots().isEmpty()) {
            // Two different causes land here, and the operator's remedy differs completely: the window
            // is behind us, or it is ahead of where the price forecast reaches.
            LocalDateTime latestStart = job.latestStart().isBefore(window.lastSlot())
                    ? job.latestStart() : window.lastSlot();
            if (window.firstSlot().isAfter(latestStart)) {
                return "Window elapsed: every hour this job could start in is earlier than "
                        + window.earliestStart().format(HOUR) + " UTC, the first hour that can still be "
                        + "scheduled. Update its start time, or widen its flexibility window. Its "
                        + "deadline " + lastEnd.format(HOUR) + " UTC has not passed, so you can also "
                        + "re-run with \"reschedule jobs whose start time has passed\" to let it start "
                        + "anywhere from now until then.";
            }
            return "Beyond the price forecast: no hour this job could start in has a published price "
                    + "forecast yet, so there is no priced window to move it into. Its baseline is still "
                    + "costed from estimated prices. It becomes schedulable once the forecast reaches "
                    + window.firstSlot().format(HOUR) + " UTC.";
        }
        // Not enough hours left to finish, independently of what anyone else is using: checked against
        // full cluster capacity, so if this fires the cluster could be idle and the job still would
        // not fit. See this method's javadoc for why it must come before the capacity message.
        if (!new BaselineCalculator(problem.capacity()).runFrom(job, window.firstSlot()).finishesByDeadline()) {
            return "Not enough time left: only the hours from " + window.firstSlot().format(HOUR)
                    + " to " + lastEnd.format(HOUR) + " UTC remain before this job's deadline, which is "
                    + "less than it needs to finish even alone on the cluster. Extend its deadline, "
                    + "reduce its CPU/GPU hours, or retire it.";
        }
        // The hours it may use are full, but free capacity exists where the forecast does not reach.
        String noPrice = unpricedHoursReason(job, problem.prices(), window, free);
        if (noPrice != null) {
            return noPrice;
        }
        // The job that took the slot: the first (by tie-break order) of those running inside this job's window.
        List<OptimizerJob> blockers = scheduled.entrySet().stream()
                .filter(e -> e.getValue().allocations().stream().anyMatch(a ->
                        !a.slotStart().isBefore(window.firstSlot()) && a.slotStart().isBefore(lastEnd)))
                .map(Map.Entry::getKey)
                .sorted(OptimizationProblem.TIE_BREAK_ORDER)
                .toList();
        String took = blockers.isEmpty() ? "other jobs use"
                : "job " + blockers.get(0).label() + " (" + blockers.get(0).priority() + ")"
                + (blockers.size() > 1 ? " and " + (blockers.size() - 1) + " other(s) use" : " uses");
        return "Cluster full between " + window.firstSlot().format(HOUR) + " and " + lastEnd.format(HOUR)
                + " UTC: " + took + " the capacity this job needs to finish by its deadline.";
    }

    /**
     * A "No forecast price" reason, or null when the cluster being full explains the job. The reason applies
     * when no run of priced hours has room for the job, yet a run that also uses unpriced hours would. The
     * free capacity is what the scheduled jobs left, counted with the same limits as {@link GreedyAllocator}.
     * ponytail: starts after the last priced hour are all treated as one start, so a capacity override in
     * that stretch is ignored; scan each of them if that ever matters.
     */
    private static String unpricedHoursReason(OptimizerJob job, PriceSeries prices, JobWindow window,
                                              ResidualCapacity free) {
        LocalDateTime first = window.firstSlot();
        LocalDateTime lastSlot = window.lastSlot();
        List<LocalDateTime> priced = prices.asMap().keySet().stream()
                .filter(hour -> !hour.isBefore(first) && !hour.isAfter(lastSlot) && prices.isCandidateAt(hour))
                .sorted().toList();
        if (priced.size() >= Duration.between(first, lastSlot).toHours() + 1) {
            return null; // every hour of the window has a price
        }
        LocalDateTime scanEnd = priced.isEmpty() ? first : priced.getLast().plusHours(1);
        for (LocalDateTime limit : List.of(job.latestStart(), lastSlot)) {
            scanEnd = limit.isBefore(scanEnd) ? limit : scanEnd;
        }
        LocalDateTime gapFrom = null;
        LocalDateTime gapTo = null;
        for (LocalDateTime start = first; !start.isAfter(scanEnd); start = start.plusHours(1)) {
            double totalCap = 0.0;
            int count = 0;
            LocalDateTime runGapFrom = null;
            LocalDateTime runGapTo = null;
            for (LocalDateTime hour = start; !hour.isAfter(lastSlot); hour = hour.plusHours(1)) {
                double cap = Math.min(1.0, free.freeAt(hour).maxShare(job));
                if (cap < AllocationSolver.MIN_SHARE || (count + 1) * AllocationSolver.MIN_SHARE > 1.0) {
                    break; // a run cannot pass through a full hour
                }
                count++;
                totalCap += cap;
                if (!prices.isCandidateAt(hour)) {
                    runGapFrom = runGapFrom == null ? hour : runGapFrom;
                    runGapTo = hour;
                }
                if (totalCap >= 1.0 - GreedyAllocator.DONE_TOLERANCE) {
                    if (runGapFrom == null) {
                        return null; // priced hours alone have room, so other jobs are the cause
                    }
                    if (gapFrom == null) {
                        gapFrom = runGapFrom;
                        gapTo = runGapTo;
                    }
                    break;
                }
            }
        }
        if (gapFrom == null) {
            return null;
        }
        return "No forecast price between " + gapFrom.format(HOUR) + " and " + gapTo.plusHours(1).format(HOUR)
                + " UTC: the priced hours in this job's window have too little free capacity, and it could only "
                + "finish by also using hours the forecast does not cover.";
    }
}
