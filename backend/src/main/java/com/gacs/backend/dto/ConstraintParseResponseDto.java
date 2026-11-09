package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Ambiguity;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Conflict;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Evidence;
import com.gacs.backend.service.constraintparse.ClassifierReconciler;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Unsupported;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of a parse. Nothing is saved: the frontend shows this for the operator to confirm, then sends the
 * confirmed fields with {@code rawConstraints}, {@code parsedAt} and {@code PENDING} through the job API.
 *
 * @param status          {@code PARSED} (ready to confirm), {@code NEEDS_CLARIFICATION} (ambiguities, conflicts
 *                        or nothing found) or {@code INVALID} (the merged fields break a job rule)
 * @param fields          the form's values with the parsed ones applied (UTC)
 * @param evidence        which phrase each parsed value came from
 * @param ambiguities     phrases left unset, each with a question for the operator
 * @param conflicts       fields given two values, in the text or between the text and the form
 * @param unsupported     parts of the text about nothing the parser supports
 * @param validationError the job validator's message when {@code status} is {@code INVALID}
 * @param parsedAt        when the parse ran (UTC); send it back as {@code parsedAt}
 * @param timeZone        the zone the text was read in
 * @param suggestions     constraints only the classifier found, which the rule-based parser has no
 *                        field for - a daily window, or an ordering against another job. Each one
 *                        the operator confirms or discards; an unconfirmed one is never applied.
 *                        Always empty when no classifier is configured.
 * @param disagreements   where the classifier and the parser read the text differently, each with
 *                        the choice the operator has to make. Neither source overrides the other,
 *                        so these are questions, not resolutions - see {@link ClassifierReconciler}.
 *                        A non-empty list makes {@code status} {@code NEEDS_CLARIFICATION}.
 */
@Schema(description = "Parsed constraints for the operator to confirm")
public record ConstraintParseResponseDto(
        String status,
        Fields fields,
        List<Evidence> evidence,
        List<Ambiguity> ambiguities,
        List<Conflict> conflicts,
        List<Unsupported> unsupported,
        String validationError,
        LocalDateTime parsedAt,
        String timeZone,
        List<Suggestion> suggestions,
        List<ClassifierReconciler.Disagreement> disagreements) {

    /** Convenience for the no-classifier case and for every caller predating one. */
    public ConstraintParseResponseDto(String status, Fields fields, List<Evidence> evidence,
                                      List<Ambiguity> ambiguities, List<Conflict> conflicts,
                                      List<Unsupported> unsupported, String validationError,
                                      LocalDateTime parsedAt, String timeZone) {
        this(status, fields, evidence, ambiguities, conflicts, unsupported, validationError, parsedAt,
                timeZone, List.of(), List.of());
    }

    /**
     * A constraint the classifier found that the form has no field for.
     *
     * @param type              the constraint kind, from the closed set the classifier was given
     * @param probability       how likely the reading is correct, 0-1. Not the model's confidence.
     * @param phrase            the operator's own words this came from
     * @param referencedJobId   the other job, for an ordering or exclusion rule; null otherwise.
     *                          Always one of the operator's own jobs - a constraint may never bind
     *                          a job they do not own.
     * @param needsConfirmation true when the probability fell in the review band, so it must be
     *                          answered before the job is saved
     */
    @Schema(description = "A constraint found only by the classifier")
    public record Suggestion(String type, double probability, String phrase, Long referencedJobId,
                             boolean needsConfirmation) {
    }

    public static final String PARSED = "PARSED";
    public static final String NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    public static final String INVALID = "INVALID";

    /** Job fields after merging the form's values with the parsed ones. */
    public record Fields(
            LocalDateTime originalStartTime,
            LocalDateTime deadline,
            Integer flexibilityWindowHours,
            String priority,
            @JsonProperty("isExempt") Boolean isExempt) {
    }
}
