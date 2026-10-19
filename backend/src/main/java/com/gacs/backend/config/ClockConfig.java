package com.gacs.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.time.Clock;

/**
 * Supplies the UTC clock used by the schedule optimizer.
 *
 * <p>The optimizer must never call {@code LocalDateTime.now()} directly: its horizon,
 * its price lookups and its deadline arithmetic all key off "now", so a hard-coded
 * system clock makes every optimizer test dependent on the wall clock and liable to
 * flake in CI. Tests replace this bean with {@link Clock#fixed} to pin time.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
