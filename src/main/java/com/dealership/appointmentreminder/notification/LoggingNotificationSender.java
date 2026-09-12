package com.dealership.appointmentreminder.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The stub sender required by the assignment: it logs the payload instead of delivering it.
 *
 * <p><b>Why this class exists:</b> it makes the system runnable and demonstrable end to end
 * without an external provider, and it gives tests something real to observe.
 *
 * <p><b>What it deliberately does NOT do:</b> it keeps no in-memory record of what it has
 * already sent. An in-memory {@code Set} of sent reminder ids would be per-instance and lost on
 * restart, so with several instances running it would prevent nothing while looking like a
 * safeguard - which is worse than no safeguard, because it invites false confidence. Whether a
 * reminder has been processed is answered by the {@code reminders} table and nothing else
 * (architecture document, section 10).
 *
 * <p>The reminder id is logged because it is the idempotency key a real provider would use to
 * suppress a duplicate delivery after a crash-and-retry.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("NOTIFICATION SENT | reminderId={} | type={} | to={} | customer={} | appointmentAt={}",
                notification.getReminderId(),
                notification.getReminderType(),
                mask(notification.getRecipient()),
                notification.getCustomerName(),
                notification.getAppointmentScheduledAt());
    }

    /**
     * Masks the contact so customer email addresses and phone numbers do not sit in plain text
     * in log aggregation (architecture document, section 11, observability).
     */
    private String mask(String contact) {
        if (contact == null || contact.length() <= 4) {
            return "****";
        }
        int visible = Math.min(3, contact.length() - 4);
        return contact.substring(0, visible) + "****" + contact.substring(contact.length() - 2);
    }
}
