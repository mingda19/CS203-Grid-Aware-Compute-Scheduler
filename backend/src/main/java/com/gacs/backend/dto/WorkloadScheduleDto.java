package com.gacs.backend.dto;

import java.time.LocalDateTime;

public record WorkloadScheduleDto(
    Long id,
    String title,
    String type,
    String cluster,
    double powerKw,
    String savings,
    String status,
    LocalDateTime startUtc,
    LocalDateTime endUtc,
    String notes
) {
    public record CreateRequest(
        String title,
        String type,
        String cluster,
        double powerKw,
        String savings,
        LocalDateTime startUtc,
        LocalDateTime endUtc,
        String notes
    ) {}

    public record StatusRequest(String status) {}
}
