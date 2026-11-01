package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.service.SchedulePlanQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/schedules")
public class SchedulePlanQueryController {
    private final SchedulePlanQueryService service;

    public SchedulePlanQueryController(SchedulePlanQueryService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List the authenticated operator's plans, newest first",
        description = "Paged stored review states. Read a plan's detail before deciding to check its current validity.")
    public ApiResponse<Page<SchedulePlanQueryService.PlanSummary>> list(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        Principal principal) {
        return ApiResponse.ok("Schedule plans retrieved", service.list(principal.getName(), page, size));
    }
}
