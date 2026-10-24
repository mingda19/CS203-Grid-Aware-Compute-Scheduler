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
        description = "Places the supplied jobs against the price forecast (an explicit forecast[] or a "
            + "forecastLocation) and the cluster's per-hour capacity. Each job's work is split into "
            + "hourly shares over consecutive hours. Places as many jobs as possible, then minimises "
            + "cost, and reports each job's cost against its baseline. A job with no room left is "
            + "returned as NO_FEASIBLE_SLOT with a reason; that is not an error."
    )
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Optimized schedule generated.",
            content = @Content(schema = @Schema(implementation = OptimizeResponseDto.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid input: missing or malformed fields, duplicate job ids, a time not on the "
                + "hour, deadline before start, no forecast or forecastLocation, or a job that cannot "
                + "finish by its deadline even alone on the cluster."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Missing data: a job's baseline hours have no forecast price, so its baseline "
                + "cannot be priced."
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden: invalid or missing JWT access token."
        )
    })
    @PostMapping("/optimize")
    public ResponseEntity<ApiResponse<OptimizeResponseDto>> optimize(
        @RequestBody(required = false) OptimizeRequestDto request
    ) {
        OptimizeResponseDto plan = optimizationService.optimize(request);
        return ResponseEntity.ok(ApiResponse.ok("Optimized schedule generated", plan));
    }
}
