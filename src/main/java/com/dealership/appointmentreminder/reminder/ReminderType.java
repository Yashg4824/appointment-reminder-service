package com.dealership.appointmentreminder.reminder;

import java.time.Duration;
import java.time.Instant;

/**
 * The kinds of reminder the system sends, each carrying how far before the appointment it is due.
 *
 * <p><b>Why this exists, and why it is an enum with a field:</b> this is the Open/Closed
 * mechanism described in architecture document section 13. Adding a 30-minute reminder is one
 * new constant here - {@code THIRTY_MINUTES(Duration.ofMinutes(30))} - and nothing else in the
 * system changes. {@code ReminderScheduleCalculator} iterates {@link #values()}, and the worker
 * and processor never branch on the type at all.
 *
 * <p>The thing being deliberately avoided is a {@code switch} on reminder type in the
 * scheduling or processing code. That is what would have to be found and edited in several
 * places every time a type was added. There is no strategy hierarchy and no factory here,
 * because the only thing that varies between types is one {@link Duration}.
 */
public enum ReminderType {

    /** Sent 24 hours before the appointment. */
    TWENTY_FOUR_HOURS(Duration.ofHours(24)),

    /** Sent 2 hours before the appointment. */
    TWO_HOURS(Duration.ofHours(2));

    private final Duration offsetBeforeAppointment;

    ReminderType(Duration offsetBeforeAppointment) {
        this.offsetBeforeAppointment = offsetBeforeAppointment;
    }

    public Duration getOffsetBeforeAppointment() {
        return offsetBeforeAppointment;
    }

    /**
     * The instant this reminder becomes due for the given appointment time.
     *
     * <p>Absolute instant arithmetic, so a daylight-saving transition between the reminder and
     * the appointment needs no special handling: "24 hours before" means exactly 24 hours
     * (architecture document, assumption 1).
     */
    public Instant dueTimeFor(Instant appointmentScheduledAt) {
        return appointmentScheduledAt.minus(offsetBeforeAppointment);
    }
}
