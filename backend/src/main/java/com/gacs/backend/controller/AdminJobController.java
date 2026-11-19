package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin Jobs", description = "Every user's jobs. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/jobs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminJobController {

    private final JobService jobs;

    public AdminJobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "List every user's jobs", description = "Ordered by original start time. Each job carries its owner's name.")
    @GetMapping
    public ApiResponse<List<JobResponseDto>> list() {
        return ApiResponse.ok("Jobs retrieved successfully", jobs.getAllJobs());
    }
}
