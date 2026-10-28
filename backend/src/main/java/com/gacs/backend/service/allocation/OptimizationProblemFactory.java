package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.CapacityOverrideInput;
import com.gacs.backend.dto.OptimizeRequestDto.ClusterCapacityInput;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.exception.UnprocessableDataException;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.PriceSeries;
import com.gacs.backend.service.PriceSeriesProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a {@code POST /api/schedules/optimize} request into a validated {@link OptimizationProblem}
 * (contract section 6.4, request table and Errors). Checks run in this order, so input mistakes are
 * reported before missing data:
 * <ol>
 *   <li>{@code 400} ({@link IllegalArgumentException}, mapped by the global handler): missing or malformed
 *       fields, duplicate ids, times not on the hour, deadline before start, neither {@code forecast} nor
 *       {@code forecastLocation}, and a job that cannot finish by its deadline even alone on the cluster;</li>
 *   <li>{@code 422} ({@link UnprocessableDataException}): a job's baseline hours are not covered by the
 *       prices, so the baseline cannot be priced.</li>
 * </ol>
 * A job that fits alone but not around other jobs is not an error: the solver reports it as
 * {@code NO_FEASIBLE_SLOT}.
 */
@Service
public class OptimizationProblemFactory {

    static final int MIN_HORIZON_HOURS = 1;
    static final int MAX_HORIZON_HOURS = 168;
    static final int DEFAULT_HORIZON_HOURS = 48;
    static final int MAX_NAME_LENGTH = 255;
    static final int MAX_LOCATION_LENGTH = 100;

    private final PriceSeriesProvider priceSeriesProvider;
    private final OptimizerProperties properties;

    public OptimizationProblemFactory(PriceSeriesProvider priceSeriesProvider, OptimizerProperties properties) {
        this.priceSeriesProvider = priceSeriesProvider;
        this.properties = properties;
    }

    public OptimizationProblem build(OptimizeRequestDto request) {
        if (request == null) {
            throw invalid("request body is required");
        }
        List<OptimizerJob> jobs = jobs(request.jobs());
        SlotCapacity capacity = capacity(request.clusterCapacity(), request.capacityOverrides());

        BaselineCalculator baselines = new BaselineCalculator(capacity);
        Map<OptimizerJob, JobPlan> baselinePlans = new HashMap<>();
        for (OptimizerJob job : jobs) {
            Optional<JobPlan> baseline = baselines.baseline(job).plan();
            if (baseline.isEmpty()) {
                throw invalid("Job " + job.label() + " cannot finish between its originalStartTime "
                        + job.originalStartTime() + " and its deadline " + job.deadline()
                        + " even alone on the cluster");
            }
            baselinePlans.put(job, baseline.get());
        }

        PriceSeries prices = prices(request, jobs);
        for (OptimizerJob job : jobs) {
            Optional<LocalDateTime> missing = EnergyCostModel.firstUnpricedSlot(
                    baselinePlans.get(job).allocations(), prices);
            if (missing.isPresent()) {
                throw new UnprocessableDataException("No forecast price for " + missing.get()
                        + ", which job " + job.label() + " uses at its original time. Its baseline cost cannot be priced.");
            }
        }
        return new OptimizationProblem(jobs, prices, capacity, baselinePlans);
    }

    // ------------------------------------------------------------------ jobs

