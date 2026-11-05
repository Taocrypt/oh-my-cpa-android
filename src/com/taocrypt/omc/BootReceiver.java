package com.taocrypt.omc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the services after boot (or after an app update) when 开机自启 is on. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            try {
                UpdateNotifier.shellReplaced(context);
            } catch (RuntimeException e) {
                Stack.appLog(context, "shell update notice failed: " + e);
            }
        }
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
                && Prefs.autostart(context) && Installer.installed(context)) {
            try {
                OmcService.start(context);
            } catch (RuntimeException e) {
                Stack.appLog(context, "autostart failed: " + e);
            }
        }
    }
}
