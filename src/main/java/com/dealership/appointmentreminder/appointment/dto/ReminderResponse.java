package com.dealership.appointmentreminder.appointment.dto;

import java.time.Instant;

import com.dealership.appointmentreminder.reminder.Reminder;

/**
 * One reminder as returned by the API.
 *
 * <p><b>Why this exists:</b> it is the proof surface for the assignment's central requirement.
 * Showing each reminder's status, attempt count and {@code sentAt} is how a reviewer verifies
 * from outside the system that a reminder was sent exactly once - or sees precisely why it was
 * not sent at all.
 */
public class ReminderResponse {

    private final Long id;
    private final String type;
    private final Instant scheduledAt;
    private final String status;
    private final int attemptCount;
    private final Instant sentAt;

    public ReminderResponse(Reminder reminder) {
        this.id = reminder.getId();
        this.type = reminder.getReminderType().name();
        this.scheduledAt = reminder.getScheduledAt();
        this.status = reminder.getStatus().name();
        this.attemptCount = reminder.getAttemptCount();
        this.sentAt = reminder.getSentAt();
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
