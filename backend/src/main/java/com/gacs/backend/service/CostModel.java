package com.gacs.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Energy and cost conversion basis for the schedule optimizer.
 *
 * <p>Constants and formulae are fixed by the "Cost Basis &amp; Energy Conversion"
 * subsection of the Schedule Optimization Engine Contract (GRID-124 AC2) in
 * {@code docs/api-contract.md}. They are defined once here so the solver, the
 * baseline calculation and the optimized calculation cannot drift apart.
 */
public final class CostModel {

    /** 1 CPU-hour draws 0.05 kWh. */
    public static final double CPU_KWH_PER_HOUR = 0.05;

    /** 1 GPU-hour draws 0.40 kWh, the standard 400W enterprise GPU baseline. */
    public static final double GPU_KWH_PER_HOUR = 0.40;

    /** Forecast prices are quoted in $/MWh; 1 MWh = 1000 kWh. */
    public static final double KWH_PER_MWH = 1000.0;

    /** Monetary outputs are reported to cent precision, matching the contract's examples. */
    public static final int MONEY_SCALE = 2;

    private CostModel() {
    }

    /**
     * Energy draw of a job in kWh: {@code (cpuHours * 0.05) + (gpuHours * 0.40)}.
     */
    public static double energyKwh(double cpuHours, double gpuHours) {
        return (cpuHours * CPU_KWH_PER_HOUR) + (gpuHours * GPU_KWH_PER_HOUR);
    }

    /**
     * Cost of running a given energy draw in a slot priced at {@code pricePerMwh}:
     * {@code energyKwh * (pricePerMwh / 1000)}.
     */
    public static double cost(double energyKwh, double pricePerMwh) {
        return energyKwh * (pricePerMwh / KWH_PER_MWH);
    }

    /**
     * Rounds a monetary amount to cent precision, half-up.
     *
     * <p>Every dollar figure leaving the optimizer passes through here, and the
     * summary totals are derived from already-rounded components, so the reported
     * baseline, optimized and savings figures are always internally consistent.
     */
    public static double round(double amount) {
        if (!Double.isFinite(amount)) {
            return 0.0;
        }
        return BigDecimal.valueOf(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
