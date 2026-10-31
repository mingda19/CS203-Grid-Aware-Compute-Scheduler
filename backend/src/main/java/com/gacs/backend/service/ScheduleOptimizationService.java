package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.dto.ExplanationResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.ScheduleExplanation;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.JobOutcome;
import com.gacs.backend.service.allocation.JobPlan;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.service.allocation.OptimizationProblemFactory;
import com.gacs.backend.service.allocation.PerJobStatus;
import com.gacs.backend.service.allocation.SlotAllocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One optimisation run (docs/api-contract.md section 6.4): validate the request into a problem, solve it,
 * and price the result against each job's baseline. The operator-facing explanation is stored by run ID.
 */
@Service
public class ScheduleOptimizationService {

    private final OptimizationProblemFactory problems;
    private final AllocationSolver solver;
    private final EnergyCostModel costs;
    private final ScheduleExplanationRepository explanations;

    public ScheduleOptimizationService(OptimizationProblemFactory problems, AllocationSolver solver,
                                       EnergyCostModel costs) {
        this(problems, solver, costs, null);
    }

    @Autowired
    public ScheduleOptimizationService(OptimizationProblemFactory problems, AllocationSolver solver,
                                       EnergyCostModel costs, ScheduleExplanationRepository explanations) {
        this.problems = problems;
        this.solver = solver;
        this.costs = costs;
        this.explanations = explanations;
    }

    /**
     * @throws IllegalArgumentException invalid input (400)
     * @throws com.gacs.backend.exception.UnprocessableDataException baseline hours without a price (422)
     */
    @Transactional(readOnly = true)
    public OptimizeResponseDto optimize(OptimizeRequestDto request) {
        return optimize(request, null);
    }

    @Transactional
    public OptimizeResponseDto optimize(OptimizeRequestDto request, String ownerEmail) {
        OptimizationProblem problem = problems.build(request);
        AllocationResult result = solver.solve(problem);
        OptimizeResponseDto response = buildResponse(problem, result);
        if (ownerEmail != null && explanations != null) {
            ScheduleExplanation explanation = new ScheduleExplanation();
            explanation.setRunId(response.runId());
            explanation.setOwnerEmail(ownerEmail);
            explanation.setExplanation(response.plainEnglishExplanation());
            explanations.save(explanation);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public ExplanationResponseDto getExplanation(String runId, String ownerEmail) {
        ScheduleExplanation explanation = explanations.findByRunIdAndOwnerEmail(runId, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Explanation not found for run: " + runId));
        return new ExplanationResponseDto(explanation.getRunId(), explanation.getExplanation());
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
                anyLeftOut ? OptimizeResponseDto.STATUS_NEEDS_ATTENTION : OptimizeResponseDto.STATUS_SCHEDULED,
                result.provenOptimal(),
                baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent,
                explain(result, problem, baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent),
                problem.prices().isSeeded(),
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
                baseline, optimized, EnergyCostModel.roundUsd(baseline - optimized));
    }

    private static double roundPercent(double percent) {
        return BigDecimal.valueOf(percent).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ explanation

    /** Operator-facing summary. Deterministic for a given result. */
    private static String explain(AllocationResult result, OptimizationProblem problem, double baselineCostUsd,
                                  double optimizedCostUsd, double savingsUsd, double savingsPercent) {
        long total = result.outcomes().size();
        long optimised = count(result, PerJobStatus.OPTIMISED);
        long exempt = count(result, PerJobStatus.UNCHANGED_EXEMPT);
        long leftOut = count(result, PerJobStatus.NO_FEASIBLE_SLOT);
        long moved = result.outcomes().stream()
                .filter(o -> o.status() == PerJobStatus.OPTIMISED)
                .filter(o -> !o.plan().start().equals(o.job().originalStartTime())
                        || !o.plan().allocations().equals(problem.baselineOf(o.job()).allocations()))
                .count();

        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Scheduled %d of %d job(s). ", total - leftOut, total));
        if (optimised > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d job(s) were placed on their cheapest plan (%d moved from their original time). ",
                    optimised, moved));
        }
        if (exempt > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d exempt job(s) run at their original time. ", exempt));
        }
        if (leftOut > 0) {
            text.append(String.format(Locale.ROOT,
                    "%d job(s) could not be placed because other jobs use the capacity they need before their "
                            + "deadline; they are left out of the cost totals. ", leftOut));
        }

        if (savingsUsd > 0) {
            text.append(String.format(Locale.ROOT,
                    "Projected energy cost is $%.4f against $%.4f at the original times, saving $%.4f (%.2f%%). ",
                    optimizedCostUsd, baselineCostUsd, savingsUsd, savingsPercent));
        } else if (savingsUsd < 0) {
            text.append(String.format(Locale.ROOT,
                    "Projected energy cost is $%.4f, which is $%.4f more than the $%.4f baseline. The baseline "
                            + "prices each job alone on the cluster at its original time; these jobs cannot all "
                            + "run that way at once, so some had to use dearer hours. ",
                    optimizedCostUsd, -savingsUsd, baselineCostUsd));
        } else {
            text.append(String.format(Locale.ROOT,
                    "Projected energy cost is $%.4f, the same as at the original times. ", optimizedCostUsd));
        }

        text.append("Every scheduled job finishes on or before its deadline.");
        if (!result.provenOptimal()) {
            text.append(" This plan comes from the heuristic search: it meets every rule but is not proven "
                    + "to be the cheapest.");
        }
        if (problem.prices().isSeeded()) {
            text.append(" Note: no stored price forecast covered this window, so a seeded fallback price curve "
                    + "was used for both the baseline and the plan.");
        }
        return text.toString();
    }

    private static long count(AllocationResult result, PerJobStatus status) {
        return result.outcomes().stream().filter(o -> o.status() == status).count();
    }
}
