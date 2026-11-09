package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The slots a job may use, ignoring capacity and other jobs (contract section 6.4, request table):
 * <ul>
 *   <li>it starts in {@code [originalStartTime, originalStartTime + flexibilityWindowHours]}, or
 *       exactly at {@code originalStartTime} when exempt;</li>
 *   <li>its last slot ends at or before {@code deadline};</li>
 *   <li>no slot it uses starts before {@link #earliestStart()}, the run's earliest schedulable hour -
 *       a schedule cannot place work in an hour that has already passed;</li>
 *   <li>every slot it uses has a forecast price, and the slots are consecutive, so a run cannot
 *       cross an unpriced hour;</li>
 *   <li>every slot it uses satisfies this window's extra restriction, if any ({@link #restrictedTo}) -
 *       the mechanism {@link PlacementConstraint#restrict} narrows a job's window through.</li>
 * </ul>
 * Shared by the solvers and the brute-force checker so they agree on what is allowed.
 *
 * <p>Every rule above is enforced in one place, {@link #usable}, so the three readers below
 * ({@link #startSlots}, {@link #runFrom} and {@link #isUsable}) cannot drift apart. That matters most
 * for {@code earliestStart}: {@link ExactAllocationSolver} pins a job's {@code a}/{@code e}/{@code x}
 * variables to zero for any hour {@link #isUsable} rejects, so putting the bound here is what keeps
 * past hours out of the MIP, out of the greedy solver's runs, and out of what
 * {@link AllocationResult} accepts as a valid plan.
 */
public final class JobWindow {

    private final OptimizerJob job;
    private final PriceSeries prices;
    /** Start of the last slot that ends by the deadline. */
    private final LocalDateTime lastSlot;
    /** No slot before this may be used; see {@link #earliestStart()}. */
    private final LocalDateTime earliestStart;
    /** Extra per-hour restriction layered on by {@link #restrictedTo}; always-true by default. */
    private final Predicate<LocalDateTime> extra;

    /**
     * @param earliestStart the earliest hour this run may schedule into, normally the current hour
     *                      truncated ({@link OptimizationProblem#earliestStart()}). Pass
     *                      {@link LocalDateTime#MIN} for an unbounded window.
     */
    public JobWindow(OptimizerJob job, PriceSeries prices, LocalDateTime earliestStart) {
        this(job, prices, earliestStart, hour -> true);
    }

    private JobWindow(OptimizerJob job, PriceSeries prices, LocalDateTime earliestStart,
                      Predicate<LocalDateTime> extra) {
        this.job = job;
        this.prices = prices;
        this.lastSlot = job.deadline().truncatedTo(ChronoUnit.HOURS).minusHours(1);
        this.earliestStart = Objects.requireNonNull(earliestStart, "earliestStart");
        this.extra = extra;
    }

    /**
     * A window identical to this one, except every hour used (for a start or within a run) must also
     * satisfy {@code allowed}. Never widens what this window already allows - composes with any earlier
     * restriction (logical AND), so repeated calls only narrow further.
     */
    public JobWindow restrictedTo(Predicate<LocalDateTime> allowed) {
        return new JobWindow(job, prices, earliestStart, extra.and(allowed));
    }

    public OptimizerJob job() {
        return job;
    }

    /** Start of the last slot the job may use. */
    public LocalDateTime lastSlot() {
        return lastSlot;
    }

    /** The earliest hour this run may schedule into. No usable slot starts before it. */
    public LocalDateTime earliestStart() {
        return earliestStart;
    }

    /**
     * Start of the first slot the job could use, before pricing and restrictions are considered:
     * its original start, or the run's earliest schedulable hour when that is later. Paired with
     * {@link #lastSlot()} it bounds the hours worth building solver variables for, so a job whose
     * original start has passed no longer contributes variables for hours nobody can use.
     */
    public LocalDateTime firstSlot() {
        return job.originalStartTime().isBefore(earliestStart) ? earliestStart : job.originalStartTime();
    }

    /** Priced slots the job may start in, earliest first. Empty when the job cannot run at all. */
    public List<LocalDateTime> startSlots() {
        List<LocalDateTime> starts = new ArrayList<>();
        LocalDateTime latest = job.latestStart().isBefore(lastSlot) ? job.latestStart() : lastSlot;
        for (LocalDateTime slot = firstSlot(); !slot.isAfter(latest); slot = slot.plusHours(1)) {
            if (usable(slot)) {
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
            if (!usable(slot)) {
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

    /** True when {@code hour} is priced and satisfies this window's extra restriction, if any. Does
     *  not check that {@code hour} is otherwise reachable by a valid run - just whether it could ever
     *  be used at all, independent of where a run starts. */
    public boolean isUsable(LocalDateTime hour) {
        return usable(hour);
    }

    /**
     * The single definition of "this job may occupy {@code hour}", independent of where a run starts:
     * the hour is at or after the run's earliest schedulable hour, carries a price the optimiser may
     * act on, and passes every restriction layered on by {@link #restrictedTo}.
     *
     * <p>"A price the optimiser may act on" is narrower than "a price": see
     * {@link PriceSeries#isCandidateAt}. An hour priced from the history fallback because the model
     * forecast did not reach it can still cost a baseline, but a job is not moved into it.
     */
    private boolean usable(LocalDateTime hour) {
        return !hour.isBefore(earliestStart) && prices.isCandidateAt(hour) && extra.test(hour);
    }
}
