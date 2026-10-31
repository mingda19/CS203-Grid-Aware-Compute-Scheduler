package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.service.AuditEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing audit log endpoints per Jira GRID-83 and GRID-134 AC4.
 * Reads and writes require ROLE_USER or ROLE_ADMIN.
 */
@Tag(name = "Audit Log", description = "Append-only audit trail recording operator decisions, runs, and schedule hashes.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/audit-events")
public class AuditEventController {

    private final AuditEventService auditEventService;

    public AuditEventController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    @Operation(
        summary = "Record an audit event",
        description = "Records an immutable audit event for an operator action (APPROVE, REJECT, REVISE, etc.). "
            + "Requires ROLE_USER or ROLE_ADMIN. Actor identity is strictly extracted server-side from the authenticated security context."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Audit event recorded successfully.",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation failed: missing required fields or invalid format."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized: Missing or invalid authentication token."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden: Caller is unauthenticated or has insufficient permissions."
        )
    })
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<AuditEventResponseDto>> recordEvent(
            @Valid @RequestBody AuditEventRequestDto request
    ) {
        AuditEventResponseDto event = auditEventService.recordEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Audit event recorded successfully", event));
    }

    @Operation(summary = "Get audit events for an optimization run", description = "Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Audit events retrieved successfully."
        )
    })
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping("/run/{runId}")
    public ResponseEntity<ApiResponse<List<AuditEventResponseDto>>> getEventsByRunId(
            @Parameter(description = "Optimization run ID", required = true) @PathVariable String runId
    ) {
        List<AuditEventResponseDto> events = auditEventService.getEventsByRunId(runId);
        return ResponseEntity.ok(ApiResponse.ok("Audit events retrieved", events));
    }

    @Operation(summary = "List audit events with pagination", description = "Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Audit events page retrieved successfully."
        )
    })
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AuditEventResponseDto>>> getEvents(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<AuditEventResponseDto> events = auditEventService.getEvents(pageable);
        return ResponseEntity.ok(ApiResponse.ok("Audit events retrieved", events));
    }

    @Operation(summary = "Get audit event by ID", description = "Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Audit event retrieved successfully."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Audit event not found."
        )
    })
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AuditEventResponseDto>> getEventById(
            @Parameter(description = "Audit event ID", required = true) @PathVariable Long id
    ) {
        AuditEventResponseDto event = auditEventService.getEventById(id);
        return ResponseEntity.ok(ApiResponse.ok("Audit event retrieved", event));
    }
}
