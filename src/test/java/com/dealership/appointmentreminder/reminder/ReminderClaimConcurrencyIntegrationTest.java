package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dealership.appointmentreminder.appointment.Appointment;
import com.dealership.appointmentreminder.support.AbstractIntegrationTest;

/**
 * Proves the concurrency guarantee that the whole multi-instance design rests on.
 *
 * <p>These tests run against real PostgreSQL with real threads and real transactions. Mockito
 * cannot stand in here: the behaviour being tested is {@code FOR UPDATE SKIP LOCKED} inside the
 * database, and a mock would only assert that the code calls a method, not that two workers
 * receive different rows.
 *
 * <p>{@code batch-size} is pinned to 1 so that several workers genuinely contend for rows. With
 * the production default of 100 the first worker would simply take everything and the test would
 * pass without ever exercising the lock.
 */
@TestPropertySource(properties = "reminder.batch-size=1")
class ReminderClaimConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReminderClaimService claimService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** A reminder that is due now, owned by its own appointment so the unique constraint is free. */
    private Reminder givenDueReminder(int index) {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        return givenPendingReminder(appointment, ReminderType.TWENTY_FOUR_HOURS,
                now().minus(Duration.ofMinutes(60 - index)));
    }

    private List<Long> claimIdsInOwnTransaction(int limit) {
        return requiresNewTransaction().execute(status ->
                reminderRepository.findDueForUpdateSkipLocked(now(), now(), limit).stream()
                        .map(Reminder::getId)
                        .collect(Collectors.toList()));
    }

    // ------------------------------------------------------------------
    // The core guarantee
    // ------------------------------------------------------------------

    /**
     * The decisive test. Worker A holds a lock on a row inside an open transaction; worker B runs
     * the identical query at the same moment.
     *
     * <p>Two things are asserted, and both matter. B must get a <i>different</i> row - that is
     * the no-duplicate-claim guarantee. And B must return <i>quickly</i> - with a plain
     * {@code FOR UPDATE} it would block until A committed, which at scale would serialise every
     * worker in the fleet behind the slowest one. Only {@code SKIP LOCKED} gives both.
     */
    @Test
    @DisplayName("A second worker skips a locked row instead of blocking on it or duplicating it")
    void shouldSkipRowLockedByAnotherWorkerInsteadOfBlocking() throws Exception {
        givenDueReminder(0);
        givenDueReminder(1);

        CountDownLatch workerAHoldsLock = new CountDownLatch(1);
        CountDownLatch allowWorkerAToCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<List<Long>> workerA = executor.submit(() -> requiresNewTransaction().execute(status -> {
                List<Long> claimed = reminderRepository.findDueForUpdateSkipLocked(now(), now(), 1)
                        .stream().map(Reminder::getId).collect(Collectors.toList());
                workerAHoldsLock.countDown();
                try {
                    // Hold the row lock open while worker B runs.
                    allowWorkerAToCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return claimed;
            }));

            assertThat(workerAHoldsLock.await(10, TimeUnit.SECONDS))
                    .as("worker A should have acquired its row lock").isTrue();

            long startedAt = System.nanoTime();
            List<Long> workerBIds = claimIdsInOwnTransaction(1);
            Duration workerBWaited = Duration.ofNanos(System.nanoTime() - startedAt);

            allowWorkerAToCommit.countDown();
            List<Long> workerAIds = workerA.get(10, TimeUnit.SECONDS);

            assertThat(workerAIds).as("worker A claims one reminder").hasSize(1);
            assertThat(workerBIds).as("worker B still finds work").hasSize(1);
            assertThat(workerBIds)
                    .as("worker B must not claim the reminder worker A is holding")
                    .doesNotContainAnyElementsOf(workerAIds);
            assertThat(workerBWaited)
                    .as("SKIP LOCKED means worker B steps over the locked row rather than waiting for it")
                    .isLessThan(Duration.ofSeconds(3));
        } finally {
            allowWorkerAToCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Eight workers claiming at once take eight different reminders")
    void shouldClaimReminderOnlyOnceWhenWorkersRunConcurrently() throws Exception {
        int workerCount = 8;
        for (int i = 0; i < workerCount; i++) {
            givenDueReminder(i);
        }

        CyclicBarrier startTogether = new CyclicBarrier(workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Callable<List<Long>>> workers = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            workers.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return claimService.claimDueReminders().stream()
                        .map(Reminder::getId).collect(Collectors.toList());
            });
        }

        List<Long> allClaimedIds = new ArrayList<>();
        try {
            for (Future<List<Long>> result : executor.invokeAll(workers, 30, TimeUnit.SECONDS)) {
                allClaimedIds.addAll(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(allClaimedIds)
                .as("no reminder may be claimed by two workers")
                .doesNotHaveDuplicates();
        assertThat(allClaimedIds).as("every due reminder should be claimed").hasSize(workerCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reminders WHERE status = 'PROCESSING'", Integer.class))
                .isEqualTo(workerCount);
    }

    @Test
    @DisplayName("Claiming marks the reminder PROCESSING and stamps when the claim expires")
    void shouldMarkClaimedReminderAsProcessingWithExpiry() {
        Reminder reminder = givenDueReminder(0);

        List<Reminder> claimed = claimService.claimDueReminders();

        assertThat(claimed).extracting(Reminder::getId).containsExactly(reminder.getId());
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PROCESSING);
        Instant processingUntil = reminderRepository.findById(reminder.getId())
                .orElseThrow(AssertionError::new).getProcessingUntil();
        assertThat(processingUntil).isEqualTo(now().plus(Duration.ofMinutes(1)));
    }

    // ------------------------------------------------------------------
    // What must NOT be claimed
    // ------------------------------------------------------------------

    @Test
    void shouldNotClaimReminderThatIsNotYetDue() {
        Appointment appointment = givenAppointmentAt(now().plus(Duration.ofDays(5)));
        givenPendingReminder(appointment, ReminderType.TWO_HOURS, now().plus(Duration.ofHours(1)));

        assertThat(claimService.claimDueReminders()).isEmpty();
    }

    /**
     * This is the real answer to "an already-sent reminder must not be sent again": the claim
     * query only ever returns PENDING rows, so a terminal reminder is never handed to a processor
     * in the first place.
     */
    @Test
    void shouldNeverClaimTerminalReminders() {
        for (ReminderStatus terminal : new ReminderStatus[]{
                ReminderStatus.SENT, ReminderStatus.FAILED, ReminderStatus.CANCELLED}) {
            Reminder reminder = givenDueReminder(0);
            forceReminderState(reminder.getId(), terminal, 0, null);
        }

        assertThat(claimService.claimDueReminders()).isEmpty();
    }

    @Test
    void shouldNotClaimReminderAlreadyBeingProcessedByAnotherWorker() {
        Reminder reminder = givenDueReminder(0);
        forceReminderState(reminder.getId(), ReminderStatus.PROCESSING, 0,
                now().plus(Duration.ofMinutes(1)));

        assertThat(claimService.claimDueReminders()).isEmpty();
    }

    @Test
    @DisplayName("A reminder that just failed waits out the retry delay before being claimed again")
    void shouldNotClaimFailedAttemptBeforeRetryDelayElapses() {
        Reminder reminder = givenDueReminder(0);
        jdbcTemplate.update("UPDATE reminders SET last_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now().minus(Duration.ofMinutes(1))), reminder.getId());

        // retry-delay is 5 minutes and the last attempt was 1 minute ago.
        assertThat(claimService.claimDueReminders()).isEmpty();

        clock.advanceBy(Duration.ofMinutes(5));
        assertThat(claimService.claimDueReminders())
                .extracting(Reminder::getId).containsExactly(reminder.getId());
    }

    @Test
    void shouldClaimNoMoreThanTheRequestedBatchSize() {
        givenDueReminder(0);
        givenDueReminder(1);
        givenDueReminder(2);

        assertThat(claimIdsInOwnTransaction(2)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Crash recovery
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Work abandoned by a crashed worker becomes claimable again once its claim expires")
    void shouldReclaimExpiredProcessingReminder() {
        Reminder reminder = givenDueReminder(0);
        forceReminderState(reminder.getId(), ReminderStatus.PROCESSING, 0,
                now().minus(Duration.ofSeconds(30)));

        int reclaimed = claimService.reclaimStaleProcessing();

        assertThat(reclaimed).isEqualTo(1);
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PENDING);
        assertThat(reminderRepository.findById(reminder.getId())
                .orElseThrow(AssertionError::new).getProcessingUntil()).isNull();
        assertThat(attemptCountOf(reminder.getId()))
                .as("each abandoned attempt counts, so a reminder that repeatedly kills its "
                        + "worker eventually fails instead of looping forever")
                .isEqualTo(1);
        assertThat(claimService.claimDueReminders())
                .extracting(Reminder::getId).containsExactly(reminder.getId());
    }

    @Test
    void shouldNotReclaimProcessingReminderWhoseClaimIsStillValid() {
        Reminder reminder = givenDueReminder(0);
        forceReminderState(reminder.getId(), ReminderStatus.PROCESSING, 0,
                now().plus(Duration.ofSeconds(30)));

        assertThat(claimService.reclaimStaleProcessing()).isZero();
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PROCESSING);
        assertThat(attemptCountOf(reminder.getId())).isZero();
    }

    @Test
    @DisplayName("A claim expires exactly when the configured processing timeout elapses")
    void shouldReclaimOnlyOnceTheProcessingTimeoutHasPassed() {
        Reminder reminder = givenDueReminder(0);
        claimService.claimDueReminders();
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PROCESSING);

        clock.advanceBy(Duration.ofSeconds(59));
        assertThat(claimService.reclaimStaleProcessing()).isZero();

        clock.advanceBy(Duration.ofSeconds(2));
        assertThat(claimService.reclaimStaleProcessing()).isEqualTo(1);
        assertThat(statusOf(reminder.getId())).isEqualTo(ReminderStatus.PENDING);
    }
}
