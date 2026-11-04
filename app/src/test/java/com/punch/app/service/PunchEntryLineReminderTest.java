package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PunchEntryLineReminderTest {
    @Test
    public void messageShowsLineNameCodeAndTeam() {
        assertEquals(
                "当前线体：包装一线（A01）\n当前班组：白班\n\n请确认当前线体无误后再进行打卡。",
                PunchEntryLineReminder.buildMessage("包装一线", "A01", "白班")
        );
    }

    @Test
    public void missingLineAndTeamUseClearFallbacks() {
        assertEquals(
                "当前线体：未绑定线体\n当前班组：未选择\n\n请确认当前线体无误后再进行打卡。",
                PunchEntryLineReminder.buildMessage(" ", null, "")
        );
    }

    @Test
    public void duplicateNameAndCodeAreNotRepeated() {
        assertEquals("A01", PunchEntryLineReminder.buildLineLabel("A01", "A01"));
    }
}
