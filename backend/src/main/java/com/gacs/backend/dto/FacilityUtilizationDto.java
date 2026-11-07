package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled (not measured) use of the facility in the current UTC hour, summed over every live approved plan.
 * Timestamps are UTC with a {@code Z} suffix, like the other schedule payloads.
 */
@Schema(description = "Scheduled CPU and GPU use of the facility in the current hour")
public record FacilityUtilizationDto(
    @Schema(description = "LIVE when at least one approved plan passed its validity check; "
        + "NO_APPROVED_PLAN otherwise", example = "LIVE")
    State state,

    @Schema(description = "Run IDs of the live approved plans that were summed; empty for NO_APPROVED_PLAN")
    List<String> runIds,

    @Schema(description = "Start of the hour the sums cover", example = "2026-11-07T14:00:00Z")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = OptimizeResponseDto.UTC_PATTERN)
    LocalDateTime hourStartUtc,

    Usage cpu,
    Usage gpu
) {

    public enum State { LIVE, NO_APPROVED_PLAN }

    @Schema(description = "Use of one resource against its per-hour limit")
    public record Usage(
        @Schema(description = "Hours scheduled in this hour; null for NO_APPROVED_PLAN", example = "160.0")
        Double used,
        @Schema(description = "Facility limit per hourly slot", example = "256.0")
        double limit,
        @Schema(description = "used / limit * 100, not clamped (may exceed 100); null when used is null "
            + "or the limit is 0 or less", example = "62.5")
        Double percent
    ) {}
}
