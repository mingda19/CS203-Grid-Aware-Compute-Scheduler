package com.gacs.backend.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An hourly price curve in $/MWh, keyed by slot start (UTC, truncated to the hour).
 *
 * <p>Both the baseline cost and the optimized cost are computed from one instance of
 * this class, which is what makes them comparable (GRID-124 AC5.3). The
 * {@code seeded} flag records whether the curve came from stored model forecasts or
 * from the seeded fallback.
 *
 * <p>Provenance is tracked per slot, not just per curve. A stored forecast reaches only as far as the
 * model predicted - the day-ahead market is cleared about a day out - while a job's window can run
 * days past that. Those later hours are filled from the history-based fallback curve so the run can
 * proceed, and {@link #estimatedSlots()} names exactly which ones, so a plan that leans on an
 * estimate can be reported as provisional rather than presented as precise (contract FR-13), and no
 * estimated price can be mistaken for a forecast one (FR-04).
 */
public final class PriceSeries {

    private final TreeMap<LocalDateTime, Double> pricesPerMwh;
    private final boolean seeded;
    private final TreeSet<LocalDateTime> estimated;

    /**
     * A curve whose provenance is uniform: when {@code seeded}, every slot is an estimate; otherwise
     * every slot is a stored model forecast.
     */
    public PriceSeries(Map<LocalDateTime, Double> pricesPerMwh, boolean seeded) {
        this(pricesPerMwh, seeded, seeded ? pricesPerMwh.keySet() : Set.of());
    }

    /**
     * @param seeded    true when no stored forecast covered the window at all and the whole curve came
     *                  from the fallback
     * @param estimated the slots whose price did not come from a stored model forecast. A subset of
     *                  {@code pricesPerMwh}'s keys; entries outside it are ignored.
     */
    public PriceSeries(Map<LocalDateTime, Double> pricesPerMwh, boolean seeded, Set<LocalDateTime> estimated) {
        this.pricesPerMwh = new TreeMap<>(pricesPerMwh);
        this.seeded = seeded;
        this.estimated = new TreeSet<>();
        for (LocalDateTime slot : estimated) {
            if (this.pricesPerMwh.containsKey(slot)) {
                this.estimated.add(slot);
            }
        }
    }

    /** Price for the slot beginning at {@code slotStart}, empty when that hour is not covered. */
    public Optional<Double> priceAt(LocalDateTime slotStart) {
        return Optional.ofNullable(pricesPerMwh.get(slotStart));
    }

    /** True when this curve came from the seeded fallback rather than stored forecasts. */
    public boolean isSeeded() {
        return seeded;
    }

    /**
     * The slots priced from the fallback curve rather than a stored model forecast, earliest first.
     * Empty when every price is a real forecast.
     */
    public Set<LocalDateTime> estimatedSlots() {
        return Collections.unmodifiableSet(estimated);
    }

    /** True when any price in this curve is an estimate, so a plan using it is provisional. */
    public boolean usedEstimatedPrices() {
        return !estimated.isEmpty();
    }

    /** True when {@code slotStart}'s price is an estimate rather than a stored forecast. */
    public boolean isEstimatedAt(LocalDateTime slotStart) {
        return estimated.contains(slotStart);
    }

    /**
     * True when a job may be <em>moved into</em> {@code slotStart}, as opposed to merely having a
     * baseline priced there.
     *
     * <p>An estimated price is good enough to cost a baseline the operator already committed to, but
     * not to justify moving work. The fallback curve is an hour-of-day average whose overnight trough
     * undercuts most real forecast hours, so treating estimated hours as candidates would have the
     * optimiser chase savings the model never predicted and report them as though it had.
     *
     * <p>The exception is a wholly {@link #isSeeded() seeded} curve: there is no forecast to prefer,
     * the fallback is the best information available, and the run is required to proceed on it
     * (GRID-124 AC5.2). Every hour is then a candidate and the whole plan is flagged as seeded.
     */
    public boolean isCandidateAt(LocalDateTime slotStart) {
        return priceAt(slotStart).isPresent() && (seeded || !isEstimatedAt(slotStart));
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
