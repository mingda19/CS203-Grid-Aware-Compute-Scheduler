package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.PriceHistoryResponse;
import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.repository.ElectricalPriceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/prices")
public class PriceHistoryController {

    private static final int MAX_LIMIT = 5000;
    private final ElectricalPriceRepository prices;

    public PriceHistoryController(ElectricalPriceRepository prices) {
        this.prices = prices;
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
