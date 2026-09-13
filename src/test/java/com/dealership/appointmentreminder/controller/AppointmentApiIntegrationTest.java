package com.dealership.appointmentreminder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;

/**
 * End-to-end tests of the HTTP API against the real database.
 *
 * <p>These cover the request contract, the persisted result, and the error responses. Time comes
 * from the injected test clock, so "30 days from now" is a fixed instant rather than whatever the
 * machine clock says.
 */
@AutoConfigureMockMvc
class AppointmentApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String createPayload(Instant scheduledAt) {
        return "{"
                + "\"dealershipId\":7,"
                + "\"customerName\":\"Ada Lovelace\","
                + "\"customerContact\":\"ada@example.com\","
                + "\"scheduledAt\":\"" + scheduledAt + "\"}";
    }

    private MockHttpServletRequestBuilder postJson(String body) {
        return post("/appointments").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long createAppointment(Instant scheduledAt) throws Exception {
        MvcResult result = mockMvc.perform(postJson(createPayload(scheduledAt)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    @Test
    void shouldCreateAppointmentWithTwoReminders() throws Exception {
        Instant scheduledAt = now().plus(Duration.ofDays(30));

        MvcResult result = mockMvc.perform(postJson(createPayload(scheduledAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.dealershipId").value(7))
                .andExpect(jsonPath("$.customerName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.customerContact").value("ada@example.com"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.reminders", org.hamcrest.Matchers.hasSize(2)))
                .andReturn();

        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        Appointment persisted = appointmentRepository.findById(id).orElseThrow(AssertionError::new);
        assertThat(persisted.getDealershipId()).isEqualTo(7L);
        assertThat(persisted.getCustomerName()).isEqualTo("Ada Lovelace");
        assertThat(persisted.getCustomerContact()).isEqualTo("ada@example.com");
        assertThat(persisted.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(persisted.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(persisted.getCreatedAt()).isEqualTo(now());

        List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id);
        assertThat(reminders)
                .extracting(Reminder::getReminderType, Reminder::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ReminderType.TWENTY_FOUR_HOURS, ReminderStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(ReminderType.TWO_HOURS, ReminderStatus.PENDING));
        assertThat(reminders)
                .extracting(Reminder::getScheduledAt)
                .containsExactlyInAnyOrder(
                        scheduledAt.minus(Duration.ofHours(24)),
                        scheduledAt.minus(Duration.ofHours(2)));
    }

    @Test
    void shouldCreateOnlyTwoHourReminderForNearAppointment() throws Exception {
        long id = createAppointment(now().plus(Duration.ofHours(3)));

        assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id))
                .extracting(Reminder::getReminderType)
                .containsExactly(ReminderType.TWO_HOURS);
    }

    @Test
    void shouldCreateNoRemindersForAppointmentWithinTwoHours() throws Exception {
        long id = createAppointment(now().plus(Duration.ofMinutes(30)));

        assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id)).isEmpty();
        // The appointment itself is still valid - too-soon-for-a-reminder is not an error.
        assertThat(appointmentRepository.findById(id)).isPresent();
    }

    @Test
    void shouldReturnAppointmentWithItsReminders() throws Exception {
        long id = createAppointment(now().plus(Duration.ofDays(10)));

        mockMvc.perform(get("/appointments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.reminders", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.reminders[0].status").value("PENDING"))
                .andExpect(jsonPath("$.reminders[0].attemptCount").value(0))
                .andExpect(jsonPath("$.reminders[0].sentAt").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Request validation")
    class Validation {

        @Test
        void shouldRejectRequestMissingEveryRequiredField() throws Exception {
            mockMvc.perform(postJson("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasSize(4)));
        }

        @Test
        void shouldRejectMissingDealershipId() throws Exception {
            mockMvc.perform(postJson("{\"customerName\":\"A\",\"customerContact\":\"a@b.com\","
                    + "\"scheduledAt\":\"" + now().plus(Duration.ofDays(1)) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("dealershipId")));
        }

        @Test
        void shouldRejectMissingCustomerName() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerContact\":\"a@b.com\","
                    + "\"scheduledAt\":\"" + now().plus(Duration.ofDays(1)) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("customerName")));
        }

        @Test
        void shouldRejectMissingCustomerContact() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerName\":\"A\","
                    + "\"scheduledAt\":\"" + now().plus(Duration.ofDays(1)) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("customerContact")));
        }

        @Test
        void shouldRejectMissingScheduledTime() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerName\":\"A\",\"customerContact\":\"a@b.com\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("scheduledAt")));
        }

        /**
         * The API's time-zone contract, enforced by the {@code OffsetDateTime} field type: a
         * timestamp with no offset cannot be parsed, rather than being silently interpreted in
         * the server's own time zone.
         */
        @Test
        void shouldRejectTimestampWithoutTimeZoneOffset() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerName\":\"A\","
                    + "\"customerContact\":\"a@b.com\",\"scheduledAt\":\"2026-09-20T14:30:00\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void shouldRejectMalformedJson() throws Exception {
            mockMvc.perform(postJson("{ this is not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        }

        @Test
        void shouldRejectContactThatIsNeitherEmailNorPhone() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerName\":\"A\","
                    + "\"customerContact\":\"not-a-contact\",\"scheduledAt\":\""
                    + now().plus(Duration.ofDays(1)) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("email address or a phone number")));
        }

        @Test
        void shouldAcceptPhoneNumberAsContact() throws Exception {
            mockMvc.perform(postJson("{\"dealershipId\":1,\"customerName\":\"A\","
                    + "\"customerContact\":\"+1 (555) 123-4567\",\"scheduledAt\":\""
                    + now().plus(Duration.ofDays(1)) + "\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectAppointmentScheduledInThePast() throws Exception {
            mockMvc.perform(postJson(createPayload(now().minus(Duration.ofHours(1)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("must be in the future")));
        }

        @Test
        @DisplayName("An appointment exactly at the current instant is rejected - the check is strictly future")
        void shouldRejectAppointmentScheduledExactlyNow() throws Exception {
            mockMvc.perform(postJson(createPayload(now())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    // ------------------------------------------------------------------
    // HTTP protocol errors - regression cover for ApiExceptionHandler
    // ------------------------------------------------------------------

    /**
     * A previous defect had a catch-all {@code @ExceptionHandler(Exception.class)} intercepting
     * Spring MVC's own exceptions and reporting all of them as 500. These tests pin the correct
     * statuses so that regression cannot return unnoticed.
     */
    @Nested
    @DisplayName("Spring MVC's own error statuses are preserved")
    class ProtocolErrors {

        @Test
        void shouldReturn404ForUnknownAppointment() throws Exception {
            mockMvc.perform(get("/appointments/{id}", 999999))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("APPOINTMENT_NOT_FOUND"));
        }

        @Test
        void shouldReturn405ForUnsupportedHttpMethod() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/appointments/{id}", 1))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
        }

        @Test
        void shouldReturn405ForGetOnTheCollectionWhichHasNoListEndpoint() throws Exception {
            mockMvc.perform(get("/appointments"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
        }

        @Test
        void shouldReturn415ForUnsupportedMediaType() throws Exception {
            mockMvc.perform(post("/appointments").contentType(MediaType.TEXT_PLAIN).content("hello"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        @Test
        void shouldExposeHealthEndpoint() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        void shouldCancelPendingRemindersWhenAppointmentIsCancelled() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(30)));

            mockMvc.perform(post("/appointments/{id}/cancel", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            assertThat(appointmentRepository.findById(id).orElseThrow(AssertionError::new).getStatus())
                    .isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id))
                    .extracting(Reminder::getStatus)
                    .containsOnly(ReminderStatus.CANCELLED);
        }

        @Test
        void shouldNotCancelAlreadySentReminder() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(30)));
            List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id);
            Long alreadySent = reminders.get(0).getId();
            jdbcTemplate.update("UPDATE reminders SET status = 'SENT', sent_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(now()), alreadySent);

            mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());

            assertThat(statusOf(alreadySent)).isEqualTo(ReminderStatus.SENT);
            assertThat(sentAtOf(alreadySent)).isNotNull();
            assertThat(statusOf(reminders.get(1).getId())).isEqualTo(ReminderStatus.CANCELLED);
        }

        @Test
        void shouldReturn404WhenCancellingNonExistentAppointment() throws Exception {
            mockMvc.perform(post("/appointments/{id}/cancel", 999999))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("APPOINTMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("Cancelling twice is harmless - a client retrying after a timeout gets 200 again")
        void shouldTreatRepeatedCancellationAsIdempotent() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(30)));

            mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());
            mockMvc.perform(post("/appointments/{id}/cancel", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }
    }

    // ------------------------------------------------------------------
    // Rescheduling
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Rescheduling")
    class Rescheduling {

        private MockHttpServletRequestBuilder patchTo(long id, Instant newTime) {
            return patch("/appointments/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"scheduledAt\":\"" + newTime + "\"}");
        }

        @Test
        void shouldMovePendingRemindersWhenRescheduledToLaterTime() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));
            Instant newTime = now().plus(Duration.ofDays(20));

            mockMvc.perform(patchTo(id, newTime)).andExpect(status().isOk());

            assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id))
                    .extracting(Reminder::getScheduledAt)
                    .containsExactlyInAnyOrder(
                            newTime.minus(Duration.ofHours(24)),
                            newTime.minus(Duration.ofHours(2)));
        }

        @Test
        @DisplayName("A reminder whose window has passed after moving the appointment earlier is cancelled")
        void shouldCancelObsoleteReminderWhenRescheduledMuchEarlier() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));

            // 3 hours away: the 24-hour reminder's due time is now well in the past.
            mockMvc.perform(patchTo(id, now().plus(Duration.ofHours(3)))).andExpect(status().isOk());

            List<Reminder> reminders = reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id);
            assertThat(reminders)
                    .extracting(Reminder::getReminderType, Reminder::getStatus)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(
                                    ReminderType.TWENTY_FOUR_HOURS, ReminderStatus.CANCELLED),
                            org.assertj.core.groups.Tuple.tuple(
                                    ReminderType.TWO_HOURS, ReminderStatus.PENDING));
        }

        @Test
        @DisplayName("Moving the appointment back into the future revives the cancelled reminder")
        void shouldReviveCancelledReminderWhenRescheduledLaterAgain() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));
            mockMvc.perform(patchTo(id, now().plus(Duration.ofHours(3)))).andExpect(status().isOk());

            mockMvc.perform(patchTo(id, now().plus(Duration.ofDays(30)))).andExpect(status().isOk());

            assertThat(reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id))
                    .extracting(Reminder::getStatus)
                    .containsOnly(ReminderStatus.PENDING);
        }

        @Test
        @DisplayName("A reminder already sent is never re-sent, even after rescheduling")
        void shouldLeaveAlreadySentReminderUntouchedWhenRescheduling() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));
            Reminder sent = reminderRepository.findByAppointmentIdOrderByScheduledAtAsc(id).get(0);
            Instant originalDueTime = sent.getScheduledAt();
            jdbcTemplate.update("UPDATE reminders SET status = 'SENT', sent_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(now()), sent.getId());

            mockMvc.perform(patchTo(id, now().plus(Duration.ofDays(30)))).andExpect(status().isOk());

            assertThat(statusOf(sent.getId())).isEqualTo(ReminderStatus.SENT);
            assertThat(reminderRepository.findById(sent.getId()).orElseThrow(AssertionError::new)
                    .getScheduledAt()).isEqualTo(originalDueTime);
        }

        @Test
        void shouldReturn409WhenReschedulingCancelledAppointment() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));
            mockMvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());

            mockMvc.perform(patchTo(id, now().plus(Duration.ofDays(20))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_APPOINTMENT_STATE"));
        }

        @Test
        void shouldReturn404WhenReschedulingNonExistentAppointment() throws Exception {
            mockMvc.perform(patchTo(999999, now().plus(Duration.ofDays(20))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldRejectRescheduleToThePast() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));

            mockMvc.perform(patchTo(id, now().minus(Duration.ofDays(1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        @Test
        void shouldRejectRescheduleWithMissingScheduledTime() throws Exception {
            long id = createAppointment(now().plus(Duration.ofDays(10)));

            mockMvc.perform(patch("/appointments/{id}", id)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }
    }
}
