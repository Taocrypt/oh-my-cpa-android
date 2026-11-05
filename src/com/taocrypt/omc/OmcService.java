package com.taocrypt.omc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps CLIProxyAPI and oh-my-cpa alive as a foreground service. */
public final class OmcService extends Service {
    public static final String ACTION_START = "com.taocrypt.omc.START";
    public static final String ACTION_STOP = "com.taocrypt.omc.STOP";
    public static final String ACTION_RESTART = "com.taocrypt.omc.RESTART";
    private static final String CHANNEL = "omc";
    private static final String CHANNEL_ALERT = "omc-alert";
    private static final int ID = 1;
    private static volatile boolean up;
    private PowerManager.WakeLock wake;

    public static boolean isUp() { return up; }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, OmcService.class).setAction(ACTION_START));
    }

    public static void restart(Context c) {
        c.startForegroundService(new Intent(c, OmcService.class).setAction(ACTION_RESTART));
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, OmcService.class).setAction(ACTION_STOP));
        } catch (IllegalStateException e) {
            final Context app = c.getApplicationContext();
            new Thread(() -> Stack.stop(app), "omc-stop").start();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        channels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? ACTION_START : intent.getAction();
        final Context app = getApplicationContext();
        if (ACTION_STOP.equals(action)) {
            up = false;
            new Thread(() -> { Tunnels.stopAll(app); Stack.stop(app); }, "omc-stop").start();
            FloatService.hide(this);
            releaseWake();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID, notification(this));
        up = true;
        holdWake();
        if (ACTION_RESTART.equals(action)) {
            Stack.appLog(app, "restart requested");
            new Thread(() -> { Stack.restart(app); Tunnels.resume(app); }, "omc-restart").start();
        } else {
            new Thread(() -> { Stack.start(app); Tunnels.resume(app); }, "omc-start").start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        up = false;
        releaseWake();
        final Context app = getApplicationContext();
        new Thread(() -> { Tunnels.stopAll(app); Stack.stop(app); }, "omc-stop").start();
        super.onDestroy();
    }

    /** A partial wake lock so a request in flight is not frozen when the screen goes off. */
    private void holdWake() {
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omc:stack");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
    }

    private static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "服务运行状态", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("CLIProxyAPI 与 oh-my-cpa 正在本机运行");
        nm.createNotificationChannel(ch);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "异常提醒", NotificationManager.IMPORTANCE_DEFAULT);
        alert.setDescription("进程多次启动失败时提醒");
        nm.createNotificationChannel(alert);
    }

    public static void notifyProblem(Context c, String title, String text) {
        try {
            channels(c);
            Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_LOGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_stat_mark)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setColor(Palette.get(c).accent)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            c.getSystemService(NotificationManager.class).notify(2, n);
        } catch (RuntimeException ignored) {
        }
    }

    private static Notification notification(Context c) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), f);
        PendingIntent floating = PendingIntent.getService(c, 2, new Intent(c, FloatService.class).setAction(FloatService.ACTION_TOGGLE), f);
        PendingIntent restart = PendingIntent.getService(c, 3, new Intent(c, OmcService.class).setAction(ACTION_RESTART), f);
        PendingIntent stop = PendingIntent.getService(c, 1, new Intent(c, OmcService.class).setAction(ACTION_STOP), f);
        String host = Stack.bindHost(c);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setColor(Palette.get(c).accent)
                .setContentTitle("Oh My CPA 运行中")
                .setContentText("CPA " + host + ":" + Stack.cpaPort(c) + " · 控制台 " + host + ":" + Stack.omcPort(c))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "悬浮窗", floating).build())
                .addAction(new Notification.Action.Builder((Icon) null, "重启", restart).build())
                .addAction(new Notification.Action.Builder((Icon) null, "停止", stop).build())
                .build();
    }
}
