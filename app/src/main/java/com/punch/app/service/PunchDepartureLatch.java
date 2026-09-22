package com.punch.app.service;

/**
 * Prevents the same continuously-present face from creating a second client_record_id.
 * The latch clears after a configurable number of consecutive no-face frames or when a
 * different employee is positively matched.
 */
public final class PunchDepartureLatch {
    private final int requiredNoFaceFrames;
    private String employeeId;
    private int noFaceFrames;

    public PunchDepartureLatch(int requiredNoFaceFrames) {
        this.requiredNoFaceFrames = Math.max(1, requiredNoFaceFrames);
    }

    public synchronized void latch(String empId) {
        String clean = clean(empId);
        if (clean.isEmpty()) {
            return;
        }
        employeeId = clean;
        noFaceFrames = 0;
    }

    public synchronized boolean shouldBlockMatchedEmployee(String empId) {
        if (employeeId == null) {
            return false;
        }
        String clean = clean(empId);
        noFaceFrames = 0;
        if (!employeeId.equals(clean)) {
            clear();
            return false;
        }
        return true;
    }

    public synchronized void onNoFaceFrame() {
        if (employeeId == null) {
            return;
        }
        noFaceFrames += 1;
        if (noFaceFrames >= requiredNoFaceFrames) {
            clear();
        }
    }

    public synchronized boolean isLatchedFor(String empId) {
        return employeeId != null && employeeId.equals(clean(empId));
    }

    public synchronized void clear() {
        employeeId = null;
        noFaceFrames = 0;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
