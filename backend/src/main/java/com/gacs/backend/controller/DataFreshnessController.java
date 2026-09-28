package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.DataFreshnessResponse;
import com.gacs.backend.service.DataFreshnessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class DataFreshnessController {

    private final DataFreshnessService dataFreshnessService;

    public DataFreshnessController(DataFreshnessService dataFreshnessService) {
        this.dataFreshnessService = dataFreshnessService;
    }

    @GetMapping("/data-freshness")
    public ResponseEntity<ApiResponse<DataFreshnessResponse>> dataFreshness() {
        return ResponseEntity.ok(ApiResponse.ok("Data freshness checked", dataFreshnessService.checkFreshness()));
    }

    @PostMapping("/data-freshness/notifications/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> markNotificationRead(@PathVariable long notificationId) {
        if (!dataFreshnessService.markRead(notificationId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok("Notification marked as read"));
    }
}
