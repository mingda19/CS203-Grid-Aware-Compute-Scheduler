package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.PriceHistoryResponse;
import com.gacs.backend.dto.PriceForecastResponse;
import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/prices")
public class PriceHistoryController {

    private static final int MAX_LIMIT = 5000;
    private final ElectricalPriceRepository prices;
    private final PredictedPriceRepository predictions;

    public PriceHistoryController(ElectricalPriceRepository prices, PredictedPriceRepository predictions) {
        this.prices = prices;
        this.predictions = predictions;
    }

    @GetMapping("/forecast")
    public ResponseEntity<ApiResponse<PriceForecastResponse>> getForecast(
        @RequestParam(defaultValue = "LZ_NORTH") String location,
        @RequestParam(defaultValue = "48") int hours
    ) {
        if (location.isBlank() || location.length() > 100) {
            throw new IllegalArgumentException("location must be a non-empty settlement point name");
        }
        if (hours < 1 || hours > 168) {
            throw new IllegalArgumentException("hours must be between 1 and 168");
        }
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC);
        var points = predictions.findLatestForecast(location, start, start.plusHours(hours)).stream()
            .map(record -> new PriceForecastResponse.ForecastPoint(record.getIntervalStartUtc(),
                record.getPredictedPrice(), record.getModelVersion(), record.getGeneratedAt(), null))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Price forecast retrieved",
            new PriceForecastResponse(points, hours)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PriceHistoryResponse>> getHistory(
        @RequestParam(defaultValue = "LZ_NORTH") String location,
        @RequestParam LocalDate startDate,
        @RequestParam LocalDate endDate,
        @RequestParam(defaultValue = "5000") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        if (location.isBlank() || location.length() > 100) {
            throw new IllegalArgumentException("location must be a non-empty settlement point name");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be zero or greater");
        }
        if (offset % limit != 0) {
            throw new IllegalArgumentException("offset must be a multiple of limit");
        }

        // Date filters are UTC calendar dates. The exclusive upper bound includes the full endDate.
        var start = startDate.atStartOfDay();
        var endExclusive = endDate.plusDays(1).atStartOfDay();
        List<ElectricalPrice> records = prices
            .findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAsc(
                location, start, endExclusive, PageRequest.of(offset / limit, limit)
            );
        long total = prices.countByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThan(
            location, start, endExclusive
        );
        var points = records.stream()
            .map(record -> new PriceHistoryResponse.PricePoint(
                record.getIntervalStartUtc(), record.getSppUsdMwh()
            ))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Price history retrieved", new PriceHistoryResponse(points, total, limit, offset)));
    }
}
