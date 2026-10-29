package com.gacs.backend.backtest;

import com.gacs.backend.service.PriceSeries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One price per UTC hour, read from a CSV written by {@code backtest/export_prices.py}
 * ({@code interval_start_utc,price}). Days are only ever handed out whole: a day missing any hour is skipped by
 * the harness and counted, never filled in.
 *
 * @param name where the prices came from, e.g. {@code actual} or {@code xgb}
 */
public record HourlyPrices(String name, Path source, TreeMap<LocalDateTime, Double> prices) {

    public static HourlyPrices load(String name, Path file) {
        TreeMap<LocalDateTime, Double> prices = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read prices from " + file + ": " + e.getMessage(), e);
        }
        if (lines.isEmpty() || !lines.getFirst().trim().equals("interval_start_utc,price")) {
            throw new IllegalArgumentException(file + ": expected header 'interval_start_utc,price'");
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException(file + " line " + (i + 1) + ": expected 2 columns");
            }
            LocalDateTime hour = LocalDateTime.parse(parts[0]);
            double price = Double.parseDouble(parts[1]);
            if (!Double.isFinite(price)) {
                throw new IllegalArgumentException(file + " line " + (i + 1) + ": price is not a number");
            }
            if (prices.put(hour, price) != null) {
                throw new IllegalArgumentException(file + " line " + (i + 1) + ": hour " + hour + " appears twice");
            }
        }
        return new HourlyPrices(name, file, prices);
    }

    /** The day's 24 hourly prices, or empty when any hour is missing. */
    public Optional<PriceSeries> day(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        Map<LocalDateTime, Double> hours = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) {
            Double price = prices.get(start.plusHours(h));
            if (price == null) {
                return Optional.empty();
            }
            hours.put(start.plusHours(h), price);
        }
        return Optional.of(new PriceSeries(hours, false));
    }
}
