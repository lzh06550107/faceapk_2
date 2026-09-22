package com.punch.app.network.dto;

public final class PunchDto {
    private PunchDto() {
    }

    public static final class LineCapacityData {
        public boolean isOverCapacity;
    }

    public static final class PunchPushData {
        public String recordId = "";
        public long snapTime;
        public String snapTimeStr = "";
        public String dates = "";
        public String attendReportId = "";
        public String attendReportTable = "";
    }

    public static final class ClockStatisticsData {
        public String date = "";
        public String lineCode = "";
        public int clockIndex;
        public String clockIndexName = "";
        public int total;
        public int page;
        public int pageSize;
        public final Summary summary = new Summary();
        public final java.util.List<Row> rows = new java.util.ArrayList<>();
    }

    public static final class Summary {
        public int total;
        public int clocked;
        public int unclocked;
        public int special;
    }

    public static final class Row {
        public String numbers = "";
        public String name = "";
        public int lineId;
        public String lineName = "";
        public String facePath = "";
        public String clockTime = "";
        public int clockStatus;
        public String clockStatusText = "";
        public String sign = "";
        public String specialText = "";
        public int lateMinutes;
        public int earlyMinutes;
    }
}
