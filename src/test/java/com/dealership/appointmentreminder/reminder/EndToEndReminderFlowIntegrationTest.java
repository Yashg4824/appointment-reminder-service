package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dealership.appointmentreminder.appointment.Appointment;
import com.dealership.appointmentreminder.notification.LoggingNotificationSender;
import com.dealership.appointmentreminder.notification.Notification;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The whole journey, through the real wiring:
 * book over HTTP, reminders persisted, worker claims, processor sends, reminder becomes SENT.
 *
 * <p>The notification sender is a {@code @SpyBean} wrapping the <b>real</b>
 * {@link LoggingNotificationSender}, so these tests exercise the same object graph the running
 * application uses, while still being able to observe and - where needed - fail it.
 *
 * <p>Nothing here waits. Time is moved by the injected clock, so a reminder due in 30 days is
 * tested in milliseconds.
 */
@AutoConfigureMockMvc
class EndToEndReminderFlowIntegrationTest extends AbstractIntegrationTest {

    @SpyBean
    private LoggingNotificationSender notificationSender;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReminderWorker worker;

    private long bookAppointmentAt(Instant scheduledAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealershipId\":7,\"customerName\":\"Katherine Johnson\","
                                + "\"customerContact\":\"katherine@example.com\","
                                + "\"scheduledAt\":\"" + scheduledAt + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Reminder reminderOfType(long appointmentId, ReminderType type) {
        return reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId).stream()
                .filter(reminder -> reminder.getReminderType() == type)
                .findFirst().orElseThrow(AssertionError::new);
    }

    @Test
    @DisplayName("Book, wait for the due time, and the reminder is delivered and marked SENT")
    void shouldDeliverReminderEndToEndFromBookingToSent() throws Exception {
        Instant appointmentAt = now().plus(Duration.ofDays(30));
        long appointmentId = bookAppointmentAt(appointmentAt);
        Reminder twentyFourHour = reminderOfType(appointmentId, ReminderType.TWENTY_FOUR_HOURS);
        assertThat(twentyFourHour.getStatus()).isEqualTo(ReminderStatus.PENDING);

        // Not due yet: a worker cycle must do nothing at all.
        clock.setInstant(twentyFourHour.getScheduledAt().minus(Duration.ofMinutes(1)));
        worker.runCycle();
        verify(notificationSender, never()).send(any());
        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.PENDING);

        // Due: the same cycle now claims, sends and records the outcome.
        clock.setInstant(twentyFourHour.getScheduledAt().plusSeconds(1));
        worker.runCycle();

