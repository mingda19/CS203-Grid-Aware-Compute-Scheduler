package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Ambiguity;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Conflict;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Evidence;
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
        String timeZone) {

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
