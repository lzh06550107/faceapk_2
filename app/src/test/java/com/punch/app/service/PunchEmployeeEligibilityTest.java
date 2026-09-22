package com.punch.app.service;

import com.punch.app.model.Employee;
import com.punch.app.utils.Constants;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchEmployeeEligibilityTest {
    @Test public void normalEnabledEmployeeIsEligible() {
        Employee e = employee();
        assertTrue(PunchEmployeeEligibility.isEligible(e));
    }
    @Test public void deletedEmployeeIsRejected() {
        Employee e = employee(); e.isDeleted = 1;
        assertFalse(PunchEmployeeEligibility.isEligible(e));
    }
    @Test public void disabledFaceIsRejected() {
        Employee e = employee(); e.faceStatus = "disabled";
        assertFalse(PunchEmployeeEligibility.isEligible(e));
    }
    @Test public void leaveAndRestAreRejected() {
        Employee e = employee(); e.status = Constants.STATUS_LEAVE;
        assertFalse(PunchEmployeeEligibility.isEligible(e));
        e.status = Constants.STATUS_REST;
        assertFalse(PunchEmployeeEligibility.isEligible(e));
    }
    @Test public void legacyBlankStatusAndFaceStatusRemainEligible() {
        Employee e = employee(); e.status = ""; e.faceStatus = "";
        assertTrue(PunchEmployeeEligibility.isEligible(e));
    }
    @Test public void nullEmployeeIsRejected() {
        assertFalse(PunchEmployeeEligibility.isEligible(null));
    }

    @Test public void faceAuthorizationRequiresDurableCurrentRegistration() {
        Employee e = employee();
        e.faceRegistered = 1;
        assertTrue(PunchEmployeeEligibility.isFaceAuthorizationCurrent(e, false));

        e.faceRegistered = 0;
        assertFalse(PunchEmployeeEligibility.isFaceAuthorizationCurrent(e, false));

        e.faceRegistered = 1;
        assertFalse(PunchEmployeeEligibility.isFaceAuthorizationCurrent(e, true));
    }

    @Test public void deletedOrDisabledDesiredFaceCannotAuthorizeStaleRuntimeMatch() {
        Employee e = employee();
        e.faceRegistered = 1;
        e.isDeleted = 1;
        assertFalse(PunchEmployeeEligibility.isFaceAuthorizationCurrent(e, false));

        e.isDeleted = 0;
        e.faceStatus = "disabled";
        assertFalse(PunchEmployeeEligibility.isFaceAuthorizationCurrent(e, false));
    }

    private Employee employee() {
        Employee e = new Employee();
        e.id = "E1";
        e.status = Constants.STATUS_NORMAL;
        e.faceStatus = "enabled";
        e.isDeleted = 0;
        return e;
    }
}
