package com.dealership.appointmentreminder.appointment;

/**
 * Lifecycle of an appointment.
 *
 * <p>Only {@link #SCHEDULED} appointments produce notifications: {@code ReminderProcessor}
 * re-reads the appointment before sending and suppresses the notification if the status has
 * moved on (architecture document, section 9c).
 */
public enum AppointmentStatus {

    /** Booked and upcoming. The only status for which reminders are sent. */
    SCHEDULED,

    /** Cancelled by the customer or the dealership. Pending reminders are cancelled with it. */
    CANCELLED,

    /** The service visit happened. Reminders are irrelevant. */
    COMPLETED
}
