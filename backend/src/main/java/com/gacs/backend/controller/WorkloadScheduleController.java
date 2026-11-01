package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.WorkloadScheduleDto;
import com.gacs.backend.model.WorkloadSchedule;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final WorkloadScheduleRepository schedules;
    private final com.gacs.backend.service.ScheduleApplicationService applications;

    public WorkloadScheduleController(WorkloadScheduleRepository schedules, com.gacs.backend.service.ScheduleApplicationService applications) {
        this.applications = applications;
        this.schedules = schedules;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkloadScheduleDto>>> list(Principal principal) {
        var data = schedules.findByOwnerEmailOrderByStartUtcAsc(principal.getName()).stream()
            .map(WorkloadScheduleController::toDto).toList();
        return ResponseEntity.ok(ApiResponse.ok("Workload schedules retrieved", data));
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

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Principal principal) {
        var schedule = schedules.findByIdAndOwnerEmail(id, principal.getName())
            .orElseThrow(() -> new IllegalArgumentException("Workload schedule not found"));
        schedules.delete(schedule);
        return ResponseEntity.ok(ApiResponse.ok("Workload schedule deleted"));
    }

    private static WorkloadScheduleDto toDto(WorkloadSchedule schedule) {
        return com.gacs.backend.service.ScheduleApplicationService.dto(schedule);
    }
}
