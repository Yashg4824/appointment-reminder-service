package com.dealership.appointmentreminder.reminder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dealership.appointmentreminder.config.ReminderProperties;

/**
 * Decides which reminders an appointment should have, and when each is due.
 *
 * <p><b>Why this class exists:</b> the timing rules are where all the interesting edge cases
 * live - an appointment booked 25 hours ahead, 3 hours ahead, 30 minutes ahead - and they are
 * pure logic. Separating them from {@code ReminderProcessor}, which does I/O and state
 * transitions, means they can be unit-tested exhaustively in milliseconds with no database and
 * no Spring context (architecture document, sections 6 and 13).
 *
 * <p><b>SOLID:</b>
 * <ul>
 *   <li>Single Responsibility - this class knows about time arithmetic and nothing else.
 *       It performs no persistence and sends nothing.</li>
 *   <li>Open/Closed - it iterates {@link ReminderType#values()}, so a new reminder type is
 *       picked up with no change to this class.</li>
 * </ul>
 *
 * <p><b>Dependencies:</b> {@link ReminderProperties}, for the grace period. That belongs here
 * because the grace period is purely a timing policy, which is this class's subject.
 * Note that it does <i>not</i> depend on {@code Clock}: "now" is passed in by the caller, which
 * keeps the class a pure function and makes it trivially testable.
 */
@Component
public class ReminderScheduleCalculator {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduleCalculator.class);

    private final ReminderProperties properties;

    public ReminderScheduleCalculator(ReminderProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the reminders that should exist for an appointment.
     *
     * <p>Rule (architecture document, section 8): for each {@link ReminderType}, compute
     * {@code appointmentScheduledAt - offset}. Create the reminder if that instant is not more
     * than the grace period in the past; otherwise create nothing, because a reminder many hours
     * late would be a wrong message rather than a late one.
     *
     * @param appointmentId          the owning appointment, already persisted so it has an id
     * @param appointmentScheduledAt the absolute instant of the appointment
     * @param now                    the current instant, supplied by the caller's Clock
     * @return reminders to persist; may be empty for an appointment booked very close to its slot
     */
    public List<Reminder> reminderPlanFor(Long appointmentId, Instant appointmentScheduledAt, Instant now) {
        List<Reminder> plan = new ArrayList<>();

        // Iterating the enum is the Open/Closed mechanism: a new reminder type is picked up
        // here with no change to this method. There is deliberately no switch on type.
        for (ReminderType type : ReminderType.values()) {
            Instant dueAt = type.dueTimeFor(appointmentScheduledAt);

            if (isStillWorthSending(dueAt, now)) {
                plan.add(new Reminder(appointmentId, type, dueAt, now));
            } else {
                // Not an error: an appointment booked close to its slot legitimately has fewer
                // reminders. Logged so the absence of a row is explainable after the fact.
                log.info("Skipping {} reminder for appointment {}: due at {} is more than the {} "
                                + "grace period in the past (now={})",
                        type, appointmentId, dueAt, properties.getGracePeriod(), now);
            }
        }
        return plan;
    }

    /**
     * Whether a reminder due at {@code dueAt} is still worth creating as of {@code now}.
     * Exposed separately because it is the single rule most worth testing in isolation.
     */
    public boolean isStillWorthSending(Instant dueAt, Instant now) {
        return !dueAt.isBefore(now.minus(properties.getGracePeriod()));
    }
}
