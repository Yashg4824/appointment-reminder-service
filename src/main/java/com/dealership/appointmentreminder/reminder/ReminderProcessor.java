package com.dealership.appointmentreminder.reminder;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dealership.appointmentreminder.appointment.Appointment;
import com.dealership.appointmentreminder.appointment.AppointmentRepository;
import com.dealership.appointmentreminder.appointment.AppointmentStatus;
import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.notification.Notification;
import com.dealership.appointmentreminder.notification.NotificationSender;

/**
 * Processes one already-claimed reminder: builds the notification, sends it, records the outcome.
 *
 * <p><b>Why this class exists:</b> it is the single place a reviewer must read to understand the
 * system's delivery semantics - when a reminder is sent, when it is retried, when it is given up
 * on. Keeping that in one class, separate from claiming and from scheduling, is what makes those
 * semantics reviewable at all.
 *
 * <p><b>Responsibility:</b> exactly one reminder's outcome. It does not poll, does not claim, and
 * does not decide when reminders are due.
 *
 * <p><b>Important:</b> this runs <i>outside</i> the claim transaction. The outcome is written
 * with a conditional update guarded on the expected current status, so a reminder that has
 * already reached a terminal state cannot be written again (architecture document, section 9b).
 *
 * <p><b>SOLID:</b>
 * <ul>
 *   <li>Dependency Inversion - it depends on the {@link NotificationSender} interface, never on
 *       the logging implementation. This is what lets a test inject a sender that fails, or one
 *       that counts invocations, which is how the retry and concurrency tests are possible.</li>
 *   <li>Open/Closed - it never branches on {@link ReminderType}, so a new reminder type flows
 *       through unchanged.</li>
 * </ul>
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link ReminderRepository} - to write the outcome.</li>
 *   <li>{@link AppointmentRepository} - to re-read the appointment and suppress the notification
 *       if it was cancelled after the reminder was claimed (architecture document, section 9c).
 *       This is an explicit load rather than a JPA association, because this code runs outside a
 *       transaction; see the note on {@link Reminder}.</li>
 *   <li>{@link NotificationSender} - the delivery abstraction.</li>
 *   <li>{@link ReminderProperties} - the retry limit and retry delay.</li>
 *   <li>{@link Clock} - to stamp {@code sent_at} and {@code last_attempt_at}.</li>
 * </ul>
 */
@Service
public class ReminderProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReminderProcessor.class);

    private final ReminderRepository reminderRepository;
    private final AppointmentRepository appointmentRepository;
    private final NotificationSender notificationSender;
    private final ReminderProperties properties;
    private final Clock clock;

    public ReminderProcessor(ReminderRepository reminderRepository,
                             AppointmentRepository appointmentRepository,
                             NotificationSender notificationSender,
                             ReminderProperties properties,
                             Clock clock) {
        this.reminderRepository = reminderRepository;
        this.appointmentRepository = appointmentRepository;
        this.notificationSender = notificationSender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sends one claimed reminder and records what happened.
     *
     * <p>Phase 2 outline:
     * <ol>
     *   <li>Re-read the appointment; if it is no longer SCHEDULED, mark the reminder CANCELLED
     *       and send nothing.</li>
     *   <li>Build the {@code Notification} and call the sender.</li>
     *   <li>On success: conditional update to SENT with {@code sent_at}.</li>
     *   <li>On failure: increment {@code attempt_count}, stamp {@code last_attempt_at}, and
     *       return to PENDING - or to FAILED once the attempt limit is reached.</li>
     * </ol>
     *
     * @param reminder a reminder this instance has already claimed and marked PROCESSING
     */
    public void process(Reminder reminder) {
        Instant now = clock.instant();
        Long reminderId = reminder.getId();

        // A reminder whose attempts are already exhausted reaches this point only by having been
        // reclaimed repeatedly - each reclaim increments attempt_count. Fail it here rather than
        // retrying forever.
        if (reminder.getAttemptCount() >= properties.getMaxAttempts()) {
            log.error("Reminder {} exhausted {} attempts without completing; marking FAILED",
                    reminderId, reminder.getAttemptCount());
            recordOutcome(reminderId, reminderRepository.markFailed(reminderId, now), "FAILED");
            return;
        }

        Optional<Appointment> appointment = appointmentRepository.findById(reminder.getAppointmentId());

        // Re-read rather than trust the state at claim time: the appointment may have been
        // cancelled between the claim committing and this send (architecture document, section 9c).
        if (!appointment.isPresent() || appointment.get().getStatus() != AppointmentStatus.SCHEDULED) {
            log.info("Suppressing reminder {}: appointment {} is no longer SCHEDULED",
                    reminderId, reminder.getAppointmentId());
            recordOutcome(reminderId, reminderRepository.markCancelled(reminderId, now), "CANCELLED");
            return;
        }

        Appointment appt = appointment.get();
        Notification notification = new Notification(
                reminderId,
                reminder.getReminderType(),
                appt.getCustomerContact(),
                appt.getCustomerName(),
                appt.getScheduledAt());

        try {
            notificationSender.send(notification);
        } catch (RuntimeException e) {
            handleSendFailure(reminder, now, e);
            return;
        }

        recordOutcome(reminderId, reminderRepository.markSent(reminderId, now), "SENT");
    }

    /**
     * Decides between one more attempt and giving up.
     *
     * <p>The failure is contained here: it is recorded against this one reminder and never
     * rethrown, so a single bad recipient cannot abandon the rest of the batch or stop the worker.
     */
    private void handleSendFailure(Reminder reminder, Instant now, RuntimeException e) {
        Long reminderId = reminder.getId();
        int attemptsAfterThis = reminder.getAttemptCount() + 1;

        if (attemptsAfterThis >= properties.getMaxAttempts()) {
            log.error("Reminder {} failed on attempt {} of {}; giving up and marking FAILED",
                    reminderId, attemptsAfterThis, properties.getMaxAttempts(), e);
            recordOutcome(reminderId, reminderRepository.markFailed(reminderId, now), "FAILED");
        } else {
            log.warn("Reminder {} failed on attempt {} of {}; retrying after {} - {}",
                    reminderId, attemptsAfterThis, properties.getMaxAttempts(),
                    properties.getRetryDelay(), e.toString());
            recordOutcome(reminderId, reminderRepository.markForRetry(reminderId, now), "PENDING (retry)");
        }
    }

    /**
     * Every outcome write is guarded on the reminder still being PROCESSING, so the row count is
     * the answer to "did I still own this reminder?".
     *
     * <p>A count of 0 is the slow-worker race the architecture documents in section 11: this
     * worker took longer than the processing timeout, another worker reclaimed the reminder, and
     * the row is no longer ours to write. The correct action is to do nothing further - the
     * reminder now belongs to whichever worker reclaimed it. It is logged at WARN because it means
     * the customer may receive this notification twice, which is the known at-least-once boundary.
     */
    private void recordOutcome(Long reminderId, int rowsUpdated, String intendedStatus) {
        if (rowsUpdated == 0) {
            log.warn("Reminder {} was no longer PROCESSING when recording {}; another worker "
                            + "reclaimed it after the processing timeout. Outcome not recorded.",
                    reminderId, intendedStatus);
        } else {
            log.debug("Reminder {} -> {}", reminderId, intendedStatus);
        }
    }
}
