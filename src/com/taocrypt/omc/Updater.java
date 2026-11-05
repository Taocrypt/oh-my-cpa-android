package com.taocrypt.omc;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Update check for the two programs. The pinned releases in {@link Installer} stay the
 * first-run default; this only runs when the user asks (or the 6-hourly hint finds one) and
 * installs only after they confirm. Every download is checked against the release's own
 * checksums.txt, the old binary is kept as files/bin/&lt;name&gt;.prev for one-tap rollback.
 */
final class Updater {
    static final class Release {
        final Installer.Pkg pkg;
        final String version, archive, sha256;
        Release(Installer.Pkg pkg, String version, String archive, String sha256) {
            this.pkg = pkg; this.version = version; this.archive = archive; this.sha256 = sha256;
        }
        String base() { return "https://github.com/" + repo(pkg) + "/releases/download/v" + version + "/"; }
    }

    static volatile String stage = "";

    private Updater() {}

    static String repo(Installer.Pkg p) {
        return p == Installer.CPA ? "router-for-me/CLIProxyAPI" : "WizisCool/oh-my-cpa";
    }

    static String label(Installer.Pkg p) {
        return p == Installer.CPA ? "CLIProxyAPI" : "oh-my-cpa";
    }

    private static boolean wanted(Installer.Pkg p, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".tar.gz")) return false;
        return p == Installer.CPA ? n.contains("linux_aarch64_no-plugin") : n.contains("linux_arm64");
    }

    private static String guessArchive(Installer.Pkg p, String v) {
        return p == Installer.CPA ? "CLIProxyAPI_" + v + "_linux_aarch64_no-plugin.tar.gz" : "oh-my-cpa_" + v + "_linux_arm64.tar.gz";
    }

    /** The newest release and its verified checksum entry. Blocking. */
    static Release latest(Context c, Installer.Pkg p) throws IOException {
        String version = null, archive = null;
        try {
            JSONObject o = new JSONObject(new String(Installer.get("https://api.github.com/repos/" + repo(p) + "/releases/latest", 2 << 20), "UTF-8"));
            version = o.getString("tag_name").replaceFirst("^v", "");
            JSONArray a = o.optJSONArray("assets");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String n = a.getJSONObject(i).optString("name");
                if (wanted(p, n)) { archive = n; break; }
            }
        } catch (Exception e) {
            // api.github.com is often unreachable or rate limited: read the tag from the release page redirect
            HttpURLConnection h = Installer.open("https://github.com/" + repo(p) + "/releases/latest", 0);
            h.setInstanceFollowRedirects(false);
            try {
                String loc = h.getHeaderField("Location");
                if (loc != null && loc.contains("/tag/")) version = loc.substring(loc.lastIndexOf('/') + 1).replaceFirst("^v", "").trim();
            } finally {
                h.disconnect();
            }
            if (version == null) throw new IOException("查询 " + label(p) + " 最新版本失败：" + e.getMessage());
        }
        if (archive == null) archive = guessArchive(p, version);
        Release tmp = new Release(p, version, archive, "");
        String sums = new String(Installer.get(Installer.wrap(c, tmp.base() + "checksums.txt"), 1 << 20), "UTF-8");
        String sha = null;
        for (String line : sums.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 2 && f[1].replaceFirst("^\\*", "").equals(archive)) sha = f[0].toLowerCase(Locale.ROOT);
        }
        if (sha == null) throw new IOException(label(p) + " " + version + " 的 checksums.txt 里没有 " + archive);
        return new Release(p, version, archive, sha);
    }

    static boolean newer(String latest, String cur) {
        if (latest == null || latest.isEmpty()) return false;
        if (cur == null || cur.isEmpty()) return true;
        String[] a = latest.replaceAll("[^0-9.]", "").split("\\."), b = cur.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length && !a[i].isEmpty() ? Long.parseLong(a[i]) : 0;
            long y = i < b.length && !b[i].isEmpty() ? Long.parseLong(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    static File prev(Context c, Installer.Pkg p) { return new File(Stack.dir(c, "bin"), p.name + ".prev"); }

    static boolean canRollback(Context c, Installer.Pkg p) { return prev(c, p).isFile(); }

    /** Download, verify, then swap the binary in (old one kept as .prev). Stops the stack; the caller restarts it. */
    static void install(Context context, Release r) throws IOException {
        Context c = context.getApplicationContext();
        String label = label(r.pkg) + " " + r.version;
        File part = new File(Stack.dir(c, "dl"), r.archive + ".part");
        stage = "下载 " + label;
        Installer.download(c, Installer.wrap(c, r.base() + r.archive), part);
        stage = "校验 SHA-256";
        String got = Installer.sha256(part);
        if (!got.equals(r.sha256)) {
            part.delete();
            throw new IOException(r.archive + " 校验失败，已删除");
        }
        stage = "解压";
        File out = Installer.bin(c, r.pkg);
        File fresh = new File(out.getParentFile(), r.pkg.name + ".new");
        Installer.extract(part, r.pkg.member, fresh);
        part.delete();
        if (!fresh.setExecutable(true, false)) Stack.appLog(c, "chmod +x failed for " + fresh);
        stage = "替换并重启";
        String cur = Prefs.installed(c, r.pkg.name);
        Stack.stop(c);
        File old = prev(c, r.pkg);
        old.delete();
        if (out.isFile() && !out.renameTo(old)) throw new IOException("无法保留旧版本");
        if (!fresh.renameTo(out)) {
            old.renameTo(out);
            throw new IOException("无法写入 " + out);
        }
        Prefs.setPrevVersion(c, r.pkg.name, cur);
        Prefs.setInstalled(c, r.pkg.name, r.version);
        Stack.appLog(c, "updated " + r.pkg.name + " " + cur + " -> " + r.version + " (previous kept for rollback)");
        stage = "";
    }

    /** Swap the .prev binary back. Stops the stack; the caller restarts it. Returns the version now installed or null. */
    static String rollback(Context context, Installer.Pkg p) {
        Context c = context.getApplicationContext();
        File old = prev(c, p);
        if (!old.isFile()) return null;
        Stack.stop(c);
        File cur = Installer.bin(c, p);
        File bad = new File(cur.getParentFile(), p.name + ".bad");
        bad.delete();
        cur.renameTo(bad);
        if (!old.renameTo(cur)) {
            bad.renameTo(cur);
            return null;
        }
        bad.delete();
        cur.setExecutable(true, false);
        String v = Prefs.prevVersion(c, p.name);
        if (v.isEmpty()) v = p.version;
        Prefs.setInstalled(c, p.name, v);
        Prefs.setPrevVersion(c, p.name, "");
        Stack.appLog(c, "rolled back " + p.name + " to " + v);
        return v;
    }
}
