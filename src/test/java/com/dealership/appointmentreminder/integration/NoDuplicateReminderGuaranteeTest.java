package com.dealership.appointmentreminder.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;

import com.dealership.appointmentreminder.dto.CreateAppointmentRequest;
import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.scheduler.ReminderWorker;
import com.dealership.appointmentreminder.service.AppointmentService;
import com.dealership.appointmentreminder.service.ReminderClaimService;
import com.dealership.appointmentreminder.service.ReminderProcessor;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;
import com.dealership.appointmentreminder.support.RecordingNotificationSender;

/**
 * The assignment's central requirement, stated as tests:
 * <b>a customer must never receive the same reminder twice.</b>
 *
 * <p>These tests prove three distinct things, at three different layers:
 * <ol>
 *   <li>a duplicate reminder row cannot be created, because the database refuses it;</li>
 *   <li>concurrent workers cannot both deliver the same reminder;</li>
 *   <li>the reminder id carried to the sender is stable across retries, so a provider that
 *       honours idempotency keys can suppress a repeat.</li>
 * </ol>
 *
 * <p><b>What these tests do NOT prove, stated plainly:</b> they do not prove exactly-once delivery
 * to an external notification provider, and no test could. Calling a remote service and committing
 * a database transaction cannot be made atomic. If a process dies after the provider accepted the
 * message but before the status is written, the reminder is reclaimed and sent again - delivery is
 * <b>at-least-once</b>. The stable reminder id exists precisely so a real provider can collapse
 * that repeat. What is proven here is the guarantee the system genuinely offers: exactly-once
 * <i>scheduling</i> and exactly-once <i>dispatch decision</i>.
 */
class NoDuplicateReminderGuaranteeTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class RecordingSenderConfig {
        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    @Autowired
    private RecordingNotificationSender sender;

    @Autowired
    private ReminderWorker worker;

    @Autowired
    private ReminderClaimService claimService;

    @Autowired
    private ReminderProcessor processor;

    @Autowired
    private AppointmentService appointmentService;

    @BeforeEach
    void resetSender() {
        sender.reset();
    }

    private CreateAppointmentRequest request(java.time.Instant scheduledAt) {
        CreateAppointmentRequest request = new CreateAppointmentRequest();
        request.setDealershipId(1L);
        request.setCustomerName("Ada Lovelace");
        request.setCustomerContact("ada@example.com");
        request.setScheduledAt(scheduledAt.atOffset(java.time.ZoneOffset.UTC));
        return request;
    }

    // ------------------------------------------------------------------
    // Layer 1: a duplicate reminder row cannot exist
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Creating an appointment produces exactly one reminder of each type")
    void shouldCreateExactlyOneReminderPerTypeWhenAppointmentIsCreated() {
        appointmentService.create(request(now().plus(Duration.ofDays(30))));

        assertThat(jdbcTemplate.queryForList(
                "SELECT appointment_id, reminder_type, count(*) FROM reminders "
                        + "GROUP BY appointment_id, reminder_type HAVING count(*) > 1"))
                .as("the duplicate-reminder invariant query must always return no rows")
                .isEmpty();
        assertThat(reminderRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Creating the same appointment twice produces two appointments, never duplicate reminders for one")
    void shouldNotProduceDuplicateRemindersForASingleAppointment() {
        appointmentService.create(request(now().plus(Duration.ofDays(30))));
        appointmentService.create(request(now().plus(Duration.ofDays(30))));

        // Two separate bookings are two separate appointments. That is correct - the guarantee is
        // per appointment, not per customer. Neither appointment has a duplicate reminder.
        assertThat(appointmentRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT appointment_id, reminder_type, count(*) FROM reminders "
                        + "GROUP BY appointment_id, reminder_type HAVING count(*) > 1")).isEmpty();
    }

    @Test
    @DisplayName("The guarantee comes from the schema: even raw SQL cannot insert a duplicate")
    void shouldRejectDuplicateReminderTypeEvenWhenApplicationCodeIsBypassed() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS, now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status, "
                        + "attempt_count, created_at, updated_at) VALUES (?, 'TWENTY_FOUR_HOURS', ?, "
                        + "'PENDING', 0, ?, ?)",
                appointment.getId(), java.sql.Timestamp.from(now()),
                java.sql.Timestamp.from(now()), java.sql.Timestamp.from(now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // Layer 2: concurrent workers cannot both deliver the same reminder
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One due reminder and eight workers racing produces exactly one delivery")
    void shouldDeliverReminderExactlyOnceWhenManyWorkersRunConcurrently() throws Exception {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(1)));

        runWorkersConcurrently(8);

        assertThat(sender.deliveredReminderIds())
                .as("the customer must receive this reminder exactly once")
                .containsExactly(reminder.getId());
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    @DisplayName("Twenty due reminders and eight workers: every reminder delivered exactly once")
    void shouldDeliverEveryReminderExactlyOnceAcrossConcurrentWorkers() throws Exception {
        List<Long> expectedIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
            expectedIds.add(givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                    now().minus(Duration.ofMinutes(i + 1))).getId());
        }

        runWorkersConcurrently(8);

        assertThat(sender.deliveredReminderIds())
                .as("no reminder delivered twice")
                .doesNotHaveDuplicates()
                .as("every reminder delivered once")
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reminders WHERE status = 'SENT'", Integer.class)).isEqualTo(20);
    }

    private void runWorkersConcurrently(int workerCount) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Callable<Void>> workers = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            workers.add(() -> {
                startTogether.await(15, TimeUnit.SECONDS);
                // Loop so that a worker which loses every race still drains any remaining work.
                for (int cycle = 0; cycle < 5; cycle++) {
                    worker.runCycle();
                }
                return null;
            });
        }
        try {
            for (Future<Void> result : executor.invokeAll(workers, 60, TimeUnit.SECONDS)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Layer 3: the idempotency key handed to the provider is stable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Retries reuse the same reminder id, so a provider can suppress the repeat")
    void shouldKeepReminderIdStableAcrossRetries() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(1)));

        // First attempt fails; the reminder returns to PENDING for another try.
        sender.failWhen(notification -> true);
        processor.process(claimService.claimDueReminders().get(0));
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PENDING);

        // Second attempt succeeds, after the retry delay has elapsed.
        sender.failWhen(notification -> false);
        clock.advanceBy(Duration.ofMinutes(6));
        processor.process(claimService.claimDueReminders().get(0));

        assertThat(sender.attempts()).hasSize(2);
        assertThat(sender.attempts()).extracting(Notification::getReminderId)
                .as("the idempotency key must not change between attempts")
                .containsExactly(reminder.getId(), reminder.getId());
        assertThat(sender.delivered()).hasSize(1);
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    @DisplayName("A reminder that reached SENT is never claimed again, so it cannot be re-delivered")
    void shouldNeverReDeliverAReminderThatIsAlreadySent() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(1)));

        worker.runCycle();
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.SENT);

        // Many further cycles, with time moving on: the reminder is terminal and invisible to the
        // claim query, so no further delivery is possible.
        for (int cycle = 0; cycle < 5; cycle++) {
            clock.advanceBy(Duration.ofHours(1));
            worker.runCycle();
        }

        assertThat(sender.delivered()).hasSize(1);
        assertThat(sender.attempts()).hasSize(1);
    }
}
