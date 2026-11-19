package com.gacs.backend.backtest;

import com.gacs.backend.backtest.BacktestRunner.DayRow;
import com.gacs.backend.backtest.BacktestRunner.JobRow;
import com.gacs.backend.backtest.BacktestRunner.Timing;
import com.gacs.backend.service.PriceSeries;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

/**
 * Ticket 2: the backtest's cost, savings and guardrail metrics (docs/backtest-plan.md section 4.2), computed from
 * a run's rows and the same price files the run used.
 *
 * <p>Definitions, for one profile, day and planning source (B = fixed schedule, all at actual prices):
 * <ul>
 *   <li><b>realised</b> savings = B - plan at actual prices: what the operator really saves. The headline.</li>
 *   <li><b>projected</b> savings = (fixed schedule - plan), both at the planning prices: what the app promised.</li>
 *   <li><b>perfect</b> savings = B - plan made with actual prices: the most the optimiser could have saved.</li>
 *   <li><b>forecast-error cost</b> = realised plan cost - perfect plan cost.</li>
 * </ul>
 * Savings rule (PRD): savings are only claimed when the baseline and the plan cover the same jobs. A day where any
 * job was not scheduled is reported but left out of every savings figure ({@code countsForSavings = false}).
 *
 * <p>Everything here is deterministic, including the bootstrap (fixed seed), except solve times, which are kept
 * in their own section.
 */
public final class BacktestMetrics {

    /** Same method and settings as model/evaluate.py: 7-day moving blocks, 2,000 draws, 2.5%-97.5%. */
    public static final int BOOTSTRAP_BLOCK_DAYS = 7;
    public static final int BOOTSTRAP_DRAWS = 2_000;
    public static final long BOOTSTRAP_SEED = 20_261_029L;

    private BacktestMetrics() {
    }

    /** Metrics for one profile, day and source. Percentages are 0-100; null when the denominator is 0. */
    public record Daily(String profile, LocalDate date, String source, int jobs, int scheduled,
                       double energyKwhBaseline, double energyKwhOptimised,
                       double baselineCost, double realisedCost, double projectedBaselineCost, double projectedCost,
                       double perfectCost, double realisedSavingsUsd, Double realisedSavingsPct,
                       double projectedSavingsUsd, Double projectedSavingsPct, Double projectionGapPct,
                       double forecastErrorCostUsd, Double perfectSavingsPct, double deadlineAdherence,
                       int violations, boolean provenOptimal, double forecastMaeAll, double forecastRmseAll,
                       Double forecastMaeUsed, Double forecastRmseUsed, int usedHours, boolean countsForSavings) {
        public int notScheduled() {
            return jobs - scheduled;
        }
    }

    /** The whole period for one profile and source. Savings figures cover {@code daysInSavings} only. */
    public record Summary(String profile, String source, int days, int daysInSavings, int daysExcluded,
                          double baselineCost, double realisedCost, double projectedBaselineCost,
                          double projectedCost, double perfectCost, double energyKwhBaseline,
                          double energyKwhOptimised, Double realisedSavingsPct, Double realisedSavingsCiLow,
                          Double realisedSavingsCiHigh, Double projectedSavingsPct, Double projectionGapPct,
                          Double perfectSavingsPct, Double shareOfPossibleSavingsCaptured,
                          double forecastErrorCostUsd, Double dailySavingsMeanPct, Double dailySavingsMedianPct,
                          Double dailySavingsStdPct, Double dailySavingsP10Pct, Double dailySavingsP90Pct,
                          double negativeDaysShare, int jobs, int scheduled, double deadlineAdherence,
                          int violations, double provenOptimalShare, double forecastMaeAll,
                          double forecastRmseAll, Double forecastMaeUsed, Double forecastRmseUsed) {
    }

    /** Solve times per source. Not repeatable: depends on the machine. */
    public record SolveTimes(String source, int solves, double medianMillis, double p95Millis, long maxMillis) {
    }

    public record Result(List<Daily> daily, List<Summary> summaries, List<SolveTimes> solveTimes) {
    }

