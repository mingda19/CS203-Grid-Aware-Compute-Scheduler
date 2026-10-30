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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Persists one optimisation run (docs/schedule-explanation-design.md sections 1, 2.7, 4): the
 * run, its items, every constraint impact (the two intrinsic job-attribute types, DEADLINE and
 * FLEXIBILITY_WINDOW, plus whichever of the four declared {@link Constraint} types actually
 * governed each job), and either an LLM-generated or deterministic-fallback explanation.
 *
 * <p>Takes {@link AllocationResult} (for each job's raw attributes via {@code JobOutcome.job()}),
 * the declared {@link Constraint}s (for the four non-intrinsic types - just the list, not the
 * full {@link OptimizationProblem}, which this class otherwise has no use for), and the
 * already-built {@link OptimizeResponseDto} (for cost figures already rounded and consistent with
 * what the caller returns). {@code result.outcomes()} and {@code response.items()} are always the
 * same size, in the same order: both are built by iterating the same list once.
 */
@Service
public class ScheduleRunRecorder {

    private final ScheduleRunRepository runs;
    private final ScheduleItemRepository items;
    private final ConstraintImpactRepository constraintImpacts;
    private final ScheduleExplanationRepository explanations;
    private final ExplanationGenerator explanationGenerator;

    public ScheduleRunRecorder(ScheduleRunRepository runs, ScheduleItemRepository items,
                               ConstraintImpactRepository constraintImpacts,
                               ScheduleExplanationRepository explanations,
                               ExplanationGenerator explanationGenerator) {
        this.runs = runs;
        this.items = items;
        this.constraintImpacts = constraintImpacts;
        this.explanations = explanations;
        this.explanationGenerator = explanationGenerator;
    }

    @Transactional
    public void record(AllocationResult result, List<Constraint> constraints, OptimizeResponseDto response,
                       String ownerEmail) {
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
        Map<Long, JobOutcome> outcomesByJobId = new HashMap<>();
        outcomes.forEach(outcome -> outcomesByJobId.put(outcome.job().id(), outcome));

        List<ScheduleItem> savedItems = new ArrayList<>();
        List<ConstraintImpact> savedImpacts = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            RecordedItem recorded = recordItem(run.getId(), outcomes.get(i), dtoItems.get(i), constraints,
                    outcomesByJobId);
            savedItems.add(recorded.item());
            savedImpacts.addAll(recorded.impacts());
        }

        // Every item/impact above already carries correct deterministic text (whyThisPlacement /
        // narrativeFragment) - the explanation step below either leaves it alone (any fallback) or
        // overwrites it with the LLM's, never the reverse, so there is no window where a row holds
        // blank or inconsistent text.
        ExplanationOutcome explanation = explanationGenerator.generate(run, savedItems, savedImpacts);
        if (explanation.usedLlm()) {
            applyLlmText(savedItems, savedImpacts, explanation.response());
            explanations.save(ScheduleExplanation.builder()
                    .scheduleRunId(run.getId())
                    .source("LLM")
                    .modelName(explanation.modelName())
                    .rawResponseJson(explanation.rawResponseJson())
                    .headline(explanation.response().headline())
                    .narrative(explanation.response().narrative())
                    .build());
        } else {
            explanations.save(ScheduleExplanation.builder()
                    .scheduleRunId(run.getId())
                    .source("DETERMINISTIC_FALLBACK")
                    .fallbackReason(explanation.fallbackReason().name())
                    .rawResponseJson(explanation.rawResponseJson())
                    .validationErrorsJson(joinErrors(explanation.validationErrors()))
                    .headline(headlineFrom(response))
                    .narrative(response.plainEnglishExplanation())
                    .build());
        }
    }

    private record RecordedItem(ScheduleItem item, List<ConstraintImpact> impacts) {
    }

    private RecordedItem recordItem(Long scheduleRunId, JobOutcome outcome, OptimizeResponseDto.ScheduleItem dtoItem,
                                    List<Constraint> constraints, Map<Long, JobOutcome> outcomesByJobId) {
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
            return new RecordedItem(item, List.of()); // NO_FEASIBLE_SLOT: nothing was scheduled, so there is no slack to report
        }

        List<ConstraintImpact> impacts = new ArrayList<>();
        OptimizerJob job = outcome.job();
        LocalDateTime scheduledStart = outcome.plan().start();
        LocalDateTime scheduledEnd = outcome.plan().end();

        long deadlineSlack = Duration.between(scheduledEnd, job.deadline()).toHours();
        impacts.add(constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(item.getId())
                .constraintType(ConstraintType.DEADLINE.name())
                .code("DEADLINE_SLACK")
                .slackHours(deadlineSlack)
                .narrativeFragment(deadlineFragment(deadlineSlack))
                .build()));

        // Exempt jobs are pinned regardless of flexibilityWindowHours - reporting a window
        // impact for them would be reporting a constraint that didn't actually govern anything.
        if (!job.exempt()) {
            long shiftHours = Duration.between(job.originalStartTime(), scheduledStart).toHours();
            long unusedWindowHours = job.flexibilityWindowHours() - shiftHours;
            impacts.add(constraintImpacts.save(ConstraintImpact.builder()
                    .scheduleItemId(item.getId())
                    .constraintType(ConstraintType.FLEXIBILITY_WINDOW.name())
                    .code("FLEX_WINDOW_SHIFT")
                    .slackHours(unusedWindowHours)
                    .narrativeFragment(flexWindowFragment(shiftHours, job.flexibilityWindowHours()))
                    .build()));
        }

        for (Constraint constraint : constraintsFor(constraints, job)) {
            declaredConstraintImpact(constraint, job, outcome, outcomesByJobId, item.getId()).ifPresent(impacts::add);
        }
        return new RecordedItem(item, impacts);
    }

    private static List<Constraint> constraintsFor(List<Constraint> constraints, OptimizerJob job) {
        return constraints.stream().filter(c -> c.jobIds().contains(job.id())).toList();
    }

    /**
     * Builds the impact row for one of the four declared {@link Constraint} types - DEADLINE and
     * FLEXIBILITY_WINDOW are handled separately above, since they are intrinsic job attributes, not
     * declared constraints. Returns empty when this constraint type has nothing to report for this
     * specific job (e.g. the before-job in a {@link PrecedenceConstraint}, whose own placement
     * isn't actually shaped by it).
     */
    private Optional<ConstraintImpact> declaredConstraintImpact(Constraint constraint, OptimizerJob job,
                                                                 JobOutcome outcome,
                                                                 Map<Long, JobOutcome> outcomesByJobId,
                                                                 Long scheduleItemId) {
        if (constraint instanceof AbsoluteTimeWindowConstraint c) {
            return Optional.of(absoluteTimeWindowImpact(c, outcome, scheduleItemId));
        }
        if (constraint instanceof PrecedenceConstraint c) {
            return precedenceImpact(c, job, outcome, outcomesByJobId, scheduleItemId);
        }
        if (constraint instanceof MutualExclusionConstraint c) {
            return mutualExclusionImpact(c, job, outcome, outcomesByJobId, scheduleItemId);
        }
        if (constraint instanceof SynchronizedFinishConstraint c) {
            return synchronizedFinishImpact(c, job, outcome, outcomesByJobId, scheduleItemId);
        }
        return Optional.empty();
    }

    private ConstraintImpact absoluteTimeWindowImpact(AbsoluteTimeWindowConstraint constraint, JobOutcome outcome,
                                                       Long scheduleItemId) {
        String fragment = String.format(Locale.ROOT, "Runs at %s, within its allowed daily window of %s-%s UTC.",
                outcome.plan().start().toLocalTime(), constraint.dailyStartTime(), constraint.dailyEndTime());
        return constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(scheduleItemId)
                .constraintType(ConstraintType.ABSOLUTE_TIME_WINDOW.name())
                .code("ABSOLUTE_WINDOW_RESPECTED")
                .narrativeFragment(fragment)
                .build());
    }

    /** Only the after-job's own placement is shaped by precedence - the before-job gets no row for
     *  this same constraint, since nothing about its placement was influenced by it. */
    private Optional<ConstraintImpact> precedenceImpact(PrecedenceConstraint constraint, OptimizerJob job,
                                                         JobOutcome outcome, Map<Long, JobOutcome> outcomesByJobId,
                                                         Long scheduleItemId) {
        if (job.id() != constraint.afterJobId()) {
            return Optional.empty();
        }
        JobOutcome before = outcomesByJobId.get(constraint.beforeJobId());
        Long slack = null;
        String fragment;
        if (before != null && before.scheduled()) {
            slack = Duration.between(before.plan().end(), outcome.plan().start()).toHours();
            fragment = String.format(Locale.ROOT, "Started %dh after job id %d finished, as required.", slack,
                    constraint.beforeJobId());
        } else {
            fragment = String.format(Locale.ROOT, "Must start after job id %d finishes, but that job was not "
                    + "scheduled, so nothing constrained this job's start.", constraint.beforeJobId());
        }
        return Optional.of(constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(scheduleItemId)
                .constraintType(ConstraintType.PRECEDENCE.name())
                .code("PRECEDENCE_GAP")
                .slackHours(slack)
                .relatedJobIds(String.valueOf(constraint.beforeJobId()))
                .narrativeFragment(fragment)
                .build()));
    }

    /** Symmetric: both jobs in the pair get a row, with the same gap, each pointing at the other. */
    private Optional<ConstraintImpact> mutualExclusionImpact(MutualExclusionConstraint constraint, OptimizerJob job,
                                                              JobOutcome outcome,
                                                              Map<Long, JobOutcome> outcomesByJobId,
                                                              Long scheduleItemId) {
        List<Long> ids = constraint.jobIds();
        long otherId = job.id() == ids.get(0) ? ids.get(1) : ids.get(0);
        JobOutcome other = outcomesByJobId.get(otherId);
        Long slack = null;
        String fragment;
        if (other != null && other.scheduled()) {
            JobPlan mine = outcome.plan();
            JobPlan theirs = other.plan();
            slack = mine.start().isBefore(theirs.start())
                    ? Duration.between(mine.end(), theirs.start()).toHours()
                    : Duration.between(theirs.end(), mine.start()).toHours();
            fragment = String.format(Locale.ROOT, "Does not overlap job id %d (%dh apart).", otherId, slack);
        } else {
            fragment = String.format(Locale.ROOT,
                    "May not overlap job id %d, but that job was not scheduled, so there was nothing to avoid.",
                    otherId);
        }
        return Optional.of(constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(scheduleItemId)
                .constraintType(ConstraintType.MUTUAL_EXCLUSION.name())
                .code("MUTUAL_EXCLUSION_GAP")
                .slackHours(slack)
                .relatedJobIds(String.valueOf(otherId))
                .narrativeFragment(fragment)
                .build()));
    }

    /** Only reports synchronization when every member is actually scheduled and actually shares this
     *  job's end time - a defensive check, not an assumption, so the "fewer than 2 flexible members"
     *  no-op edge case ({@code SynchronizedGroupPlacer}) never produces a false "finishes together". */
    private Optional<ConstraintImpact> synchronizedFinishImpact(SynchronizedFinishConstraint constraint,
                                                                 OptimizerJob job, JobOutcome outcome,
                                                                 Map<Long, JobOutcome> outcomesByJobId,
                                                                 Long scheduleItemId) {
        List<Long> memberIds = constraint.jobIds();
        List<JobOutcome> memberOutcomes = memberIds.stream().map(outcomesByJobId::get).filter(Objects::nonNull)
                .toList();
        boolean allScheduled = memberOutcomes.size() == memberIds.size()
                && memberOutcomes.stream().allMatch(JobOutcome::scheduled);
        if (!allScheduled) {
            return Optional.empty();
        }
        LocalDateTime myEnd = outcome.plan().end();
        boolean allSameEnd = memberOutcomes.stream().allMatch(o -> o.plan().end().equals(myEnd));
        if (!allSameEnd) {
            return Optional.empty();
        }

        List<Long> otherIds = memberIds.stream().filter(id -> id != job.id()).toList();
        String relatedCsv = otherIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String otherIdsText = otherIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        String fragment = String.format(Locale.ROOT, "Finishes at the same time as job id(s) %s, as required.",
                otherIdsText);
        return Optional.of(constraintImpacts.save(ConstraintImpact.builder()
                .scheduleItemId(scheduleItemId)
                .constraintType(ConstraintType.SYNCHRONIZED_FINISH.name())
                .code("SYNCHRONIZED_FINISH_GROUP")
                .relatedJobIds(relatedCsv)
                .narrativeFragment(fragment)
                .build()));
    }

    /** Overwrites each row's deterministic text with the LLM's, matching by job id (items) or by
     *  {@code (jobId, constraintType, relatedJobIds)} (impacts) - never by array position, since a
     *  job can carry two constraints of the same type. {@code ExplanationValidator} has already
     *  confirmed full, exact coverage, so every row here has a match. */
    private void applyLlmText(List<ScheduleItem> scheduleItems, List<ConstraintImpact> constraintImpactRows,
                              LlmExplanationResponse response) {
        Map<Long, String> jobExplanationByJobId = new HashMap<>();
        response.jobExplanations().forEach(e -> jobExplanationByJobId.put(e.jobId(), e.explanation()));
        for (ScheduleItem item : scheduleItems) {
            String text = jobExplanationByJobId.get(item.getJobId());
            if (text != null) {
                item.setWhyThisPlacement(text);
                items.save(item);
            }
        }

        Map<Long, Long> scheduleItemIdToJobId = new HashMap<>();
        scheduleItems.forEach(item -> scheduleItemIdToJobId.put(item.getId(), item.getJobId()));
        Map<String, String> constraintExplanationByKey = new HashMap<>();
        response.constraintExplanations().forEach(e -> constraintExplanationByKey.put(
                impactKey(e.jobId(), e.constraintType(), e.relatedJobIds()), e.explanation()));
        for (ConstraintImpact impact : constraintImpactRows) {
            Long jobId = scheduleItemIdToJobId.get(impact.getScheduleItemId());
            if (jobId == null) {
                continue;
            }
            String text = constraintExplanationByKey.get(
                    impactKey(jobId, impact.getConstraintType(), parseRelatedJobIds(impact.getRelatedJobIds())));
            if (text != null) {
                impact.setNarrativeFragment(text);
                constraintImpacts.save(impact);
            }
        }
    }

    /** Related-job-ids are compared as a set: order never carries meaning. */
    private static String impactKey(long jobId, String constraintType, List<Long> relatedJobIds) {
        return jobId + "|" + constraintType + "|" + new TreeSet<>(relatedJobIds);
    }

    private static List<Long> parseRelatedJobIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(part -> !part.isEmpty())
                .map(Long::parseLong).toList();
    }

    private static String joinErrors(List<String> errors) {
        return errors.isEmpty() ? null : String.join("; ", errors);
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
