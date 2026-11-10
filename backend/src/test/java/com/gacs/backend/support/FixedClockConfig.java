package com.gacs.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Pins the application clock for integration tests whose fixtures use absolute dates.
 *
 * <p>Those tests build jobs at fixed timestamps in October 2026 and assert on exact scheduled hours.
 * Against {@link Clock#systemUTC()} they only pass while those dates are still in the future, so they
 * were time bombs: once the real date passed 2026-10-20 every fixture job became a job in the past.
 * That went unnoticed until the scheduler began rejecting past start times and refusing to place work
 * in elapsed hours, which is what these fixtures had quietly become.
 *
 * <p>{@code ClockConfig} declares its bean {@code @ConditionalOnMissingBean(Clock.class)} precisely so
 * a test can supply its own; this is that bean. {@link #INSTANT} is one hour before the earliest
 * fixture job start (2026-10-20T10:00Z), so every fixture slot is still ahead of "now".
 *
 * <p>Import it with {@code @Import(FixedClockConfig.class)}. Tests that derive their dates from the
 * real clock instead - {@code ScheduleExportIntegrationTest} - must not use this, and keep the system
 * clock or a clock they control themselves.
 */
@TestConfiguration
public class FixedClockConfig {

    /** One hour before the earliest fixture job start. */
    public static final Instant INSTANT = Instant.parse("2026-10-20T09:00:00Z");

    @Bean
    public Clock clock() {
        return Clock.fixed(INSTANT, ZoneOffset.UTC);
    }
}
