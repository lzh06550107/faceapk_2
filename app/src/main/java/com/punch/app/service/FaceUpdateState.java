package com.punch.app.service;

/** Lightweight state holder for normal background face-library updates. */
public final class FaceUpdateState {
    private volatile boolean updating;
    private volatile int total;
    private volatile int completed;
    private volatile String status = "";

    public void begin(int total, String status) {
        this.total = Math.max(0, total);
        this.completed = 0;
        this.status = safe(status);
        this.updating = true;
    }

    public void progress(int completed) {
        this.completed = Math.max(0, Math.min(completed, total));
    }

    public void finish(String status) {
        this.completed = total;
        this.status = safe(status);
        this.updating = false;
    }

    public void fail(String status) {
        this.status = safe(status);
        this.updating = false;
    }

    public boolean isUpdating() {
        return updating;
    }

    public int getTotal() {
        return total;
    }

    public int getCompleted() {
        return completed;
    }

    public String getStatus() {
        return status;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
