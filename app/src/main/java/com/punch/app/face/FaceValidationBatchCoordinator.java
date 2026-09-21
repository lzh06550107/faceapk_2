package com.punch.app.face;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Coalesces queued/running rebuild-validation requests when an existing batch already covers
 * every employee requested by the new caller. This class is pure Java so the coalescing contract
 * can be regression-tested without Android / Face SDK dependencies.
 */
final class FaceValidationBatchCoordinator<T> {
    interface Listener<T> {
        void onDone(T result);
    }

    static final class Submission<T> {
        private final Batch<T> batch;
        private final boolean owner;

        private Submission(Batch<T> batch, boolean owner) {
            this.batch = batch;
            this.owner = owner;
        }

        boolean isOwner() {
            return owner;
        }

        int coveredEmployeeCount() {
            return batch.employeeIds.size();
        }

        int listenerCount() {
            synchronized (batch) {
                return batch.listeners.size();
            }
        }
    }

    private static final class Batch<T> {
        final Set<String> employeeIds;
        final List<Listener<T>> listeners = new ArrayList<>();

        Batch(Set<String> employeeIds) {
            this.employeeIds = Collections.unmodifiableSet(new HashSet<>(employeeIds));
        }
    }

    private final List<Batch<T>> activeBatches = new ArrayList<>();

    synchronized Submission<T> submit(Set<String> requestedEmployeeIds, Listener<T> listener) {
        Set<String> requested = requestedEmployeeIds == null
                ? Collections.emptySet()
                : new HashSet<>(requestedEmployeeIds);
        for (Batch<T> batch : activeBatches) {
            if (batch.employeeIds.containsAll(requested)) {
                addListener(batch, listener);
                return new Submission<>(batch, false);
            }
        }

        Batch<T> batch = new Batch<>(requested);
        addListener(batch, listener);
        activeBatches.add(batch);
        return new Submission<>(batch, true);
    }

    void complete(Submission<T> submission, T result) {
        if (submission == null || !submission.owner) {
            return;
        }
        List<Listener<T>> listeners;
        synchronized (this) {
            if (!activeBatches.remove(submission.batch)) {
                return;
            }
            synchronized (submission.batch) {
                listeners = new ArrayList<>(submission.batch.listeners);
                submission.batch.listeners.clear();
            }
        }
        for (Listener<T> listener : listeners) {
            if (listener != null) {
                listener.onDone(result);
            }
        }
    }

    synchronized int activeBatchCount() {
        return activeBatches.size();
    }

    private void addListener(Batch<T> batch, Listener<T> listener) {
        if (listener == null) {
            return;
        }
        synchronized (batch) {
            batch.listeners.add(listener);
        }
    }
}
