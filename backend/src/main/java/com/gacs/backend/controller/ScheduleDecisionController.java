package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.ScheduleDecisionDto;
import com.gacs.backend.model.DecisionAction;
import com.gacs.backend.service.ScheduleReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules/{runId}")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Schedule Decisions", description = "Records owner decisions without dispatching workloads. Missing and unowned plans return 404.")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Decision appended and review state updated atomically. Repeated requests append history."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed UUID or JSON, non-object body, or invalid comment (must be a string of at most 2000 characters)."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authenticated email has no database user."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing or invalid JWT."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Optimization plan not found or not owned by the operator.")
})
public class ScheduleDecisionController {
    private final ScheduleReviewService reviews;

    public ScheduleDecisionController(ScheduleReviewService reviews) { this.reviews = reviews; }

    @PostMapping("/approve")
    @Operation(summary = "Record approval of an optimization plan")
    public ResponseEntity<ApiResponse<ScheduleDecisionDto>> approve(@PathVariable UUID runId,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ScheduleDecisionDto.Request.class)))
        @RequestBody Map<String, Object> request, Principal principal) {
        return decide(runId, DecisionAction.APPROVE, request, principal);
    }

    @PostMapping("/reject")
    @Operation(summary = "Record rejection of an optimization plan")
    public ResponseEntity<ApiResponse<ScheduleDecisionDto>> reject(@PathVariable UUID runId,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ScheduleDecisionDto.Request.class)))
        @RequestBody Map<String, Object> request, Principal principal) {
        return decide(runId, DecisionAction.REJECT, request, principal);
    }

    @PostMapping("/revise")
    @Operation(summary = "Record a request for revision without editing or rerunning the plan")
    public ResponseEntity<ApiResponse<ScheduleDecisionDto>> revise(@PathVariable UUID runId,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = ScheduleDecisionDto.Request.class)))
        @RequestBody Map<String, Object> request, Principal principal) {
        return decide(runId, DecisionAction.REVISE, request, principal);
    }

    private ResponseEntity<ApiResponse<ScheduleDecisionDto>> decide(UUID runId, DecisionAction action,
                                                                    Map<String, Object> request, Principal principal) {
        Object comment = request.get("comment");
        if (comment != null && !(comment instanceof String)) {
            throw new IllegalArgumentException("comment must be a string");
        }
        return ResponseEntity.ok(ApiResponse.ok("Schedule decision recorded", reviews.decide(runId, action,
            (String) comment, principal.getName())));
    }
}
