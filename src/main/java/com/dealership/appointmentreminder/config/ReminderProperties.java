package com.dealership.appointmentreminder.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed access to the {@code reminder.*} settings in {@code application.yml}.
 *
 * <p><b>Why this exists:</b> every timing rule in the system is a policy decision that a
 * reviewer or operator may want to change without recompiling: how often to poll, how large a
 * batch to claim, how long a claim survives, how long to wait before retrying, when to give up,
 * and how late a reminder may be and still be worth sending. Collecting them in one typed
 * object keeps those decisions visible in a single place instead of as scattered literals.
 *
 * <p><b>SOLID:</b> supports Dependency Inversion in a small way - the worker and processor
 * depend on this configuration object rather than reading the environment themselves.
 */
@ConfigurationProperties(prefix = "reminder")
public class ReminderProperties {

    /** How often each instance polls for due reminders. */
    private long pollIntervalMs = 10_000L;

    /** Maximum reminders claimed in one cycle. The backpressure limit (doc section 11). */
    private int batchSize = 100;

    /** How long a claimed reminder stays claimed before any worker may reclaim it. */
    private Duration processingTimeout = Duration.ofMinutes(1);

    /** How long after a failed attempt before the reminder is eligible again. */
    private Duration retryDelay = Duration.ofMinutes(5);

    /** Attempts before a reminder is marked FAILED. */
    private int maxAttempts = 5;

    /**
     * How far in the past a reminder's due time may be and still be worth creating.
     * A reminder a few minutes late is a late reminder; one many hours late would be a
     * wrong message (architecture document, section 8).
     */
    private Duration gracePeriod = Duration.ofMinutes(15);

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }

    public void setProcessingTimeout(Duration processingTimeout) {
        this.processingTimeout = processingTimeout;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }
}
