package com.gacs.backend.backtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * (Re)computes the metrics of an existing backtest run. Skipped in normal builds; run it from {@code backend/} with:
 * <pre>
 *   mvn test -Dtest=BacktestMetricsRun -Dbacktest.metrics=../backtest/output/&lt;run-id&gt;
 * </pre>
 * New runs of {@link BacktestRun} compute their metrics themselves.
 */
@EnabledIfSystemProperty(named = "backtest.metrics", matches = ".+")
class BacktestMetricsRun {

    @Test
    void metrics() {
        Path backend = Path.of("").toAbsolutePath();
        Path runDir = Path.of(System.getProperty("backtest.metrics")).toAbsolutePath().normalize();
        BacktestMetrics.Result metrics = MetricsFiles.write(runDir, backend.getParent());
        BacktestRun.printHeadline(metrics);
        System.out.println("Metrics written to " + runDir);
    }
}
