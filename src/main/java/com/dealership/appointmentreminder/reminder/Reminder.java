package com.dealership.appointmentreminder.reminder;

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
 * One scheduled reminder for one appointment. Maps to the {@code reminders} table.
 *
 * <p><b>Why this class exists:</b> a reminder is a durable row, not something computed on the
 * fly. That single decision is what lets the database - rather than application code - guarantee
 * that a reminder is never sent twice, through
 * {@code UNIQUE (appointment_id, reminder_type)}. This table is also the work queue the
 * reminder worker claims from.
 *
 * <p><b>Why {@code appointmentId} is a plain Long and not a JPA {@code @ManyToOne}:</b> reminders
 * are claimed in one transaction and then processed <i>after</i> that transaction commits
 * (architecture document, section 11 - the claim must commit before the network call). A lazy
 * association would therefore throw {@code LazyInitializationException} the moment the processor
 * touched it, and an eager one would join {@code appointments} on every claim query. The
 * processor needs the appointment only to re-check its status before sending, so it loads it
 * explicitly through {@code AppointmentRepository}. Referential integrity is still enforced, by
 * the foreign key in the schema.
 */
@Entity
@Table(name = "reminders")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 30)
    private ReminderType reminderType;

    /** When this reminder becomes due to be sent. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReminderStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * Set while a worker holds this row; null otherwise. Once this instant passes without a
     * terminal outcome the row is reclaimable.
     */
    @Column(name = "processing_until")
    private Instant processingUntil;

    /** Gates retries, so a failed attempt is not retried on the very next worker cycle. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected Reminder() {
    }

    public Reminder(Long appointmentId, ReminderType reminderType, Instant scheduledAt, Instant now) {
        this.appointmentId = appointmentId;
        this.reminderType = reminderType;
        this.scheduledAt = scheduledAt;
        this.status = ReminderStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public ReminderType getReminderType() {
        return reminderType;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getProcessingUntil() {
        return processingUntil;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void setStatus(ReminderStatus status) {
        this.status = status;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Marks this reminder as claimed until {@code processingUntil}.
     *
     * <p>Only called from inside the claim transaction, while this row is held by
     * {@code FOR UPDATE SKIP LOCKED}. The row lock is what makes a plain field update safe here:
     * no other transaction can read or write the row until this one commits. Outcome writes,
     * which happen later with no lock held, use conditional UPDATE statements instead
     * (architecture document, section 9b).
     */
    public void markProcessing(Instant processingUntil, Instant now) {
        this.status = ReminderStatus.PROCESSING;
        this.processingUntil = processingUntil;
        this.updatedAt = now;
    }
}
