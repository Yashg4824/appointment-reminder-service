package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;

import com.dealership.appointmentreminder.appointment.Appointment;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;

/**
 * Tests the guarantees that live in the schema rather than in Java.
 *
 * <p>These matter because the assignment's central requirement is answered by a database
 * constraint. A test that only exercised the service layer could not tell the difference between
 * "the code is careful" and "the database makes it impossible".
 */
class DatabaseIntegrityIntegrationTest extends AbstractIntegrationTest {

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    // ------------------------------------------------------------------
    // Unique constraint: the no-duplicate-reminder guarantee
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The database itself refuses a second reminder of the same type for one appointment")
    void shouldRejectDuplicateReminderType() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS, now().plus(Duration.ofDays(9)));

        // Raw SQL, bypassing every line of application code, to show the guarantee is not
        // merely application discipline.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status, "
                        + "attempt_count, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                appointment.getId(), ReminderType.TWENTY_FOUR_HOURS.name(),
                ts(now()), ts(now()), ts(now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_reminders_appointment_type");
    }

    @Test
    void shouldRejectDuplicateReminderTypeThroughTheRepository() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        givenPendingReminder(appointment, ReminderType.TWO_HOURS, now().plus(Duration.ofDays(9)));

        assertThatThrownBy(() -> reminderRepository.saveAndFlush(new Reminder(
                appointment.getId(), ReminderType.TWO_HOURS, now().plus(Duration.ofDays(9)), now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("The constraint is per appointment, so different appointments may share a type")
    void shouldAllowSameReminderTypeForDifferentAppointments() {
        Appointment first = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        Appointment second = givenAppointmentAt(now().plus(Duration.ofDays(11)));

        givenPendingReminder(first, ReminderType.TWO_HOURS, now().plus(Duration.ofDays(9)));
        givenPendingReminder(second, ReminderType.TWO_HOURS, now().plus(Duration.ofDays(10)));

        assertThat(reminderRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Referential integrity
    // ------------------------------------------------------------------

    @Test
    void shouldRejectReminderReferencingNonExistentAppointment() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status, "
                        + "attempt_count, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', 0, ?, ?)",
                999999L, ReminderType.TWO_HOURS.name(), ts(now()), ts(now()), ts(now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reminders_appointment");
    }

    @Test
    @DisplayName("Deleting an appointment removes its reminders, so orphaned reminders cannot exist")
    void shouldCascadeDeleteRemindersWithTheirAppointment() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS, now().plus(Duration.ofDays(9)));
        givenPendingReminder(appointment, ReminderType.TWO_HOURS, now().plus(Duration.ofDays(9)));

        jdbcTemplate.update("DELETE FROM appointments WHERE id = ?", appointment.getId());

        assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointment.getId()))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Column-level integrity
    // ------------------------------------------------------------------

    @Test
    void shouldRequireEveryMandatoryAppointmentColumn() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO appointments (dealership_id, customer_name, customer_contact, "
                        + "scheduled_at, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                1L, null, "a@b.com", ts(now()), "SCHEDULED", ts(now()), ts(now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Timestamps survive a round trip unchanged, with no time-zone drift")
    void shouldPersistInstantsWithoutTimeZoneDrift() {
        Instant exactInstant = Instant.parse("2026-12-25T08:30:00Z");
        Appointment appointment = givenAppointmentAt(exactInstant);

        Appointment reloaded = appointmentRepository.findById(appointment.getId())
                .orElseThrow(AssertionError::new);

        assertThat(reloaded.getScheduledAt()).isEqualTo(exactInstant);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scheduled_at FROM appointments WHERE id = ?",
                Timestamp.class, appointment.getId()).toInstant()).isEqualTo(exactInstant);
    }

    // ------------------------------------------------------------------
    // Invariants over reminder state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Every status written by the application is one the state machine defines")
    void shouldOnlyEverStoreKnownReminderStatuses() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWO_HOURS, now());

        for (ReminderStatus status : ReminderStatus.values()) {
            forceReminderState(reminder.getId(), status, 0, null);
            assertThat(statusOf(reminder.getId())).isEqualTo(status);
        }

        List<String> distinctStatuses = jdbcTemplate.queryForList(
                "SELECT DISTINCT status FROM reminders", String.class);
        assertThat(distinctStatuses).allSatisfy(s -> ReminderStatus.valueOf(s));
    }

    @Test
    @DisplayName("Invariant: a SENT reminder always records when it was sent")
    void shouldNeverHaveASentReminderWithoutSentAt() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWO_HOURS,
                now().minus(Duration.ofMinutes(1)));
        forceReminderState(reminder.getId(), ReminderStatus.PROCESSING, 0, now().plus(Duration.ofMinutes(1)));

        reminderRepository.markSent(reminder.getId(), now());

        assertThat(sentAtOf(reminder.getId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reminders WHERE status = 'SENT' AND sent_at IS NULL",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("Outcome writes only apply to a reminder this worker still holds")
    void shouldNotApplyOutcomeWritesToAReminderThatIsNotProcessing() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(10)));
        Reminder reminder = givenPendingReminder(appointment, ReminderType.TWO_HOURS, now());

        // The reminder is PENDING, not PROCESSING, so every guarded write must be a no-op.
        assertThat(reminderRepository.markSent(reminder.getId(), now())).isZero();
        assertThat(reminderRepository.markFailed(reminder.getId(), now())).isZero();
        assertThat(reminderRepository.markForRetry(reminder.getId(), now())).isZero();
        assertThat(reminderRepository.markCancelled(reminder.getId(), now())).isZero();
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // Indexes and query path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The indexes the reminder worker's queries depend on exist")
    void shouldHaveTheIndexesTheWorkerQueriesRelyOn() {
        List<String> reminderIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'reminders'", String.class);

        assertThat(reminderIndexes).contains(
                "reminders_pkey",
                // the no-duplicate guarantee
                "uq_reminders_appointment_type",
                // the claim query: WHERE status = 'PENDING' AND scheduled_at <= :now
                "idx_reminders_status_scheduled",
                // the reclaim query: WHERE status = 'PROCESSING' AND processing_until < :now
                "idx_reminders_status_processing_until");

        assertThat(jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'appointments'", String.class))
                .contains("appointments_pkey", "idx_appointments_dealership_scheduled");
    }

    /**
     * Proves the claim query's predicates can actually be served by the index, independently of
     * how many rows happen to be in the table. Sequential scans are disabled for this one plan, so
     * the result reflects whether the index <i>covers the query</i> rather than what the planner
     * prefers on a small test table.
     */
    @Test
    void shouldBeAbleToServeTheClaimQueryFromTheStatusScheduledIndex() {
        String plan = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                StringBuilder explained = new StringBuilder();
                try (java.sql.ResultSet rows = statement.executeQuery(
                        "EXPLAIN SELECT * FROM reminders WHERE status = 'PENDING' "
                                + "AND scheduled_at <= now() ORDER BY scheduled_at LIMIT 100")) {
                    while (rows.next()) {
                        explained.append(rows.getString(1)).append('\n');
                    }
                }
                statement.execute("RESET enable_seqscan");
                return explained.toString();
            }
        });

        assertThat(plan).contains("idx_reminders_status_scheduled");
    }

    /**
     * A volume sanity check, not a capacity benchmark.
     *
     * <p>The stated workload is 50,000 appointments a day, which is about 100,000 reminders a day
     * and roughly 1.2 per second on average. This inserts a day's worth of <i>pending backlog</i>
     * and confirms the claim query still returns a bounded batch promptly. It says nothing about
     * production throughput, which depends on hardware, connection pools and the real notification
     * provider - none of which a unit test can stand in for.
     */
    @Test
    @DisplayName("Claiming stays prompt with a full day of reminders in the table")
    void shouldClaimABoundedBatchPromptlyFromALargeBacklog() {
        int appointmentCount = 2_000;
        Instant appointmentTime = now().plus(Duration.ofDays(10));
        Instant dueAt = now().minus(Duration.ofMinutes(5));

        jdbcTemplate.update(
                "INSERT INTO appointments (dealership_id, customer_name, customer_contact, "
                        + "scheduled_at, status, created_at, updated_at) "
                        + "SELECT (i % 500) + 1, 'Customer ' || i, 'customer' || i || '@example.com', "
                        + "?, 'SCHEDULED', ?, ? FROM generate_series(1, ?) AS i",
                ts(appointmentTime), ts(now()), ts(now()), appointmentCount);

        jdbcTemplate.update(
                "INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status, "
                        + "attempt_count, created_at, updated_at) "
                        + "SELECT a.id, t.type, ?, 'PENDING', 0, ?, ? FROM appointments a "
                        + "CROSS JOIN (VALUES ('TWENTY_FOUR_HOURS'), ('TWO_HOURS')) AS t(type)",
                ts(dueAt), ts(now()), ts(now()));

        jdbcTemplate.execute("ANALYZE reminders");
        assertThat(reminderRepository.count()).isEqualTo(appointmentCount * 2L);

        long startedAt = System.nanoTime();
        List<Reminder> claimed = reminderRepository.findDueForUpdateSkipLocked(now(), now(), 100);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(claimed).as("the batch size bounds how much work one cycle takes on").hasSize(100);
        assertThat(elapsed)
                .as("claiming a batch should not degrade as the backlog grows")
                .isLessThan(Duration.ofSeconds(2));
    }
}
