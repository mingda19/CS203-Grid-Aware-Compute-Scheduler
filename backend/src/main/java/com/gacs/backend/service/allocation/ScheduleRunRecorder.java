package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleExplanation;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import com.gacs.backend.repository.ConstraintImpactRepository;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.repository.ScheduleItemRepository;
import com.gacs.backend.repository.ScheduleRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Persists one optimisation run (docs/schedule-explanation-design.md sections 1, 2.7, 4): the
 * run, its items, the DEADLINE/FLEXIBILITY_WINDOW constraint impacts the solver already enforces
 * today, and a deterministic-fallback explanation (no LLM client exists yet in Phase A, so
 * {@code source} is always {@code DETERMINISTIC_FALLBACK}).
 *
 * <p>Takes {@link AllocationResult} (for each job's raw attributes via {@code JobOutcome.job()})
 * and the already-built {@link OptimizeResponseDto} (for cost figures already rounded and
 * consistent with what the caller returns) - never {@link OptimizationProblem}, which this class
 * doesn't need. {@code result.outcomes()} and {@code response.items()} are always the same size,
 * in the same order: both are built by iterating the same list once.
 */
@Service
public class ScheduleRunRecorder {

    private final ScheduleRunRepository runs;
    private final ScheduleItemRepository items;
    private final ConstraintImpactRepository constraintImpacts;
    private final ScheduleExplanationRepository explanations;

    public ScheduleRunRecorder(ScheduleRunRepository runs, ScheduleItemRepository items,
                               ConstraintImpactRepository constraintImpacts,
                               ScheduleExplanationRepository explanations) {
        this.runs = runs;
        this.items = items;
        this.constraintImpacts = constraintImpacts;
        this.explanations = explanations;
    }

    @Transactional
    public void record(AllocationResult result, OptimizeResponseDto response, String ownerEmail) {
        ScheduleRun run = runs.save(ScheduleRun.builder()
                .runId(response.runId())
                .ownerEmail(ownerEmail)
                .status(response.status())
                .provenOptimal(response.provenOptimal())
                .baselineCostUsd(response.baselineCostUsd())
                .optimizedCostUsd(response.optimizedCostUsd())
                .projectedSavingsUsd(response.projectedSavingsUsd())
                .projectedSavingsPercent(response.projectedSavingsPercent())
                .usedSeededFallbackPrices(response.usedSeededFallbackPrices())
                .build());

        List<JobOutcome> outcomes = result.outcomes();
        List<OptimizeResponseDto.ScheduleItem> dtoItems = response.items();
        for (int i = 0; i < outcomes.size(); i++) {
            recordItem(run.getId(), outcomes.get(i), dtoItems.get(i));
        }

        explanations.save(ScheduleExplanation.builder()
                .scheduleRunId(run.getId())
                .source("DETERMINISTIC_FALLBACK")
                .fallbackReason("NOT_CONFIGURED")
                .headline(headlineFrom(response))
                .narrative(response.plainEnglishExplanation())
                .build());
    }

    private void recordItem(Long scheduleRunId, JobOutcome outcome, OptimizeResponseDto.ScheduleItem dtoItem) {
        ScheduleItem item = items.save(ScheduleItem.builder()
                .scheduleRunId(scheduleRunId)
                .jobId(dtoItem.jobId())
                .jobName(dtoItem.jobName())
                .baselineStart(dtoItem.baselineStart())
                .scheduledStart(dtoItem.scheduledStart())
                .scheduledEnd(dtoItem.scheduledEnd())
                .perJobStatus(dtoItem.perJobStatus())
                .reason(dtoItem.reason())
                .baselineCostUsd(dtoItem.baselineCostUsd())
                .optimizedCostUsd(dtoItem.optimizedCostUsd())
                .savingsUsd(dtoItem.savingsUsd())
                .whyThisPlacement(whyThisPlacement(outcome))
                .build());

        if (!outcome.scheduled()) {
            return; // NO_FEASIBLE_SLOT: nothing was scheduled, so there is no slack to report
        }

        OptimizerJob job = outcome.job();
        LocalDateTime scheduledStart = outcome.plan().start();
        LocalDateTime scheduledEnd = outcome.plan().end();

        long deadlineSlack = Duration.between(scheduledEnd, job.deadline()).toHours();
        constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(item.getId())
                .constraintType(ConstraintType.DEADLINE.name())
                .code("DEADLINE_SLACK")
                .slackHours(deadlineSlack)
                .narrativeFragment(deadlineFragment(deadlineSlack))
                .build());

        // Exempt jobs are pinned regardless of flexibilityWindowHours - reporting a window
        // impact for them would be reporting a constraint that didn't actually govern anything.
        if (!job.exempt()) {
            long shiftHours = Duration.between(job.originalStartTime(), scheduledStart).toHours();
            long unusedWindowHours = job.flexibilityWindowHours() - shiftHours;
            constraintImpacts.save(ConstraintImpact.builder()
                    .scheduleItemId(item.getId())
                    .constraintType(ConstraintType.FLEXIBILITY_WINDOW.name())
                    .code("FLEX_WINDOW_SHIFT")
                    .slackHours(unusedWindowHours)
                    .narrativeFragment(flexWindowFragment(shiftHours, job.flexibilityWindowHours()))
                    .build());
        }
    }

    private static String whyThisPlacement(JobOutcome outcome) {
        return switch (outcome.status()) {
            case OPTIMISED -> "Placed on its cheapest feasible plan.";
            case UNCHANGED_EXEMPT -> "Kept at its original time because it is exempt.";
            case NO_FEASIBLE_SLOT -> outcome.reason();
        };
    }

    private static String deadlineFragment(long slackHours) {
        return slackHours <= 0
                ? "Finishes exactly at its deadline - no slack remaining."
                : String.format(Locale.ROOT, "Finishes %dh before its deadline.", slackHours);
    }

    private static String flexWindowFragment(long shiftHours, int windowHours) {
        return shiftHours <= 0
                ? String.format(Locale.ROOT,
                        "Started at its original time; none of its %dh allowed window was needed.", windowHours)
                : String.format(Locale.ROOT,
                        "Moved %dh later than its original start, using %d of its %dh allowed window.",
                        shiftHours, shiftHours, windowHours);
    }

    /** Short derived one-liner, computed the same way {@code ScheduleOptimizationService.explain(...)}
     *  derives its own numbers - purely from already-known response fields. */
    private static String headlineFrom(OptimizeResponseDto response) {
        long total = response.items().size();
        long scheduled = response.items().stream()
                .filter(i -> !"NO_FEASIBLE_SLOT".equals(i.perJobStatus()))
                .count();
        String savings;
        if (response.projectedSavingsUsd() > 0) {
            savings = String.format(Locale.ROOT, "saved $%.2f (%.2f%%)",
                    response.projectedSavingsUsd(), response.projectedSavingsPercent());
        } else if (response.projectedSavingsUsd() < 0) {
            savings = String.format(Locale.ROOT, "cost $%.2f (%.2f%%) more than baseline",
                    -response.projectedSavingsUsd(), -response.projectedSavingsPercent());
        } else {
            savings = "no change in cost";
        }
        return String.format(Locale.ROOT, "Scheduled %d of %d job(s); %s.", scheduled, total, savings);
    }
}
