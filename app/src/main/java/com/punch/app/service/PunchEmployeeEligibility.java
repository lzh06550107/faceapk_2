package com.punch.app.service;

import com.punch.app.model.Employee;
import com.punch.app.utils.Constants;

/** Final desired-state gate after FaceSearch match and before punch creation. */
public final class PunchEmployeeEligibility {
    private PunchEmployeeEligibility() {
    }

    public static boolean isEligible(Employee employee) {
        if (employee == null || employee.isDeleted != 0) {
            return false;
        }
        String faceStatus = safe(employee.faceStatus);
        if (!faceStatus.isEmpty() && !"enabled".equalsIgnoreCase(faceStatus)) {
            return false;
        }
        String status = safe(employee.status);
        return !Constants.STATUS_LEAVE.equals(status)
                && !Constants.STATUS_REST.equals(status);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
