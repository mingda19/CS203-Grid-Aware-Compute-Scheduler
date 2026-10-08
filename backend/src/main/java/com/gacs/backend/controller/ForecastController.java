package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.ForecastResponse;
import com.gacs.backend.dto.ForecastResponse.ForecastPoint;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {

    private static final int MAX_DAYS = 14;
    private final PredictedPriceRepository predictions;

    public ForecastController(PredictedPriceRepository predictions) {
        this.predictions = predictions;
    }

    @Operation(summary = "Get hourly price forecasts",
        description = "Returns predicted day-ahead prices (USD/MWh) per target hour, UTC. When several runs cover the same hour, "
            + "the most recently generated one is returned. An empty points list means no forecast exists for the range.")
    @GetMapping("/prices")
    public ResponseEntity<ApiResponse<ForecastResponse>> getPriceForecast(
        @Parameter(description = "Settlement point") @RequestParam(defaultValue = "LZ_NORTH") String location,
        @Parameter(description = "First UTC date, inclusive (default: today)") @RequestParam(required = false) LocalDate startDate,
        @Parameter(description = "Last UTC date, inclusive (default: startDate + 1 day)") @RequestParam(required = false) LocalDate endDate
    ) {
        if (location.isBlank() || location.length() > 100) {
            throw new IllegalArgumentException("location must be a non-empty settlement point name");
        }
        LocalDate start = startDate != null ? startDate : LocalDate.now(ZoneOffset.UTC);
        LocalDate end = endDate != null ? endDate : start.plusDays(1);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        if (start.plusDays(MAX_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("date range must not exceed " + MAX_DAYS + " days");
        }

        // Rows are ordered by hour then generatedAt, so the last one put per hour is the latest run.
        Map<LocalDateTime, PredictedPrice> latest = new LinkedHashMap<>();
        predictions.findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            location, start.atStartOfDay(), end.plusDays(1).atStartOfDay()
        ).forEach(p -> latest.put(p.getIntervalStartUtc(), p));

        var points = latest.values().stream()
            .map(p -> new ForecastPoint(p.getIntervalStartUtc(), p.getPredictedPrice(), p.getModelVersion(), p.getGeneratedAt(), null))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Forecast retrieved", new ForecastResponse(location, points)));
    }
}
