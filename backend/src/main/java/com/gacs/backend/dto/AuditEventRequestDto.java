package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gacs.backend.model.AuditActionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload to record an operator decision audit event.
 * Per AC4.1a, caller-supplied actor identity is NEVER accepted from client payloads;
 * actor identity is resolved server-side strictly from Spring Security's authenticated principal.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Payload to record an operator decision audit event. Caller identity is extracted server-side.")
public record AuditEventRequestDto(

    @Schema(description = "Action taken by operator", example = "APPROVE")
    @NotNull(message = "actionType is required")
    AuditActionType actionType,

    @Schema(description = "Identifier of the optimization run", example = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44")
    @NotBlank(message = "runId is required")
    @Size(max = 64, message = "runId must not exceed 64 characters")
    String runId,

    @Schema(description = "Cryptographic SHA-256 hash of the schedule acted upon", example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    @NotBlank(message = "scheduleHash is required")
    @Size(min = 64, max = 64, message = "scheduleHash must be a 64-character SHA-256 hex string")
    String scheduleHash,

    @Schema(description = "Justification and reasoning provided by the operator", example = "Approved schedule after verifying cooling margin.")
    @NotBlank(message = "operatorRationale is required")
    @Size(max = 2000, message = "operatorRationale must not exceed 2000 characters")
    String operatorRationale
) {}
