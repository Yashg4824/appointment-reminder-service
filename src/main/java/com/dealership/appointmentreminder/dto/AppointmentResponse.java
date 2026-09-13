package com.dealership.appointmentreminder.dto;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.Reminder;

/**
 * An appointment and its reminder plan, as returned by the API.
 *
 * <p><b>Why the reminders are included:</b> an appointment booked close to its slot legitimately
 * has fewer than two reminders (architecture document, section 8). Returning the plan makes that
 * rule visible to the caller rather than something they have to infer from silence.
 *
 * <p><b>Why a DTO rather than returning the entity:</b> it keeps the JSON contract independent of
 * the database schema, so a column can be added or renamed without changing the API, and internal
 * bookkeeping such as {@code processingUntil} is never exposed.
 */
public class AppointmentResponse {

    private final Long id;
    private final Long dealershipId;
    private final String customerName;
    private final String customerContact;
    private final Instant scheduledAt;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<ReminderResponse> reminders;

    public AppointmentResponse(Appointment appointment, List<Reminder> reminders) {
        this.id = appointment.getId();
        this.dealershipId = appointment.getDealershipId();
        this.customerName = appointment.getCustomerName();
        this.customerContact = appointment.getCustomerContact();
        this.scheduledAt = appointment.getScheduledAt();
        this.status = appointment.getStatus().name();
        this.createdAt = appointment.getCreatedAt();
        this.updatedAt = appointment.getUpdatedAt();
        this.reminders = reminders.stream().map(ReminderResponse::new).collect(Collectors.toList());
    }

    public Long getId() {
        return id;
    }

    public Long getDealershipId() {
        return dealershipId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerContact() {
        return customerContact;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ReminderResponse> getReminders() {
        return reminders;
    }
}
