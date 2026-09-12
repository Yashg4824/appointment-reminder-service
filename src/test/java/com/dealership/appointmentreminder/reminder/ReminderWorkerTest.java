package com.dealership.appointmentreminder.reminder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Unit tests for the worker's orchestration, using mocks.
 *
 * <p>Mocks are appropriate here and only here: this class contains no database or concurrency
 * behaviour, only the decision of what to call and in what order, plus its error containment.
 * The claiming and processing behaviour itself is tested against the real database elsewhere.
 *
 * <p>The containment behaviour matters more than it looks: an exception escaping a
 * {@code @Scheduled} method stops all of that method's future executions, which would silently
 * halt reminder delivery for the whole instance.
 */
class ReminderWorkerTest {

    private final ReminderClaimService claimService = mock(ReminderClaimService.class);
    private final ReminderProcessor processor = mock(ReminderProcessor.class);
    private final ReminderWorker worker = new ReminderWorker(claimService, processor);

    private Reminder reminderWithId(long id) {
        Reminder reminder = mock(Reminder.class);
        when(reminder.getId()).thenReturn(id);
        return reminder;
    }

    @Test
    @DisplayName("Abandoned work is released before new work is claimed, so it can be re-claimed in the same cycle")
    void shouldReclaimStaleWorkBeforeClaimingNewWork() {
        when(claimService.claimDueReminders()).thenReturn(Arrays.asList());

        worker.runCycle();

        InOrder order = inOrder(claimService);
        order.verify(claimService).reclaimStaleProcessing();
        order.verify(claimService).claimDueReminders();
    }

    @Test
    void shouldProcessEveryClaimedReminder() {
        Reminder first = reminderWithId(1L);
        Reminder second = reminderWithId(2L);
        when(claimService.claimDueReminders()).thenReturn(Arrays.asList(first, second));

        worker.runCycle();

        verify(processor).process(first);
        verify(processor).process(second);
    }

    @Test
    @DisplayName("One reminder blowing up does not abandon the rest of the batch")
    void shouldContinueProcessingRemainingRemindersWhenOneFails() {
        Reminder failing = reminderWithId(1L);
        Reminder healthy = reminderWithId(2L);
        when(claimService.claimDueReminders()).thenReturn(Arrays.asList(failing, healthy));
        doThrow(new RuntimeException("boom")).when(processor).process(failing);

        assertThatCode(worker::runCycle).doesNotThrowAnyException();

        verify(processor).process(healthy);
    }

    @Test
    @DisplayName("A database failure during claiming is contained, so the scheduler keeps running")
    void shouldNotPropagateExceptionWhenClaimingFails() {
        when(claimService.claimDueReminders())
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database is down"));

        assertThatCode(worker::runCycle).doesNotThrowAnyException();

        verify(processor, org.mockito.Mockito.never()).process(any());
    }

    @Test
    void shouldNotPropagateExceptionWhenStaleReclaimFails() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database is down"))
                .when(claimService).reclaimStaleProcessing();

        assertThatCode(worker::runCycle).doesNotThrowAnyException();
    }
}
