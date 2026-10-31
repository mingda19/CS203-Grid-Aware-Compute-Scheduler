package com.gacs.backend.dto;

import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Audit event response DTO representing an immutable operator decision.
 */
@Schema(description = "Audit event record representing an immutable operator decision")
public record AuditEventResponseDto(
    @Schema(description = "Unique audit event ID", example = "1") Long id,
    @Schema(description = "Authenticated operator who performed the action", example = "operator@gacs.internal") String actor,
    @Schema(description = "Action type", example = "APPROVE") AuditActionType actionType,
    @Schema(description = "Timestamp when the audit event occurred in UTC", example = "2026-10-28T09:30:00Z") Instant timestamp,
    @Schema(description = "Associated optimizer run ID", example = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44") String runId,
    @Schema(description = "SHA-256 digest of the schedule payload", example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") String scheduleHash,
    @Schema(description = "Operator rationale and justification") String operatorRationale
) {
    public static AuditEventResponseDto fromEntity(AuditEvent entity) {
        return new AuditEventResponseDto(
            entity.getId(),
            entity.getActor(),
            entity.getActionType(),
            entity.getTimestamp(),
            entity.getRunId(),
            entity.getScheduleHash(),
            entity.getOperatorRationale()
        );
    }
}
