package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Persisted explanation for an optimization run")
public record ExplanationResponseDto(
    String runId,
    String explanation,
    List<JobExplanation> items
) {
    public ExplanationResponseDto(String runId, String explanation) {
        this(runId, explanation, List.of());
    }

    public record JobExplanation(Long jobId, String whyThisPlacement, List<ConstraintExplanation> constraints) {}
    public record ConstraintExplanation(String constraintType, String code, Long slackHours,
                                        List<Long> relatedJobIds, String explanation) {}
}
