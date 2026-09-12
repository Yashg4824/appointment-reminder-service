package com.dealership.appointmentreminder.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the single {@link Clock} every component reads the current time from.
 *
 * <p><b>Why this exists:</b> architecture document section 16 requires that timing behaviour be
 * testable deterministically. If classes called {@code Instant.now()} directly, testing "a
 * reminder becomes reclaimable once processing_until passes" would need {@code Thread.sleep},
 * which is slow and flaky. With an injected clock a test substitutes
 * {@code Clock.fixed(...)} and moves time instantly.
 *
 * <p><b>Why not a custom TimeProvider interface:</b> {@code java.time.Clock} is already that
 * abstraction, and it is already injectable. Document section 13 lists a custom wrapper among
 * the abstractions deliberately not introduced.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
