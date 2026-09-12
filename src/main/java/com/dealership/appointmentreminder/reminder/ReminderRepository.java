package com.dealership.appointmentreminder.reminder;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database access for {@link Reminder}, including the two hand-written statements this
 * architecture depends on.
 *
 * <p>The claim and reclaim statements are deliberately explicit native SQL rather than generated
 * queries: {@code FOR UPDATE SKIP LOCKED} has no JPQL equivalent, and these are the lines a
 * reviewer most needs to be able to read (architecture document, section 6).
 *
 * <p><b>Why the outcome updates are conditional statements rather than entity mutations:</b> they
 * run after the claim transaction has committed, with no row lock held. Writing
 * {@code WHERE id = :id AND status = 'PROCESSING'} means there is no read-then-write window and
 * therefore no lost update; the returned row count tells the caller whether it still owned the
 * reminder (architecture document, section 9b).
 *
 * <p><b>SOLID:</b> Dependency Inversion - the worker and processor depend on this interface,
 * never on an {@code EntityManager} or {@code JdbcTemplate}.
 */
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /**
     * All reminders for one appointment, for {@code GET /appointments/{id}}.
     *
     * <p>This is the proof surface for the no-duplicates requirement: it shows every reminder,
     * its status, its attempt count and when it was sent.
     */
    List<Reminder> findByAppointmentIdOrderByScheduledAtAsc(Long appointmentId);

    /**
     * Claims due reminders for the calling transaction.
     *
     * <p>{@code FOR UPDATE} locks the selected rows until the transaction ends.
     * {@code SKIP LOCKED} makes a concurrent transaction step over rows another transaction
     * already holds rather than blocking on them, so two workers running this query at the same
     * moment both return immediately with <b>disjoint</b> result sets. That is the entire
     * multi-instance coordination mechanism (architecture document, section 9a).
     *
     * <p>{@code status = 'PENDING'} is what excludes reminders that are already SENT, FAILED,
     * CANCELLED or in flight. The {@code last_attempt_at} predicate is the retry gate: a reminder
     * that just failed is not picked up again until the retry delay has elapsed.
     *
     * <p>{@code LIMIT :batchSize} is the backpressure limit - a worker never takes on more work
     * in one cycle than it is configured to handle.
     */
    @Query(value =
            "SELECT * FROM reminders "
                    + "WHERE status = 'PENDING' "
                    + "  AND scheduled_at <= :now "
                    + "  AND (last_attempt_at IS NULL OR last_attempt_at <= :retryCutoff) "
                    + "ORDER BY scheduled_at "
                    + "LIMIT :batchSize "
                    + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Reminder> findDueForUpdateSkipLocked(@Param("now") Instant now,
                                              @Param("retryCutoff") Instant retryCutoff,
                                              @Param("batchSize") int batchSize);

    /**
     * Returns work abandoned by a crashed worker to PENDING. Crash recovery, in one statement.
     *
     * <p>Any reminder still PROCESSING after its {@code processing_until} has passed is assumed to
     * belong to a worker that died. The statement is idempotent, so running it concurrently on
     * every instance is safe: whichever runs first releases the rows and the others match nothing.
     *
     * <p>{@code attempt_count} is incremented here as well as on failure, so a reminder that
     * repeatedly kills its worker eventually exhausts its attempts and is failed rather than
     * looping forever (architecture document, section 11).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'PENDING', processing_until = NULL, "
                    + "    attempt_count = attempt_count + 1, updated_at = :now "
                    + "WHERE status = 'PROCESSING' AND processing_until < :now",
            nativeQuery = true)
    int reclaimStaleProcessing(@Param("now") Instant now);

    /**
     * Records a successful send. Guarded on PROCESSING, so a reminder that is no longer owned by
     * the caller is not written.
     *
     * @return 1 if this caller still owned the reminder, 0 if it did not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'SENT', sent_at = :now, last_attempt_at = :now, "
                    + "    processing_until = NULL, updated_at = :now "
                    + "WHERE id = :id AND status = 'PROCESSING'",
            nativeQuery = true)
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Records a failed attempt and returns the reminder to PENDING so it is retried once the
     * retry delay has elapsed.
     *
     * @return 1 if this caller still owned the reminder, 0 if it did not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'PENDING', attempt_count = attempt_count + 1, "
                    + "    last_attempt_at = :now, processing_until = NULL, updated_at = :now "
                    + "WHERE id = :id AND status = 'PROCESSING'",
            nativeQuery = true)
    int markForRetry(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Gives up on a reminder: the attempt limit is exhausted, or the failure was permanent.
     * Terminal, and needs human attention.
     *
     * @return 1 if this caller still owned the reminder, 0 if it did not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'FAILED', attempt_count = attempt_count + 1, "
                    + "    last_attempt_at = :now, processing_until = NULL, updated_at = :now "
                    + "WHERE id = :id AND status = 'PROCESSING'",
            nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Suppresses a claimed reminder whose appointment is no longer SCHEDULED. Used by the
     * processor when it re-reads the appointment and finds it cancelled after the claim.
     *
     * @return 1 if this caller still owned the reminder, 0 if it did not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'CANCELLED', processing_until = NULL, updated_at = :now "
                    + "WHERE id = :id AND status = 'PROCESSING'",
            nativeQuery = true)
    int markCancelled(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Suppresses every not-yet-claimed reminder for a cancelled appointment.
     *
     * <p>Only PENDING rows are touched: a SENT reminder cannot be unsent, and a PROCESSING one
     * belongs to a worker that will discover the cancellation when it re-reads the appointment.
     *
     * @return how many reminders were suppressed
     */
    // flushAutomatically ensures the caller's pending appointment update is written before this
    // statement runs. clearAutomatically is deliberately NOT set: it would detach the caller's
    // managed Appointment mid-transaction, which is a subtle trap for no benefit here.
    @Modifying(flushAutomatically = true)
    @Query(value =
            "UPDATE reminders "
                    + "SET status = 'CANCELLED', updated_at = :now "
                    + "WHERE appointment_id = :appointmentId AND status = 'PENDING'",
            nativeQuery = true)
    int cancelPendingForAppointment(@Param("appointmentId") Long appointmentId, @Param("now") Instant now);
}
