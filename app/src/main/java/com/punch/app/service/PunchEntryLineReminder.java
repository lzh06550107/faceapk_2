package com.punch.app.service;

/** Builds the line/team reminder shown whenever the user enters the punch screen. */
public final class PunchEntryLineReminder {
    private PunchEntryLineReminder() {
    }

    public static String buildMessage(String lineName, String lineCode, String teamName) {
        String line = buildLineLabel(lineName, lineCode);
        String team = clean(teamName);
        if (team.isEmpty()) {
            team = "未选择";
        }
        return "当前线体：" + line
                + "\n当前班组：" + team
                + "\n\n请确认当前线体无误后再进行打卡。";
    }

    static String buildLineLabel(String lineName, String lineCode) {
        String name = clean(lineName);
        return name.isEmpty() ? "未选择" : name;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
