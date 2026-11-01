package com.gacs.backend.service.constraintparse;

import com.gacs.backend.model.JobPriority;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the parser found in one piece of text. Times are UTC, as in the job API. A field is null when the text
 * did not say, or said it ambiguously or in two different ways; {@link #ambiguities()} and {@link #conflicts()}
 * say which.
 *
 * <p>Offsets ({@code start}, {@code end}) index the text as the caller sent it, so the UI can highlight the
 * phrase.
 */
public record ParsedConstraints(
        LocalDateTime originalStartTime,
        LocalDateTime deadline,
        Integer flexibilityWindowHours,
        Boolean isExempt,
        JobPriority priority,
        List<Evidence> evidence,
        List<Ambiguity> ambiguities,
        List<Conflict> conflicts,
        List<Unsupported> unsupported) {

    public ParsedConstraints {
        evidence = List.copyOf(evidence);
        ambiguities = List.copyOf(ambiguities);
        conflicts = List.copyOf(conflicts);
        unsupported = List.copyOf(unsupported);
    }

    /** True when at least one field was filled. */
    public boolean hasAnyField() {
        return originalStartTime != null || deadline != null || flexibilityWindowHours != null
                || isExempt != null || priority != null;
    }

    /** The phrase a field's value came from. {@code value} is the API form: ISO UTC time, number, etc. */
    public record Evidence(ConstraintField field, String value, String phrase, int start, int end) {
    }

    /** A phrase that could mean more than one thing. {@code field} is null when it could affect several. */
    public record Ambiguity(ConstraintField field, AmbiguityReason reason, String phrase, String message,
                            int start, int end) {
    }

    /** The same field given two different values, in the text or between the text and the form. */
    public record Conflict(ConstraintField field, List<String> phrases, String message) {
        public Conflict {
            phrases = List.copyOf(phrases);
        }
    }

    /** Part of the text that is not about any supported field, e.g. "needs 16 GPUs". */
    public record Unsupported(String phrase, int start, int end) {
    }
}
