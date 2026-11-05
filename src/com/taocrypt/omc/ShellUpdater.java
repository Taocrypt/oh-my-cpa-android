package com.taocrypt.omc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Updates the Android shell (this app) from the project's GitHub releases: API first, the
 * /releases/latest redirect when the API is rate limited. The APK is checked against the
 * release's SHA-256 digest when GitHub publishes one, then for package name and signer, and
 * handed to the system installer through a PackageInstaller session.
 */
final class ShellUpdater {
    static final String REPO = "Taocrypt/oh-my-cpa-android";
    static final String HOME = "https://github.com/" + REPO;
    static final String RELEASES = HOME + "/releases";
    static final String SITE = "https://taocrypt.github.io/oh-my-cpa-android/";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ACTION_STATUS = "com.taocrypt.omc.SHELL_INSTALL_STATUS";
    private static final String NO_RELEASE = "项目还没有发布版本";

    static volatile boolean busy;
    /** last progress line of a running shell update, for the update page to show after a rebuild */
    static volatile String status = "";

    static final class Info {
        final String version, tag, apkUrl, sha256, notes, page;
        final long size;

        Info(String version, String tag, String apkUrl, String sha256, long size, String notes, String page) {
            this.version = version;
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
            this.page = page;
        }
    }

    interface Progress { void on(String stage, int percent); }

    interface Result { void done(String message); }

    private ShellUpdater() {}

    static String current(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static String assetName(String version) { return "oh-my-cpa-" + version + ".apk"; }

    private static HttpURLConnection open(String url, boolean follow) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(follow);
        conn.setRequestProperty("User-Agent", "oh-my-cpa-android/" + MainActivity.APP_VERSION);
        return conn;
    }

