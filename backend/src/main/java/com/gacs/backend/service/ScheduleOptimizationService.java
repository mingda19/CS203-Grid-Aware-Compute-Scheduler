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
import com.gacs.backend.service.allocation.UnscheduledReason;
import com.gacs.backend.service.allocation.ScheduleRunRecorder;
import com.gacs.backend.service.allocation.SlotAllocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;
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
        OptimizationProblem problem = problems.build(request);
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

    /**
     * The run summary an operator reads above the plan, as a point-form list.
     *
     * <p>Deliberately deterministic and assembled here from the run's own numbers. An LLM used to
     * write this; it was removed because the solver consumes most of the request budget and the
     * call that followed it timed out on every run that reached a model, so the fallback below was
     * what operators actually saw anyway - while still costing a provider round trip to discover.
     *
     * <p>One line per fact, and one line per <em>kind</em> of problem rather than per job, since a
     * run that leaves eleven jobs out for two different reasons should read as two lines, not
     * eleven. The closing line is unconditional: every figure above it is derived from a price
     * forecast, and a projection must never be read as a settled number (contract FR-26).
     */
    private static String explain(AllocationResult result, OptimizationProblem problem, double baselineCostUsd,
                                  double optimizedCostUsd, double savingsUsd, double savingsPercent) {
        JobCounts counts = JobCounts.of(result, problem);
        List<String> lines = new ArrayList<>();

        lines.add(String.format(Locale.ROOT, "- Scheduled %d/%d jobs",
                counts.total() - counts.leftOut(), counts.total()));
        if (counts.optimised() > 0) {
            lines.add(String.format(Locale.ROOT, "- %d %s placed on the cheapest available slots (%d moved)",
                    counts.optimised(), jobWord(counts.optimised()), counts.moved()));
        }
        if (counts.exempt() > 0) {
            lines.add(String.format(Locale.ROOT, "- %d exempt %s run at the original time",
                    counts.exempt(), jobWord(counts.exempt())));
        }
        if (counts.rescheduledFromElapsedStart() > 0) {
            // Their baseline partly elapsed before the run, so the saving is not like-for-like.
            long n = counts.rescheduledFromElapsedStart();
            lines.add(String.format(Locale.ROOT,
                    "- %d %s had a start time that has already passed and %s rescheduled into the hours"
                            + " between now and %s deadline, as confirmed for this run; %s savings are"
                            + " measured against a baseline that itself already partly elapsed",
                    n, jobWord(n), n == 1 ? "was" : "were", n == 1 ? "its" : "their",
                    n == 1 ? "its" : "their"));
        }
        unscheduledByReason(result).forEach((reason, count) ->
                lines.add(String.format(Locale.ROOT, "- %d %s could not be scheduled: %s",
                        count, jobWord(count), reason.plural())));
        lines.add(costLine(baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent));
        if (!result.provenOptimal()) {
            lines.add("- This plan comes from the heuristic search: it meets every rule but is not proven cheapest");
        }
        lines.add(pricingCaveat(problem));
        return String.join("\n", lines);
    }

    /**
     * How many jobs landed in each outcome, computed once and handed to the summary.
     *
     * <p>No longer splits the left-out jobs by cause: the summary groups those by
     * {@link UnscheduledReason} instead, which scales to every cause rather than the two this
     * record used to distinguish.
     */
    private record JobCounts(long total, long optimised, long exempt, long leftOut, long moved,
                             long rescheduledFromElapsedStart) {
        static JobCounts of(AllocationResult result, OptimizationProblem problem) {
            long moved = result.outcomes().stream()
                    .filter(o -> o.status() == PerJobStatus.OPTIMISED)
                    .filter(o -> !o.plan().start().equals(o.job().originalStartTime())
                            || !o.plan().allocations().equals(problem.baselineOf(o.job()).allocations()))
                    .count();
            // Counted apart from `moved`: these jobs moved because their start had already passed
            // and the operator opted in, not because a cheaper hour was found.
            long rescheduled = result.outcomes().stream()
                    .filter(o -> o.status() == PerJobStatus.OPTIMISED)
                    .filter(o -> o.job().rescheduledFromElapsedStart())
                    .count();
            return new JobCounts(result.outcomes().size(), count(result, PerJobStatus.OPTIMISED),
                    count(result, PerJobStatus.UNCHANGED_EXEMPT), count(result, PerJobStatus.NO_FEASIBLE_SLOT),
                    moved, rescheduled);
        }
    }

    /** How many jobs fell to each kind of problem, in the enum's own order so runs read alike. */
    private static Map<UnscheduledReason, Long> unscheduledByReason(AllocationResult result) {
        return result.outcomes().stream()
                .filter(outcome -> outcome.status() == PerJobStatus.NO_FEASIBLE_SLOT)
                .collect(Collectors.groupingBy(outcome -> UnscheduledReason.of(outcome.reason()),
                        () -> new EnumMap<>(UnscheduledReason.class), Collectors.counting()));
    }

    private static String jobWord(long count) {
        return count == 1 ? "job" : "jobs";
    }

    private static String costLine(double baselineCostUsd, double optimizedCostUsd, double savingsUsd,
                                   double savingsPercent) {
        if (savingsUsd > 0) {
            return String.format(Locale.ROOT,
                    "- Projected energy cost is $%.4f against $%.4f at the original times, saving $%.4f (%.2f%%)",
                    optimizedCostUsd, baselineCostUsd, savingsUsd, savingsPercent);
        }
        if (savingsUsd < 0) {
            // Not a failure: each baseline prices its job alone on the cluster, which several jobs
            // cannot all do at once, so a feasible plan can legitimately cost more than the sum of
            // baselines. Said plainly, or the number reads as the optimiser having made things worse.
            return String.format(Locale.ROOT,
                    "- Projected energy cost is $%.4f against $%.4f at the original times, $%.4f more"
                            + " (the baseline prices each job alone on the cluster, which they cannot all do at once)",
                    optimizedCostUsd, baselineCostUsd, -savingsUsd);
        }
        return String.format(Locale.ROOT,
                "- Projected energy cost is $%.4f, the same as at the original times", optimizedCostUsd);
    }

    /** The closing caveat, naming estimated hours when any were used (contract FR-13, FR-26). */
    private static String pricingCaveat(OptimizationProblem problem) {
        String base = "The above optimisation plan is based on forecasted prices; projected savings are"
                + " estimates and non-final.";
        if (problem.prices().isSeeded()) {
            return base + " No stored forecast covered this window, so a history-based fallback price curve"
                    + " was used throughout.";
        }
        if (problem.prices().usedEstimatedPrices()) {
            int estimated = problem.prices().estimatedSlots().size();
            int total = problem.prices().asMap().size();
            return base + String.format(Locale.ROOT,
                    " %d of %d hours in the window had no forecast and were estimated from history.",
                    estimated, total);
        }
        return base;
    }


    private static long count(AllocationResult result, PerJobStatus status) {
        return result.outcomes().stream().filter(o -> o.status() == status).count();
    }
}
