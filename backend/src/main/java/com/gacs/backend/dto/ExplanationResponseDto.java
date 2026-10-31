package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Persisted explanation for an optimization run")
public record ExplanationResponseDto(
    @Schema(description = "Optimization run ID") String runId,
    @Schema(description = "Plain-English explanation generated for the run") String explanation
) {}
