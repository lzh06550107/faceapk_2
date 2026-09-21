package com.punch.app.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes platform event bodies away from heartbeat fetching and deduplicates
 * repeated cursors. A completed body is retained as ACK_PENDING until its ACK succeeds.
 */
public final class PlatformEventExecutor {
    public enum SubmitResult {
        STARTED,
        IGNORED_RUNNING,
        ACK_RETRY_QUEUED
    }

    private final PlatformEventStateMachine stateMachine = new PlatformEventStateMachine();
    private final Map<String, AckWork> pendingAcks = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public PlatformEventExecutor(String threadName) {
        String safeName = threadName == null || threadName.trim().isEmpty()
                ? "platform-event-executor"
                : threadName.trim();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, safeName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public SubmitResult submit(String cursor, EventWork work) {
        if (work == null) {
            throw new IllegalArgumentException("work == null");
        }
        PlatformEventStateMachine.SubmitAction action = stateMachine.onSubmit(cursor);
        if (action == PlatformEventStateMachine.SubmitAction.IGNORE_RUNNING) {
            return SubmitResult.IGNORED_RUNNING;
        }
        if (action == PlatformEventStateMachine.SubmitAction.RETRY_ACK) {
            executor.execute(() -> retryAck(cursor));
            return SubmitResult.ACK_RETRY_QUEUED;
        }
        executor.execute(() -> runBody(cursor, work));
        return SubmitResult.STARTED;
    }

    private void runBody(String cursor, EventWork work) {
        final AckWork ackWork;
        try {
            ackWork = work.process();
        } catch (RuntimeException | Error e) {
            stateMachine.resetRunning(cursor);
            return;
        }
        if (ackWork == null) {
            stateMachine.resetRunning(cursor);
            return;
        }
        pendingAcks.put(cursor, ackWork);
        stateMachine.markAckPending(cursor);
        retryAck(cursor);
    }

    private void retryAck(String cursor) {
        AckWork ackWork = pendingAcks.get(cursor);
        if (ackWork == null) {
            return;
        }
        boolean success;
        try {
            success = ackWork.acknowledge();
        } catch (RuntimeException | Error e) {
            success = false;
        }
        if (success) {
            pendingAcks.remove(cursor);
            stateMachine.markAcked(cursor);
        } else {
            stateMachine.markAckFailed(cursor);
        }
    }

    void shutdownForTest() {
        executor.shutdownNow();
    }

    public interface EventWork {
        AckWork process();
    }

    public interface AckWork {
        boolean acknowledge();
    }
}
