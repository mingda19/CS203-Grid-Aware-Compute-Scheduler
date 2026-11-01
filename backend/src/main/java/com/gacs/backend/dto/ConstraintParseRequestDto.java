package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Text to parse, plus what the job form already holds (docs/constraint-parser-plan.md section 3.4).
 *
 * @param rawConstraints the operator's plain-English constraints
 * @param draft          the job form's current values, all optional; the parser checks its result against them
 * @param timeZone       IANA zone the operator means, e.g. {@code Asia/Singapore}; defaults to
 *                       {@code gacs.parser.default-zone}
 */
@Schema(description = "Natural-language constraints to parse into job fields")
public record ConstraintParseRequestDto(
        @Schema(description = "Operator's constraints in plain English", example = "Finish by 6am tomorrow, can wait 4 hours",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "rawConstraints is required")
        @Size(max = 1000, message = "rawConstraints must not exceed 1000 characters")
        String rawConstraints,

        @Schema(description = "The job form's current values (UTC), all optional")
        Draft draft,

        @Schema(description = "IANA time zone the text's times are in", example = "Asia/Singapore")
        @Size(max = 64, message = "timeZone must not exceed 64 characters")
        String timeZone) {

    /** Job form values, in the same form as the job API. Any of them may be null. */
    public record Draft(
            LocalDateTime originalStartTime,
            LocalDateTime deadline,
            Integer flexibilityWindowHours,
            String priority,
            @JsonProperty("isExempt") Boolean isExempt) {

        public static final Draft EMPTY = new Draft(null, null, null, null, null);
    }
}
