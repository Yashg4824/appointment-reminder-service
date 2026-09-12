package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.dealership.appointmentreminder.config.ReminderProperties;

/**
 * Unit tests for the reminder timing rules.
 *
 * <p>No Spring context and no database: "now" is passed in as a parameter, so every case here is
 * a pure function call. This is the class where all the awkward edge cases live, and it is the
 * fastest place to prove them.
 *
 * <p>The rule under test: a reminder is created when
 * {@code appointmentTime - offset} is not more than the grace period (15 minutes) in the past.
 */
class ReminderScheduleCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(15);
    private static final Long APPOINTMENT_ID = 42L;

    private ReminderScheduleCalculator calculator;

    @BeforeEach
    void setUp() {
        ReminderProperties properties = new ReminderProperties();
        properties.setGracePeriod(GRACE);
        calculator = new ReminderScheduleCalculator(properties);
    }

    private List<ReminderType> typesFor(Duration untilAppointment) {
        return calculator.reminderPlanFor(APPOINTMENT_ID, NOW.plus(untilAppointment), NOW)
                .stream()
                .map(Reminder::getReminderType)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("An appointment far in the future gets both reminders")
    void shouldCreateBothRemindersForAppointmentFarInTheFuture() {
        assertThat(typesFor(Duration.ofDays(30)))
                .containsExactlyInAnyOrder(ReminderType.TWENTY_FOUR_HOURS, ReminderType.TWO_HOURS);
    }

    @Test
    @DisplayName("An appointment 25 hours away gets both reminders")
    void shouldCreateBothRemindersWhenAppointmentIsMoreThanTwentyFourHoursAway() {
        assertThat(typesFor(Duration.ofHours(25)))
                .containsExactlyInAnyOrder(ReminderType.TWENTY_FOUR_HOURS, ReminderType.TWO_HOURS);
    }

    @Test
    @DisplayName("An appointment 12 hours away gets only the 2-hour reminder")
    void shouldCreateOnlyTwoHourReminderForNearAppointment() {
        assertThat(typesFor(Duration.ofHours(12))).containsExactly(ReminderType.TWO_HOURS);
    }

    @Test
    @DisplayName("An appointment 30 minutes away gets no reminders at all")
    void shouldCreateNoReminderForAppointmentWithinTwoHours() {
        assertThat(typesFor(Duration.ofMinutes(30))).isEmpty();
    }

    @Test
    void shouldBuildReminderWithCorrectOwnerTypeDueTimeAndInitialState() {
        Instant appointmentAt = NOW.plus(Duration.ofDays(3));

        List<Reminder> plan = calculator.reminderPlanFor(APPOINTMENT_ID, appointmentAt, NOW);

        assertThat(plan).hasSize(2);
        Reminder twentyFourHour = plan.stream()
                .filter(r -> r.getReminderType() == ReminderType.TWENTY_FOUR_HOURS)
                .findFirst().orElseThrow(AssertionError::new);

        assertThat(twentyFourHour.getAppointmentId()).isEqualTo(APPOINTMENT_ID);
        assertThat(twentyFourHour.getScheduledAt()).isEqualTo(appointmentAt.minus(Duration.ofHours(24)));
        assertThat(twentyFourHour.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(twentyFourHour.getAttemptCount()).isZero();
        assertThat(twentyFourHour.getSentAt()).isNull();
        assertThat(twentyFourHour.getProcessingUntil()).isNull();
    }

    /**
     * The grace period is the only reason these cases differ, and it is the rule most likely to be
     * misremembered: a reminder whose due time has just passed is still worth sending, while one
     * whose due time passed long ago would be a wrong message rather than a late one.
     */
    @Nested
    @DisplayName("Grace period boundary (15 minutes)")
    class GracePeriodBoundary {

        @Test
        @DisplayName("Due exactly now: created")
        void shouldCreateReminderDueExactlyNow() {
            // Appointment exactly 24h away -> the 24-hour reminder is due at this very instant.
            assertThat(typesFor(Duration.ofHours(24)))
                    .contains(ReminderType.TWENTY_FOUR_HOURS);
        }

        @Test
        @DisplayName("Due exactly one grace period ago: still created (boundary is inclusive)")
        void shouldCreateReminderDueExactlyAtTheGraceBoundary() {
            // 24h - 15m: the 24-hour reminder's due time is exactly 15 minutes in the past.
            assertThat(typesFor(Duration.ofHours(24).minus(GRACE)))
                    .contains(ReminderType.TWENTY_FOUR_HOURS);
        }

        @Test
        @DisplayName("Due one second beyond the grace period: not created")
        void shouldNotCreateReminderDueJustBeyondTheGraceBoundary() {
            assertThat(typesFor(Duration.ofHours(24).minus(GRACE).minusSeconds(1)))
                    .containsExactly(ReminderType.TWO_HOURS);
        }

        @Test
        @DisplayName("An appointment 1h50m away still gets the 2-hour reminder, because of grace")
        void shouldStillCreateTwoHourReminderJustInsideGrace() {
            // Worth stating explicitly: "less than 2 hours away" does NOT mean "no reminders".
            assertThat(typesFor(Duration.ofMinutes(110))).containsExactly(ReminderType.TWO_HOURS);
        }

        @Test
        @DisplayName("An appointment 1h44m away gets nothing, because grace has elapsed")
        void shouldCreateNothingOnceTwoHourReminderIsBeyondGrace() {
            assertThat(typesFor(Duration.ofMinutes(104))).isEmpty();
        }
    }

    @Nested
    @DisplayName("ReminderType offsets")
    class Offsets {

        @Test
        void shouldSubtractTwentyFourHoursForTwentyFourHourReminder() {
            Instant appointmentAt = Instant.parse("2026-06-20T09:00:00Z");
            assertThat(ReminderType.TWENTY_FOUR_HOURS.dueTimeFor(appointmentAt))
                    .isEqualTo(Instant.parse("2026-06-19T09:00:00Z"));
        }

        @Test
        void shouldSubtractTwoHoursForTwoHourReminder() {
            Instant appointmentAt = Instant.parse("2026-06-20T09:00:00Z");
            assertThat(ReminderType.TWO_HOURS.dueTimeFor(appointmentAt))
                    .isEqualTo(Instant.parse("2026-06-20T07:00:00Z"));
        }

        /**
         * "24 hours before" is an absolute duration, not "the same wall-clock time yesterday".
         * Across a daylight-saving transition those differ by an hour; using instants means the
         * arithmetic needs no special case, which this test pins down.
         */
        @Test
        void shouldUseAbsoluteDurationAcrossDaylightSavingTransition() {
            // 2026-03-08 is the US spring-forward date; 09:00 EDT that morning is 13:00 UTC.
            Instant appointmentAt = Instant.parse("2026-03-08T13:00:00Z");

            assertThat(ReminderType.TWENTY_FOUR_HOURS.dueTimeFor(appointmentAt))
                    .isEqualTo(Instant.parse("2026-03-07T13:00:00Z"));
            assertThat(Duration.between(
                    ReminderType.TWENTY_FOUR_HOURS.dueTimeFor(appointmentAt), appointmentAt))
                    .isEqualTo(Duration.ofHours(24));
        }
    }
}
