package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.WorkloadScheduleDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/workload-schedules")
public class WorkloadScheduleController {

    private final com.gacs.backend.service.ScheduleApplicationService applications;

    public WorkloadScheduleController(com.gacs.backend.service.ScheduleApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkloadScheduleDto>>> list(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Workload schedules retrieved", applications.visible(principal.getName())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkloadScheduleDto>> create(
        Principal principal
    ) {
        throw new com.gacs.backend.exception.ScheduleExportRejectedException(
            com.gacs.backend.exception.ScheduleExportRejectedException.Reason.NOT_APPROVED,
            "Direct calendar creation is disabled; use POST /api/schedules/{runId}/apply with an approved run");
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<WorkloadScheduleDto>> updateStatus(
        @PathVariable Long id, @RequestBody WorkloadScheduleDto.StatusRequest request, Principal principal
    ) {
        return ResponseEntity.ok(ApiResponse.ok("Workload schedule updated",
            applications.status(id, request.status(), principal.getName())));
    }
}
