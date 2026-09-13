package com.dealership.appointmentreminder.entity;

/**
 * Lifecycle of a single reminder. The definitions matter, so they are spelled out precisely
 * here rather than left to the reader's assumption (architecture document, section 8).
 *
 * <p>Valid transitions:
 * <pre>
 *   (created) -> PENDING
 *   PENDING    -> PROCESSING   claimed by a worker
 *   PENDING    -> CANCELLED    appointment was cancelled
 *   PROCESSING -> SENT         the sender returned successfully
 *   PROCESSING -> PENDING      the sender failed (retry), or processing_until expired
 *   PROCESSING -> FAILED       retry limit reached, or a permanent error
 * </pre>
 * SENT, FAILED and CANCELLED are terminal.
 */
public enum ReminderStatus {

    /**
     * This reminder still needs to be processed. It may not be due yet, may be due and waiting
     * for a worker, or may be waiting out the retry delay after a failed attempt.
     */
    PENDING,

    /**
     * A worker has claimed this row and is working on it. If {@code processing_until} passes
     * without a terminal outcome, any worker may reclaim it - that is the crash-recovery
     * mechanism, and it is also why a worker that is merely slow can have its work reclaimed
     * (architecture document, section 11).
     */
    PROCESSING,

    /**
     * Notification processing completed successfully: the {@code NotificationSender} returned
     * without error.
     *
     * <p>This is deliberately <b>not</b> a claim that the customer received exactly one message.
     * Delivery to an external provider is at-least-once, because sending and committing cannot
     * be made atomic (architecture document, section 11).
     */
    SENT,

    /**
     * Processing did not succeed after the retry limit, or the error was permanent.
     * Terminal, and needs human attention.
     */
    FAILED,

    /** The appointment was cancelled before this reminder was processed. */
    CANCELLED
}
