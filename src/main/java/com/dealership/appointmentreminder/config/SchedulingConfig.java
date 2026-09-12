package com.dealership.appointmentreminder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support, which drives
 * {@code ReminderWorker}.
 *
 * <p><b>Why this is its own class rather than an annotation on the application class:</b>
 * scheduling is the one behaviour that must be switched off in most tests. Isolating the
 * annotation here lets a test slice exclude this configuration, so an integration test can
 * drive a worker cycle explicitly and assert on the result, instead of racing a background
 * timer.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
