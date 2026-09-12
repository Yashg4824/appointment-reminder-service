package com.dealership.appointmentreminder.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's real {@code Clock.systemUTC()} with a clock the test drives.
 *
 * <p>Registered as {@code @Primary} rather than as a same-named bean override, because Spring Boot
 * 2.7 disables bean-definition overriding by default. Marking this one primary resolves the
 * ambiguity without having to relax that safety setting.
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableTestClock testClock() {
        return new MutableTestClock();
    }
}
