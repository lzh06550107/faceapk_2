package com.punch.app.service;

import java.util.HashMap;
import java.util.Map;

/** In-process cursor state used to prevent duplicate platform-event work. */
public final class PlatformEventStateMachine {
    public enum SubmitAction {
        START,
        IGNORE_RUNNING,
        RETRY_ACK
    }

    private enum State {
        RUNNING,
        ACK_PENDING
    }

    private final Map<String, State> states = new HashMap<>();

    public synchronized SubmitAction onSubmit(String cursor) {
        String key = key(cursor);
        State state = states.get(key);
        if (state == State.RUNNING) {
            return SubmitAction.IGNORE_RUNNING;
        }
        if (state == State.ACK_PENDING) {
            return SubmitAction.RETRY_ACK;
        }
        states.put(key, State.RUNNING);
        return SubmitAction.START;
    }

    public synchronized void markAckPending(String cursor) {
        states.put(key(cursor), State.ACK_PENDING);
    }

    public synchronized void markAcked(String cursor) {
        states.remove(key(cursor));
    }

    public synchronized void markAckFailed(String cursor) {
        states.put(key(cursor), State.ACK_PENDING);
    }

    public synchronized void resetRunning(String cursor) {
        if (states.get(key(cursor)) == State.RUNNING) {
            states.remove(key(cursor));
        }
    }

    private String key(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            throw new IllegalArgumentException("cursor is blank");
        }
        return cursor.trim();
    }
}
