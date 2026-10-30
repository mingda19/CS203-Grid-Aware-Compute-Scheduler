package com.gacs.backend.service.allocation;

import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 3 of the guardrail pipeline (docs/schedule-explanation-design.md section 4.1 item 3): since
 * the LLM never produces a number, there is nothing left to cross-check numerically. Just two
 * checks remain:
 * <ol>
 *   <li>every {@code jobExplanations[].jobId}/{@code constraintExplanations[].jobId} refers to a
 *       real row in this run, and every real row has a corresponding explanation - no gaps, no
 *       fabricated extras;</li>
 *   <li>every string field is non-blank and within a backend-enforced length ceiling (provider
 *       JSON-schema modes don't reliably enforce string length).</li>
 * </ol>
 * Returns every problem found, not just the first - so {@code validationErrorsJson} can show the
 * whole picture at once rather than one check at a time across repeated attempts.
 */
final class ExplanationValidator {

    static final int HEADLINE_MAX_LENGTH = 200; // matches ScheduleExplanation.headline's column length
    static final int PROSE_MAX_LENGTH = 2000;

    private ExplanationValidator() {
    }

    /** @param impacts every {@link ConstraintImpact} belonging to the items in {@code items} */
    static List<String> validate(LlmExplanationResponse response, List<ScheduleItem> items,
                                 List<ConstraintImpact> impacts) {
        List<String> errors = new ArrayList<>();
        checkField(response.headline(), "headline", HEADLINE_MAX_LENGTH, errors);
        checkField(response.narrative(), "narrative", PROSE_MAX_LENGTH, errors);

        Map<Long, ScheduleItem> itemsByJobId = new HashMap<>();
        items.forEach(item -> itemsByJobId.put(item.getJobId(), item));
        checkJobExplanations(response, itemsByJobId, errors);

        Map<Long, Long> scheduleItemIdToJobId = new HashMap<>();
        items.forEach(item -> scheduleItemIdToJobId.put(item.getId(), item.getJobId()));
        checkConstraintExplanations(response, impacts, scheduleItemIdToJobId, errors);

        return errors;
    }

    private static void checkJobExplanations(LlmExplanationResponse response, Map<Long, ScheduleItem> itemsByJobId,
                                              List<String> errors) {
        Set<Long> explained = new HashSet<>();
        for (LlmExplanationResponse.JobExplanation entry : response.jobExplanations()) {
            if (!itemsByJobId.containsKey(entry.jobId())) {
                errors.add("jobExplanations references job id " + entry.jobId() + ", which is not in this run");
                continue;
            }
            if (!explained.add(entry.jobId())) {
                errors.add("jobExplanations has more than one entry for job id " + entry.jobId());
            }
            checkField(entry.explanation(), "jobExplanations[jobId=" + entry.jobId() + "].explanation",
                    PROSE_MAX_LENGTH, errors);
        }
        for (Long jobId : itemsByJobId.keySet()) {
            if (!explained.contains(jobId)) {
                errors.add("jobExplanations is missing an entry for job id " + jobId);
            }
        }
    }

    private static void checkConstraintExplanations(LlmExplanationResponse response, List<ConstraintImpact> impacts,
                                                      Map<Long, Long> scheduleItemIdToJobId, List<String> errors) {
        Map<ImpactKey, ConstraintImpact> impactsByKey = new HashMap<>();
        for (ConstraintImpact impact : impacts) {
            Long jobId = scheduleItemIdToJobId.get(impact.getScheduleItemId());
            if (jobId == null) {
                continue; // an impact whose item isn't in this run's item list - not this run's concern
            }
            impactsByKey.put(keyOf(jobId, impact.getConstraintType(), impact.getRelatedJobIds()), impact);
        }

        Set<ImpactKey> explained = new HashSet<>();
        for (LlmExplanationResponse.ConstraintExplanation entry : response.constraintExplanations()) {
            ImpactKey key = keyOf(entry.jobId(), entry.constraintType(), entry.relatedJobIds());
            if (!impactsByKey.containsKey(key)) {
                errors.add("constraintExplanations has no matching constraint impact for job id " + entry.jobId()
                        + ", type " + entry.constraintType() + ", relatedJobIds " + entry.relatedJobIds());
                continue;
            }
            if (!explained.add(key)) {
                errors.add("constraintExplanations has more than one entry for job id " + entry.jobId()
                        + ", type " + entry.constraintType() + ", relatedJobIds " + entry.relatedJobIds());
            }
            checkField(entry.explanation(),
                    "constraintExplanations[jobId=" + entry.jobId() + ", type=" + entry.constraintType()
                            + "].explanation",
                    PROSE_MAX_LENGTH, errors);
        }
        impactsByKey.forEach((key, impact) -> {
            if (!explained.contains(key)) {
                errors.add("constraintExplanations is missing an entry for job id " + key.jobId() + ", type "
                        + key.constraintType() + ", relatedJobIds " + key.relatedJobIds());
            }
        });
    }

    private static void checkField(String value, String fieldName, int maxLength, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(fieldName + " must not be blank");
        } else if (value.length() > maxLength) {
            errors.add(fieldName + " is " + value.length() + " characters, over the limit of " + maxLength);
        }
    }

    /** Related-job-ids are compared as a set: order never carries meaning. */
    private record ImpactKey(long jobId, String constraintType, Set<Long> relatedJobIds) {
    }

    private static ImpactKey keyOf(long jobId, String constraintType, List<Long> relatedJobIds) {
        return new ImpactKey(jobId, constraintType, Set.copyOf(relatedJobIds));
    }

    private static ImpactKey keyOf(long jobId, String constraintType, String relatedJobIdsCsv) {
        return new ImpactKey(jobId, constraintType, parseRelatedJobIds(relatedJobIdsCsv));
    }

    private static Set<Long> parseRelatedJobIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }
}