        verify(notificationSender).send(argThat(notification ->
                notification.getReminderId().equals(twentyFourHour.getId())
                        && notification.getReminderType() == ReminderType.TWENTY_FOUR_HOURS
                        && notification.getRecipient().equals("katherine@example.com")
                        && notification.getAppointmentScheduledAt().equals(appointmentAt)));
        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.SENT);
        assertThat(sentAtOf(twentyFourHour.getId())).isEqualTo(now());
    }

    @Test
    @DisplayName("Both reminders fire, each at its own due time and each exactly once")
    void shouldDeliverBothRemindersAtTheirOwnDueTimes() throws Exception {
        Instant appointmentAt = now().plus(Duration.ofDays(30));
        long appointmentId = bookAppointmentAt(appointmentAt);
        Reminder twentyFourHour = reminderOfType(appointmentId, ReminderType.TWENTY_FOUR_HOURS);
        Reminder twoHour = reminderOfType(appointmentId, ReminderType.TWO_HOURS);

        clock.setInstant(twentyFourHour.getScheduledAt().plusSeconds(1));
        worker.runCycle();
        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.SENT);
        assertThat(statusOf(twoHour.getId()))
                .as("the 2-hour reminder is still 22 hours away")
                .isEqualTo(ReminderStatus.PENDING);

        clock.setInstant(twoHour.getScheduledAt().plusSeconds(1));
        worker.runCycle();
        assertThat(statusOf(twoHour.getId())).isEqualTo(ReminderStatus.SENT);

        verify(notificationSender, org.mockito.Mockito.times(2)).send(any(Notification.class));
    }

    @Test
    @DisplayName("A cancelled appointment's reminders are never delivered, however many cycles run")
    void shouldNeverDeliverRemindersForCancelledAppointment() throws Exception {
        Instant appointmentAt = now().plus(Duration.ofDays(30));
        long appointmentId = bookAppointmentAt(appointmentAt);
        Reminder twentyFourHour = reminderOfType(appointmentId, ReminderType.TWENTY_FOUR_HOURS);

        mockMvc.perform(post("/appointments/{id}/cancel", appointmentId)).andExpect(status().isOk());

        clock.setInstant(twentyFourHour.getScheduledAt().plus(Duration.ofHours(1)));
        worker.runCycle();
        worker.runCycle();

        verify(notificationSender, never()).send(any());
        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.CANCELLED);
    }

    // ------------------------------------------------------------------
    // Restart and failure scenarios
    // ------------------------------------------------------------------

    /**
     * A restart is simulated rather than performed: the point being verified is that no reminder
     * state lives in memory. Everything the worker needs is in the database, so a cycle run by a
     * freshly started instance behaves identically to one run by a long-lived instance.
     */
    @Test
    @DisplayName("Reminders pending before a restart are still delivered afterwards")
    void shouldStillDeliverRemindersThatWerePendingBeforeARestart() throws Exception {
        Instant appointmentAt = now().plus(Duration.ofDays(30));
        long appointmentId = bookAppointmentAt(appointmentAt);
        Reminder twentyFourHour = reminderOfType(appointmentId, ReminderType.TWENTY_FOUR_HOURS);

        // Nothing was claimed before the "restart"; the row is simply sitting in the table.
        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.PENDING);

        clock.setInstant(twentyFourHour.getScheduledAt().plusSeconds(1));
        worker.runCycle();

        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.SENT);
        verify(notificationSender).send(any());
    }

    @Test
    @DisplayName("Work left PROCESSING by a killed worker is reclaimed and delivered after a restart")
    void shouldReclaimAndDeliverStaleProcessingRemindersAfterARestart() throws Exception {
        Instant appointmentAt = now().plus(Duration.ofDays(30));
        long appointmentId = bookAppointmentAt(appointmentAt);
        Reminder twentyFourHour = reminderOfType(appointmentId, ReminderType.TWENTY_FOUR_HOURS);

        clock.setInstant(twentyFourHour.getScheduledAt().plusSeconds(1));
        // Exactly what a worker killed mid-send leaves behind: claimed, with an expiry now past.
        forceReminderState(twentyFourHour.getId(), ReminderStatus.PROCESSING, 0,
                now().minus(Duration.ofSeconds(30)));

        worker.runCycle();

        assertThat(statusOf(twentyFourHour.getId())).isEqualTo(ReminderStatus.SENT);
        assertThat(attemptCountOf(twentyFourHour.getId()))
                .as("the abandoned attempt is counted").isEqualTo(1);
        verify(notificationSender).send(any());
    }

    @Test
    @DisplayName("One reminder failing does not stop the others in the same batch being delivered")
    void shouldKeepDeliveringOtherRemindersWhenOneFails() {
        Appointment failing = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        Appointment healthy = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        Reminder failingReminder = givenPendingReminder(failing, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(2)));
        Reminder healthyReminder = givenPendingReminder(healthy, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(1)));

        doThrow(new IllegalStateException("provider rejected this recipient"))
                .when(notificationSender)
                .send(argThat(notification -> notification.getReminderId().equals(failingReminder.getId())));

        worker.runCycle();

        assertThat(statusOf(healthyReminder.getId()))
                .as("the healthy reminder in the same batch is unaffected")
                .isEqualTo(ReminderStatus.SENT);
        assertThat(statusOf(failingReminder.getId()))
                .as("the failing reminder is returned for a later retry")
                .isEqualTo(ReminderStatus.PENDING);
        assertThat(attemptCountOf(failingReminder.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A reminder whose appointment is too soon is never created, so nothing is ever sent")
    void shouldSendNothingForAppointmentBookedTooCloseToItsSlot() throws Exception {
        long appointmentId = bookAppointmentAt(now().plus(Duration.ofMinutes(30)));

        List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId);
        assertThat(reminders).isEmpty();

        clock.advanceBy(Duration.ofMinutes(29));
        worker.runCycle();

        verify(notificationSender, never()).send(any());
    }
}