    /**
     * @param forecasts planning prices by source name; perfect foresight uses {@code actual}
     */
    public static Result compute(List<DayRow> days, List<JobRow> jobs, List<Timing> timings, HourlyPrices actual,
                                 Map<String, HourlyPrices> forecasts) {
        Map<String, DayRow> perfectByDay = new HashMap<>();
        for (DayRow row : days) {
            if (row.source().equals(BacktestRunner.PERFECT)) {
                perfectByDay.put(row.profile() + "|" + row.date(), row);
            }
        }
        Map<String, List<JobRow>> jobsByDay = new HashMap<>();
        for (JobRow job : jobs) {
            jobsByDay.computeIfAbsent(key(job.profile(), job.date(), job.source()), k -> new ArrayList<>()).add(job);
        }

        List<Daily> daily = new ArrayList<>();
        for (DayRow row : days) {
            DayRow perfect = perfectByDay.get(row.profile() + "|" + row.date());
            if (perfect == null) {
                throw new IllegalArgumentException("no perfect-foresight row for " + row.profile() + " " + row.date());
            }
            HourlyPrices planning = row.source().equals(BacktestRunner.PERFECT) ? actual : forecasts.get(row.source());
            if (planning == null) {
                throw new IllegalArgumentException("no prices for source " + row.source());
            }
            daily.add(daily(row, perfect, jobsByDay.getOrDefault(key(row.profile(), row.date(), row.source()),
                    List.of()), actual, planning));
        }

        Map<String, List<Daily>> groups = new LinkedHashMap<>();
        for (Daily d : daily) {
            groups.computeIfAbsent(d.profile() + "|" + d.source(), k -> new ArrayList<>()).add(d);
        }
        List<Summary> summaries = new ArrayList<>();
        for (List<Daily> group : groups.values()) {
            summaries.add(summary(group, actual, groupForecast(group, actual, forecasts), jobsByDay));
        }
        return new Result(List.copyOf(daily), List.copyOf(summaries), solveTimes(timings));
    }

    private static HourlyPrices groupForecast(List<Daily> group, HourlyPrices actual, Map<String, HourlyPrices> forecasts) {
        String source = group.getFirst().source();
        return source.equals(BacktestRunner.PERFECT) ? actual : forecasts.get(source);
    }

    private static String key(String profile, LocalDate date, String source) {
        return profile + "|" + date + "|" + source;
    }

    // ------------------------------------------------------------------ per day

    private static Daily daily(DayRow row, DayRow perfect, List<JobRow> jobs, HourlyPrices actual,
                               HourlyPrices planning) {
        double baseline = row.baselineCostActual();
        double realised = row.optimisedCostActual();
        double projectedBaseline = row.baselineCostPlanned();
        double projected = row.optimisedCostPlanned();
        double perfectCost = perfect.optimisedCostActual();
        double realisedSavings = baseline - realised;
        double projectedSavings = projectedBaseline - projected;
        Double realisedPct = percent(realisedSavings, baseline);
        Double projectedPct = percent(projectedSavings, projectedBaseline);

        double energyAll = jobs.stream().mapToDouble(JobRow::energyKwh).sum();
        double energyScheduled = jobs.stream().filter(j -> j.scheduledStart() != null)
                .mapToDouble(JobRow::energyKwh).sum();
        long placed = jobs.stream().filter(j -> j.scheduledStart() != null).count();
        long onTime = jobs.stream().filter(j -> j.scheduledEnd() != null && !j.scheduledEnd().isAfter(j.deadline()))
                .count();

        PriceSeries actualDay = actual.day(row.date()).orElseThrow();
        PriceSeries planningDay = planning.day(row.date()).orElseThrow();
        List<Double> allErrors = errors(actualDay, planningDay, actualDay.asMap().keySet());
        TreeSet<LocalDateTime> used = new TreeSet<>();
        for (JobRow job : jobs) {
            if (job.scheduledStart() != null) {
                for (LocalDateTime h = job.scheduledStart(); h.isBefore(job.scheduledEnd()); h = h.plusHours(1)) {
                    used.add(h);
                }
            }
        }
        List<Double> usedErrors = errors(actualDay, planningDay, used);

        return new Daily(row.profile(), row.date(), row.source(), row.jobs(), row.scheduled(), energyAll,
                energyScheduled, baseline, realised, projectedBaseline, projected, perfectCost, realisedSavings,
                realisedPct, projectedSavings, projectedPct,
                realisedPct == null || projectedPct == null ? null : projectedPct - realisedPct,
                realised - perfectCost, percent(baseline - perfectCost, baseline),
                placed == 0 ? 1.0 : (double) onTime / placed, row.violations(), row.provenOptimal(),
                mae(allErrors), rmse(allErrors), used.isEmpty() ? null : mae(usedErrors),
                used.isEmpty() ? null : rmse(usedErrors), used.size(), row.scheduled() == row.jobs());
    }

