package com.taocrypt.omc;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes uncaught Java crashes to files/logs/crash.log so they can be shared from the log sheet. */
final class CrashLog {
    private static boolean installed;

    private CrashLog() {}

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        final Context c = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(c, "crash in thread " + t.getName(), e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static File file(Context c) {
        return new File(Stack.dir(c, "logs"), "crash.log");
    }

    static void write(Context c, String what, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        File f = file(c);
        if (f.length() > 512 * 1024) f.delete();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(("==== " + ts + "  " + what + "\n" + sw + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    static String deviceInfo(Context c) {
        String app = "?";
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            app = pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception ignored) {
        }
        return "app " + app + "\nCLIProxyAPI " + Prefs.installed(c, Installer.CPA.name) + "  oh-my-cpa " + Prefs.installed(c, Installer.OMC.name)
                + "\nandroid " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + "\ndevice " + Build.MANUFACTURER + " " + Build.MODEL + "\nabi " + String.join(",", Build.SUPPORTED_ABIS)
                + "\ncompatNet " + Prefs.compatNet(c) + "  lan " + Prefs.lan(c) + "  ports " + Prefs.cpaPort(c) + "/" + Prefs.omcPort(c) + "\n";
    }
}
