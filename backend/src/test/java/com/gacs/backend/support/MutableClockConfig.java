package com.gacs.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

/**
 * Supplies a {@link MutableClock} as the application clock, for integration tests that need time to
 * pass rather than to stand still.
 *
 * <p>Starts at the real instant, so fixtures a test derives from {@code Instant.now()} or
 * {@code LocalDateTime.now()} still line up with what the application sees. Inject the
 * {@code MutableClock} and move it with {@link MutableClock#set}.
 *
 * <p>Import it with {@code @Import(MutableClockConfig.class)}. The bean method must be named
 * {@code clock}, matching {@code ClockConfig}, so this definition replaces that one rather than
 * colliding with it.
 *
 * <p>The bean is scoped to the Spring test context, which is cached and shared between the methods of
 * a test class, so a test that moves it should reset it afterwards.
 */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    public MutableClock clock() {
        return new MutableClock(Instant.now());
    }
}
