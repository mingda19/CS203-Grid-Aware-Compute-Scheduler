package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The slots a job may use, ignoring capacity and other jobs (contract section 6.4, request table):
 * <ul>
 *   <li>it starts in {@code [originalStartTime, originalStartTime + flexibilityWindowHours]}, or
 *       exactly at {@code originalStartTime} when exempt;</li>
 *   <li>its last slot ends at or before {@code deadline};</li>
 *   <li>every slot it uses has a forecast price, and the slots are consecutive, so a run cannot
 *       cross an unpriced hour.</li>
 * </ul>
 * Shared by the solvers and the brute-force checker so they agree on what is allowed.
 */
public final class JobWindow {

    private final OptimizerJob job;
    private final PriceSeries prices;
    /** Start of the last slot that ends by the deadline. */
    private final LocalDateTime lastSlot;

    public JobWindow(OptimizerJob job, PriceSeries prices) {
        this.job = job;
        this.prices = prices;
        this.lastSlot = job.deadline().truncatedTo(ChronoUnit.HOURS).minusHours(1);
    }

    public OptimizerJob job() {
        return job;
    }

    /** Start of the last slot the job may use. */
    public LocalDateTime lastSlot() {
        return lastSlot;
    }

    /** Priced slots the job may start in, earliest first. Empty when the job cannot run at all. */
    public List<LocalDateTime> startSlots() {
        List<LocalDateTime> starts = new ArrayList<>();
        LocalDateTime latest = job.latestStart().isBefore(lastSlot) ? job.latestStart() : lastSlot;
        for (LocalDateTime slot = job.originalStartTime(); !slot.isAfter(latest); slot = slot.plusHours(1)) {
            if (prices.priceAt(slot).isPresent()) {
                starts.add(slot);
            }
        }
        return starts;
    }

    /**
     * The consecutive priced slots from {@code start} up to {@link #lastSlot()}: every slot a run that
     * begins at {@code start} could use. Stops before the first unpriced slot.
     */
    public List<LocalDateTime> runFrom(LocalDateTime start) {
        List<LocalDateTime> run = new ArrayList<>();
        for (LocalDateTime slot = start; !slot.isAfter(lastSlot); slot = slot.plusHours(1)) {
            if (prices.priceAt(slot).isEmpty()) {
                break;
            }
            run.add(slot);
        }
        return run;
    }

    /** True when {@code plan} starts in an allowed slot and uses only slots in its run. */
    public boolean allows(JobPlan plan) {
        return startSlots().contains(plan.start()) && runFrom(plan.start()).size() >= plan.slotCount();
    }
}
