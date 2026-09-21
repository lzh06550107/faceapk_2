package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlatformEventStateMachineTest {
    @Test
    public void newCursorStartsBodyOnce() {
        PlatformEventStateMachine state = new PlatformEventStateMachine();
        assertEquals(PlatformEventStateMachine.SubmitAction.START,
                state.onSubmit("ABC"));
        assertEquals(PlatformEventStateMachine.SubmitAction.IGNORE_RUNNING,
                state.onSubmit("ABC"));
    }

    @Test
    public void completedBodyMovesToAckPendingAndDuplicateRetriesAckOnly() {
        PlatformEventStateMachine state = new PlatformEventStateMachine();
        state.onSubmit("ABC");
        state.markAckPending("ABC");
        assertEquals(PlatformEventStateMachine.SubmitAction.RETRY_ACK,
                state.onSubmit("ABC"));
    }

    @Test
    public void ackSuccessRemovesCursorSoFutureDeliveryMayStartFresh() {
        PlatformEventStateMachine state = new PlatformEventStateMachine();
        state.onSubmit("ABC");
        state.markAckPending("ABC");
        state.markAcked("ABC");
        assertEquals(PlatformEventStateMachine.SubmitAction.START,
                state.onSubmit("ABC"));
    }

    @Test
    public void ackFailureKeepsAckPending() {
        PlatformEventStateMachine state = new PlatformEventStateMachine();
        state.onSubmit("ABC");
        state.markAckPending("ABC");
        state.markAckFailed("ABC");
        assertEquals(PlatformEventStateMachine.SubmitAction.RETRY_ACK,
                state.onSubmit("ABC"));
    }
}
