package com.punch.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.punch.app.activity.KioskHomeActivity;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.SystemAppController;

/**
 * Restores the dedicated-device environment after Android 12 finishes booting.
 */
public class SystemBootReceiver extends BroadcastReceiver {
    private static final String TAG = "SystemBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SessionManager.get().init(appContext);
        if (!SystemAppController.isPlatformSystemApp(appContext)) {
            return;
        }

        // Match the historical Device Owner behavior: a managed production image always
        // comes back in kiosk mode after reboot.
        SessionManager.get().saveKioskEnabled(true);
        KioskManager.ensureOwnerKioskPolicies(appContext);

        try {
            Intent launch = new Intent(appContext, KioskHomeActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launch.putExtra(KioskHomeActivity.EXTRA_FORCE_FRESH_TARGET, true);
            appContext.startActivity(launch);
            AppLogger.i(TAG, "Restored Android 12 system-app kiosk after boot");
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Unable to launch kiosk home after boot", e);
        }
    }
}