    private static List<Double> errors(PriceSeries actual, PriceSeries planning, Collection<LocalDateTime> hours) {
        List<Double> errors = new ArrayList<>();
        for (LocalDateTime hour : hours) {
            errors.add(planning.priceAt(hour).orElseThrow() - actual.priceAt(hour).orElseThrow());
        }
        return errors;
    }

    // ------------------------------------------------------------------ whole period

    private static Summary summary(List<Daily> group, HourlyPrices actual, HourlyPrices planning,
                                   Map<String, List<JobRow>> jobsByDay) {
        List<Daily> sorted = group.stream().sorted((a, b) -> a.date().compareTo(b.date())).toList();
        List<Daily> inSavings = sorted.stream().filter(Daily::countsForSavings).toList();

        double baseline = sum(inSavings, Daily::baselineCost);
        double realised = sum(inSavings, Daily::realisedCost);
        double projectedBaseline = sum(inSavings, Daily::projectedBaselineCost);
        double projected = sum(inSavings, Daily::projectedCost);
        double perfect = sum(inSavings, Daily::perfectCost);
        Double realisedPct = percent(baseline - realised, baseline);
        Double projectedPct = percent(projectedBaseline - projected, projectedBaseline);
        double[] ci = bootstrapSavingsPct(inSavings);

        double[] dailyPct = inSavings.stream().map(Daily::realisedSavingsPct).filter(p -> p != null)
                .mapToDouble(Double::doubleValue).sorted().toArray();
        long negative = inSavings.stream().filter(d -> d.realisedSavingsUsd() < 0).count();

        int placed = 0;
        int onTime = 0;
        List<Double> allErrors = new ArrayList<>();
        List<Double> usedErrors = new ArrayList<>();
        for (Daily d : sorted) {
            PriceSeries actualDay = actual.day(d.date()).orElseThrow();
            PriceSeries planningDay = planning.day(d.date()).orElseThrow();
            allErrors.addAll(errors(actualDay, planningDay, actualDay.asMap().keySet()));
            TreeSet<LocalDateTime> used = new TreeSet<>();
            for (JobRow job : jobsByDay.getOrDefault(key(d.profile(), d.date(), d.source()), List.of())) {
                if (job.scheduledStart() != null) {
                    placed++;
                    if (!job.scheduledEnd().isAfter(job.deadline())) {
                        onTime++;
                    }
                    for (LocalDateTime h = job.scheduledStart(); h.isBefore(job.scheduledEnd()); h = h.plusHours(1)) {
                        used.add(h);
                    }
                }
            }
            usedErrors.addAll(errors(actualDay, planningDay, used));
        }

        Daily first = sorted.getFirst();
        return new Summary(first.profile(), first.source(), sorted.size(), inSavings.size(),
                sorted.size() - inSavings.size(), baseline, realised, projectedBaseline, projected, perfect,
                sum(sorted, Daily::energyKwhBaseline), sum(sorted, Daily::energyKwhOptimised),
                realisedPct, ci == null ? null : ci[0], ci == null ? null : ci[1], projectedPct,
                realisedPct == null || projectedPct == null ? null : projectedPct - realisedPct,
                percent(baseline - perfect, baseline),
                baseline - perfect == 0 ? null : (baseline - realised) / (baseline - perfect),
                realised - perfect,
                dailyPct.length == 0 ? null : Arrays.stream(dailyPct).average().orElseThrow(),
                dailyPct.length == 0 ? null : quantile(dailyPct, 0.5),
                dailyPct.length < 2 ? null : sampleStd(dailyPct),
                dailyPct.length == 0 ? null : quantile(dailyPct, 0.1),
                dailyPct.length == 0 ? null : quantile(dailyPct, 0.9),
                inSavings.isEmpty() ? 0.0 : (double) negative / inSavings.size(),
                sorted.stream().mapToInt(Daily::jobs).sum(), sorted.stream().mapToInt(Daily::scheduled).sum(),
                placed == 0 ? 1.0 : (double) onTime / placed, sorted.stream().mapToInt(Daily::violations).sum(),
                (double) sorted.stream().filter(Daily::provenOptimal).count() / sorted.size(),
                mae(allErrors), rmse(allErrors), usedErrors.isEmpty() ? null : mae(usedErrors),
                usedErrors.isEmpty() ? null : rmse(usedErrors));
    }

