package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Tag(name = "Compute Job Management", description = "CRUD endpoints for managing flexible compute workloads submitted for grid-aware scheduling.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Creates a new compute job for the authenticated operator.
     */
    @Operation(
            summary = "Create a compute job",
            description = "Creates a structured compute workload for the authenticated operator per GRID-9 and GRID-112."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Compute job created successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed: invalid resource counts, deadlines, or missing required fields."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            )
    })
    @PostMapping
    public ResponseEntity<ApiResponse<JobResponseDto>> createJob(
            @Valid @RequestBody JobRequestDto request,
            Principal principal
    ) {
        JobResponseDto created = jobService.createJob(request, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Job created successfully", created));
    }

    /**
     * Lists all compute jobs belonging to the authenticated operator.
     */
    @Operation(
            summary = "List all compute jobs",
            description = "Retrieves all compute workloads owned by the authenticated operator, ordered chronologically by original start time ascending."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Compute jobs retrieved successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<JobResponseDto>>> listJobs(Principal principal) {
        List<JobResponseDto> jobs = jobService.getJobsByOwner(principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Jobs retrieved successfully", jobs));
    }

    /**
     * Retrieves details for a specific compute job owned by the authenticated operator.
     */
    @Operation(
            summary = "Get compute job by ID",
            description = "Retrieves full details for a single compute job owned by the authenticated operator. Returns 404 if not found or owned by another user."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Compute job retrieved successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Not Found: Job does not exist or belongs to another operator."
            )
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobResponseDto>> getJob(
            @Parameter(description = "Numeric identifier of the compute job", required = true, example = "101")
            @PathVariable Long id,
            Principal principal
    ) {
        JobResponseDto job = jobService.getJobById(id, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Job retrieved successfully", job));
    }

    /**
     * Updates an existing compute job owned by the authenticated operator.
     */
    @Operation(
            summary = "Update compute job",
            description = "Updates a compute workload. Omitted rawConstraints, parsedAt, and confirmationOutcome preserve stored values; null clears them subject to validation. Changing rawConstraints resets old parse metadata before applying replacements."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Compute job updated successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed: invalid resource counts, deadlines, or constraints."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Not Found: Job does not exist or belongs to another operator."
            )
    })
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<JobResponseDto>> updateJob(
            @Parameter(description = "Numeric identifier of the compute job to update", required = true, example = "101")
            @PathVariable Long id,
            @Valid @RequestBody JobRequestDto request,
            Principal principal
    ) {
        JobResponseDto updated = jobService.updateJob(id, request, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Job updated successfully", updated));
    }

    /**
     * Deletes a compute job owned by the authenticated operator.
     */
    @Operation(
            summary = "Delete compute job",
            description = "Deletes a compute workload owned by the authenticated operator."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Compute job deleted successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Not Found: Job does not exist or belongs to another operator."
            )
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteJob(
            @Parameter(description = "Numeric identifier of the compute job to delete", required = true, example = "101")
            @PathVariable Long id,
            Principal principal
    ) {
        jobService.deleteJob(id, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Job deleted successfully"));
    }
}
