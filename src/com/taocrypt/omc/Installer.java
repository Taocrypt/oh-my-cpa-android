package com.taocrypt.omc;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The APK carries neither binary. On first launch this downloads the pinned linux/arm64
 * releases from GitHub, checks each archive's SHA-256 against the value pinned here and
 * against the release's own checksums.txt, then unpacks the one executable into files/bin.
 */
public final class Installer {

    public static final class Pkg {
        public final String name, version, base, archive, sha256, member;
        Pkg(String name, String version, String base, String archive, String sha256, String member) {
            this.name = name; this.version = version; this.base = base; this.archive = archive;
            this.sha256 = sha256; this.member = member;
        }
        String url(String file) { return base + file; }
    }

    // CPA: the no-plugin build is the static one; the plugin build links glibc and cannot run on Android.
    public static final Pkg CPA = new Pkg("cli-proxy-api", "8.0.51",
            "https://github.com/router-for-me/CLIProxyAPI/releases/download/v8.0.51/",
            "CLIProxyAPI_8.0.51_linux_aarch64_no-plugin.tar.gz",
            "6aaeeb3628e3eec2c35bc2bc603dcdfb4ccb1875420c46878e63050543924d54", "cli-proxy-api");
    public static final Pkg OMC = new Pkg("oh-my-cpa", "0.3.19",
            "https://github.com/WizisCool/oh-my-cpa/releases/download/v0.3.19/",
            "oh-my-cpa_0.3.19_linux_arm64.tar.gz",
            "63398db29bf4894fb67ed1970ca1422ed812a2173e113f105e2791fcaca03980", "oh-my-cpa");
    public static final Pkg[] ALL = {CPA, OMC};

    public static final String MIRROR = "https://ghfast.top/";

    // shared state the setup screen polls
    public static volatile String stage = "";
    public static volatile int percent = -1;
    public static volatile String detail = "";
    public static volatile String error = "";
    public static volatile boolean busy;
    private static final Object LOCK = new Object();

    private Installer() {}

    public static File bin(Context c, Pkg p) {
        return new File(Stack.dir(c, "bin"), p.name);
    }

    public static boolean installed(Context c) {
        for (Pkg p : ALL) {
            // any installed version counts: an update from the component sheet replaces the pinned one
            if (!bin(c, p).isFile() || Prefs.installed(c, p.name).isEmpty()) return false;
        }
        return true;
    }

