package com.gacs.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PriceHistoryResponse(
    List<PricePoint> points,
    long totalRecords,
    int limit,
    int offset
) {
    public record PricePoint(LocalDateTime intervalStartUtc, Double sppUsdMwh) {}
}
