package com.gacs.backend.dto;

import java.time.Instant;
import java.util.List;

public record DataFreshnessResponse(
        List<DatasetFreshness> datasets,
        List<FreshnessNotification> notifications,
        Instant checkedAt
) {
    public record DatasetFreshness(
            String dataset,
            Instant latestRecordTimestamp,
            String status,
            Long ageMinutes,
            String message
    ) {}

    public record FreshnessNotification(
            long id,
            String dataset,
            String message,
            Instant createdAt,
            boolean read
    ) {}
}
