package com.gacs.backend.service;

import com.gacs.backend.dto.ExplanationResponseDto;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.OptimizationFailedException;
import com.gacs.backend.model.OptimizerRunStatus;
import com.gacs.backend.model.ScheduleExplanation;
import com.gacs.backend.model.ScheduleRun;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.repository.ScheduleRunRepository;
import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.JobOutcome;
import com.gacs.backend.service.allocation.JobPlan;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.service.allocation.OptimizationProblemFactory;
import com.gacs.backend.service.allocation.PerJobStatus;
import com.gacs.backend.service.allocation.ScheduleRunRecorder;
import com.gacs.backend.service.allocation.SlotAllocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * One optimisation run (docs/api-contract.md section 6.4): validate the request into a problem,
 * solve it, price the result against each job's baseline, then persist it via
 * {@link ScheduleRunRecorder}. Also serves back the persisted explanation for a run
 * ({@link #getExplanation}) - it does not re-run optimisation or regenerate anything, only reads
 * what {@link ScheduleRunRecorder} already wrote.
 *
 * <p>{@link #optimize(OptimizeRequestDto, String, BiConsumer)} is deliberately not
 * {@code @Transactional}:
 * {@link ScheduleRunRecorder#record} itself holds no single transaction spanning this whole call
 * (it brackets an external LLM call that must never hold a database connection - see
 * {@code ScheduleRunPersistence}'s javadoc). Adding {@code @Transactional} here would defeat that
 * by Spring's REQUIRED propagation folding both of {@code ScheduleRunPersistence}'s short
 * transactions back into one that spans the HTTP call again. Nothing else in that method touches
 * the database in a way that needs transactional wrapping. {@link #getExplanation} has no such
 * concern - it is a plain read, so it is {@code @Transactional(readOnly = true)} as usual.
 */
@Service
public class ScheduleOptimizationService {

    private final OptimizationProblemFactory problems;
    private final AllocationSolver solver;
    private final EnergyCostModel costs;
    private final ScheduleRunRecorder recorder;
    private final ScheduleRunRepository runs;
    private final ScheduleExplanationRepository explanations;

    public ScheduleOptimizationService(OptimizationProblemFactory problems, AllocationSolver solver,
                                       EnergyCostModel costs, ScheduleRunRecorder recorder,
                                       ScheduleRunRepository runs, ScheduleExplanationRepository explanations) {
        this.problems = problems;
        this.solver = solver;
        this.costs = costs;
        this.recorder = recorder;
        this.runs = runs;
        this.explanations = explanations;
    }

    /**
     * @throws IllegalArgumentException invalid input (400)
     * @throws com.gacs.backend.exception.UnprocessableDataException baseline hours without a price (422)
     */
    /**
     * The {@code afterExplanationPersisted} hook runs inside the same transaction
     * {@link ScheduleRunRecorder} commits the explanation in - see {@code
     * ScheduleRunPersistence.finalizeExplanation}'s javadoc. {@code ScheduleReviewService} uses this
     * to record its own review-plan row atomically with the explanation, instead of in a separate
     * transaction that could leave one without the other if it failed. A caller with nothing to do
     * there passes a no-op. The hook also receives {@link OptimizationProblem#snapshot()} of the
     * inputs this run used (GRID-141).
     */
    public OptimizeResponseDto optimize(OptimizeRequestDto request, String ownerEmail,
                                        BiConsumer<OptimizeResponseDto, String> afterExplanationPersisted) {
        return optimize(request, java.util.Map.of(), ownerEmail, afterExplanationPersisted);
    }

    /** As above, solving against the facility less what approved plans use ({@code reserved}, by hour). */
    public OptimizeResponseDto optimize(OptimizeRequestDto request,
                                        java.util.Map<java.time.LocalDateTime, com.gacs.backend.service.allocation.CapacityLimit> reserved,
                                        String ownerEmail,
                                        BiConsumer<OptimizeResponseDto, String> afterExplanationPersisted) {
        OptimizationProblem problem = problems.build(request, reserved);
        AllocationResult result;
        try {
            result = solver.solve(problem);
        } catch (RuntimeException failure) {
            // Record the failure (no plan, so nothing to approve or export) and tell the caller its run id.
            String runId = UUID.randomUUID().toString();
            runs.save(ScheduleRun.builder().runId(runId).ownerEmail(ownerEmail)
                    .status(OptimizerRunStatus.FAILED.name()).provenOptimal(false).usedSeededFallbackPrices(problem.prices().usedEstimatedPrices()).build());
            throw new OptimizationFailedException(runId, problem.snapshot(), failure);
        }
        OptimizeResponseDto response = buildResponse(problem, result);
        String snapshot = problem.snapshot();
        recorder.record(result, problem.constraints(), problem.synchronizedGroups(), response, ownerEmail,
                persisted -> afterExplanationPersisted.accept(persisted, snapshot));
        return response;
    }

    /**
     * Returns the explanation saved when the run was created - the same text the operator already
     * saw in {@code OptimizeResponseDto.plainEnglishExplanation} when there is no LLM configured,
     * or the LLM's narrative when there is. Scoped to the run's owner: a run id that exists but
     * belongs to someone else is reported the same as one that does not exist at all, so this
     * never confirms another operator's run id.
     *
     * @throws ResourceNotFoundException no such run for this owner, or no explanation persisted for it
     */
    @Transactional(readOnly = true)
    public ExplanationResponseDto getExplanation(String runId, String ownerEmail) {
        ScheduleRun run = runs.findByRunId(runId)
                .filter(candidate -> candidate.getOwnerEmail().equals(ownerEmail))
                .orElseThrow(() -> new ResourceNotFoundException("Explanation not found for run: " + runId));
        ScheduleExplanation explanation = explanations.findByScheduleRunId(run.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Explanation not found for run: " + runId));
        return new ExplanationResponseDto(run.getRunId(), explanation.getNarrative(), recorder.jobExplanations(run.getId()));
    }

    // ------------------------------------------------------------------ response

    private OptimizeResponseDto buildResponse(OptimizationProblem problem, AllocationResult result) {
        List<ScheduleItem> items = new ArrayList<>();
        double baselineTotal = 0.0;
        double optimizedTotal = 0.0;
        for (JobOutcome outcome : result.outcomes()) {
            ScheduleItem item = item(outcome, problem);
            items.add(item);
            if (item.baselineCostUsd() != null) {
                baselineTotal += item.baselineCostUsd();
                optimizedTotal += item.optimizedCostUsd();
            }
        }
        // Totals are sums of the already-rounded item costs, rounded again only to remove float dust.
        double baselineCostUsd = EnergyCostModel.roundUsd(baselineTotal);
        double optimizedCostUsd = EnergyCostModel.roundUsd(optimizedTotal);
        double savingsUsd = EnergyCostModel.roundUsd(baselineCostUsd - optimizedCostUsd);
        double savingsPercent = baselineCostUsd == 0.0 ? 0.0 : roundPercent(savingsUsd / baselineCostUsd * 100.0);

        boolean anyLeftOut = result.outcomes().stream().anyMatch(o -> o.status() == PerJobStatus.NO_FEASIBLE_SLOT);
        return new OptimizeResponseDto(
                UUID.randomUUID().toString(),
                !anyLeftOut ? OptimizeResponseDto.STATUS_SCHEDULED
                        : result.outcomes().stream().noneMatch(JobOutcome::scheduled)
                        ? OptimizeResponseDto.STATUS_INFEASIBLE : OptimizeResponseDto.STATUS_NEEDS_ATTENTION,
                result.provenOptimal(),
                baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent,
                explain(result, problem, baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent),
                problem.prices().usedEstimatedPrices(),
                List.copyOf(items));
    }

    private ScheduleItem item(JobOutcome outcome, OptimizationProblem problem) {
        var job = outcome.job();
        if (!outcome.scheduled()) {
            return new ScheduleItem(job.id(), job.name(), job.originalStartTime(), null, null, 0.0, 0.0, List.of(),
                    outcome.status().name(), outcome.reason(), null, null, null);
        }
        JobPlan plan = outcome.plan();
        List<Allocation> allocations = new ArrayList<>();
        for (SlotAllocation slot : plan.allocations()) {
            allocations.add(new Allocation(slot.slotStart(), slot.cpuHours(job), slot.gpuHours(job)));
        }
        double baseline = EnergyCostModel.roundUsd(costs.costUsd(problem.baselineOf(job), problem.prices()));
        double optimized = EnergyCostModel.roundUsd(costs.costUsd(plan, problem.prices()));
        return new ScheduleItem(job.id(), job.name(), job.originalStartTime(), plan.start(), plan.end(),
                job.cpuHours(), job.gpuHours(), List.copyOf(allocations), outcome.status().name(), null,
                baseline, optimized, EnergyCostModel.roundUsd(baseline - optimized),
                // Set only for a job the factory actually re-anchored, which it does solely for jobs
                // whose originalStartTime had already passed in a run that opted in. So the flag
                // means "this job moved because its start had elapsed", not merely "the run allowed
                // it" - which is what lets the UI separate those jobs from ones moved to save money.
                job.rescheduledFromElapsedStart());
    }

    private static double roundPercent(double percent) {
        return BigDecimal.valueOf(percent).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ explanation

    /** Operator-facing summary. Deterministic for a given result. */
    private static String explain(AllocationResult result, OptimizationProblem problem, double baselineCostUsd,
                                  double optimizedCostUsd, double savingsUsd, double savingsPercent) {
        JobCounts counts = JobCounts.of(result, problem);
        return countsSentence(counts)
                + costSentence(baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent)
                + caveatsSentence(result, problem);
    }

    /**
     * How many jobs landed in each outcome, computed once and handed to each sentence below.
     *
     * @param elapsed of the {@code leftOut} jobs, how many had no start slot left at all because their
     *                window ended before the run's earliest schedulable hour. Counted separately
     *                because the summary must not blame capacity for a job that time ran out on - the
     *                operator's remedy is completely different (contract FR-20).
     * @param rescheduledFromElapsedStart of the scheduled jobs, how many were placed only because the
     *                run opted in to rescheduling jobs whose start had already passed. Reported
     *                separately from {@code moved}: those jobs moved because their start had elapsed,
     *                outside the flexibility window the operator originally gave, and an operator
     *                reading "N moved from their original time" should not have to guess which of
     *                them moved for that reason rather than to find a cheaper hour.
     */
    private record JobCounts(long total, long optimised, long exempt, long leftOut, long elapsed, long moved,
                             long rescheduledFromElapsedStart) {
        static JobCounts of(AllocationResult result, OptimizationProblem problem) {
            long moved = result.outcomes().stream()
                    .filter(o -> o.status() == PerJobStatus.OPTIMISED)
                    .filter(o -> !o.plan().start().equals(o.job().originalStartTime())
                            || !o.plan().allocations().equals(problem.baselineOf(o.job()).allocations()))
                    .count();
            long elapsed = result.outcomes().stream()
                    .filter(o -> o.status() == PerJobStatus.NO_FEASIBLE_SLOT)
                    .filter(o -> problem.windowFor(o.job()).startSlots().isEmpty())
                    .count();
            long rescheduled = result.outcomes().stream()
                    .filter(JobOutcome::scheduled)
                    .filter(o -> o.job().rescheduledFromElapsedStart())
                    .count();
            return new JobCounts(result.outcomes().size(), count(result, PerJobStatus.OPTIMISED),
                    count(result, PerJobStatus.UNCHANGED_EXEMPT), count(result, PerJobStatus.NO_FEASIBLE_SLOT),
                    elapsed, moved, rescheduled);
        }

        /** Jobs left out for want of capacity rather than for want of time. */
        long blockedByCapacity() {
            return leftOut - elapsed;
        }
    }

    private static String countsSentence(JobCounts counts) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Scheduled %d of %d job(s). ",
                counts.total() - counts.leftOut(), counts.total()));
        if (counts.optimised() > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d job(s) were placed on their cheapest plan (%d moved from their original time). ",
                    counts.optimised(), counts.moved()));
        }
        if (counts.exempt() > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d exempt job(s) run at their original time. ", counts.exempt()));
        }
        if (counts.rescheduledFromElapsedStart() > 0) {
            // FR-20 / the operator's own framing: these jobs were placed under an explicitly
            // confirmed widening, so the summary says so plainly rather than letting them read as
            // ordinary cost optimisation. The caveat about their savings is deliberate - see the
            // note in OptimizeResponseDto.ScheduleItem.rescheduledFromElapsedStart.
            text.append(String.format(Locale.ROOT,
                    "%d job(s) had a start time that has already passed and were rescheduled into the "
                            + "hours between now and their deadline, as confirmed for this run; their "
                            + "savings are measured against a baseline that itself already partly "
                            + "elapsed. ", counts.rescheduledFromElapsedStart()));
        }
        if (counts.elapsed() > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d job(s) could not be placed because their scheduling window has already passed; "
                            + "they are left out of the cost totals. ", counts.elapsed()));
        }
        if (counts.blockedByCapacity() > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d job(s) could not be placed because other jobs use the capacity they need before their "
                            + "deadline; they are left out of the cost totals. ", counts.blockedByCapacity()));
        }
        return text.toString();
    }

    private static String costSentence(double baselineCostUsd, double optimizedCostUsd, double savingsUsd,
                                       double savingsPercent) {
        if (savingsUsd > 0) {
            return String.format(Locale.ROOT,
                    "Projected energy cost is $%.4f against $%.4f at the original times, saving $%.4f (%.2f%%). ",
                    optimizedCostUsd, baselineCostUsd, savingsUsd, savingsPercent);
        }
        if (savingsUsd < 0) {
            return String.format(Locale.ROOT,
                    "Projected energy cost is $%.4f, which is $%.4f more than the $%.4f baseline. The baseline "
                            + "prices each job alone on the cluster at its original time; these jobs cannot all "
                            + "run that way at once, so some had to use dearer hours. ",
                    optimizedCostUsd, -savingsUsd, baselineCostUsd);
        }
        return String.format(Locale.ROOT, "Projected energy cost is $%.4f, the same as at the original times. ",
                optimizedCostUsd);
    }

    private static String caveatsSentence(AllocationResult result, OptimizationProblem problem) {
        StringBuilder text = new StringBuilder();
        text.append("Every scheduled job finishes on or before its deadline.");
        if (!result.provenOptimal()) {
            text.append(" This plan comes from the heuristic search: it meets every rule but is not proven "
                    + "to be the cheapest.");
        }
        if (problem.prices().isSeeded()) {
            text.append(" Note: no stored price forecast covered this window, so a seeded fallback price curve "
                    + "was used for both the baseline and the plan.");
        } else if (problem.prices().usedEstimatedPrices()) {
            // FR-13: a plan resting on estimated prices is reported provisional, naming what was
            // missing, rather than presented as precise. The forecast reaches about a day out while a
            // job may be scheduled days ahead, so the later hours are estimates by necessity.
            int estimated = problem.prices().estimatedSlots().size();
            int total = problem.prices().asMap().size();
            text.append(String.format(Locale.ROOT,
                    " This plan is provisional: the stored forecast covered %d of %d hours in the window,"
                            + " and the remaining %d (from %s UTC onwards) were estimated from the"
                            + " history-based fallback curve. Costs and savings for any job touching those"
                            + " hours are estimates, not forecasts.",
                    total - estimated, total, estimated,
                    problem.prices().estimatedSlots().iterator().next()));
        }
        return text.toString();
    }

    private static long count(AllocationResult result, PerJobStatus status) {
        return result.outcomes().stream().filter(o -> o.status() == status).count();
    }
}
