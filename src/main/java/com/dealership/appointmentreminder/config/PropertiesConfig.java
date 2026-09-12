package com.dealership.appointmentreminder.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the typed configuration objects as beans.
 *
 * <p>Kept explicit rather than annotating {@link ReminderProperties} with {@code @Component},
 * so that all configuration wiring is visible in one file.
 */
@Configuration
@EnableConfigurationProperties(ReminderProperties.class)
public class PropertiesConfig {
}
