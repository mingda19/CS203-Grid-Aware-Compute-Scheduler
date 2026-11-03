package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.WorkloadScheduleDto;
import com.gacs.backend.service.ScheduleApplicationService;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules/{runId}")
public class ScheduleApplicationController {
    private final ScheduleApplicationService service;
    public ScheduleApplicationController(ScheduleApplicationService service) { this.service = service; }
    @PostMapping("/apply")
    public ApiResponse<List<WorkloadScheduleDto>> apply(@PathVariable UUID runId, Principal principal) {
        return ApiResponse.ok("Approved schedule applied", service.apply(runId, principal.getName()));
    }
    @GetMapping
    public ApiResponse<ScheduleApplicationService.PlanDetail> detail(@PathVariable UUID runId, Principal principal) {
        return ApiResponse.ok("Schedule plan retrieved", service.detail(runId, principal.getName()));
    }
}