    /** Blocking. Returns true when both binaries are in place. */
    public static boolean ensure(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            if (installed(c)) return true;
            busy = true;
            error = "";
            try {
                int i = 0;
                for (Pkg p : ALL) {
                    i++;
                    if (bin(c, p).isFile() && !Prefs.installed(c, p.name).isEmpty()) continue;
                    install(c, p, i, ALL.length);
                }
                stage = "下载完成";
                percent = 100;
                detail = "";
                return true;
            } catch (Exception e) {
                error = String.valueOf(e.getMessage() == null ? e : e.getMessage());
                Stack.appLog(c, "install failed: " + e);
                return false;
            } finally {
                busy = false;
            }
        }
    }

    static String wrap(Context c, String url) {
        String m = Prefs.mirrorPrefix(c);
        return m.isEmpty() || !url.startsWith("https://github.com/") ? url : m + url;
    }

    private static void install(Context c, Pkg p, int idx, int total) throws IOException {
        String label = "(" + idx + "/" + total + ") " + p.name + " " + p.version;
        File dl = Stack.dir(c, "dl");
        // 1. checksums.txt (small; the pinned hash still decides if it cannot be read)
        stage = "读取校验文件 " + label;
        percent = -1;
        detail = "";
        String listed = null;
        try {
            String sums = new String(get(wrap(c, p.url("checksums.txt")), 1 << 20), "UTF-8");
            for (String line : sums.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2 && f[1].replaceFirst("^\\*", "").equals(p.archive)) listed = f[0].toLowerCase(Locale.ROOT);
            }
            if (listed == null) throw new IOException("checksums.txt 里没有 " + p.archive);
            if (!listed.equals(p.sha256)) throw new IOException("checksums.txt 与内置校验值不一致：" + p.archive);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("不一致")) throw e;
            Stack.appLog(c, "checksums.txt for " + p.name + " unavailable (" + e.getMessage() + "); using the pinned SHA-256");
        }
        // 2. archive, resumable
        stage = "下载 " + label;
        File part = new File(dl, p.archive + ".part");
        download(c, wrap(c, p.url(p.archive)), part);
        // 3. verify
        stage = "校验 SHA-256 " + label;
        percent = -1;
        detail = "";
        String got = sha256(part);
        if (!got.equals(p.sha256)) {
            part.delete();
            throw new IOException(p.archive + " 校验失败（得到 " + got.substring(0, 12) + "…），已删除，请重试");
        }
        Stack.appLog(c, p.archive + " sha256 ok " + got);
        // 4. unpack the executable
        stage = "解压 " + label;
        File out = bin(c, p);
        File tmp = new File(out.getParentFile(), p.name + ".new");
        extract(part, p.member, tmp);
        if (!tmp.setExecutable(true, false)) Stack.appLog(c, "chmod +x failed for " + tmp);
        out.delete();
        if (!tmp.renameTo(out)) throw new IOException("无法写入 " + out);
        part.delete();
        Prefs.setInstalled(c, p.name, p.version);
        Stack.appLog(c, "installed " + p.name + " " + p.version + " -> " + out);
    }

    static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "oh-my-cpa-android/" + MainActivity.APP_VERSION);
        if (from > 0) h.setRequestProperty("Range", "bytes=" + from + "-");
        return h;
    }

    static byte[] get(String url, int max) throws IOException {
        HttpURLConnection h = open(url, 0);
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) >= 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > max) throw new IOException("too large: " + url);
                }
            }
            return buf.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    static void download(Context c, String url, File part) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection h = open(url, have);
            try {
                int code = h.getResponseCode();
                boolean append = code == 206 && have > 0;
                if (code == 416 && have > 0) { // already complete (or junk): let the hash decide
                    return;
                }
                if (code != 200 && code != 206) throw new IOException("HTTP " + code + "（" + url + "）");
                if (!append) have = 0;
                long len = h.getContentLengthLong();
                long total = len > 0 ? len + have : -1;
                long done = have;
                long t0 = System.currentTimeMillis(), last0 = 0;
                try (InputStream in = new BufferedInputStream(h.getInputStream(), 65536);
                     OutputStream out = new FileOutputStream(part, append)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        out.write(b, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last0 > 250) {
                            last0 = now;
                            percent = total > 0 ? (int) (done * 100 / total) : -1;
                            double secs = Math.max(0.5, (now - t0) / 1000.0);
                            detail = mb(done) + (total > 0 ? " / " + mb(total) : "") + "  ·  "
                                    + String.format(Locale.US, "%.1f MB/s", (done - have) / secs / 1048576.0);
                        }
                    }
                }
                if (total > 0 && done < total) throw new IOException("下载中断（" + mb(done) + " / " + mb(total) + "）");
                percent = 100;
                return;
            } catch (IOException e) {
                last = e;
                Stack.appLog(c, "download attempt " + attempt + " failed: " + e);
                detail = "网络中断，正在续传（第 " + attempt + " 次）…";
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { throw new IOException("interrupted"); }
            } finally {
                h.disconnect();
            }
        }
        throw last != null ? last : new IOException("download failed");
    }

    static String mb(long b) {
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) >= 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Minimal ustar/GNU reader: copies the regular file whose base name is {@code member}. */
    static void extract(File tgz, String member, File to) throws IOException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(new FileInputStream(tgz), 65536), 65536)) {
            byte[] hdr = new byte[512];
            String longName = null;
            while (true) {
                if (!readFully(in, hdr)) break;
                boolean zero = true;
                for (byte x : hdr) if (x != 0) { zero = false; break; }
                if (zero) break;
                String name = longName != null ? longName : cstr(hdr, 0, 100);
                longName = null;
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty() && hdr[156] != 'L') name = prefix + "/" + name;
                long size = octal(hdr, 124, 12);
                char type = (char) hdr[156];
                long padded = (size + 511) / 512 * 512;
                if (type == 'L') {
                    byte[] nb = new byte[(int) padded];
                    if (!readFully(in, nb)) break;
                    longName = cstr(nb, 0, (int) size);
                    continue;
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                if ((type == '0' || type == 0) && base.equals(member)) {
                    try (OutputStream out = new FileOutputStream(to)) {
                        copy(in, out, size);
                        if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
                    }
                    return;
                }
                skip(in, padded);
                continue;
            }
        }
        throw new IOException("压缩包里没有 " + member);
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] b = new byte[65536];
        long left = size;
        while (left > 0) {
            int n = in.read(b, 0, (int) Math.min(b.length, left));
            if (n < 0) throw new IOException("truncated archive");
            out.write(b, 0, n);
            left -= n;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[65536];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) throw new IOException("truncated archive");
            n -= r;
        }
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        String s = cstr(b, off, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
}
