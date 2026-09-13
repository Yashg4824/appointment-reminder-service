package com.dealership.appointmentreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.ReminderType;

/**
 * Tests the stub notification sender.
 *
 * <p>The log line is this class's only observable output, so capturing it is the only way to prove
 * the payload reaches the sender intact. The assertions are about the <i>content</i> of the
 * message, not about Logback itself.
 */
class LoggingNotificationSenderTest {

    private static final Instant APPOINTMENT_AT = Instant.parse("2026-06-20T09:00:00Z");

    private LoggingNotificationSender sender;
    private ListAppender<ILoggingEvent> capturedLogs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        sender = new LoggingNotificationSender();
        logger = (Logger) LoggerFactory.getLogger(LoggingNotificationSender.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        logger.addAppender(capturedLogs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(capturedLogs);
    }

    private String singleLoggedMessage() {
        List<String> messages = capturedLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }

    @Test
    void shouldLogTheNotificationPayloadInsteadOfSendingIt() {
        sender.send(new Notification(77L, ReminderType.TWO_HOURS,
                "katherine@example.com", "Katherine Johnson", APPOINTMENT_AT));

        String logged = singleLoggedMessage();

        assertThat(logged).contains("NOTIFICATION SENT");
        assertThat(logged).contains("reminderId=77");
        assertThat(logged).contains("TWO_HOURS");
        assertThat(logged).contains("Katherine Johnson");
        // The absolute appointment time, not a relative phrase - which is what makes a slightly
        // late reminder still correct.
        assertThat(logged).contains(APPOINTMENT_AT.toString());
        assertThat(capturedLogs.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("Customer contact details are masked, so they do not sit in plain text in logs")
    void shouldMaskCustomerContactInLogOutput() {
        sender.send(new Notification(1L, ReminderType.TWENTY_FOUR_HOURS,
                "katherine@example.com", "Katherine Johnson", APPOINTMENT_AT));

        String logged = singleLoggedMessage();

        assertThat(logged).doesNotContain("katherine@example.com");
        assertThat(logged).contains("****");
    }

    @Test
    void shouldMaskShortContactsCompletely() {
        sender.send(new Notification(1L, ReminderType.TWO_HOURS, "a@b", "X", APPOINTMENT_AT));

        assertThat(singleLoggedMessage()).contains("to=****");
    }

    @Test
    @DisplayName("The stub completes normally - a successful send is signalled by not throwing")
    void shouldReturnNormallyWithoutContactingAnyExternalService() {
        assertThatCode(() -> sender.send(new Notification(1L, ReminderType.TWO_HOURS,
                "someone@example.com", "Someone", APPOINTMENT_AT)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNotificationMissingRequiredFields() {
        assertThatCode(() -> new Notification(null, ReminderType.TWO_HOURS,
                "a@b.com", "Someone", APPOINTMENT_AT))
                .isInstanceOf(NullPointerException.class);
    }
}
