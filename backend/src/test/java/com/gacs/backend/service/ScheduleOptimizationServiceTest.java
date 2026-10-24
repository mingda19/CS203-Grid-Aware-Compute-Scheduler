package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ClusterCapacityInput;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.HybridAllocationSolver;
import com.gacs.backend.service.allocation.OptimizationProblemFactory;
import com.gacs.backend.service.allocation.OptimizerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4: how a run becomes the contract's response (section 6.4): statuses, allocations, rounding,
 * totals, negative savings and the explanation. Uses the real validator and solver.
 */
class ScheduleOptimizationServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);
    private static final EnergyCostModel COSTS = new EnergyCostModel(OptimizerProperties.DEFAULTS);

    private final PriceSeriesProvider provider = mock(PriceSeriesProvider.class);
    private final OptimizationProblemFactory factory = new OptimizationProblemFactory(provider, OptimizerProperties.DEFAULTS);
    private final ScheduleOptimizationService service =
            new ScheduleOptimizationService(factory, new HybridAllocationSolver(COSTS), COSTS);

    private static LocalDateTime at(int hour) {
        return T0.plusHours(hour);
    }

    private static List<ForecastPointInput> forecast(double... perHour) {
        List<ForecastPointInput> points = new ArrayList<>();
        for (int h = 0; h < perHour.length; h++) {
            points.add(new ForecastPointInput(at(h), perHour[h]));
        }
        return points;
    }

    private static JobInput job(long id, double cpu, double gpu, int start, int flex, int deadline, boolean exempt) {
        return new JobInput(id, "job-" + id, cpu, gpu, at(start), at(deadline), flex, exempt);
    }

    private static OptimizeRequestDto request(List<JobInput> jobs, Double cpuPerSlot, List<ForecastPointInput> prices) {
        ClusterCapacityInput capacity = cpuPerSlot == null ? null : new ClusterCapacityInput(cpuPerSlot, null);
        return new OptimizeRequestDto(jobs, capacity, null, null, prices, null);
    }

    private static ScheduleItem item(OptimizeResponseDto response, long id) {
        return response.items().stream().filter(i -> i.jobId() == id).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("The contract's worked example: 9.6 kWh moved from $112 to $24")
    void contractExample() {
        // 10:00 costs $112, 01:00 the next day (slot 15) costs $24, everything in between $100.
        double[] prices = new double[16];
        java.util.Arrays.fill(prices, 100.0);
        prices[0] = 112.0;
        prices[15] = 24.0;
        OptimizeResponseDto response = service.optimize(request(
                List.of(new JobInput(101L, "LLM Fine-Tuning Run #42", 64.0, 16.0, T0, at(32), 15, false)),
                null, forecast(prices)));

        assertThat(UUID.fromString(response.runId()).version()).isEqualTo(4);
        assertThat(response.status()).isEqualTo("SCHEDULED");
        assertThat(response.provenOptimal()).isTrue();
        assertThat(response.baselineCostUsd()).isEqualTo(1.0752);
        assertThat(response.optimizedCostUsd()).isEqualTo(0.2304);
        assertThat(response.projectedSavingsUsd()).isEqualTo(0.8448);
        assertThat(response.projectedSavingsPercent()).isEqualTo(78.57);
        assertThat(response.usedSeededFallbackPrices()).isFalse();

        ScheduleItem item = item(response, 101);
        assertThat(item.jobName()).isEqualTo("LLM Fine-Tuning Run #42");
        assertThat(item.baselineStart()).isEqualTo(T0);
        assertThat(item.scheduledStart()).isEqualTo(at(15));
        assertThat(item.scheduledEnd()).isEqualTo(at(16));
        assertThat(item.allocatedCpuHours()).isEqualTo(64.0);
        assertThat(item.allocatedGpuHours()).isEqualTo(16.0);
        assertThat(item.allocations()).hasSize(1);
        assertThat(item.allocations().getFirst().intervalStartUtc()).isEqualTo(at(15));
        assertThat(item.allocations().getFirst().cpuHours()).isCloseTo(64.0, within(1e-6));
        assertThat(item.perJobStatus()).isEqualTo("OPTIMISED");
        assertThat(item.reason()).isNull();
        assertThat(item.baselineCostUsd()).isEqualTo(1.0752);
        assertThat(item.optimizedCostUsd()).isEqualTo(0.2304);
        assertThat(item.savingsUsd()).isEqualTo(0.8448);
    }

    @Test
    @DisplayName("Totals are sums of the rounded item costs, and savings reconcile exactly")
    void totalsReconcile() {
        OptimizeResponseDto response = service.optimize(request(List.of(
                job(1, 37.0, 3.0, 0, 3, 6, false),
                job(2, 91.0, 0.0, 1, 2, 6, false),
                job(3, 13.0, 7.0, 2, 0, 6, true)), null, forecast(33.17, 41.93, 12.71, 28.39, 19.07, 45.61)));

        double baseline = response.items().stream().mapToDouble(ScheduleItem::baselineCostUsd).sum();
        double optimized = response.items().stream().mapToDouble(ScheduleItem::optimizedCostUsd).sum();
        assertThat(response.baselineCostUsd()).isEqualTo(EnergyCostModel.roundUsd(baseline));
        assertThat(response.optimizedCostUsd()).isEqualTo(EnergyCostModel.roundUsd(optimized));
        assertThat(response.projectedSavingsUsd())
                .isEqualTo(EnergyCostModel.roundUsd(response.baselineCostUsd() - response.optimizedCostUsd()));
        for (ScheduleItem item : response.items()) {
            assertThat(item.savingsUsd())
                    .isEqualTo(EnergyCostModel.roundUsd(item.baselineCostUsd() - item.optimizedCostUsd()));
            assertThat(BigDecimalScale.of(item.baselineCostUsd())).isLessThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("A job's allocations add up to its allocated hours, over consecutive hours")
    void allocationsAddUp() {
        // 600 CPU-hours at 256 per hour needs three hours.
        OptimizeResponseDto response = service.optimize(request(List.of(job(1, 600.0, 32.0, 0, 1, 5, false)),
                null, forecast(30, 10, 20, 40, 50)));

        ScheduleItem item = item(response, 1);
        assertThat(item.allocations().size()).isGreaterThanOrEqualTo(3);
        assertThat(item.allocations().stream().mapToDouble(Allocation::cpuHours).sum()).isCloseTo(600.0, within(1e-6));
        assertThat(item.allocations().stream().mapToDouble(Allocation::gpuHours).sum()).isCloseTo(32.0, within(1e-6));
        for (int i = 1; i < item.allocations().size(); i++) {
            assertThat(item.allocations().get(i).intervalStartUtc())
                    .isEqualTo(item.allocations().get(i - 1).intervalStartUtc().plusHours(1));
        }
        assertThat(item.scheduledEnd()).isEqualTo(item.allocations().getLast().intervalStartUtc().plusHours(1));
    }

    @Test
    @DisplayName("A job with no room: NEEDS_ATTENTION, no times, no allocations, null costs, left out of totals")
    void noFeasibleSlot() {
        OptimizeResponseDto response = service.optimize(request(List.of(
                job(1, 256.0, 0.0, 0, 0, 1, true),
                job(2, 100.0, 0.0, 0, 0, 1, false)), null, forecast(10)));

        assertThat(response.status()).isEqualTo("NEEDS_ATTENTION");
        ScheduleItem left = item(response, 2);
        assertThat(left.perJobStatus()).isEqualTo("NO_FEASIBLE_SLOT");
        assertThat(left.baselineStart()).isEqualTo(T0);
        assertThat(left.scheduledStart()).isNull();
        assertThat(left.scheduledEnd()).isNull();
        assertThat(left.allocations()).isEmpty();
        assertThat(left.allocatedCpuHours()).isZero();
        assertThat(left.baselineCostUsd()).isNull();
        assertThat(left.optimizedCostUsd()).isNull();
        assertThat(left.savingsUsd()).isNull();
        assertThat(left.reason()).contains("Cluster full");
        // Totals cover only the exempt job: 256 CPU-hours = 12.8 kWh at $10/MWh.
        assertThat(response.baselineCostUsd()).isEqualTo(0.128);
        assertThat(response.optimizedCostUsd()).isEqualTo(0.128);
        assertThat(response.plainEnglishExplanation()).contains("1 job(s) could not be placed");
    }

    @Test
    @DisplayName("Savings go negative when jobs cannot all run at their original times (decision 2)")
    void negativeSavings() {
        OptimizeResponseDto response = service.optimize(request(List.of(
                job(1, 200.0, 0.0, 0, 1, 2, false),
                job(2, 200.0, 0.0, 0, 1, 2, false)), 300.0, forecast(20, 50)));

        assertThat(response.status()).isEqualTo("SCHEDULED");
        assertThat(response.baselineCostUsd()).isEqualTo(0.4);
        assertThat(response.optimizedCostUsd()).isEqualTo(0.55);
        assertThat(response.projectedSavingsUsd()).isEqualTo(-0.15);
        assertThat(response.projectedSavingsPercent()).isEqualTo(-37.5);
        assertThat(response.plainEnglishExplanation()).contains("more than the $0.4000 baseline");
    }

    @Test
    @DisplayName("Exempt jobs keep their original time and save nothing")
    void exemptJob() {
        OptimizeResponseDto response = service.optimize(request(List.of(job(1, 20.0, 0.0, 0, 0, 3, true)),
                null, forecast(50, 10, 10)));

        ScheduleItem item = item(response, 1);
        assertThat(item.perJobStatus()).isEqualTo("UNCHANGED_EXEMPT");
        assertThat(item.scheduledStart()).isEqualTo(item.baselineStart());
        assertThat(item.savingsUsd()).isZero();
        assertThat(response.projectedSavingsPercent()).isZero();
    }

    @Test
    @DisplayName("Seeded fallback prices are flagged and explained")
    void seededFallback() {
        Map<LocalDateTime, Double> curve = new LinkedHashMap<>();
        for (int h = 0; h < 48; h++) {
            curve.put(at(h), 20.0 + h);
        }
        when(provider.load("LZ_NORTH", T0, 48)).thenReturn(new PriceSeries(curve, true));
        OptimizeResponseDto response = service.optimize(new OptimizeRequestDto(
                List.of(job(1, 20.0, 0.0, 0, 2, 5, false)), null, "LZ_NORTH", null, null, null));

        assertThat(response.usedSeededFallbackPrices()).isTrue();
        assertThat(response.plainEnglishExplanation()).contains("seeded fallback");
    }

    @Test
    @DisplayName("A heuristic plan is reported as not proven, and the explanation says so")
    void heuristicPlanIsFlagged() {
        AllocationSolver exact = new HybridAllocationSolver(COSTS);
        AllocationSolver unproven = problem -> new AllocationResult(exact.solve(problem).outcomes(), false);
        ScheduleOptimizationService heuristicService = new ScheduleOptimizationService(factory, unproven, COSTS);

        OptimizeResponseDto response = heuristicService.optimize(request(List.of(job(1, 20.0, 0.0, 0, 1, 3, false)),
                null, forecast(10, 20, 30)));

        assertThat(response.provenOptimal()).isFalse();
        assertThat(response.plainEnglishExplanation()).contains("heuristic search");
    }

    @Test
    @DisplayName("Items keep request order")
    void itemsKeepRequestOrder() {
        OptimizeResponseDto response = service.optimize(request(List.of(
                job(3, 20.0, 0.0, 0, 0, 3, false),
                job(1, 20.0, 0.0, 0, 0, 3, false),
                job(2, 20.0, 0.0, 0, 0, 3, true)), null, forecast(10, 10, 10)));

        assertThat(response.items()).extracting(ScheduleItem::jobId).containsExactly(3L, 1L, 2L);
    }

    /** Number of decimal places a double needs. */
    private static final class BigDecimalScale {
        static int of(double value) {
            return Math.max(0, new java.math.BigDecimal(Double.toString(value)).stripTrailingZeros().scale());
        }
    }
}
