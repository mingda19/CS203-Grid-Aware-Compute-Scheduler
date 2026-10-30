package com.gacs.backend.dto;

import com.gacs.backend.model.DecisionAction;
import com.gacs.backend.model.ReviewState;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record ScheduleDecisionDto(Long decisionId, UUID runId, DecisionAction action, Long operatorId,
                                 String comment, Instant decidedAt, ReviewState reviewState) {
    public record Request(@Schema(description = "Optional comment, trimmed after validating its original length", maxLength = 2000)
                          @Size(max = 2000) String comment) {}
}
