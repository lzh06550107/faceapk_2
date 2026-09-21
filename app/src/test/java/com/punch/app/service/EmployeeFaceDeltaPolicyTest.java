package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EmployeeFaceDeltaPolicyTest {
    @Test
    public void newEnabledEmployeeWithFaceNeedsRegister() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.REGISTER,
                EmployeeFaceDeltaPolicy.classify(false, false, false, true, true, false));
    }

    @Test
    public void profileOnlyUpdateNeedsNoFaceWork() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.NONE,
                EmployeeFaceDeltaPolicy.classify(true, false, false, true, true, true));
    }

    @Test
    public void faceChangeForRegisteredEmployeeNeedsReplace() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.REPLACE,
                EmployeeFaceDeltaPolicy.classify(true, true, false, true, true, true));
    }

    @Test
    public void disabledEmployeeNeedsRemove() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.REMOVE,
                EmployeeFaceDeltaPolicy.classify(true, true, false, false, true, true));
    }

    @Test
    public void deletedEmployeeNeedsRemove() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.REMOVE,
                EmployeeFaceDeltaPolicy.classify(true, false, true, true, true, true));
    }
    @Test
    public void existingUnregisteredEmployeeNeedsRegister() {
        assertEquals(EmployeeFaceDeltaPolicy.Action.REGISTER,
                EmployeeFaceDeltaPolicy.classify(true, false, false, true, true, false));
    }

}
