package com.punch.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.punch.app.utils.SessionManager;

public class SyncService extends Service {
    public static final String ACTION_SYNC_NOW = "com.punch.app.SYNC_NOW";
    public static final String EXTRA_SYNC_TRIGGER = "sync_trigger";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC_NOW.equals(intent.getAction())) {
            HeartbeatManager.get(getApplicationContext()).triggerNow(
                    parseTrigger(intent.getStringExtra(EXTRA_SYNC_TRIGGER))
            );
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void triggerSync(Context context) {
        triggerSync(context, SyncTrigger.AFTER_PUNCH);
    }

    public static void triggerSync(Context context, boolean forcePunchRetry) {
        triggerSync(context, forcePunchRetry ? SyncTrigger.MANUAL : SyncTrigger.AFTER_PUNCH);
    }

    public static void triggerSync(Context context, SyncTrigger trigger) {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        SyncTrigger safeTrigger = trigger != null ? trigger : SyncTrigger.AFTER_PUNCH;
        Context appContext = context.getApplicationContext();
        HeartbeatManager.get(appContext).triggerNow(safeTrigger);
    }

    private static SyncTrigger parseTrigger(String value) {
        if (value == null || value.trim().isEmpty()) {
            return SyncTrigger.AFTER_PUNCH;
        }
        try {
            return SyncTrigger.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SyncTrigger.AFTER_PUNCH;
        }
    }
}
