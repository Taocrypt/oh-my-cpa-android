package com.taocrypt.omc;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plugin mode, driven by the console's own "启用插件系统" switch (CPA config plugins.enabled).
 *
 * CPA plugins are native c-shared .so files that CPA dlopen()s, which the static no-plugin
 * build cannot do. When the switch is on, CPA runs as its glibc plugin build instead, started
 * natively through the glibc dynamic loader bundled in the APK (Termux's Android-patched glibc;
 * no PRoot, no root): ld-linux-aarch64.so.1 --library-path files/glibc files/bin/cli-proxy-api-plugin.
 * When it is off, the static build runs as before. The plugin build is downloaded the first time
 * the switch is turned on, for the same CPA version as the static build, and checked against the
 * release's checksums.txt (and the pinned hash for the pinned version).
 */
final class Plugins {
    static final String BIN = "cli-proxy-api-plugin";
    static final String PINNED_VERSION = "8.0.51";
    static final String PINNED_SHA = "411aa08783c572bdb72a6ba0ffcc0a75155be27e75a1c5aef3b49d8b96da4d52";
    static final String GLIBC_VERSION = "2.44-0 (termux gpkg)";

    static volatile String stage = "";
    private static final Object LOCK = new Object();

    private Plugins() {}

    static File bin(Context c) { return new File(Stack.dir(c, "bin"), BIN); }
    static File glibcDir(Context c) { return Stack.dir(c, "glibc"); }
    static File loader(Context c) { return new File(glibcDir(c), "ld-linux-aarch64.so.1"); }

    static String archive(String v) { return "CLIProxyAPI_" + v + "_linux_aarch64.tar.gz"; }

    /** plugins.enabled in CPA's config file: the console's switch, as CPA wrote it */
    static boolean enabledInConfig(Context c) {
        File f = Stack.cpaConfig(c);
        if (!f.isFile()) return false;
        return enabledIn(Stack.read(f, 1 << 20));
    }

    static boolean enabledIn(String text) {
        String[] lines = text.split("\n");
        boolean in = false;
        int indent = -1;
        for (String l : lines) {
            if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
            boolean top = !Character.isWhitespace(l.charAt(0));
            if (top) {
                if (in) return false;
                in = l.matches("^plugins:\\s*(#.*)?$");
                continue;
            }
            if (!in) continue;
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') n++;
            if (indent < 0) indent = n;
            if (n != indent) continue;
            String t = l.trim();
            if (t.startsWith("enabled:")) {
                String v = t.substring(8).replaceAll("#.*$", "").trim().replace("\"", "").replace("'", "");
                return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
            }
        }
        return false;
    }

    /** the plugin build matches the installed static CPA version and the glibc runtime is unpacked */
    static boolean ready(Context c) {
        String want = Prefs.installed(c, Installer.CPA.name);
        return bin(c).isFile() && !want.isEmpty() && want.equals(Prefs.installed(c, BIN)) && glibcReady(c);
    }

    private static boolean glibcReady(Context c) {
        return loader(c).isFile() && GLIBC_VERSION.equals(Prefs.installed(c, "glibc"));
    }

    /** CPA's command line in plugin mode */
    static void command(Context c, java.util.List<String> cmd, Map<String, String> env) {
        File g = glibcDir(c);
        cmd.add(loader(c).getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(g.getAbsolutePath());
        cmd.add(bin(c).getAbsolutePath());
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        // pure-Go DNS (the HTTPS_PROXY / DnsProxy path still resolves names through Android)
        env.put("GODEBUG", "netdns=go");
    }

    /** Blocking. Unpacks glibc and downloads the plugin build when missing. */
    static void ensure(Context context) throws IOException {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            if (ready(c)) return;
            try {
                if (!glibcReady(c)) unpackGlibc(c);
                String v = Prefs.installed(c, Installer.CPA.name);
                if (v.isEmpty()) throw new IOException("CLIProxyAPI 还没有安装");
                if (!(bin(c).isFile() && v.equals(Prefs.installed(c, BIN)))) download(c, v);
            } finally {
                stage = "";
            }
        }
    }

    private static void unpackGlibc(Context c) throws IOException {
        stage = "解压 glibc 运行库";
        AssetManager am = c.getAssets();
        Map<String, String> sums = new HashMap<>();
        try (InputStream in = am.open("glibc/SHA256SUMS")) {
            String s = new String(readAll(in), "UTF-8");
            for (String line : s.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2) sums.put(f[1], f[0].toLowerCase(Locale.ROOT));
            }
        }
        File dir = glibcDir(c);
        for (Map.Entry<String, String> e : sums.entrySet()) {
            File out = new File(dir, e.getKey());
            File tmp = new File(dir, e.getKey() + ".new");
            try (InputStream in = am.open("glibc/" + e.getKey()); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) >= 0) o.write(b, 0, n);
            }
            String got = Installer.sha256(tmp);
            if (!got.equals(e.getValue())) {
                tmp.delete();
                throw new IOException("glibc 文件校验失败：" + e.getKey());
            }
            tmp.setExecutable(true, false);
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("无法写入 " + out);
        }
        Prefs.setInstalled(c, "glibc", GLIBC_VERSION);
        Stack.appLog(c, "glibc " + GLIBC_VERSION + " unpacked -> " + dir);
    }

    private static void download(Context c, String v) throws IOException {
        String base = "https://github.com/router-for-me/CLIProxyAPI/releases/download/v" + v + "/";
        String file = archive(v);
        stage = "读取校验文件";
        String sha = null;
        try {
            String sums = new String(Installer.get(Installer.wrap(c, base + "checksums.txt"), 1 << 20), "UTF-8");
            for (String line : sums.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2 && f[1].replaceFirst("^\\*", "").equals(file)) sha = f[0].toLowerCase(Locale.ROOT);
            }
        } catch (IOException e) {
            if (!PINNED_VERSION.equals(v)) throw new IOException("读取 checksums.txt 失败：" + e.getMessage());
            Stack.appLog(c, "checksums.txt unavailable (" + e.getMessage() + "); using the pinned SHA-256");
        }
        if (PINNED_VERSION.equals(v)) {
            if (sha != null && !sha.equals(PINNED_SHA)) throw new IOException("checksums.txt 与内置校验值不一致：" + file);
            sha = PINNED_SHA;
        }
        if (sha == null) throw new IOException("checksums.txt 里没有 " + file);
        stage = "下载 CLIProxyAPI " + v + "（插件版）";
        File part = new File(Stack.dir(c, "dl"), file + ".part");
        Installer.download(c, Installer.wrap(c, base + file), part);
        stage = "校验 SHA-256";
        String got = Installer.sha256(part);
        if (!got.equals(sha)) {
            part.delete();
            throw new IOException(file + " 校验失败，已删除");
        }
        stage = "解压";
        File out = bin(c);
        File tmp = new File(out.getParentFile(), BIN + ".new");
        Installer.extract(part, "cli-proxy-api", tmp);
        part.delete();
        tmp.setExecutable(true, false);
        out.delete();
        if (!tmp.renameTo(out)) throw new IOException("无法写入 " + out);
        Prefs.setInstalled(c, BIN, v);
        Stack.appLog(c, file + " sha256 ok; installed plugin build " + v + " -> " + out);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
