package com.dealership.appointmentreminder.appointment.dto;

import java.time.OffsetDateTime;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * Request body for {@code POST /appointments}.
 *
 * <p><b>Why {@code scheduledAt} is an {@link OffsetDateTime} and not a {@code LocalDateTime}:</b>
 * this is the API's time-zone contract, enforced by the type system. Jackson will reject
 * {@code "2026-09-20T14:30:00"} because it carries no offset, and accept
 * {@code "2026-09-20T14:30:00-04:00"} because it does. Accepting a bare local date-time would
 * force the server to guess a zone, and guessing the server's own zone is the classic source of
 * off-by-one-hour bugs (architecture document, section 7).
 *
 * <p><b>Why the "must be in the future" rule is not a {@code @Future} annotation here:</b> Bean
 * Validation would compare against its own internal clock rather than the injected {@code Clock},
 * which would make that rule untestable at a fixed instant. The check lives in
 * {@code AppointmentService} instead, where the clock is injected.
 */
public class CreateAppointmentRequest {

    @NotNull(message = "dealershipId is required")
    private Long dealershipId;

    @NotBlank(message = "customerName is required")
    @Size(max = 200, message = "customerName must be at most 200 characters")
    private String customerName;

    @NotBlank(message = "customerContact is required")
    @Size(max = 200, message = "customerContact must be at most 200 characters")
    private String customerContact;

    @NotNull(message = "scheduledAt is required, as ISO-8601 with an offset, e.g. 2026-09-20T14:30:00-04:00")
    private OffsetDateTime scheduledAt;

    public Long getDealershipId() {
        return dealershipId;
    }

    public void setDealershipId(Long dealershipId) {
        this.dealershipId = dealershipId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerContact() {
        return customerContact;
    }

    public void setCustomerContact(String customerContact) {
        this.customerContact = customerContact;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
