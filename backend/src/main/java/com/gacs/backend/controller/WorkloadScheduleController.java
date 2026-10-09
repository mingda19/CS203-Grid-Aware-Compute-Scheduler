package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.WorkloadScheduleDto;
import com.gacs.backend.model.WorkloadSchedule;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import org.springframework.http.HttpStatus;
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
import java.util.Set;

@RestController
@RequestMapping("/api/workload-schedules")
public class WorkloadScheduleController {

    private static final Set<String> VALID_STATUSES = Set.of("Scheduled", "Running", "Throttled", "Completed");
    private final WorkloadScheduleRepository schedules;

    public WorkloadScheduleController(WorkloadScheduleRepository schedules) {
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
        @RequestBody WorkloadScheduleDto.CreateRequest request, Principal principal
    ) {
        validate(request);
        var schedule = new WorkloadSchedule();
        schedule.setOwnerEmail(principal.getName());
        schedule.setTitle(request.title().trim());
        schedule.setType(request.type().trim());
        schedule.setCluster(request.cluster().trim());
        schedule.setPowerKw(request.powerKw());
        schedule.setSavings(request.savings());
        schedule.setStatus("Scheduled");
        schedule.setStartUtc(request.startUtc());
        schedule.setEndUtc(request.endUtc());
        schedule.setNotes(request.notes());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Workload schedule created", toDto(schedules.save(schedule))));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<WorkloadScheduleDto>> updateStatus(
        @PathVariable Long id, @RequestBody WorkloadScheduleDto.StatusRequest request, Principal principal
    ) {
        if (request.status() == null || !VALID_STATUSES.contains(request.status())) {
            throw new IllegalArgumentException("status must be Scheduled, Running, Throttled, or Completed");
        }
        var schedule = schedules.findByIdAndOwnerEmail(id, principal.getName())
            .orElseThrow(() -> new IllegalArgumentException("Workload schedule not found"));
        schedule.setStatus(request.status());
        return ResponseEntity.ok(ApiResponse.ok("Workload schedule updated", toDto(schedules.save(schedule))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Principal principal) {
        var schedule = schedules.findByIdAndOwnerEmail(id, principal.getName())
            .orElseThrow(() -> new IllegalArgumentException("Workload schedule not found"));
        schedules.delete(schedule);
        return ResponseEntity.ok(ApiResponse.ok("Workload schedule deleted"));
    }

    private static void validate(WorkloadScheduleDto.CreateRequest request) {
        if (request.title() == null || request.title().isBlank() || request.title().length() > 200) {
            throw new IllegalArgumentException("title must be between 1 and 200 characters");
        }
        if (request.type() == null || request.type().isBlank() || request.type().length() > 40) {
            throw new IllegalArgumentException("type must be between 1 and 40 characters");
        }
        if (request.cluster() == null || request.cluster().isBlank() || request.cluster().length() > 200) {
            throw new IllegalArgumentException("cluster must be between 1 and 200 characters");
        }
        if (!Double.isFinite(request.powerKw()) || request.powerKw() < 0) {
            throw new IllegalArgumentException("powerKw must be zero or greater");
        }
        if (request.startUtc() == null || request.endUtc() == null || !request.endUtc().isAfter(request.startUtc())) {
            throw new IllegalArgumentException("endUtc must be after startUtc");
        }
        if (request.notes() != null && request.notes().length() > 2000) {
            throw new IllegalArgumentException("notes must be at most 2000 characters");
        }
    }

    private static WorkloadScheduleDto toDto(WorkloadSchedule schedule) {
        return new WorkloadScheduleDto(schedule.getId(), schedule.getTitle(), schedule.getType(),
            schedule.getCluster(), schedule.getPowerKw(), schedule.getSavings(), schedule.getStatus(),
            schedule.getStartUtc(), schedule.getEndUtc(), schedule.getNotes());
    }
}
