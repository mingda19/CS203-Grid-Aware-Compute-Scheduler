package com.gacs.backend.backtest;

import com.gacs.backend.service.allocation.CapacityLimit;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything one backtest run depends on, besides the input files themselves (whose hashes go in the manifest).
 *
 * @param dataDir          folder with {@code actual_prices.csv} and {@code forecast_<source>.csv}
 * @param profileFiles     workload profile JSON files
 * @param sources          planning price sources, e.g. {@code xgb}; each needs {@code forecast_<source>.csv}.
 *                         Perfect foresight (planning on actual prices) is always included.
 * @param from             first day, inclusive
 * @param to               last day, inclusive
 * @param capacity         per-hour facility capacity (decision D6: the default facility, 256 CPU / 64 GPU)
 * @param exactIterations  iterations the exact solver gets per pass before the heuristic is used (not a time
 *                         budget: that would make results depend on machine speed)
 * @param threads          days solved in parallel
 */
public record BacktestConfig(Path dataDir, List<Path> profileFiles, List<String> sources, LocalDate from,
                             LocalDate to, CapacityLimit capacity, int exactIterations, int threads) {

    public static final String ACTUAL = "actual";

    public BacktestConfig {
        profileFiles = List.copyOf(profileFiles);
        sources = List.copyOf(sources);
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("backtest end " + to + " is before its start " + from);
        }
        if (profileFiles.isEmpty()) {
            throw new IllegalArgumentException("at least one workload profile is required");
        }
        if (sources.contains(ACTUAL)) {
            throw new IllegalArgumentException("'actual' is always run as perfect foresight; do not list it as a source");
        }
        if (exactIterations < 1) {
            throw new IllegalArgumentException("exactIterations must be at least 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
    }

    public Path actualPricesFile() {
        return dataDir.resolve("actual_prices.csv");
    }

    public Path forecastFile(String source) {
        return dataDir.resolve("forecast_" + source + ".csv");
    }
}
