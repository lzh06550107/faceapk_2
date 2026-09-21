package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KioskRestorePolicyTest {

    @Test
    public void restoreRequiresInteractiveUnlockedManagedKioskWithoutResumedActivity() {
        assertTrue(KioskRestorePolicy.shouldRestore(
                true, true, true, false, false, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                false, true, true, false, false, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                true, false, true, false, false, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                true, true, false, false, false, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                true, true, true, true, false, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                true, true, true, false, true, 0
        ));
        assertFalse(KioskRestorePolicy.shouldRestore(
                true, true, true, false, false, 1
        ));
    }
}
