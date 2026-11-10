package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AuditEventDetailDto;
import com.gacs.backend.dto.AuditEventFilter;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.service.AuditEventService;
import com.gacs.backend.service.ScheduleReviewService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private final ScheduleReviewService scheduleReviewService;

    public AuditEventController(AuditEventService auditEventService, ScheduleReviewService scheduleReviewService) {
        this.auditEventService = auditEventService;
        this.scheduleReviewService = scheduleReviewService;
    }

    @Operation(
        summary = "Record an audit event",
        description = "Records an immutable audit event for an operator action (APPROVE, REJECT, REVISE, REVOKE, etc.). "
            + "Schedule decisions use the same ownership, validity and transactional checks as /api/schedules/{runId}/{action}; "
            + "their schedule hash is computed server-side. Requires ROLE_USER or ROLE_ADMIN. "
            + "Actor identity is extracted from the authenticated security context."
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
            @Valid @RequestBody AuditEventRequestDto request, Principal principal
    ) {
        // REVOKE belongs with the other three, not in the default branch. Left there it recorded a
        // governance action straight into the audit log through auditEventService.recordEvent, which
        // checks neither that the caller owns the run nor that the run is in a state a revocation is
        // legal from - so anyone authenticated could append a REVOKE event naming someone else's run,
        // and a run's own owner could "revoke" a plan that was never approved. Every state and
        // ownership rule for a decision lives in ScheduleReviewService.decide; routing REVOKE through
        // it is what makes this endpoint and POST /api/schedules/{runId}/revoke enforce the same gate.
        AuditEventResponseDto event = switch (request.actionType()) {
            case APPROVE, REJECT, REVISE, REVOKE -> {
                var decision = scheduleReviewService.decide(UUID.fromString(request.runId().trim()),
                        request.actionType(), request.operatorRationale(), principal.getName());
                yield auditEventService.getEventById(decision.decisionId());
            }
            default -> auditEventService.recordEvent(request);
        };
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
            @Parameter(description = "Optimization run ID", required = true) @PathVariable String runId,
            Principal principal
    ) {
        // Scoped for the same reason as the query endpoint above: a run id is guessable and was
        // enough on its own to read another operator's decision history.
        List<AuditEventResponseDto> events =
                auditEventService.getEventsByRunIdForActor(runId, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Audit events retrieved", events));
    }

    @Operation(
        summary = "Query audit events with filters and pagination",
        description = "Filters by date range, operator, decision outcome, run and job (GRID-134 AC6.1a). "
            + "All filters are optional; results are newest first. Requires ROLE_USER or ROLE_ADMIN."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Audit events page retrieved successfully."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid filter: malformed date or action type, or 'from' is not before 'to'."
        )
    })
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<AuditEventResponseDto>>> getEvents(
            @Parameter(description = "Earliest event time, inclusive (ISO-8601 UTC)", example = "2026-10-28T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Latest event time, exclusive (ISO-8601 UTC)", example = "2026-10-29T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Operator email; case-insensitive substring match", example = "operator@gacs")
            @RequestParam(required = false) String actor,
            @Parameter(description = "Decision outcome", example = "APPROVE")
            @RequestParam(required = false) AuditActionType actionType,
            @Parameter(description = "Target optimiser run ID; case-insensitive substring match", example = "7f3c1e52")
            @RequestParam(required = false) String runId,
            @Parameter(description = "Target job ID; events on runs whose stored plan includes the job", example = "101")
            @RequestParam(required = false) Long jobId,
            @PageableDefault(size = 20) Pageable pageable,
            Principal principal
    ) {
        AuditEventFilter filter = new AuditEventFilter(from, to, actor, actionType, runId, jobId, null);
        // An operator sees only their own trail. Previously this query was unscoped, so any
        // authenticated user could read every other operator's decisions just by calling it - the
        // jobs API has always scoped by owner, this one never did. Scoping is unconditional: an
        // auditor view for FR-34 should be its own explicitly-authorised endpoint, not a role check
        // quietly widening the one every operator already uses.
        Page<AuditEventResponseDto> events = auditEventService.getEvents(
                filter.scopedTo(principal.getName()), pageable);
        return ResponseEntity.ok(ApiResponse.ok("Audit events retrieved", events));
    }


    @Operation(
        summary = "Get audit event by ID",
        description = "Returns the event with the optimiser run and stored schedule it refers to (GRID-134 AC6.1b). "
            + "The schedule is included only for the run's owner; scheduleHashMatches says whether it still "
            + "matches the event's scheduleHash. Requires ROLE_USER or ROLE_ADMIN."
    )
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
    public ResponseEntity<ApiResponse<AuditEventDetailDto>> getEventById(
            @Parameter(description = "Audit event ID", required = true) @PathVariable Long id,
            Principal principal
    ) {
        AuditEventDetailDto event = auditEventService.getEventDetail(id, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Audit event retrieved", event));
    }
}
