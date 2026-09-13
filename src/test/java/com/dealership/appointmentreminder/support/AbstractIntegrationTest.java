package com.dealership.appointmentreminder.support;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.repository.AppointmentRepository;
import com.dealership.appointmentreminder.repository.ReminderRepository;

/**
 * Base class for tests that need the real PostgreSQL database.
 *
 * <p><b>Why a real database and not H2:</b> the core of this design is
 * {@code FOR UPDATE SKIP LOCKED}, which H2 does not implement. Testing the claim query against an
 * in-memory database would be testing behaviour that does not exist in production.
 *
 * <p><b>Why cleanup by deletion rather than {@code @Transactional} rollback:</b> the concurrency
 * tests use several threads, and each thread has its own connection and its own transaction. Data
 * written inside an uncommitted test transaction would be invisible to those threads, and rolling
 * back the test transaction would not undo what they committed. Deleting in {@code @BeforeEach}
 * keeps every test independent without that trap, and keeps all tests consistent with each other.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected AppointmentRepository appointmentRepository;

    @Autowired
    protected ReminderRepository reminderRepository;

    @Autowired
    protected MutableTestClock clock;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabaseAndClock() {
        // Children first: reminders reference appointments.
        jdbcTemplate.update("DELETE FROM reminders");
        jdbcTemplate.update("DELETE FROM appointments");
        clock.reset();
    }

    protected Instant now() {
        return clock.instant();
    }

    /** Persists a SCHEDULED appointment at the given instant, with no reminders. */
    protected Appointment givenAppointmentAt(Instant scheduledAt) {
        return appointmentRepository.saveAndFlush(new Appointment(
                1L, "Test Customer", "customer@example.com", scheduledAt,
                AppointmentStatus.SCHEDULED, now()));
    }

    /** Persists a PENDING reminder due at the given instant. */
    protected Reminder givenPendingReminder(Appointment appointment, ReminderType type, Instant dueAt) {
        return reminderRepository.saveAndFlush(
                new Reminder(appointment.getId(), type, dueAt, now()));
    }

    /**
     * Forces a reminder into a state the entity's public API cannot express - an exhausted attempt
     * count, or a claim held by a worker that has since died.
     *
     * <p>Done in SQL on purpose: adding setters to the production entity merely so tests can build
     * these fixtures would be changing production code to suit the tests.
     */
    protected void forceReminderState(Long reminderId,
                                      ReminderStatus status,
                                      int attemptCount,
                                      Instant processingUntil) {
        jdbcTemplate.update(
                "UPDATE reminders SET status = ?, attempt_count = ?, processing_until = ? WHERE id = ?",
                status.name(), attemptCount, processingUntil == null ? null : java.sql.Timestamp.from(processingUntil),
                reminderId);
    }

    protected ReminderStatus statusOf(Long reminderId) {
        return ReminderStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM reminders WHERE id = ?", String.class, reminderId));
    }

    protected int attemptCountOf(Long reminderId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM reminders WHERE id = ?", Integer.class, reminderId);
    }

    protected Instant sentAtOf(Long reminderId) {
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM reminders WHERE id = ?", java.sql.Timestamp.class, reminderId);
        return ts == null ? null : ts.toInstant();
    }
}
