package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.dealership.appointmentreminder.appointment.Appointment;
import com.dealership.appointmentreminder.appointment.AppointmentService;
import com.dealership.appointmentreminder.notification.Notification;
import com.dealership.appointmentreminder.notification.NotificationSender;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;

/**
 * Tests the processor's delivery semantics against the real database.
 *
 * <p>The {@link NotificationSender} is mocked because it is the boundary to the outside world and
 * the test needs to make it fail on demand. Everything else - the claim, the state transitions,
 * the attempt counting - runs for real, because those are exactly the parts a mock would hide.
 *
 * <p>Reminders are always obtained through {@code claimDueReminders()} rather than constructed by
 * hand, so each test exercises the same path production uses: claimed, committed as PROCESSING,
 * then processed outside that transaction.
 */
class ReminderProcessorIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private NotificationSender notificationSender;

    @Autowired
    private ReminderClaimService claimService;

    @Autowired
    private ReminderProcessor processor;

    @Autowired
    private AppointmentService appointmentService;

    private Appointment appointment;

    /** A due, claimable reminder belonging to a SCHEDULED appointment. */
    private Reminder givenClaimableReminder() {
        appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        return givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(1)));
    }

    private Reminder claimSingleReminder() {
        List<Reminder> claimed = claimService.claimDueReminders();
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    // ------------------------------------------------------------------
    // Success
    // ------------------------------------------------------------------

    @Test
    void shouldMarkReminderSentAfterSuccessfulNotification() {
        Reminder reminder = givenClaimableReminder();

        processor.process(claimSingleReminder());

        verify(notificationSender).send(any(Notification.class));
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.SENT);
        assertThat(sentAtOf(reminder.getId())).isEqualTo(now());
    }

    @Test
    @DisplayName("The notification carries the customer's details and the absolute appointment time")
    void shouldSendNotificationContainingAppointmentAndCustomerDetails() {
        Reminder reminder = givenClaimableReminder();

        processor.process(claimSingleReminder());

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(notificationSender).send(sent.capture());
        Notification notification = sent.getValue();

        assertThat(notification.getReminderId()).isEqualTo(reminder.getId());
        assertThat(notification.getReminderType()).isEqualTo(ReminderType.TWENTY_FOUR_HOURS);
        assertThat(notification.getRecipient()).isEqualTo(appointment.getCustomerContact());
        assertThat(notification.getCustomerName()).isEqualTo(appointment.getCustomerName());
        assertThat(notification.getAppointmentScheduledAt()).isEqualTo(appointment.getScheduledAt());
    }

    // ------------------------------------------------------------------
    // Failure and retry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A transient failure returns the reminder to PENDING for another attempt")
    void shouldMarkReminderForRetryWhenNotificationThrows() {
        Reminder reminder = givenClaimableReminder();
        doThrow(new RuntimeException("provider timed out")).when(notificationSender).send(any());

        processor.process(claimSingleReminder());

        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PENDING);
        assertThat(attemptCountOf(reminder.getId())).isEqualTo(1);
        assertThat(sentAtOf(reminder.getId())).isNull();
    }

    @Test
    @DisplayName("A failure on the final permitted attempt gives up and marks the reminder FAILED")
    void shouldMarkReminderFailedWhenLastAttemptThrows() {
        Reminder reminder = givenClaimableReminder();
        // max-attempts is 5, so an attempt count of 4 makes this the last permitted attempt.
        forceReminderState(reminder.getId(), ReminderStatus.PENDING, 4, null);
        doThrow(new RuntimeException("invalid recipient")).when(notificationSender).send(any());

        processor.process(claimSingleReminder());

        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.FAILED);
        assertThat(attemptCountOf(reminder.getId())).isEqualTo(5);
        assertThat(sentAtOf(reminder.getId())).isNull();
    }

    @Test
    @DisplayName("A reminder whose attempts are already exhausted is failed without being sent")
    void shouldFailWithoutSendingWhenAttemptsAreAlreadyExhausted() {
        Reminder reminder = givenClaimableReminder();
        // Reached by repeated crash-and-reclaim: each reclaim increments the attempt count.
        forceReminderState(reminder.getId(), ReminderStatus.PENDING, 5, null);

        processor.process(claimSingleReminder());

        verify(notificationSender, never()).send(any());
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.FAILED);
    }

    @Test
    @DisplayName("A failing reminder is retried until the limit and then stops being retried")
    void shouldStopRetryingOnceTheAttemptLimitIsReached() {
        Reminder reminder = givenClaimableReminder();
        doThrow(new RuntimeException("still failing")).when(notificationSender).send(any());

        // Five attempts, each separated by more than the retry delay so the reminder is claimable.
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Reminder> claimed = claimService.claimDueReminders();
            if (claimed.isEmpty()) {
                break;
            }
            processor.process(claimed.get(0));
            clock.advanceBy(Duration.ofMinutes(6));
        }

        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.FAILED);
        assertThat(attemptCountOf(reminder.getId())).isEqualTo(5);
        assertThat(claimService.claimDueReminders())
                .as("a FAILED reminder is never claimed again")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Appointment state changes discovered after the claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A reminder is suppressed if its appointment was cancelled after the claim")
    void shouldNotSendReminderWhenAppointmentWasCancelledAfterClaiming() {
        Reminder reminder = givenClaimableReminder();
        Reminder claimed = claimSingleReminder();

        // The customer cancels while the worker holds the claim.
        appointmentService.cancel(appointment.getId());

        processor.process(claimed);

        verify(notificationSender, never()).send(any());
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.CANCELLED);
        assertThat(sentAtOf(reminder.getId())).isNull();
    }

    /**
     * The slow-worker race the architecture documents: this worker took longer than the processing
     * timeout, another worker reclaimed the reminder, and the outcome write therefore matches no
     * rows. What is guaranteed is that the late worker does not overwrite the new owner's state.
     *
     * <p>This test deliberately does not assert that the notification was skipped. It was not: the
     * send happens before the guarded write. That is the at-least-once boundary, and pretending
     * otherwise would make this test a lie.
     */
    @Test
    @DisplayName("A worker that lost its claim does not overwrite the reminder's state")
    void shouldNotRecordOutcomeWhenClaimWasLostToAnotherWorker() {
        Reminder reminder = givenClaimableReminder();
        Reminder claimed = claimSingleReminder();

        // Another worker reclaims it after the processing timeout expires.
        forceReminderState(reminder.getId(), ReminderStatus.PENDING, 1, null);

        processor.process(claimed);

        assertThat(statusOf(reminder.getId()))
                .as("the reminder still belongs to whoever reclaimed it")
                .isEqualTo(ReminderStatus.PENDING);
        assertThat(sentAtOf(reminder.getId())).isNull();
    }

    @Test
    @DisplayName("An exception from the sender never escapes the processor")
    void shouldContainSenderFailureRatherThanPropagatingIt() {
        givenClaimableReminder();
        doThrow(new IllegalStateException("provider exploded")).when(notificationSender).send(any());

        org.assertj.core.api.Assertions
                .assertThatCode(() -> processor.process(claimSingleReminder()))
                .doesNotThrowAnyException();
    }
}
