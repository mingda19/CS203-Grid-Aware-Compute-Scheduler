package com.gacs.backend.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock a test can move, for asserting behaviour that depends on time passing.
 *
 * <p>Needed because some behaviour can no longer be faked by back-dating data. The scheduler refuses
 * to place work in hours that have already elapsed, so "a plan whose slots have ended" cannot be
 * produced by optimising a job in the past - the optimiser correctly reports that as infeasible. The
 * only way to reach that state is the way it happens in production: make a real plan, then let time
 * pass. This clock is how a test lets time pass.
 *
 * <p>Writes are published through a volatile field, so a move is visible to application code running
 * on another thread. {@link #withZone} keeps the mutability, unlike {@link Clock#fixed}.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return other.equals(zone) ? this : new Zoned(other);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    /** Moves this clock to {@code next}. */
    public void set(Instant next) {
        this.instant = next;
    }

    /** A view of this clock in another zone that still follows {@link #set}. */
    private final class Zoned extends Clock {
        private final ZoneId viewZone;

        private Zoned(ZoneId viewZone) {
            this.viewZone = viewZone;
        }

        @Override
        public ZoneId getZone() {
            return viewZone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return other.equals(viewZone) ? this : MutableClock.this.withZone(other);
        }

        @Override
        public Instant instant() {
            return MutableClock.this.instant();
        }
    }
}
