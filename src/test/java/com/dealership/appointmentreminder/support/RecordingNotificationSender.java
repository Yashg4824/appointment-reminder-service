package com.dealership.appointmentreminder.support;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.service.NotificationSender;

/**
 * A notification sender that records what it was asked to deliver, and can be told to fail.
 *
 * <p>Used instead of a Mockito mock in the concurrency tests, because several worker threads call
 * it at once and the assertions are about exact delivery counts. The queues below are concurrent,
 * so a count of "delivered exactly once" is trustworthy rather than a race.
 */
public class RecordingNotificationSender implements NotificationSender {

    private final Queue<Notification> attempts = new ConcurrentLinkedQueue<>();
    private final Queue<Notification> delivered = new ConcurrentLinkedQueue<>();

    private volatile Predicate<Notification> failureCondition = notification -> false;

    @Override
    public void send(Notification notification) {
        attempts.add(notification);
        if (failureCondition.test(notification)) {
            throw new IllegalStateException("simulated notification provider failure");
        }
        delivered.add(notification);
    }

    /** Every call the processor made, including the ones that failed. */
    public List<Notification> attempts() {
        return new java.util.ArrayList<>(attempts);
    }

    /** Only the calls that returned normally - what the customer would actually have received. */
    public List<Notification> delivered() {
        return new java.util.ArrayList<>(delivered);
    }

    public List<Long> deliveredReminderIds() {
        return delivered.stream().map(Notification::getReminderId).collect(Collectors.toList());
    }

    public void failWhen(Predicate<Notification> condition) {
        this.failureCondition = condition;
    }

    public void reset() {
        attempts.clear();
        delivered.clear();
        failureCondition = notification -> false;
    }
}
