package com.dealership.appointmentreminder.notification;

/**
 * Delivers a notification to a customer.
 *
 * <p><b>Why this interface exists:</b> it is the seam between the reminder-processing logic and
 * the outside world. {@code ReminderProcessor} depends on this type and never on a concrete
 * sender, so adding email, SMS or push delivery later requires no change to processing,
 * retrying or state-transition logic.
 *
 * <p><b>SOLID:</b>
 * <ul>
 *   <li>Dependency Inversion - the processor depends on this abstraction, not on
 *       {@link LoggingNotificationSender}.</li>
 *   <li>Liskov Substitution - every implementation must honour the same contract: return
 *       normally on success, throw on failure. The processor's retry logic depends on nothing
 *       else, so any implementation is substitutable.</li>
 *   <li>Interface Segregation - one method. It deliberately has no {@code supports(...)},
 *       {@code validateRecipient(...)} or {@code getDeliveryStatus(...)}: no current requirement
 *       needs them, and an implementation should not be forced to stub out methods it has no use
 *       for. Multi-channel routing, when it is real, is a separate abstraction rather than extra
 *       methods here (architecture document, section 14).</li>
 * </ul>
 *
 * <p><b>Contract:</b> returning normally means the notification was handed off successfully.
 * Throwing means the attempt failed and the caller decides whether to retry. Implementations
 * must not swallow failures, because a silent failure would be recorded as a successful send.
 */
public interface NotificationSender {

    /**
     * Delivers the notification.
     *
     * @param notification what to send, and to whom
     * @throws RuntimeException if delivery failed; the caller treats this as a failed attempt
     */
    void send(Notification notification);
}
