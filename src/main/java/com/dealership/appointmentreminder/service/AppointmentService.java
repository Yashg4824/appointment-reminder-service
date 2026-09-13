package com.dealership.appointmentreminder.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.dto.CreateAppointmentRequest;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.exception.AppointmentNotFoundException;
import com.dealership.appointmentreminder.exception.InvalidAppointmentRequestException;
import com.dealership.appointmentreminder.exception.InvalidAppointmentStateException;
import com.dealership.appointmentreminder.repository.AppointmentRepository;
import com.dealership.appointmentreminder.repository.ReminderRepository;

/**
 * Appointment business logic: create, cancel, reschedule.
 *
 * <p><b>Why this class exists:</b> it is the one place that knows an appointment and its
 * reminders must be written together. Creating both inside a single {@code @Transactional}
 * method is the foundation the whole design rests on - a committed appointment always has its
 * complete reminder plan, so there is no window in which one exists without the other, and no
 * second write to another system that could fail independently (architecture document,
 * section 4).
 *
 * <p><b>Responsibility:</b> the appointment transaction boundary. It does not send notifications,
 * does not poll, and does not compute reminder times itself - that last one belongs to
 * {@link ReminderScheduleCalculator}.
 *
 * <p><b>SOLID:</b>
 * <ul>
 *   <li>Single Responsibility - business rules and their transaction, with HTTP concerns left to
 *       the controller and persistence left to the repositories.</li>
 *   <li>Dependency Inversion - it depends on repository interfaces, not on an
 *       {@code EntityManager} or {@code JdbcTemplate}.</li>
 * </ul>
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link AppointmentRepository} - persistence, including the row-locking read used by
 *       cancel and reschedule.</li>
 *   <li>{@link ReminderRepository} - to persist the reminder plan in the same transaction.</li>
 *   <li>{@link ReminderScheduleCalculator} - which reminders are due and when. Injected rather
 *       than inlined so those rules stay independently testable.</li>
 *   <li>{@link Clock} - every timestamp this service writes comes from here, so tests can pin
 *       "now" to a fixed instant.</li>
 * </ul>
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    /** Anything with a single {@code @} and a dot-bearing domain. Intentionally permissive. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Digits, optionally with a leading +, after stripping spaces, dashes and parentheses. */
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");

    private final AppointmentRepository appointmentRepository;
    private final ReminderRepository reminderRepository;
    private final ReminderScheduleCalculator scheduleCalculator;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ReminderRepository reminderRepository,
                              ReminderScheduleCalculator scheduleCalculator,
                              Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.reminderRepository = reminderRepository;
        this.scheduleCalculator = scheduleCalculator;
        this.clock = clock;
    }

    /**
     * Creates an appointment together with its reminders, atomically.
     *
     * <p><b>Transaction boundary:</b> this whole method is one transaction. The appointment
     * insert and both reminder inserts either all commit or all roll back, so a committed
     * appointment always has its complete reminder plan. This is what removes the dual-write
     * problem: there is no second system to write to and therefore no window in which an
     * appointment exists with no reminders scheduled.
     *
     * <p>The appointment is saved first because its generated id is the foreign key the reminder
     * rows need. {@code save} on an {@code IDENTITY} entity issues the INSERT immediately, so the
     * id is available within the same transaction - no extra flush is required.
     */
    @Transactional
    public AppointmentResponse create(CreateAppointmentRequest request) {
        Instant now = clock.instant();
        Instant scheduledAt = request.getScheduledAt().toInstant();

        validateScheduledInFuture(scheduledAt, now);
        validateContact(request.getCustomerContact());

        Appointment appointment = appointmentRepository.save(new Appointment(
                request.getDealershipId(),
                request.getCustomerName().trim(),
                request.getCustomerContact().trim(),
                scheduledAt,
                AppointmentStatus.SCHEDULED,
                now));

        List<Reminder> reminders = scheduleCalculator.reminderPlanFor(appointment.getId(), scheduledAt, now);
        reminderRepository.saveAll(reminders);

        log.info("Created appointment {} for dealership {} at {} with {} reminder(s)",
                appointment.getId(), appointment.getDealershipId(), scheduledAt, reminders.size());

        return new AppointmentResponse(appointment, reminders);
    }

    /** Loads an appointment with its reminder plan. Read-only, so no write transaction is taken. */
    @Transactional(readOnly = true)
    public AppointmentResponse findById(Long appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));
        return new AppointmentResponse(
                appointment, reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId));
    }

    /**
     * Cancels an appointment and suppresses its unsent reminders.
     *
     * <p><b>Transaction boundary and locking:</b> the appointment row is read with
     * {@code SELECT ... FOR UPDATE}, so this transaction and a concurrent worker claim serialise
     * on that row instead of interleaving. Only {@code PENDING} reminders are cancelled - one
     * already {@code SENT} stays {@code SENT}, and one already {@code PROCESSING} belongs to the
     * worker that claimed it, which will discover the cancellation when it re-reads the
     * appointment before sending (architecture document, section 9c).
     *
     * <p>Idempotent: cancelling an already-cancelled appointment changes nothing and still
     * returns 200.
     */
    @Transactional
    public AppointmentResponse cancel(Long appointmentId) {
        Instant now = clock.instant();

        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentStateException(
                    "Appointment " + appointmentId + " is COMPLETED and cannot be cancelled");
        }

        if (appointment.getStatus() == AppointmentStatus.SCHEDULED) {
            appointment.setStatus(AppointmentStatus.CANCELLED);
            appointment.setUpdatedAt(now);

            int cancelled = reminderRepository.cancelPendingForAppointment(appointmentId, now);
            log.info("Cancelled appointment {}, suppressing {} pending reminder(s)", appointmentId, cancelled);
        }

        return new AppointmentResponse(
                appointment, reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId));
    }

    /**
     * Moves an appointment to a new time and moves its unsent reminders with it.
     *
     * <p>Rules, per architecture document section 8:
     * <ul>
     *   <li>A reminder already {@code SENT} is left alone. No second reminder of the same type is
     *       ever sent, which is the literal reading of the no-duplicates requirement and needs no
     *       versioning machinery.</li>
     *   <li>A reminder currently {@code PROCESSING} is left alone: a worker owns it.</li>
     *   <li>{@code PENDING} and {@code CANCELLED} reminders are recomputed against the new time -
     *       revived if the new due time is in the future, cancelled if it is now in the past.</li>
     *   <li>A type with no row yet is inserted if its new due time is in the future.</li>
     * </ul>
     */
    @Transactional
    public AppointmentResponse reschedule(Long appointmentId, Instant newScheduledAt) {
        Instant now = clock.instant();
        validateScheduledInFuture(newScheduledAt, now);

        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new AppointmentNotFoundException(appointmentId));

        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new InvalidAppointmentStateException(
                    "Appointment " + appointmentId + " is " + appointment.getStatus()
                            + " and cannot be rescheduled");
        }

        appointment.setScheduledAt(newScheduledAt);
        appointment.setUpdatedAt(now);

        Map<ReminderType, Reminder> existing = new EnumMap<>(ReminderType.class);
        for (Reminder reminder : reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId)) {
            existing.put(reminder.getReminderType(), reminder);
        }

        for (ReminderType type : ReminderType.values()) {
            Instant dueAt = type.dueTimeFor(newScheduledAt);
            boolean worthSending = scheduleCalculator.isStillWorthSending(dueAt, now);
            Reminder reminder = existing.get(type);

            if (reminder == null) {
                if (worthSending) {
                    reminderRepository.save(new Reminder(appointmentId, type, dueAt, now));
                }
            } else if (isReschedulable(reminder.getStatus())) {
                reminder.setScheduledAt(dueAt);
                reminder.setStatus(worthSending ? ReminderStatus.PENDING : ReminderStatus.CANCELLED);
                reminder.setUpdatedAt(now);
            }
            // SENT, FAILED and PROCESSING reminders are deliberately untouched.
        }

        log.info("Rescheduled appointment {} to {}", appointmentId, newScheduledAt);

        return new AppointmentResponse(
                appointment, reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(appointmentId));
    }

    /**
     * Only reminders that have neither been sent nor claimed may be moved. CANCELLED is included
     * so that a reminder suppressed by an earlier reschedule is revived if the appointment moves
     * back into the future - without it, moving an appointment earlier and then later again would
     * silently lose that reminder, and the unique constraint would prevent re-creating it.
     */
    private boolean isReschedulable(ReminderStatus status) {
        return status == ReminderStatus.PENDING || status == ReminderStatus.CANCELLED;
    }

    private void validateScheduledInFuture(Instant scheduledAt, Instant now) {
        if (!scheduledAt.isAfter(now)) {
            throw new InvalidAppointmentRequestException(
                    "scheduledAt must be in the future (received " + scheduledAt + ", now is " + now + ")");
        }
    }

    /**
     * The contact must be usable by some notification channel. Deliberately permissive: rejecting
     * valid real-world addresses is a worse failure than accepting an odd-looking one, and the
     * stub sender cannot tell the difference anyway.
     */
    private void validateContact(String contact) {
        String trimmed = contact.trim();
        String normalisedPhone = trimmed.replaceAll("[\\s()\\-.]", "");
        if (!EMAIL.matcher(trimmed).matches() && !PHONE.matcher(normalisedPhone).matches()) {
            throw new InvalidAppointmentRequestException(
                    "customerContact must be an email address or a phone number");
        }
    }
}
