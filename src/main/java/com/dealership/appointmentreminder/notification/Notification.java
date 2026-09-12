package com.dealership.appointmentreminder.notification;

import java.time.Instant;
import java.util.Objects;

import com.dealership.appointmentreminder.reminder.ReminderType;

/**
 * An immutable value object describing one notification to be delivered.
 *
 * <p><b>Why this class exists:</b> it is the contract between {@code ReminderProcessor} and any
 * {@link NotificationSender}. Passing a value object rather than a {@code Reminder} entity keeps
 * senders away from persistence concerns - a sender cannot accidentally mutate or lazily load
 * database state - and means a future SMS or email sender needs no knowledge of JPA.
 *
 * <p><b>Why it carries {@code reminderId}:</b> that is the stable idempotency key. A real
 * provider would use it to suppress a duplicate caused by a retry after a crash, which is the
 * documented path from at-least-once to effectively-once delivery (architecture document,
 * section 11).
 *
 * <p><b>Why it carries the absolute {@code appointmentScheduledAt}:</b> the message states the
 * actual appointment time rather than a relative phrase like "in 24 hours". That one choice is
 * what makes a reminder delivered slightly late still correct, and is therefore what makes a
 * polling worker safe (architecture document, assumption 3).
 */
public final class Notification {

    private final Long reminderId;
    private final ReminderType reminderType;
    private final String recipient;
    private final String customerName;
    private final Instant appointmentScheduledAt;

    public Notification(Long reminderId,
                        ReminderType reminderType,
                        String recipient,
                        String customerName,
                        Instant appointmentScheduledAt) {
        this.reminderId = Objects.requireNonNull(reminderId, "reminderId");
        this.reminderType = Objects.requireNonNull(reminderType, "reminderType");
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.customerName = Objects.requireNonNull(customerName, "customerName");
        this.appointmentScheduledAt = Objects.requireNonNull(appointmentScheduledAt, "appointmentScheduledAt");
    }

    /** The stable idempotency key for this notification. */
    public Long getReminderId() {
        return reminderId;
    }

    public ReminderType getReminderType() {
        return reminderType;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Instant getAppointmentScheduledAt() {
        return appointmentScheduledAt;
    }
}
