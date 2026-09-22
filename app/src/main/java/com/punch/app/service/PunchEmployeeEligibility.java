package com.punch.app.service;

import com.punch.app.model.Employee;
import com.punch.app.utils.Constants;

/** Final desired-state gate after FaceSearch match and before punch creation. */
public final class PunchEmployeeEligibility {
    private PunchEmployeeEligibility() {
    }

    public static boolean isEligible(Employee employee) {
        if (!hasEnabledDesiredFace(employee)) {
            return false;
        }
        String status = safe(employee.status);
        return !Constants.STATUS_LEAVE.equals(status)
                && !Constants.STATUS_REST.equals(status);
    }

    /**
     * Final face-authorization gate between a runtime FaceSearch match and punch creation.
     *
     * <p>A changed/deleted/disabled desired state is authoritative even while the asynchronous
     * FaceApplyWorker is still converging the native runtime library. Requiring the durable
     * registration flag and rejecting a pending UPSERT prevents an old runtime face from
     * authorizing a punch after the server has already delivered a newer face version.</p>
     */
    public static boolean isFaceAuthorizationCurrent(Employee employee, boolean faceUpdatePending) {
        return hasEnabledDesiredFace(employee)
                && employee.faceRegistered == 1
                && !faceUpdatePending;
    }

    private static boolean hasEnabledDesiredFace(Employee employee) {
        if (employee == null || employee.isDeleted != 0) {
            return false;
        }
        String faceStatus = safe(employee.faceStatus);
        return faceStatus.isEmpty() || "enabled".equalsIgnoreCase(faceStatus);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
