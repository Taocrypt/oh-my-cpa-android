package com.taocrypt.omc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.File;

/**
 * Progress for kernel (CPA / oh-my-cpa) and shell updates in the notification shade, so an
 * update keeps showing where it is when the panel is closed, the app goes to the background or
 * the system install screen gets dismissed.
 */
final class UpdateNotifier {
    private static final String CHANNEL = "omc-update";
    static final int ID_SHELL = 2101;
    static final int ID_KERNEL = 2102;

    /** one notification per kernel program, so two updates at once don't fight over one */
    static int kernelId(Installer.Pkg p) {
        for (int i = 0; i < Installer.ALL.length; i++) if (Installer.ALL[i] == p) return ID_KERNEL + i;
        return ID_KERNEL;
    }
    private static final String PREFS = "update-notifier";
    private static final String KEY_PENDING = "shellPending";

    private UpdateNotifier() {}

    private static NotificationManager nm(Context c) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "更新进度", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("下载和安装内核、外壳更新时显示进度");
        ch.setSound(null, null);
        ch.enableVibration(false);
        m.createNotificationChannel(ch);
        return m;
    }

    private static PendingIntent openApp(Context c, String action, int req) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) i.setAction(action);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Builder base(Context c, String title, String text) {
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(Palette.get(c).accent)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b;
    }

    private static void post(Context c, int id, Notification.Builder b) {
        try {
            nm(c).notify(id, b.build());
        } catch (RuntimeException ignored) {
        }
    }

    static void cancel(Context c, int id) {
        try {
            nm(c).cancel(id);
        } catch (RuntimeException ignored) {
        }
    }

    /** pc in 0..100, or -1 for an indeterminate step. Ongoing: it can't be swiped away mid-update. */
    static void progress(Context c, int id, String title, String text, int pc) {
        Notification.Builder b = base(c, title, text)
                .setOngoing(true)
                .setProgress(100, Math.max(pc, 0), pc < 0)
                .setContentIntent(openApp(c, MainActivity.ACTION_UPDATES, id));
        if (pc >= 0) b.setSubText(pc + "%");
        post(c, id, b);
    }

    /** A finished or failed step; tapping it runs tap (or opens the update page). */
    static void finish(Context c, int id, String title, String text, PendingIntent tap) {
        Notification.Builder b = base(c, title, text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap != null ? tap : openApp(c, MainActivity.ACTION_UPDATES, id));
        post(c, id, b);
    }

    // ---- shell

    /** The system installer wants a confirmation: keep it one tap away even if its screen was dismissed or blocked. */
    static void shellConfirm(Context c, String version, Intent confirm) {
        Intent i = new Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, ID_SHELL, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = base(c, "外壳 " + version + " 等待安装", "点按这里打开系统安装界面")
                .setOngoing(true)
                .setProgress(100, 100, false)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "安装", pi).build());
        post(c, ID_SHELL, b);
    }

    /** The installer gave up (cancelled or failed): offer to retry with the file already downloaded. */
    static void shellRetry(Context c, String title, String text, File apk) {
        PendingIntent tap = null;
        if (apk != null && apk.isFile()) {
            Intent i = new Intent(c, MainActivity.class)
                    .setAction(MainActivity.ACTION_SHELL_INSTALL)
                    .putExtra(MainActivity.EXTRA_APK, apk.getAbsolutePath())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            tap = PendingIntent.getActivity(c, ID_SHELL + 100, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            text = text + "\n点按重新安装";
        }
        finish(c, ID_SHELL, title, text, tap);
    }

    static void markShellPending(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING, on).apply();
    }

    /** After the package was replaced: the old process died before it could clear its own notification. */
    static void shellReplaced(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_PENDING, false)) return;
        p.edit().putBoolean(KEY_PENDING, false).apply();
        finish(c, ID_SHELL, "外壳已更新到 " + ShellUpdater.current(c), "点按打开 Oh My CPA", openApp(c, null, ID_SHELL));
        Storage.delete(Storage.shellUpdateDir(c));
    }
}
