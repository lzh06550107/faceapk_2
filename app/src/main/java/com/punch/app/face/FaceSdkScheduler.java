package com.punch.app.face;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;

/**
 * Serializes every native Face SDK operation while giving realtime recognition
 * priority over queued background library maintenance.
 */
public final class FaceSdkScheduler {
    private static final int DEFAULT_MAX_REALTIME_BURST = 8;
    private static final long DEFAULT_BACKGROUND_AGING_MS = 250L;
    private static final ThreadLocal<Long> CURRENT_QUEUE_WAIT_MS = new ThreadLocal<>();
    private static final FaceSdkScheduler SHARED = new FaceSdkScheduler(
            DEFAULT_MAX_REALTIME_BURST,
            DEFAULT_BACKGROUND_AGING_MS,
            "face-sdk-scheduler"
    );

    private final Object monitor = new Object();
    private final Deque<Task<?>> realtimeQueue = new ArrayDeque<>();
    private final Deque<Task<?>> backgroundQueue = new ArrayDeque<>();
    private final Deque<Task<?>> maintenanceQueue = new ArrayDeque<>();
    private final int maxRealtimeBurst;
    private final long backgroundAgingMs;
    private final Thread worker;
    private volatile boolean running = true;
    private int realtimeBurst;

    public static FaceSdkScheduler shared() {
        return SHARED;
    }

    public static long currentQueueWaitMs() {
        Long waitMs = CURRENT_QUEUE_WAIT_MS.get();
        return waitMs == null ? 0L : waitMs;
    }

    FaceSdkScheduler(int maxRealtimeBurst, long backgroundAgingMs, String threadName) {
        this.maxRealtimeBurst = Math.max(1, maxRealtimeBurst);
        this.backgroundAgingMs = Math.max(0L, backgroundAgingMs);
        worker = new Thread(this::runLoop,
                threadName == null || threadName.trim().isEmpty()
                        ? "face-sdk-scheduler"
                        : threadName.trim());
        worker.setDaemon(true);
        worker.start();
    }

    public <T> T callRealtime(String name, Operation<T> operation) {
        return submit(Priority.REALTIME, name, operation);
    }

    public <T> T callBackground(String name, Operation<T> operation) {
        return submit(Priority.BACKGROUND, name, operation);
    }

    public <T> T callMaintenance(String name, Operation<T> operation) {
        return submit(Priority.MAINTENANCE, name, operation);
    }

    private <T> T submit(Priority priority, String name, Operation<T> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation == null");
        }
        if (Thread.currentThread() == worker) {
            return operation.run();
        }
        Task<T> task = new Task<>(priority, name, operation);
        synchronized (monitor) {
            if (!running) {
                throw new IllegalStateException("FaceSdkScheduler is stopped");
            }
            queueFor(priority).addLast(task);
            monitor.notifyAll();
        }
        return task.awaitResult();
    }

    private void runLoop() {
        while (true) {
            Task<?> task;
            synchronized (monitor) {
                while (running && isEmptyLocked()) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                        break;
                    }
                }
                if (!running && isEmptyLocked()) {
                    return;
                }
                task = chooseNextLocked(System.currentTimeMillis());
            }
            if (task != null) {
                task.execute();
            }
        }
    }

    private Task<?> chooseNextLocked(long nowMs) {
        boolean backgroundAged = isBackgroundAgedLocked(nowMs);
        if (!realtimeQueue.isEmpty()
                && !(backgroundAged && realtimeBurst >= maxRealtimeBurst)) {
            realtimeBurst++;
            return realtimeQueue.removeFirst();
        }
        if (!backgroundQueue.isEmpty()) {
            realtimeBurst = 0;
            return backgroundQueue.removeFirst();
        }
        if (!realtimeQueue.isEmpty()) {
            realtimeBurst++;
            return realtimeQueue.removeFirst();
        }
        realtimeBurst = 0;
        return maintenanceQueue.pollFirst();
    }

    private boolean isBackgroundAgedLocked(long nowMs) {
        Task<?> background = backgroundQueue.peekFirst();
        return background != null && nowMs - background.enqueuedAtMs >= backgroundAgingMs;
    }

    private boolean isEmptyLocked() {
        return realtimeQueue.isEmpty() && backgroundQueue.isEmpty() && maintenanceQueue.isEmpty();
    }

    private Deque<Task<?>> queueFor(Priority priority) {
        if (priority == Priority.REALTIME) {
            return realtimeQueue;
        }
        if (priority == Priority.BACKGROUND) {
            return backgroundQueue;
        }
        return maintenanceQueue;
    }

    int realtimeQueueSizeForTest() {
        synchronized (monitor) {
            return realtimeQueue.size();
        }
    }

    int backgroundQueueSizeForTest() {
        synchronized (monitor) {
            return backgroundQueue.size();
        }
    }

    void shutdownForTest() {
        synchronized (monitor) {
            running = false;
            monitor.notifyAll();
        }
        worker.interrupt();
        try {
            worker.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public interface Operation<T> {
        T run();
    }

    private enum Priority {
        REALTIME,
        BACKGROUND,
        MAINTENANCE
    }

    private static final class Task<T> {
        final Priority priority;
        final String name;
        final Operation<T> operation;
        final long enqueuedAtMs = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        T result;
        RuntimeException failure;
        Error error;

        Task(Priority priority, String name, Operation<T> operation) {
            this.priority = priority;
            this.name = name == null ? "" : name;
            this.operation = operation;
        }

        void execute() {
            long queueWaitMs = Math.max(0L, System.currentTimeMillis() - enqueuedAtMs);
            CURRENT_QUEUE_WAIT_MS.set(queueWaitMs);
            try {
                result = operation.run();
            } catch (RuntimeException e) {
                failure = e;
            } catch (Error e) {
                error = e;
            } finally {
                CURRENT_QUEUE_WAIT_MS.remove();
                done.countDown();
            }
        }

        T awaitResult() {
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Face SDK task " + name, e);
            }
            if (error != null) {
                throw error;
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