    /**
     * 95% interval for the period's realised savings % (ratio of totals), by a 7-day moving-block bootstrap over
     * days in date order, as model/evaluate.py does it: each draw strings together ceil(n / 7) blocks of 7
     * consecutive days starting anywhere, cut to n days. Seeded, so the same days always give the same interval.
     */
    static double[] bootstrapSavingsPct(List<Daily> days) {
        int n = days.size();
        if (n == 0) {
            return null;
        }
        double[] baseline = days.stream().mapToDouble(Daily::baselineCost).toArray();
        double[] realised = days.stream().mapToDouble(Daily::realisedCost).toArray();
        int block = Math.min(BOOTSTRAP_BLOCK_DAYS, n);
        int blocks = (n + block - 1) / block;
        Random random = new Random(BOOTSTRAP_SEED);
        double[] draws = new double[BOOTSTRAP_DRAWS];
        int valid = 0;
        for (int d = 0; d < BOOTSTRAP_DRAWS; d++) {
            double b = 0.0;
            double r = 0.0;
            int taken = 0;
            for (int k = 0; k < blocks && taken < n; k++) {
                int start = random.nextInt(n - block + 1);
                for (int i = 0; i < block && taken < n; i++, taken++) {
                    b += baseline[start + i];
                    r += realised[start + i];
                }
            }
            if (b != 0.0) {
                draws[valid++] = 100.0 * (b - r) / b;
            }
        }
        if (valid == 0) {
            return null;
        }
        double[] sorted = Arrays.copyOf(draws, valid);
        Arrays.sort(sorted);
        return new double[] {quantile(sorted, 0.025), quantile(sorted, 0.975)};
    }

    private static List<SolveTimes> solveTimes(List<Timing> timings) {
        Map<String, List<Long>> bySource = new LinkedHashMap<>();
        timings.forEach(t -> bySource.computeIfAbsent(t.source(), s -> new ArrayList<>()).add(t.solveMillis()));
        List<SolveTimes> result = new ArrayList<>();
        bySource.forEach((source, millis) -> {
            double[] sorted = millis.stream().mapToDouble(Long::doubleValue).sorted().toArray();
            result.add(new SolveTimes(source, sorted.length, quantile(sorted, 0.5), quantile(sorted, 0.95),
                    (long) sorted[sorted.length - 1]));
        });
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ helpers

    static Double percent(double part, double whole) {
        return whole == 0.0 ? null : 100.0 * part / whole;
    }

    /** Linear interpolation between order statistics: numpy's default, so results match pandas/numpy. */
    static double quantile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int below = (int) Math.floor(position);
        int above = Math.min(below + 1, sorted.length - 1);
        return sorted[below] + (position - below) * (sorted[above] - sorted[below]);
    }

    static double sampleStd(double[] values) {
        double mean = Arrays.stream(values).average().orElseThrow();
        double squares = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum();
        return Math.sqrt(squares / (values.length - 1));
    }

    private static double mae(List<Double> errors) {
        return errors.isEmpty() ? 0.0 : errors.stream().mapToDouble(Math::abs).average().orElseThrow();
    }

    private static double rmse(List<Double> errors) {
        return errors.isEmpty() ? 0.0 : Math.sqrt(errors.stream().mapToDouble(e -> e * e).average().orElseThrow());
    }

    private static double sum(List<Daily> days, java.util.function.ToDoubleFunction<Daily> field) {
        return days.stream().mapToDouble(field).sum();
    }

    static Optional<Daily> find(List<Daily> daily, LocalDate date, String source) {
        return daily.stream().filter(d -> d.date().equals(date) && d.source().equals(source)).findFirst();
    }
}
