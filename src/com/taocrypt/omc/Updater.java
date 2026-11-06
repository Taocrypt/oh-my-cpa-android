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

    /**
     * The newest release that already has our archive and checksums.txt, with its verified checksum
     * entry. Blocking. Upstream publishes the release first and CI uploads assets minutes later, so a
     * brand-new tag without assets is skipped in favour of the previous complete one.
     */
    static Release latest(Context c, Installer.Pkg p) throws IOException {
        IOException apiErr;
        try {
            JSONArray list = new JSONArray(new String(Installer.get("https://api.github.com/repos/" + repo(p) + "/releases?per_page=10", 4 << 20), "UTF-8"));
            String skipped = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                String version = o.getString("tag_name").replaceFirst("^v", ""), archive = null;
                boolean sums = false;
                JSONArray a = o.optJSONArray("assets");
                if (a != null) for (int j = 0; j < a.length(); j++) {
                    String n = a.getJSONObject(j).optString("name");
                    if ("checksums.txt".equals(n)) sums = true;
                    else if (archive == null && wanted(p, n)) archive = n;
                }
                if (archive == null || !sums) {
                    if (skipped == null) skipped = version;
                    continue;
                }
                if (skipped != null) Stack.appLog(c, label(p) + " v" + skipped + " has no assets yet; using v" + version);
                return verified(c, p, version, archive);
            }
            throw new IOException("最近的 " + label(p) + " 版本都没有 linux arm64 安装包");
        } catch (IOException e) {
            apiErr = e;
        } catch (Exception e) {
            apiErr = new IOException(e.getMessage());
        }
        // api.github.com is often unreachable or rate limited: read the tag from the release page redirect
        String version = null;
        HttpURLConnection h = Installer.open("https://github.com/" + repo(p) + "/releases/latest", 0);
        h.setInstanceFollowRedirects(false);
        try {
            String loc = h.getHeaderField("Location");
            if (loc != null && loc.contains("/tag/")) version = loc.substring(loc.lastIndexOf('/') + 1).replaceFirst("^v", "").trim();
        } finally {
            h.disconnect();
        }
        if (version == null) throw new IOException("查询 " + label(p) + " 最新版本失败：" + apiErr.getMessage());
        try {
            return verified(c, p, version, guessArchive(p, version));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404"))
                throw new IOException(label(p) + " v" + version + " 刚发布，安装包还在上传，过几分钟再检查");
            throw e;
        }
    }

    private static Release verified(Context c, Installer.Pkg p, String version, String archive) throws IOException {
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

    /**
     * One kernel update in flight. It lives here, not in the update page, so the page can be
     * rebuilt (onResume, theme change, navigating away) and still show the download, and so
     * CPA and oh-my-cpa can update at the same time, each with its own progress and notification.
     */
    static final class Job {
        final Release r;
        final Installer.Progress pr = new Installer.Progress();
        volatile String stage = "准备下载";
        volatile boolean running = true, ok;
        volatile String error = "";
        volatile boolean swapped;
        Job(Release r) { this.r = r; }

        /** one line for the page: stage, and while downloading the percent and speed */
        String line() {
            String s = stage;
            if (s.startsWith("下载")) s += "  " + (pr.percent >= 0 ? pr.percent + "%" : "") + "  " + pr.detail;
            return s;
        }
    }

    private static final java.util.Map<String, Job> JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object SWAP = new Object();
    /** set by the activity while it is alive; told on the main thread when a job ends */
    static volatile Runnable onJobEnded;

    static Job job(Installer.Pkg p) { return JOBS.get(p.name); }

    static boolean anyRunning() {
        for (Job j : JOBS.values()) if (j.running) return true;
        return false;
    }

    /** start updating r.pkg in the background (or return the job already running for it) */
    static synchronized Job start(Context context, Release r) {
        Job cur = JOBS.get(r.pkg.name);
        if (cur != null && cur.running) return cur;
        final Context c = context.getApplicationContext();
        final Job j = new Job(r);
        JOBS.put(r.pkg.name, j);
        final int id = UpdateNotifier.kernelId(r.pkg);
        final String title = "正在更新 " + label(r.pkg) + " " + r.version;
        UpdateNotifier.progress(c, id, title, "准备下载", -1);
        Thread notes = new Thread(() -> {
            String last = "";
            while (j.running) {
                boolean dl = j.stage.startsWith("下载");
                String key = j.stage + "|" + (dl ? j.pr.percent : -1);
                if (!key.equals(last) && j.running) {
                    last = key;
                    UpdateNotifier.progress(c, id, title, j.stage, dl ? j.pr.percent : -1);
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "omc-upd-note-" + r.pkg.name);
        notes.setDaemon(true);
        new Thread(() -> {
            try {
                install(c, j);
                j.ok = true;
                Prefs.setUpdatesSeen(c, Prefs.updatesSeen(c).replace(r.pkg.name + "=" + r.version, "")
                        .replaceAll("^,|,$", "").replace(",,", ","));
            } catch (IOException | RuntimeException e) {
                j.error = String.valueOf(e.getMessage());
                Stack.appLog(c, "update " + r.pkg.name + " " + r.version + " failed: " + e);
            }
            boolean restart;
            synchronized (Updater.class) {
                j.running = false;
                // restart once, when the last update in flight is done
                restart = !anyRunning() && anySwapped();
                if (restart) for (Job o : JOBS.values()) o.swapped = false;
            }
            notes.interrupt();
            if (j.ok) {
                UpdateNotifier.finish(c, id, label(r.pkg) + " 已更新到 v" + r.version,
                        restart ? "服务正在重启" : "等另一个内核更新完成后一起重启", null);
            } else {
                UpdateNotifier.finish(c, id, label(r.pkg) + " v" + r.version + " 更新失败", j.error + "\n点按打开更新页重试", null);
            }
            if (restart) {
                try {
                    OmcService.restart(c);
                } catch (RuntimeException e) {
                    try { c.startService(new android.content.Intent(c, OmcService.class).setAction(OmcService.ACTION_RESTART)); }
                    catch (RuntimeException ignored) { }
                }
            }
            Runnable cb = onJobEnded;
            if (cb != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
        }, "omc-upd-" + r.pkg.name).start();
        notes.start();
        return j;
    }

    private static boolean anySwapped() {
        for (Job o : JOBS.values()) if (o.swapped) return true;
        return false;
    }

    /** Download, verify, then swap the binary in (old one kept as .prev). The caller restarts the stack. */
    private static void install(Context c, Job j) throws IOException {
        Release r = j.r;
        String label = label(r.pkg) + " " + r.version;
        File part = new File(Stack.dir(c, "dl"), r.archive + ".part");
        j.stage = "下载 " + label;
        Installer.download(c, Installer.wrap(c, r.base() + r.archive), part, j.pr);
        j.stage = "校验 SHA-256";
        String got = Installer.sha256(part);
        if (!got.equals(r.sha256)) {
            part.delete();
            throw new IOException(r.archive + " 校验失败，已删除");
        }
        j.stage = "解压";
        File out = Installer.bin(c, r.pkg);
        File fresh = new File(out.getParentFile(), r.pkg.name + ".new");
        Installer.extract(part, r.pkg.member, fresh);
        part.delete();
        if (!fresh.setExecutable(true, false)) Stack.appLog(c, "chmod +x failed for " + fresh);
        j.stage = "替换";
        synchronized (SWAP) {
            String cur = Prefs.installed(c, r.pkg.name);
            // renaming over a running binary is fine on Linux; the restart afterwards picks up the new one
            File old = prev(c, r.pkg);
            old.delete();
            if (out.isFile() && !out.renameTo(old)) throw new IOException("无法保留旧版本");
            if (!fresh.renameTo(out)) {
                old.renameTo(out);
                throw new IOException("无法写入 " + out);
            }
            Prefs.setPrevVersion(c, r.pkg.name, cur);
            Prefs.setInstalled(c, r.pkg.name, r.version);
            j.swapped = true;
            Stack.appLog(c, "updated " + r.pkg.name + " " + cur + " -> " + r.version + " (previous kept for rollback)");
        }
        j.stage = "重启服务";
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
