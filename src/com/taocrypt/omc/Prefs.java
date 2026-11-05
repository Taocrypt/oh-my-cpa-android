package com.taocrypt.omc;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Secrets made once on first launch and kept for good (losing the master key loses OMC's encrypted data), plus app settings. */
public final class Prefs {
    private static final String FILE = "omc";
    public static final int DEFAULT_CPA_PORT = 8317;
    public static final int DEFAULT_OMC_PORT = 8080;
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static synchronized String once(Context c, String key, int bytes) {
        String v = p(c).getString(key, "");
        if (v.isEmpty()) {
            v = hex(bytes);
            p(c).edit().putString(key, v).commit();
        }
        return v;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** OMCPA_MASTER_KEY: 32 random bytes as hex. */
    public static String masterKey(Context c) { return once(c, "masterKey", 32); }

    /** CPA management key = console sign-in password. 16 random bytes as hex. */
    public static String managementKey(Context c) { return once(c, "managementKey", 16); }

    // ---- download mirror: "" = direct. The 0.1.0 boolean toggle maps to ghfast.top.
    public static boolean mirror(Context c) { return !mirrorPrefix(c).isEmpty(); }
    public static void setMirror(Context c, boolean v) { setMirrorPrefix(c, v ? Installer.MIRROR : ""); }
    public static String mirrorPrefix(Context c) {
        SharedPreferences s = p(c);
        if (s.contains("mirrorPrefix")) return s.getString("mirrorPrefix", "");
        return s.getBoolean("mirror", false) ? Installer.MIRROR : "";
    }
    public static void setMirrorPrefix(Context c, String v) {
        v = v == null ? "" : v.trim();
        if (!v.isEmpty() && !v.startsWith("http")) v = "https://" + v;
        if (!v.isEmpty() && !v.endsWith("/")) v = v + "/";
        p(c).edit().putString("mirrorPrefix", v).putBoolean("mirror", !v.isEmpty()).apply();
    }

    public static boolean compatNet(Context c) { return p(c).getBoolean("compatNet", true); }
    public static void setCompatNet(Context c, boolean v) { p(c).edit().putBoolean("compatNet", v).apply(); }

    public static String installed(Context c, String name) { return p(c).getString("ver_" + name, ""); }
    public static void setInstalled(Context c, String name, String v) { p(c).edit().putString("ver_" + name, v).commit(); }
    /** the version kept as files/bin/<name>.prev for rollback */
    public static String prevVersion(Context c, String name) { return p(c).getString("prev_" + name, ""); }
    public static void setPrevVersion(Context c, String name, String v) { p(c).edit().putString("prev_" + name, v).commit(); }

    // ---- network settings
    public static boolean validPort(int i) { return i >= 1024 && i <= 65535; }
    public static int cpaPort(Context c) { int v = p(c).getInt("cpaPort", DEFAULT_CPA_PORT); return validPort(v) ? v : DEFAULT_CPA_PORT; }
    public static int omcPort(Context c) { int v = p(c).getInt("omcPort", DEFAULT_OMC_PORT); return validPort(v) ? v : DEFAULT_OMC_PORT; }
    public static void setPorts(Context c, int cpa, int omc) { p(c).edit().putInt("cpaPort", cpa).putInt("omcPort", omc).commit(); }
    public static boolean lan(Context c) { return p(c).getBoolean("lan", false); }
    public static void setLan(Context c, boolean v) { p(c).edit().putBoolean("lan", v).commit(); }
    public static boolean mdns(Context c) { return p(c).getBoolean("mdns", false); }
    public static void setMdns(Context c, boolean v) { p(c).edit().putBoolean("mdns", v).apply(); }
    public static boolean autostart(Context c) { return p(c).getBoolean("autostart", false); }
    public static void setAutostart(Context c, boolean v) { p(c).edit().putBoolean("autostart", v).apply(); }
    /** the "App 设置" entry added to the console's own sidebar */
    public static boolean injectEntry(Context c) { return p(c).getBoolean("injectEntry", true); }
    public static void setInjectEntry(Context c, boolean v) { p(c).edit().putBoolean("injectEntry", v).apply(); }
    /** hand the management key to plugin pages the way CPA's management centre does (localStorage cli-proxy-auth) */
    public static boolean pluginKey(Context c) { return p(c).getBoolean("pluginKey", true); }
    public static void setPluginKey(Context c, boolean v) { p(c).edit().putBoolean("pluginKey", v).apply(); }

    // ---- appearance: the console's live palette, mirrored so the first frame matches it
    public static String palette(Context c) { return p(c).getString("palette", ""); }
    public static void setPalette(Context c, String json) { p(c).edit().putString("palette", json).apply(); }
    /** floating button position: distance from the bottom edge as a fraction of the height */
    public static float fabY(Context c) { return p(c).getFloat("fabY", 0.13f); }
    public static void setFabY(Context c, float v) { p(c).edit().putFloat("fabY", Math.max(0f, Math.min(0.9f, v))).apply(); }

    // ---- floating window (same ranges as magpie)
    public static int floatAlpha(Context c) { return clamp(p(c).getInt("floatAlpha", 90), 20, 100); }
    public static int floatWidth(Context c) { return clamp(p(c).getInt("floatW", 90), 30, 100); }
    public static int floatHeight(Context c) { return clamp(p(c).getInt("floatH", 45), 20, 95); }
    public static int floatX(Context c) { return p(c).getInt("floatX", 24); }
    public static int floatY(Context c) { return p(c).getInt("floatY", 200); }
    public static boolean floatMini(Context c) { return p(c).getBoolean("floatMini", false); }
    public static boolean floatLock(Context c) { return p(c).getBoolean("floatLock", false); }
    public static void saveFloatLook(Context c, int alpha, int w, int h) {
        p(c).edit().putInt("floatAlpha", clamp(alpha, 20, 100)).putInt("floatW", clamp(w, 30, 100)).putInt("floatH", clamp(h, 20, 95)).apply();
    }
    public static void saveFloatPos(Context c, int x, int y) { p(c).edit().putInt("floatX", x).putInt("floatY", y).apply(); }
    public static void saveFloatMini(Context c, boolean v) { p(c).edit().putBoolean("floatMini", v).apply(); }
    public static void saveFloatLock(Context c, boolean v) { p(c).edit().putBoolean("floatLock", v).apply(); }
    public static int bubbleSize(Context c) { return clamp(p(c).getInt("bubbleSize", 36), 32, 96); }
    public static int bubbleAlpha(Context c) { return clamp(p(c).getInt("bubbleAlpha", 90), 20, 100); }
    public static boolean bubbleSnap(Context c) { return p(c).getBoolean("bubbleSnap", true); }
    public static boolean bubbleHide(Context c) { return p(c).getBoolean("bubbleHide", true); }
    public static int bubbleHideDelay(Context c) { return clamp(p(c).getInt("bubbleHideDelay", 3), 1, 15); }
    public static int bubbleX(Context c) { return p(c).getInt("bubbleX", -1); }
    public static int bubbleY(Context c) { return p(c).getInt("bubbleY", -1); }
    public static void saveBubbleLook(Context c, int size, int alpha) { p(c).edit().putInt("bubbleSize", size).putInt("bubbleAlpha", alpha).apply(); }
    public static void saveBubbleSnap(Context c, boolean v) { p(c).edit().putBoolean("bubbleSnap", v).apply(); }
    public static void saveBubbleHide(Context c, boolean v) { p(c).edit().putBoolean("bubbleHide", v).apply(); }
    public static void saveBubbleHideDelay(Context c, int v) { p(c).edit().putInt("bubbleHideDelay", v).apply(); }
    public static void saveBubblePos(Context c, int x, int y) { p(c).edit().putInt("bubbleX", x).putInt("bubbleY", y).apply(); }

    // ---- remote access
    public static boolean cfOn(Context c) { return p(c).getBoolean("cfOn", false); }
    public static void setCfOn(Context c, boolean v) { p(c).edit().putBoolean("cfOn", v).apply(); }
    /** route /v1 and /v1beta on the public address to the CPA API, the rest to the console */
    public static boolean cfApi(Context c) { return p(c).getBoolean("cfApi", false); }
    public static void setCfApi(Context c, boolean v) { p(c).edit().putBoolean("cfApi", v).apply(); }
    public static boolean tsOn(Context c) { return p(c).getBoolean("tsOn", false); }
    public static void setTsOn(Context c, boolean v) { p(c).edit().putBoolean("tsOn", v).apply(); }
    public static String tsAuthKey(Context c) { return p(c).getString("tsAuthKey", ""); }
    public static String tsHostname(Context c) { return p(c).getString("tsHostname", "oh-my-cpa-android"); }
    public static void setTs(Context c, String key, String host) { p(c).edit().putString("tsAuthKey", key).putString("tsHostname", host).apply(); }

    // ---- update check
    public static long lastUpdateCheck(Context c) { return p(c).getLong("lastUpdateCheck", 0); }
    public static void setLastUpdateCheck(Context c, long v) { p(c).edit().putLong("lastUpdateCheck", v).apply(); }
    /** "name=version,name=version" of newer releases seen by the last check */
    public static String updatesSeen(Context c) { return p(c).getString("updatesSeen", ""); }
    public static void setUpdatesSeen(Context c, String v) { p(c).edit().putString("updatesSeen", v).apply(); }
    /** the newest shell (this app) version the last check found, for the update dot */
    public static String shellSeen(Context c) { return p(c).getString("shellSeen", ""); }
    public static void setShellSeen(Context c, String v) { p(c).edit().putString("shellSeen", v).apply(); }
}
