package com.punch.app.service;

import android.content.Context;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.PunchRecord;

/** Single persistence boundary shared by production punch and test-only stress builds. */
public final class PunchPersistence {
    private PunchPersistence() {}

    public static boolean persist(Context context, PunchRecord record, String queueAction) {
        if (context == null || record == null || queueAction == null || queueAction.trim().isEmpty()) {
            return false;
        }
        DatabaseHelper db = DatabaseHelper.get(context);
        return db.insertPunchRecordAndEnqueue(record, queueAction);
    }
}
