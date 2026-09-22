package com.punch.app.model;

public class PunchRecord {
    public static final String STATE_ACCEPTING = "ACCEPTING";
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_UPLOADING = "UPLOADING";
    public static final String STATE_SYNCED = "SYNCED";

    public String id;
    public String clientRecordId;
    public String empId;
    public String empName;
    public String dept;
    public long punchTime;
    public String punchDate;
    public String punchType;
    public String shiftName;
    public String lineCode;
    public int teamBindingId;
    public int clockIndex;
    public double matchScore;
    public String snapImagePath;
    public String snapImageMimeType;
    public int snapImageWidth;
    public int snapImageHeight;
    public long snapImageSize;
    public long snapCapturedAt;
    public int isSynced;
    public String punchState = STATE_ACCEPTED;
}
