package com.punch.app.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RecordsPunchOptionsTest {

    @Test
    public void onlineOptionsUseFirstThreeValidRangesAndFixedOvertimeIndexes() {
        List<RecordsFragment.PunchOption> options = RecordsFragment.buildPunchOptions(
                Arrays.asList(
                        "06:00-10:00",
                        " ",
                        "10:00-14:00",
                        null,
                        "14:00-18:00",
                        "18:00-22:00"
                ),
                true
        );

        assertEquals(8, options.size());
        assertOption(options.get(0), "06:00-10:00 \u4e0a\u73ed", 1, false);
        assertOption(options.get(5), "14:00-18:00 \u4e0b\u73ed", 6, false);
        assertOption(options.get(6), "\u52a0\u73ed\u4e0a\u73ed", 7, false);
        assertOption(options.get(7), "\u52a0\u73ed\u4e0b\u73ed", 8, false);
    }

    @Test
    public void onlineOptionsAlwaysContainOvertimeChoices() {
        List<RecordsFragment.PunchOption> options = RecordsFragment.buildPunchOptions(
                Collections.emptyList(),
                true
        );

        assertEquals(2, options.size());
        assertOption(options.get(0), "\u52a0\u73ed\u4e0a\u73ed", 7, false);
        assertOption(options.get(1), "\u52a0\u73ed\u4e0b\u73ed", 8, false);
    }

    @Test
    public void offlineModeDoesNotExposeShiftOptions() {
        List<RecordsFragment.PunchOption> options = RecordsFragment.buildPunchOptions(
                Arrays.asList(
                        "06:00-10:00",
                        "10:00-14:00",
                        "14:00-18:00"
                ),
                false
        );

        assertTrue(options.isEmpty());
    }

    private static void assertOption(RecordsFragment.PunchOption option,
                                     String expectedLabel,
                                     int expectedClockIndex,
                                     boolean expectedFreePunch) {
        assertEquals(expectedLabel, option.label);
        assertEquals(expectedClockIndex, option.clockIndex);
        if (expectedFreePunch) {
            assertTrue(option.freePunch);
        } else {
            assertFalse(option.freePunch);
        }
    }
}
