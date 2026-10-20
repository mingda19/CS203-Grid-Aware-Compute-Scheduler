package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.service.ScheduleOptimizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Schedule optimization endpoint, per the Schedule Optimization Engine Contract
 * (GRID-124) in {@code docs/api-contract.md}.
 */
@Tag(name = "Schedule Optimization",
    description = "Generates a cost-optimized, deadline-safe schedule for an operator's compute jobs.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/schedules")
public class ScheduleOptimizationController {

    private final ScheduleOptimizationService optimizationService;

    public ScheduleOptimizationController(ScheduleOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @Operation(
        summary = "Run the schedule optimizer",
        description = "Evaluates the supplied jobs - or, when the jobs field is omitted, the "
            + "authenticated operator's own eligible stored jobs - against the price forecast "
            + "for the requested location and horizon, and returns a proposed schedule with its "
            + "cost against the fixed-schedule baseline. Jobs that cannot be moved are returned "
            + "at their original time with a per-job status explaining why."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Optimized schedule generated.",
            content = @Content(schema = @Schema(implementation = OptimizeResponseDto.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid request: empty job set, malformed job fields, a horizon outside "
                + "1-168 hours, non-positive capacity, or a job whose baseline cannot be priced."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden: invalid or missing JWT access token."
        )
    })
    @PostMapping("/optimize")
    public ResponseEntity<ApiResponse<OptimizeResponseDto>> optimize(
        @RequestBody(required = false) OptimizeRequestDto request,
        Principal principal
    ) {
        OptimizeResponseDto plan = optimizationService.optimize(request, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Optimized schedule generated", plan));
    }
}
