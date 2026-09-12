package com.dealership.appointmentreminder.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} whose current instant the test controls.
 *
 * <p>Why this exists: every timing rule in the application reads time from an injected
 * {@code Clock}. Substituting this one makes "24 hours before the appointment", "the processing
 * timeout has expired" and "this reminder is now due" testable instantly and deterministically,
 * with no {@code Thread.sleep} and no dependency on the machine's real clock.
 */
public class MutableTestClock extends Clock {

    /** An arbitrary but fixed reference instant, so every test starts from the same "now". */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-06-15T10:00:00Z");

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableTestClock() {
        this(DEFAULT_NOW, ZoneOffset.UTC);
    }

    public MutableTestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableTestClock(instant, newZone);
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    /** Moves time forward, for example past a reminder's due time or a processing timeout. */
    public void advanceBy(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public void reset() {
        this.instant = DEFAULT_NOW;
    }
}
