package com.dealership.appointmentreminder.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.service.ReminderClaimService;
import com.dealership.appointmentreminder.service.ReminderProcessor;

/**
 * The scheduled trigger that drives reminder delivery. Runs on <b>every</b> application instance.
 *
 * <p><b>Why this class exists:</b> reminders must be sent at a future instant, and something has
 * to wake up and notice. This is that something. It is deliberately thin - it decides
 * <i>when</i> work happens and delegates <i>what</i> the work is, so it contains no business
 * logic and needs almost no testing of its own.
 *
 * <p><b>One cycle</b> (architecture document, section 5.3):
 * <ol>
 *   <li>Release work abandoned by any crashed worker.</li>
 *   <li>Claim a bounded batch of due reminders, in a transaction that commits.</li>
 *   <li>Process each claimed reminder, outside that transaction.</li>
 * </ol>
 *
 * <p><b>Why it is safe to run this on every instance:</b> the claim uses
 * {@code FOR UPDATE SKIP LOCKED}, so concurrent workers receive disjoint sets of rows. There is
 * no leader election and no instance-specific configuration - every instance runs identical code
 * (architecture document, section 12).
 *
 * <p><b>SOLID:</b> Single Responsibility - scheduling and orchestration only. The two collaborators
 * below are the units that hold the actual behaviour.
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link ReminderClaimService} - must be a separate bean so that Spring's transactional
 *       proxy is actually applied; see that class for why calling a transactional method on
 *       {@code this} would silently break the claim.</li>
 *   <li>{@link ReminderProcessor} - one reminder's outcome.</li>
 * </ul>
 */
@Component
public class ReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(ReminderWorker.class);

    private final ReminderClaimService claimService;
    private final ReminderProcessor processor;

    public ReminderWorker(ReminderClaimService claimService, ReminderProcessor processor) {
        this.claimService = claimService;
        this.processor = processor;
    }

    /**
     * Runs one cycle.
     *
     * <p>{@code fixedDelay} (not {@code fixedRate}) so that a slow cycle cannot overlap with the
     * next one on the same instance: the delay is measured from the end of the previous run.
     *
     * <p>Phase 2 will implement the three steps; failures must be caught and logged here, because
     * an exception escaping a {@code @Scheduled} method silently stops future executions.
     */
    @Scheduled(fixedDelayString = "${reminder.poll-interval-ms}")
    public void runCycle() {
        try {
            // 1. Return work abandoned by any crashed worker before looking for new work,
            //    so a reminder released this cycle can be claimed in the same cycle.
            claimService.reclaimStaleProcessing();

            // 2. Claim a bounded batch. This commits before anything is sent.
            List<Reminder> claimed = claimService.claimDueReminders();

            // 3. Process each one outside the claim transaction.
            for (Reminder reminder : claimed) {
                processReminderSafely(reminder);
            }
        } catch (Exception e) {
            // An exception escaping a @Scheduled method stops all future executions of it,
            // which would silently halt reminder delivery. Nothing may escape here.
            log.error("Reminder worker cycle failed; will retry on the next poll", e);
        }
    }

    /**
     * One bad reminder must not abandon the rest of the batch. The processor already records
     * failures itself; this is the backstop for anything it could not handle, and such a reminder
     * is left PROCESSING so the processing timeout reclaims it.
     */
    private void processReminderSafely(Reminder reminder) {
        try {
            processor.process(reminder);
        } catch (Exception e) {
            log.error("Unhandled error processing reminder {}; leaving it for the processing timeout to reclaim",
                    reminder.getId(), e);
        }
    }
}
