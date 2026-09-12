package com.dealership.appointmentreminder.appointment.dto;

import java.time.OffsetDateTime;

import javax.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /appointments/{id}}.
 *
 * <p>As with {@link CreateAppointmentRequest}, the type is {@link OffsetDateTime} so that a
 * timestamp without an offset is rejected by the parser rather than silently interpreted in the
 * server's own time zone.
 */
public class RescheduleAppointmentRequest {

    @NotNull(message = "scheduledAt is required, as ISO-8601 with an offset, e.g. 2026-09-20T14:30:00-04:00")
    private OffsetDateTime scheduledAt;

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
