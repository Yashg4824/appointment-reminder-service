package com.dealership.appointmentreminder.appointment;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A vehicle service appointment. Maps to the {@code appointments} table.
 *
 * <p><b>Why this class exists:</b> it is the record the customer booked, and the source of the
 * one value everything else derives from - {@link #scheduledAt}. Reminder due times are computed
 * from it, and the processor checks {@link #status} on it before sending.
 *
 * <p><b>Time handling:</b> {@code scheduledAt} is an {@link Instant}, stored in a
 * {@code TIMESTAMPTZ} column. An absolute instant cannot be misread in another time zone, and
 * "24 hours before" is then plain instant arithmetic with no DST special case. {@code
 * LocalDateTime} is deliberately absent from this class (architecture document, section 7).
 *
 * <p><b>Note on {@code createdAt}/{@code updatedAt}:</b> these are set by
 * {@code AppointmentService} from the injected {@code Clock}, not by JPA {@code @PrePersist}
 * callbacks. A callback would call {@code Instant.now()} internally and silently bypass the
 * injected clock, which would make time-dependent tests non-deterministic.
 */
@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dealership_id", nullable = false)
    private Long dealershipId;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    /** Email address or phone number. One channel per appointment in this version. */
    @Column(name = "customer_contact", nullable = false, length = 200)
    private String customerContact;

    /** The absolute instant of the appointment. All reminder times derive from this. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected Appointment() {
    }

    public Appointment(Long dealershipId,
                       String customerName,
                       String customerContact,
                       Instant scheduledAt,
                       AppointmentStatus status,
                       Instant now) {
        this.dealershipId = dealershipId;
        this.customerName = customerName;
        this.customerContact = customerContact;
        this.scheduledAt = scheduledAt;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
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

    public AppointmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(AppointmentStatus status) {
        this.status = status;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