    private static List<OptimizerJob> jobs(List<JobInput> inputs) {
        if (inputs == null) {
            throw invalid("jobs is required");
        }
        if (inputs.isEmpty()) {
            throw invalid("jobs must contain at least one job");
        }
        List<OptimizerJob> jobs = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            OptimizerJob job = job(inputs.get(index), "jobs[" + index + "]");
            if (!ids.add(job.id())) {
                throw invalid("jobs[" + index + "].id " + job.id() + " is used by more than one job");
            }
            jobs.add(job);
        }
        return jobs;
    }

    private static OptimizerJob job(JobInput input, String where) {
        if (input == null) {
            throw invalid(where + " must not be null");
        }
        if (input.id() == null) {
            throw invalid(where + ".id is required");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw invalid(where + ".name must not be blank");
        }
        if (input.name().length() > MAX_NAME_LENGTH) {
            throw invalid(where + ".name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        double cpu = requireNonNegative(input.cpuHours(), where + ".cpuHours");
        double gpu = requireNonNegative(input.gpuHours(), where + ".gpuHours");
        if (cpu == 0.0 && gpu == 0.0) {
            throw invalid(where + " must have cpuHours or gpuHours greater than 0");
        }
        LocalDateTime start = required(input.originalStartTime(), where + ".originalStartTime");
        requireOnTheHour(start, where + ".originalStartTime");
        LocalDateTime deadline = required(input.deadline(), where + ".deadline");
        if (deadline.isBefore(start)) {
            throw invalid(where + ".deadline must be at or after originalStartTime");
        }
        int flexibility = required(input.flexibilityWindowHours(), where + ".flexibilityWindowHours");
        if (flexibility < 0) {
            throw invalid(where + ".flexibilityWindowHours must be 0 or more");
        }
        boolean exempt = required(input.isExempt(), where + ".isExempt");
        if (input.priority() != null && !input.priority().isBlank()) {
            try {
                JobPriority.valueOf(input.priority().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw invalid(where + ".priority must be one of CRITICAL, HIGH, NORMAL, LOW");
            }
        }
        return new OptimizerJob(input.id(), input.name().trim(), cpu, gpu, start, deadline, flexibility, exempt);
    }

    // ------------------------------------------------------------------ capacity

    private SlotCapacity capacity(ClusterCapacityInput cluster, List<CapacityOverrideInput> overrides) {
        Double clusterCpu = cluster == null ? null : cluster.maxConcurrentCpuHours();
        Double clusterGpu = cluster == null ? null : cluster.maxConcurrentGpuHours();
        if (clusterCpu != null) {
            requireNonNegative(clusterCpu, "clusterCapacity.maxConcurrentCpuHours");
        }
        if (clusterGpu != null) {
            requireNonNegative(clusterGpu, "clusterCapacity.maxConcurrentGpuHours");
        }

        Map<LocalDateTime, CapacityLimit> perSlot = new LinkedHashMap<>();
        if (overrides != null) {
            for (int index = 0; index < overrides.size(); index++) {
                String where = "capacityOverrides[" + index + "]";
                CapacityOverrideInput override = overrides.get(index);
                if (override == null) {
                    throw invalid(where + " must not be null");
                }
                LocalDateTime slot = required(override.intervalStartUtc(), where + ".intervalStartUtc");
                requireOnTheHour(slot, where + ".intervalStartUtc");
                double cpu = requireNonNegative(override.maxCpuHours(), where + ".maxCpuHours");
                double gpu = requireNonNegative(override.maxGpuHours(), where + ".maxGpuHours");
                if (perSlot.put(slot, new CapacityLimit(cpu, gpu)) != null) {
                    throw invalid(where + ".intervalStartUtc " + slot + " appears more than once");
                }
            }
        }
        return SlotCapacity.resolve(properties, clusterCpu, clusterGpu, perSlot);
    }

    // ------------------------------------------------------------------ prices

    /** {@code forecast} wins when both are sent; otherwise prices load for the location from the earliest start. */
    private PriceSeries prices(OptimizeRequestDto request, List<OptimizerJob> jobs) {
        if (request.forecast() != null) {
            return explicitPrices(request.forecast());
        }
        String location = request.forecastLocation();
        if (location == null) {
            throw invalid("Either forecast or forecastLocation is required");
        }
        if (location.isBlank()) {
            throw invalid("forecastLocation must not be blank");
        }
        if (location.length() > MAX_LOCATION_LENGTH) {
            throw invalid("forecastLocation must be at most " + MAX_LOCATION_LENGTH + " characters");
        }
        int horizon = request.horizonHours() == null ? DEFAULT_HORIZON_HOURS : request.horizonHours();
        if (horizon < MIN_HORIZON_HOURS || horizon > MAX_HORIZON_HOURS) {
            throw invalid("horizonHours must be between " + MIN_HORIZON_HOURS + " and " + MAX_HORIZON_HOURS);
        }
        LocalDateTime earliest = jobs.stream().map(OptimizerJob::originalStartTime).min(LocalDateTime::compareTo)
                .orElseThrow();
        return priceSeriesProvider.load(location.trim(), earliest, horizon);
    }

    private static PriceSeries explicitPrices(List<ForecastPointInput> points) {
        if (points.isEmpty()) {
            throw invalid("forecast must contain at least one price");
        }
        Map<LocalDateTime, Double> prices = new LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++) {
            String where = "forecast[" + index + "]";
            ForecastPointInput point = points.get(index);
            if (point == null) {
                throw invalid(where + " must not be null");
            }
            LocalDateTime slot = required(point.intervalStartUtc(), where + ".intervalStartUtc");
            requireOnTheHour(slot, where + ".intervalStartUtc");
            Double price = required(point.predictedPrice(), where + ".predictedPrice");
            if (!Double.isFinite(price)) {
                throw invalid(where + ".predictedPrice must be a finite number");
            }
            if (prices.put(slot, price) != null) {
                throw invalid(where + ".intervalStartUtc " + slot + " appears more than once");
            }
        }
        return new PriceSeries(prices, false);
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static double requireNonNegative(Double value, String field) {
        double number = required(value, field);
        if (!Double.isFinite(number) || number < 0.0) {
            throw invalid(field + " must be a finite number of 0 or more");
        }
        return number;
    }

    private static void requireOnTheHour(LocalDateTime time, String field) {
        if (!time.equals(time.truncatedTo(ChronoUnit.HOURS))) {
            throw invalid(field + " must be on the hour, was " + time);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
