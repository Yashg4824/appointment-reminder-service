package com.dealership.appointmentreminder.reminder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dealership.appointmentreminder.config.ReminderProperties;

/**
 * Claims due reminders for this instance, and releases work abandoned by a crashed one.
 *
 * <p><b>Why this class exists - and why it is separate from {@code ReminderWorker}:</b> this is
 * not decoration. Spring's {@code @Transactional} is applied by a proxy that wraps the bean. If
 * the worker called its own transactional claim method, the call would go straight to {@code
 * this} and bypass the proxy, so the claim would run with <i>no transaction at all</i>. Row locks
 * are released at the end of a transaction, so with no transaction {@code FOR UPDATE SKIP LOCKED}
 * would silently stop providing mutual exclusion and two instances could claim the same reminder.
 * Putting the claim in a separate bean is what guarantees the proxy is used.
 *
 * <p><b>Responsibility:</b> the claim transaction, and nothing else. It does not send, does not
 * decide retries, and does not know what a notification is.
 *
 * <p><b>SOLID:</b> Single Responsibility - it owns one transaction boundary. Splitting it from
 * the worker also separates <i>when</i> work happens (a schedule) from <i>how work is safely
 * taken</i> (a database transaction).
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link ReminderRepository} - where the claim and reclaim statements live.</li>
 *   <li>{@link ReminderProperties} - batch size, processing timeout and retry delay are the
 *       policy inputs to a claim.</li>
 *   <li>{@link Clock} - the claim compares {@code scheduled_at} against now and computes
 *       {@code processing_until}; taking the clock as a dependency is what makes crash-recovery
 *       tests deterministic.</li>
 * </ul>
 */
@Service
public class ReminderClaimService {

    private static final Logger log = LoggerFactory.getLogger(ReminderClaimService.class);

    private final ReminderRepository reminderRepository;
    private final ReminderProperties properties;
    private final Clock clock;

    public ReminderClaimService(ReminderRepository reminderRepository,
                                ReminderProperties properties,
                                Clock clock) {
        this.reminderRepository = reminderRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns abandoned work to PENDING so another worker can pick it up.
     *
     * <p>Any reminder still PROCESSING after its {@code processing_until} has passed is assumed
     * to belong to a worker that died. One idempotent UPDATE; safe to run concurrently on every
     * instance, because whichever runs first releases the rows and the others match nothing
     * (architecture document, section 11).
     *
     * @return how many rows were released
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimStaleProcessing() {
        int reclaimed = reminderRepository.reclaimStaleProcessing(clock.instant());
        if (reclaimed > 0) {
            log.warn("Reclaimed {} reminder(s) abandoned by a worker that did not finish within {}",
                    reclaimed, properties.getProcessingTimeout());
        }
        return reclaimed;
    }

    /**
     * Claims up to {@code batchSize} due reminders for this instance and marks them PROCESSING.
     *
     * <p>Runs in its own transaction, which <b>commits before the caller sends anything</b>.
     * That ordering is deliberate: it stops a slow notification provider from holding database
     * row locks, and it makes PROCESSING a durable "someone is working on this" marker, which is
     * what makes timeout-based recovery possible (architecture document, section 11).
     *
     * @return the claimed reminders, already marked PROCESSING and committed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reminder> claimDueReminders() {
        Instant now = clock.instant();
        Instant retryCutoff = now.minus(properties.getRetryDelay());
        Instant processingUntil = now.plus(properties.getProcessingTimeout());

        List<Reminder> claimed = reminderRepository.findDueForUpdateSkipLocked(
                now, retryCutoff, properties.getBatchSize());

        // These rows are held by FOR UPDATE for the rest of this transaction, so no other
        // transaction can read or write them. That row lock is the guard here, which is why a
        // plain field update is safe and no conditional UPDATE is needed at this point.
        for (Reminder reminder : claimed) {
            reminder.markProcessing(processingUntil, now);
        }

        if (!claimed.isEmpty()) {
            log.info("Claimed {} due reminder(s), held until {}", claimed.size(), processingUntil);
        }
        // Returning ends the transaction, which commits the PROCESSING state and releases the
        // locks. The caller then sends with no transaction open and no locks held.
        return claimed;
    }
}