    private static String strip(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** The newest release. Blocking. */
    static Info latest(Context c) throws IOException {
        IOException apiError;
        try {
            HttpURLConnection conn = open(API, true);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code == 404) throw new IOException(NO_RELEASE);
            if (code != 200) throw new IOException("GitHub API HTTP " + code);
            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            String tag = o.optString("tag_name", "");
            if (tag.isEmpty()) throw new IOException("发布信息不完整");
            String want = assetName(strip(tag));
            String apk = "", sha = "";
            long size = -1;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                // prefer oh-my-cpa-<version>.apk, else the first .apk
                for (int pass = 0; pass < 2 && apk.isEmpty(); pass++) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        boolean match = pass == 0 ? name.equals(want) : name.toLowerCase(Locale.ROOT).endsWith(".apk");
                        if (!match) continue;
                        apk = a.optString("browser_download_url", "");
                        size = a.optLong("size", -1);
                        String dg = a.optString("digest", "");
                        if (dg.startsWith("sha256:")) sha = dg.substring(7).toLowerCase(Locale.ROOT);
                        break;
                    }
                }
            }
            if (apk.isEmpty()) throw new IOException("最新发布 " + tag + " 里没有 APK 文件");
            return new Info(strip(tag), tag, apk, sha, size, o.optString("body", ""), o.optString("html_url", RELEASES));
        } catch (IOException e) {
            if (NO_RELEASE.equals(e.getMessage())) throw e;
            apiError = e;
        } catch (Exception e) {
            apiError = new IOException(e.getMessage());
        }
        // fallback: read the tag from github.com/…/releases/latest's redirect
        HttpURLConnection conn = open(RELEASES + "/latest", false);
        int code;
        String loc;
        try {
            code = conn.getResponseCode();
            loc = conn.getHeaderField("Location");
        } finally {
            conn.disconnect();
        }
        if (code / 100 != 3 || loc == null) throw apiError;
        int i = loc.indexOf("/releases/tag/");
        if (i < 0) throw new IOException(NO_RELEASE);
        String tag = java.net.URLDecoder.decode(loc.substring(i + "/releases/tag/".length()), "UTF-8");
        String ver = strip(tag);
        return new Info(ver, tag, RELEASES + "/download/" + tag + "/" + assetName(ver), "", -1, notesFromPage(loc), loc);
    }

    /** Release notes from the tag's HTML page, used when the API is rate limited. Best effort. */
    private static String notesFromPage(String page) {
        try {
            HttpURLConnection conn = open(page, true);
            if (conn.getResponseCode() != 200) return "";
            String html = readAll(conn.getInputStream());
            int i = html.indexOf("class=\"markdown-body");
            if (i < 0) return "";
            i = html.indexOf('>', i) + 1;
            int end = html.indexOf("</div>", i);
            if (end < 0) return "";
            String body = html.substring(i, end)
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("(?i)</(p|li|h[1-6])>", "\n")
                    .replaceAll("(?i)<li[^>]*>", "• ")
                    .replaceAll("<[^>]+>", "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
            return body.replaceAll("\n{3,}", "\n\n").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Markdown release body as plain text for the dialog. */
    static String plainNotes(String md) {
        if (md == null) return "";
        String t = md.replace("\r", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Downloads the APK (through the mirror when set) and checks SHA-256, package name and signer. Blocking. */
    static File download(Context ctx, Info r, Progress ui) throws IOException {
        final Context c = ctx.getApplicationContext();
        File dir = Storage.shellUpdateDir(c);
        Storage.delete(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, assetName(r.version));
        final String title = "正在下载外壳 " + r.version;
        Progress p = (st, pc) -> {
            status = pc >= 0 ? st + "  " + pc + "%" : st;
            if (ui != null) ui.on(st, pc);
            UpdateNotifier.progress(c, UpdateNotifier.ID_SHELL, pc >= 0 ? title : st, pc >= 0 ? "可以离开这个页面，下载会继续" : "马上就好", pc);
        };
        p.on(title, 0);
        HttpURLConnection conn = open(Installer.wrap(c, r.apkUrl), true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("下载失败 HTTP " + code);
            long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : r.size;
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException(e);
            }
            try (InputStream in = conn.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(buf)) >= 0) {
                    o.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    int pc = total > 0 ? (int) Math.min(100, got * 100 / total) : -1;
                    if (pc != last) {
                        last = pc;
                        p.on(title, pc);
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            if (!r.sha256.isEmpty() && !r.sha256.equalsIgnoreCase(sb.toString())) {
                Storage.delete(out);
                throw new IOException("SHA-256 不匹配，已丢弃下载的文件");
            }
        } finally {
            conn.disconnect();
        }
        p.on("正在校验签名", -1);
        PackageManager pm = c.getPackageManager();
        PackageInfo mine, theirs;
        try {
            mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
        theirs = pm.getPackageArchiveInfo(out.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (theirs == null) {
            Storage.delete(out);
            throw new IOException("下载的文件不是有效的 APK");
        }
        if (!c.getPackageName().equals(theirs.packageName)) {
            Storage.delete(out);
            throw new IOException("包名不一致：" + theirs.packageName);
        }
        if (!sameSigner(mine, theirs)) {
            Storage.delete(out);
            throw new IOException("签名和当前安装的不一致，不能覆盖安装");
        }
        return out;
    }

    private static boolean sameSigner(PackageInfo a, PackageInfo b) {
        if (a.signingInfo == null || b.signingInfo == null) return false;
        Signature[] x = a.signingInfo.getApkContentsSigners();
        Signature[] y = b.signingInfo.getApkContentsSigners();
        if (x == null || y == null || x.length == 0) return false;
        for (Signature s : y) for (Signature t : x) if (Arrays.equals(s.toByteArray(), t.toByteArray())) return true;
        if (b.signingInfo.hasPastSigningCertificates()) {
            Signature[] h = b.signingInfo.getSigningCertificateHistory();
            if (h != null) for (Signature s : h) for (Signature t : x) if (Arrays.equals(s.toByteArray(), t.toByteArray())) return true;
        }
        return false;
    }

    static String versionOf(Context c, File apk) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi != null && pi.versionName != null) return pi.versionName;
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Hands the APK to the system installer; the user confirms on the system screen. */
    @SuppressWarnings("deprecation")
    static void install(Context a, File apk, Result result) throws IOException {
        final Context app = a.getApplicationContext();
        final String ver = versionOf(app, apk);
        status = "正在交给系统安装器";
        UpdateNotifier.progress(app, UpdateNotifier.ID_SHELL, "正在安装外壳 " + ver, "正在交给系统安装器", -1);
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            // the notification stays even when the screen below is blocked (app in background) or dismissed
                            UpdateNotifier.shellConfirm(ctx, ver, confirm);
                            status = "请在系统界面确认安装";
                            try {
                                ctx.startActivity(confirm);
                            } catch (RuntimeException e) {
                                result.done("请在通知栏点「安装」继续");
                            }
                        }
                        return;
                    }
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (RuntimeException ignored) {
                    }
                    busy = false;
                    UpdateNotifier.markShellPending(ctx, false);
                    if (st == PackageInstaller.STATUS_SUCCESS) {
                        status = "外壳已更新";
                        UpdateNotifier.finish(ctx, UpdateNotifier.ID_SHELL, "外壳已更新到 " + ver, "点按打开 Oh My CPA", null);
                        result.done("外壳已更新");
                    } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                        status = "已取消安装，可以在通知栏点按重新安装";
                        UpdateNotifier.shellRetry(ctx, "外壳 " + ver + " 安装已取消", "下载好的安装包还在", apk);
                        result.done(status);
                    } else {
                        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        String why = msg == null ? "代码 " + st : msg;
                        status = "安装失败：" + why;
                        UpdateNotifier.shellRetry(ctx, "外壳 " + ver + " 安装失败", why, apk);
                        result.done(status);
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, new IntentFilter(ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(rx, new IntentFilter(ACTION_STATUS));
            Intent cb = new Intent(ACTION_STATUS).setPackage(app.getPackageName());
            // targetSdk 28: PendingIntents are mutable by default, which the installer needs to fill in the status
            PendingIntent pend = PendingIntent.getBroadcast(app, id, cb, PendingIntent.FLAG_UPDATE_CURRENT);
            UpdateNotifier.markShellPending(app, true);
            s.commit(pend.getIntentSender());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        return b.toString(StandardCharsets.UTF_8.name());
    }
}
