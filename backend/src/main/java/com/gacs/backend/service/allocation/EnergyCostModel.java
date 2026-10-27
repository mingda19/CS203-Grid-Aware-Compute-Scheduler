package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Energy and cost under the contract's cost basis (section 6.4, AC2):
 * <pre>
 * jobKwh(job)        = cpuHours * cpuKwhPerHour + gpuHours * gpuKwhPerHour
 * slotCostUsd(job,t) = share(job,t) * jobKwh / 1000 * predictedPrice(t)    // price is $/MWh
 * jobCostUsd         = sum of slotCostUsd over the slots the job uses
 * </pre>
 * Costs here are raw. Rounding to {@link #COST_DECIMALS} places happens only when building the
 * response.
 */
public final class EnergyCostModel {

    /** Costs are reported to 4 decimal places. */
    public static final int COST_DECIMALS = 4;

    private static final double KWH_PER_MWH = 1000.0;

    private final OptimizerProperties properties;

    public EnergyCostModel(OptimizerProperties properties) {
        this.properties = properties;
    }

    public double jobKwh(OptimizerJob job) {
        return job.cpuHours() * properties.cpuKwhPerHour() + job.gpuHours() * properties.gpuKwhPerHour();
    }

    public double slotCostUsd(OptimizerJob job, double share, double pricePerMwh) {
        return share * jobKwh(job) / KWH_PER_MWH * pricePerMwh;
    }

    /**
     * Cost of running {@code job} with these allocations. Every slot must have a price; call
     * {@link #firstUnpricedSlot} first when that is not already known.
     */
    public double costUsd(OptimizerJob job, List<SlotAllocation> allocations, PriceSeries prices) {
        double cost = 0.0;
        for (SlotAllocation allocation : allocations) {
            double price = prices.priceAt(allocation.slotStart()).orElseThrow(() -> new IllegalArgumentException(
                    "no forecast price for slot " + allocation.slotStart() + " used by job " + job.label()));
            cost += slotCostUsd(job, allocation.share(), price);
        }
        return cost;
    }

    public double costUsd(JobPlan plan, PriceSeries prices) {
        return costUsd(plan.job(), plan.allocations(), prices);
    }

    /** The first slot in {@code allocations} without a forecast price, if any. */
    public static Optional<LocalDateTime> firstUnpricedSlot(List<SlotAllocation> allocations, PriceSeries prices) {
        return allocations.stream()
                .map(SlotAllocation::slotStart)
                .filter(slot -> prices.priceAt(slot).isEmpty())
                .findFirst();
    }

    /** Rounds a dollar amount to {@link #COST_DECIMALS} places, half up. */
    public static double roundUsd(double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("cost must be finite, was " + amount);
        }
        return BigDecimal.valueOf(amount).setScale(COST_DECIMALS, RoundingMode.HALF_UP).doubleValue();
    }
}
