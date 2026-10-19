package com.gacs.backend.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An hourly price curve in $/MWh, keyed by slot start (UTC, truncated to the hour).
 *
 * <p>Both the baseline cost and the optimized cost are computed from one instance of
 * this class, which is what makes them comparable (GRID-124 AC5.3). The
 * {@code seeded} flag records whether the curve came from stored model forecasts or
 * from the seeded fallback.
 */
public final class PriceSeries {

    private final TreeMap<LocalDateTime, Double> pricesPerMwh;
    private final boolean seeded;

    public PriceSeries(Map<LocalDateTime, Double> pricesPerMwh, boolean seeded) {
        this.pricesPerMwh = new TreeMap<>(pricesPerMwh);
        this.seeded = seeded;
    }

    /** Price for the slot beginning at {@code slotStart}, empty when that hour is not covered. */
    public Optional<Double> priceAt(LocalDateTime slotStart) {
        return Optional.ofNullable(pricesPerMwh.get(slotStart));
    }

    /** True when this curve came from the seeded fallback rather than stored forecasts. */
    public boolean isSeeded() {
        return seeded;
    }

    public boolean isEmpty() {
        return pricesPerMwh.isEmpty();
    }

    public Map<LocalDateTime, Double> asMap() {
        return Collections.unmodifiableMap(pricesPerMwh);
    }

    /** Cheapest covered slot, used only to build the plain-English explanation. */
    public Optional<Map.Entry<LocalDateTime, Double>> cheapestSlot() {
        return pricesPerMwh.entrySet().stream().min(Map.Entry.comparingByValue());
    }

    /** Dearest covered slot, used only to build the plain-English explanation. */
    public Optional<Map.Entry<LocalDateTime, Double>> dearestSlot() {
        return pricesPerMwh.entrySet().stream().max(Map.Entry.comparingByValue());
    }
}
