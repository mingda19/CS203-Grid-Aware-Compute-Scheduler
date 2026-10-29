package com.gacs.backend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gacs.backend.backtest.BacktestMetrics.Daily;
import com.gacs.backend.backtest.BacktestMetrics.SolveTimes;
import com.gacs.backend.backtest.BacktestMetrics.Summary;
import com.gacs.backend.backtest.BacktestRunner.DayRow;
import com.gacs.backend.backtest.BacktestRunner.JobRow;
import com.gacs.backend.backtest.BacktestRunner.Timing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads a backtest run folder, computes {@link BacktestMetrics} from it, and writes:
 * <ul>
 *   <li>{@code metrics_daily.csv}: one row per profile, day and source;</li>
 *   <li>{@code metrics_summary.csv}: one row per profile and source, for the whole period;</li>
 *   <li>{@code metrics.json}: the summaries, solve times (not repeatable), bootstrap settings and the hashes of
 *       everything the metrics were computed from.</li>
 * </ul>
 * The two CSV files are repeatable: the same run always gives byte-identical copies.
 */
public final class MetricsFiles {

    public static final List<String> REPEATABLE_FILES = List.of("metrics_daily.csv", "metrics_summary.csv");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MetricsFiles() {
    }

    /**
     * Computes and writes the metrics for a run. The price files are taken from the run's manifest and must still
     * have the hashes recorded there, so the metrics always use the prices the run used.
     *
     * @param repoRoot what the manifest's {@code inputs.dataDir} is relative to
     */
    public static BacktestMetrics.Result write(Path runDir, Path repoRoot) {
        JsonNode manifest = BacktestOutput.read(runDir.resolve(BacktestOutput.MANIFEST));
        Path dataDir = repoRoot.resolve(manifest.path("inputs").path("dataDir").asText());
        Map<String, String> inputHashes = BacktestOutput.inputHashes(manifest);
        inputHashes.forEach((file, hash) -> {
            if (!BacktestOutput.sha256(dataDir.resolve(file)).equals(hash)) {
                throw new IllegalStateException("input " + file + " has changed since run "
                        + manifest.path("runId").asText() + "; metrics would use different prices");
            }
        });

        HourlyPrices actual = HourlyPrices.load(BacktestConfig.ACTUAL, dataDir.resolve("actual_prices.csv"));
        Map<String, HourlyPrices> forecasts = new LinkedHashMap<>();
        for (JsonNode source : manifest.path("config").path("sources")) {
            forecasts.put(source.asText(),
                    HourlyPrices.load(source.asText(), dataDir.resolve("forecast_" + source.asText() + ".csv")));
        }

        BacktestMetrics.Result result = BacktestMetrics.compute(readDays(runDir.resolve("days.csv")),
                readJobs(runDir.resolve("jobs.csv")), readTimings(runDir.resolve("timings.csv")), actual, forecasts);
        try {
            Files.writeString(runDir.resolve("metrics_daily.csv"), String.join("\n", dailyCsv(result.daily())) + "\n");
            Files.writeString(runDir.resolve("metrics_summary.csv"),
                    String.join("\n", summaryCsv(result.summaries())) + "\n");
            Files.writeString(runDir.resolve("metrics.json"), JSON.writeValueAsString(json(result, runDir, manifest)) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write metrics to " + runDir, e);
        }
        return result;
    }

    // ------------------------------------------------------------------ reading a run

    static List<DayRow> readDays(Path file) {
        return readCsv(file, r -> new DayRow(r.get("profile"), LocalDate.parse(r.get("date")), r.get("source"),
                Integer.parseInt(r.get("jobs")), Integer.parseInt(r.get("scheduled")), Integer.parseInt(r.get("exempt")),
                Double.parseDouble(r.get("energy_kwh")), Double.parseDouble(r.get("baseline_cost_actual")),
                Double.parseDouble(r.get("optimised_cost_actual")), Double.parseDouble(r.get("baseline_cost_planned")),
                Double.parseDouble(r.get("optimised_cost_planned")), Boolean.parseBoolean(r.get("proven_optimal")),
                Integer.parseInt(r.get("violations")), Integer.parseInt(r.get("rejected_draws")),
                Integer.parseInt(r.get("jobs_not_generated"))));
    }

    static List<JobRow> readJobs(Path file) {
        return readCsv(file, r -> new JobRow(r.get("profile"), LocalDate.parse(r.get("date")), r.get("source"),
                Long.parseLong(r.get("job_id")), r.get("job_class"), Double.parseDouble(r.get("cpu_hours")),
                Double.parseDouble(r.get("gpu_hours")), Double.parseDouble(r.get("energy_kwh")),
                LocalDateTime.parse(r.get("original_start")), LocalDateTime.parse(r.get("deadline")),
                Integer.parseInt(r.get("flexibility_hours")), Boolean.parseBoolean(r.get("exempt")), r.get("status"),
                time(r.get("scheduled_start")), time(r.get("scheduled_end")), Integer.parseInt(r.get("hours")),
                number(r.get("baseline_cost_actual")), number(r.get("optimised_cost_actual")),
                number(r.get("optimised_cost_planned"))));
    }

    static List<Timing> readTimings(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        return readCsv(file, r -> new Timing(r.get("profile"), LocalDate.parse(r.get("date")), r.get("source"),
                Long.parseLong(r.get("solve_ms"))));
    }

    private static <T> List<T> readCsv(Path file, Function<Map<String, String>, T> parse) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        String[] header = lines.getFirst().split(",", -1);
        List<T> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] cells = lines.get(i).split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.length; c++) {
                row.put(header[c], cells[c]);
            }
            rows.add(parse.apply(row));
        }
        return rows;
    }

    private static LocalDateTime time(String cell) {
        return cell.isEmpty() ? null : LocalDateTime.parse(cell);
    }

    private static Double number(String cell) {
        return cell.isEmpty() ? null : Double.parseDouble(cell);
    }

    // ------------------------------------------------------------------ writing

    static List<String> dailyCsv(List<Daily> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,date,source,jobs,scheduled,not_scheduled,counts_for_savings,energy_kwh_baseline,"
                + "energy_kwh_optimised,baseline_cost,realised_cost,projected_baseline_cost,projected_cost,perfect_cost,"
                + "realised_savings_usd,realised_savings_pct,projected_savings_usd,projected_savings_pct,"
                + "projection_gap_pct,forecast_error_cost_usd,perfect_savings_pct,deadline_adherence,violations,"
                + "proven_optimal,forecast_mae_all,forecast_rmse_all,forecast_mae_used,forecast_rmse_used,used_hours");
        for (Daily d : rows) {
            lines.add(String.join(",", d.profile(), d.date().toString(), d.source(), str(d.jobs()),
                    str(d.scheduled()), str(d.notScheduled()), String.valueOf(d.countsForSavings()),
                    num(d.energyKwhBaseline()), num(d.energyKwhOptimised()), num(d.baselineCost()),
                    num(d.realisedCost()), num(d.projectedBaselineCost()), num(d.projectedCost()),
                    num(d.perfectCost()), num(d.realisedSavingsUsd()), num(d.realisedSavingsPct()),
                    num(d.projectedSavingsUsd()), num(d.projectedSavingsPct()), num(d.projectionGapPct()),
                    num(d.forecastErrorCostUsd()), num(d.perfectSavingsPct()), num(d.deadlineAdherence()),
                    str(d.violations()), String.valueOf(d.provenOptimal()), num(d.forecastMaeAll()),
                    num(d.forecastRmseAll()), num(d.forecastMaeUsed()), num(d.forecastRmseUsed()),
                    str(d.usedHours())));
        }
        return lines;
    }

    static List<String> summaryCsv(List<Summary> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,source,days,days_in_savings,days_excluded,baseline_cost,realised_cost,"
                + "projected_baseline_cost,projected_cost,perfect_cost,energy_kwh_baseline,energy_kwh_optimised,"
                + "realised_savings_pct,realised_savings_ci_low,realised_savings_ci_high,projected_savings_pct,"
                + "projection_gap_pct,perfect_savings_pct,share_of_possible_savings_captured,forecast_error_cost_usd,"
                + "daily_savings_mean_pct,daily_savings_median_pct,daily_savings_std_pct,daily_savings_p10_pct,"
                + "daily_savings_p90_pct,negative_days_share,jobs,scheduled,deadline_adherence,violations,"
                + "proven_optimal_share,forecast_mae_all,forecast_rmse_all,forecast_mae_used,forecast_rmse_used");
        for (Summary s : rows) {
            lines.add(String.join(",", s.profile(), s.source(), str(s.days()), str(s.daysInSavings()),
                    str(s.daysExcluded()), num(s.baselineCost()), num(s.realisedCost()),
                    num(s.projectedBaselineCost()), num(s.projectedCost()), num(s.perfectCost()),
                    num(s.energyKwhBaseline()), num(s.energyKwhOptimised()), num(s.realisedSavingsPct()),
                    num(s.realisedSavingsCiLow()), num(s.realisedSavingsCiHigh()), num(s.projectedSavingsPct()),
                    num(s.projectionGapPct()), num(s.perfectSavingsPct()), num(s.shareOfPossibleSavingsCaptured()),
                    num(s.forecastErrorCostUsd()), num(s.dailySavingsMeanPct()), num(s.dailySavingsMedianPct()),
                    num(s.dailySavingsStdPct()), num(s.dailySavingsP10Pct()), num(s.dailySavingsP90Pct()),
                    num(s.negativeDaysShare()), str(s.jobs()), str(s.scheduled()), num(s.deadlineAdherence()),
                    str(s.violations()), num(s.provenOptimalShare()), num(s.forecastMaeAll()),
                    num(s.forecastRmseAll()), num(s.forecastMaeUsed()), num(s.forecastRmseUsed())));
        }
        return lines;
    }

    private static ObjectNode json(BacktestMetrics.Result result, Path runDir, JsonNode manifest) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", manifest.path("runId").asText());
        ObjectNode computedFrom = root.putObject("computedFrom");
        computedFrom.put("days.csv", BacktestOutput.sha256(runDir.resolve("days.csv")));
        computedFrom.put("jobs.csv", BacktestOutput.sha256(runDir.resolve("jobs.csv")));
        computedFrom.set("inputs", manifest.path("inputs").path("sha256"));
        ObjectNode bootstrap = root.putObject("bootstrap");
        bootstrap.put("method", "moving block over days in date order, ratio of totals");
        bootstrap.put("blockDays", BacktestMetrics.BOOTSTRAP_BLOCK_DAYS);
        bootstrap.put("draws", BacktestMetrics.BOOTSTRAP_DRAWS);
        bootstrap.put("seed", BacktestMetrics.BOOTSTRAP_SEED);
        bootstrap.put("interval", "2.5% - 97.5%");
        ObjectNode outputs = root.putObject("outputs");
        for (String name : REPEATABLE_FILES) {
            outputs.put(name, BacktestOutput.sha256(runDir.resolve(name)));
        }
        root.set("summaries", JSON.valueToTree(result.summaries()));
        ArrayNode times = root.putArray("solveTimesNotRepeatable");
        for (SolveTimes t : result.solveTimes()) {
            times.add(JSON.valueToTree(t));
        }
        return root;
    }

    private static String num(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.8f", value);
    }

    private static String str(int value) {
        return String.valueOf(value);
    }
}
