package com.taocrypt.omc;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Remote access (ported from the magpie shell), run natively (no PRoot/root):
 *  - Cloudflare quick tunnel: the shell asks api.trycloudflare.com for a tunnel itself, resolves the
 *    edge with Android's DNS and hands both to cloudflared, so cloudflared needs no DNS of its own.
 *  - Tailscale in userspace-networking mode (no VPN slot); incoming tailnet connections land on 127.0.0.1.
 *  - mDNS: advertises the console and the CPA API as _http._tcp on the LAN with NsdManager.
 * The quick tunnel points at the oh-my-cpa console; with "cfApi" on, an ingress file sends
 * /v1 and /v1beta to the CPA API on the same hostname. Tailscale's userspace netstack forwards
 * every port to 127.0.0.1, so both the console and the API are reachable over the tailnet.
 */
final class Tunnels {
    interface Progress { void on(String stage, int percent); }

    private static final Object LOCK = new Object();
    private static Process cf, tsd;
    private static volatile String cfUrl = "", cfState = "";
    private static volatile boolean cfWanted, tsWanted;
    private static volatile String tsIp = "";
    private static NsdManager.RegistrationListener[] mdns;

    private Tunnels() {}

    // ------------------------------------------------------------------ files

    static File cloudflared(Context c) { return new File(Stack.dir(c, "bin"), "cloudflared"); }
    static File tailscaled(Context c) { return new File(Stack.dir(c, "bin"), "tailscaled"); }
    static File tailscale(Context c) { return new File(Stack.dir(c, "bin"), "tailscale"); }
    static File tsSocket(Context c) { return new File(Stack.dir(c, "tailscale"), "tailscaled.sock"); }
    static File log(Context c, String name) { return new File(Stack.dir(c, "logs"), name + ".log"); }

    static boolean hasCloudflared(Context c) { return cloudflared(c).isFile(); }
    static boolean hasTailscale(Context c) { return tailscaled(c).isFile() && tailscale(c).isFile(); }

    static void downloadCloudflared(Context c, Progress p) throws IOException {
        String url = Installer.wrap(c, "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64");
        File tmp = new File(Stack.dir(c, "bin"), "cloudflared.download");
        download(url, tmp, "cloudflared", p, null);
        tmp.setExecutable(true, false);
        if (!tmp.renameTo(cloudflared(c))) throw new IOException("could not move cloudflared into place");
        Stack.appLog(c, "installed cloudflared");
    }

    static void downloadTailscale(Context c, Progress p) throws IOException {
        report(p, "正在查询 Tailscale 最新版本…", -1);
        JSONObject j;
        try {
            j = new JSONObject(get("https://pkgs.tailscale.com/stable/?mode=json"));
        } catch (Exception e) {
            throw new IOException("查询 Tailscale 版本失败：" + e.getMessage());
        }
        String name = j.optJSONObject("Tarballs") == null ? "" : j.optJSONObject("Tarballs").optString("arm64");
        if (name.isEmpty()) throw new IOException("没有找到 arm64 版本");
        String url = "https://pkgs.tailscale.com/stable/" + name;
        String sha = "";
        try { sha = get(url + ".sha256").trim().split("\\s+")[0]; } catch (IOException ignored) { }
        File tgz = new File(Stack.dir(c, "tmp"), name);
        download(url, tgz, "Tailscale", p, sha);
        report(p, "正在解压 Tailscale…", 100);
        File dir = Stack.dir(c, "bin");
        boolean d = false, t = false;
        try (InputStream in = new GZIPInputStream(new FileInputStream(tgz), 65536)) {
            byte[] hdr = new byte[512];
            while (readFully(in, hdr)) {
                String n = new String(hdr, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
                if (n.isEmpty()) break;
                long size = Long.parseLong(new String(hdr, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim(), 8);
                String base = n.substring(n.lastIndexOf('/') + 1);
                boolean want = hdr[156] == '0' || hdr[156] == 0;
                OutputStream out = null;
                File tmp = null;
                if (want && ("tailscaled".equals(base) || "tailscale".equals(base))) {
                    tmp = new File(dir, base + ".download");
                    out = new FileOutputStream(tmp);
                }
                long left = size;
                byte[] b = new byte[65536];
                while (left > 0) {
                    int k = in.read(b, 0, (int) Math.min(b.length, left));
                    if (k < 0) throw new IOException("truncated archive");
                    if (out != null) out.write(b, 0, k);
                    left -= k;
                }
                long pad = (512 - size % 512) % 512;
                while (pad > 0) { long s = in.skip(pad); if (s <= 0) { if (in.read() < 0) break; s = 1; } pad -= s; }
                if (out != null) {
                    out.close();
                    tmp.setExecutable(true, false);
                    File dst = new File(dir, base);
                    if (!tmp.renameTo(dst)) throw new IOException("could not move " + base);
                    if ("tailscaled".equals(base)) d = true; else t = true;
                }
            }
        } finally {
            tgz.delete();
        }
        if (!d || !t) throw new IOException("压缩包里没有 tailscale / tailscaled");
        Stack.appLog(c, "installed " + name);
    }

    // ------------------------------------------------------------------ component versions

    /** Installed version of "cloudflared" or "tailscale", or "" when not installed / unreadable. */
    static String installedVersion(Context c, String which) {
        boolean cfw = "cloudflared".equals(which);
        if (cfw ? !hasCloudflared(c) : !hasTailscale(c)) return "";
        File bin = cfw ? cloudflared(c) : tailscale(c);
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), cfw ? "--version" : "version");
            pb.redirectErrorStream(true);
            Stack.common(c, pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            String out = read(p.getInputStream());
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(out);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Latest published version of "cloudflared" or "tailscale". */
    static String latestVersion(Context c, String which) throws IOException {
        if ("cloudflared".equals(which)) {
            HttpURLConnection conn = open("https://github.com/cloudflare/cloudflared/releases/latest");
            conn.setInstanceFollowRedirects(false);
            try {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.contains("/tag/")) return loc.substring(loc.lastIndexOf('/') + 1).trim();
            } finally {
                conn.disconnect();
            }
            try {
                return new JSONObject(get("https://api.github.com/repos/cloudflare/cloudflared/releases/latest")).getString("tag_name");
            } catch (org.json.JSONException e) {
                throw new IOException("无法读取 cloudflared 最新版本");
            }
        }
        try {
            return new JSONObject(get("https://pkgs.tailscale.com/stable/?mode=json")).getString("TarballsVersion");
        } catch (org.json.JSONException e) {
            throw new IOException("无法读取 Tailscale 最新版本");
        }
    }

    static boolean newer(String latest, String cur) {
        if (cur.isEmpty() || latest.isEmpty()) return false;
        String[] a = latest.replaceAll("[^0-9.]", "").split("\\."), b = cur.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length && !a[i].isEmpty() ? Long.parseLong(a[i]) : 0;
            long y = i < b.length && !b[i].isEmpty() ? Long.parseLong(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** Replace an installed component with the latest build, restarting it if it was running. Blocking. */
    static void updateComponent(Context c, String which, Progress p) throws IOException {
        if ("cloudflared".equals(which)) {
            boolean was = cloudflareRunning();
            if (was) stopCloudflare(false);
            downloadCloudflared(c, p);
            if (was) startCloudflare(c);
        } else {
            boolean was = tailscaleRunning();
            if (was) stopTailscale(false);
            downloadTailscale(c, p);
            if (was) startTailscale(c);
        }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int k = in.read(b, off, b.length - off);
            if (k < 0) return false;
            off += k;
        }
        return true;
    }

    private static void download(String url, File to, String label, Progress p, String sha) throws IOException {
        report(p, "正在下载 " + label + "…", 0);
        HttpURLConnection conn = open(url);
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + url);
            long total = conn.getContentLengthLong(), got = 0;
            int last = -1;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) >= 0) {
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                    got += n;
                    int pc = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pc != last) { last = pc; report(p, "正在下载 " + label + "…", pc); }
                }
                out.getFD().sync();
            }
        } finally {
            conn.disconnect();
        }
        if (sha != null && !sha.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            if (!sha.equalsIgnoreCase(sb.toString())) {
                to.delete();
                throw new IOException(label + " SHA-256 校验失败");
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "oh-my-cpa-android/" + MainActivity.APP_VERSION);
        return conn;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            return read(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) >= 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void report(Progress p, String s, int pc) {
        if (p != null) p.on(s, pc);
    }

    // ------------------------------------------------------------------ cloudflare

    static String cloudflareUrl() { return cfUrl; }
    static String cloudflareState() { return cfState; }
    static boolean cloudflareRunning() { synchronized (LOCK) { return cf != null && cf.isAlive(); } }

    /** Blocking. Creates a fresh quick tunnel and starts cloudflared for it. */
    static void startCloudflare(Context ctx) throws IOException {
        final Context c = ctx.getApplicationContext();
        stopCloudflare(false);
        cfWanted = true;
        cfUrl = "";
        cfState = "正在申请临时域名…";
        HttpURLConnection conn = open("https://api.trycloudflare.com/tunnel");
        JSONObject r;
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "cloudflared/2026.10.6");
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("trycloudflare HTTP " + code);
            r = new JSONObject(read(conn.getInputStream())).getJSONObject("result");
        } catch (org.json.JSONException e) {
            throw new IOException("trycloudflare 返回内容无法解析");
        } finally {
            conn.disconnect();
        }
        String id = r.optString("id"), host = r.optString("hostname");
        File dir = Stack.dir(c, "tunnel");
        File creds = new File(dir, "quick.json");
        try (FileOutputStream out = new FileOutputStream(creds)) {
            JSONObject j = new JSONObject();
            j.put("AccountTag", r.optString("account_tag"));
            j.put("TunnelSecret", r.optString("secret"));
            j.put("TunnelID", id);
            out.write(j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(cloudflared(c).getAbsolutePath());
        cmd.add("tunnel");
        cmd.add("--no-autoupdate");
        cmd.add("--protocol");
        cmd.add("http2");
        int edges = 0;
        for (String region : new String[]{"region1.v2.argotunnel.com", "region2.v2.argotunnel.com"}) {
            try {
                int k = 0;
                for (InetAddress a : InetAddress.getAllByName(region)) {
                    if (a instanceof Inet4Address && k < 2) {
                        cmd.add("--edge");
                        cmd.add(a.getHostAddress() + ":7844");
                        k++;
                        edges++;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (edges == 0) throw new IOException("无法解析 Cloudflare 边缘节点");
        cmd.add("--credentials-file");
        cmd.add(creds.getAbsolutePath());
        if (Prefs.cfApi(c)) {
            File cfg = new File(dir, "ingress.yml");
            String y = "tunnel: " + id + "\n"
                    + "credentials-file: " + creds.getAbsolutePath() + "\n"
                    + "ingress:\n"
                    + "  - path: \"^/v1(beta)?(/|$)\"\n"
                    + "    service: http://127.0.0.1:" + Prefs.cpaPort(c) + "\n"
                    + "  - service: http://127.0.0.1:" + Prefs.omcPort(c) + "\n";
            try (FileOutputStream out = new FileOutputStream(cfg)) {
                out.write(y.getBytes(StandardCharsets.UTF_8));
            }
            cmd.add("--config");
            cmd.add(cfg.getAbsolutePath());
        } else {
            cmd.add("--url");
            cmd.add("http://127.0.0.1:" + Prefs.omcPort(c));
        }
        cmd.add("--metrics");
        cmd.add("127.0.0.1:0");
        cmd.add("run");
        cmd.add(id);
        cloudflared(c).setExecutable(true, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Stack.dir(c, "home"));
        pb.redirectErrorStream(true);
        Stack.common(c, pb.environment());
        final String url = "https://" + host;
        cfState = "正在连接 Cloudflare…";
        Process p = pb.start();
        synchronized (LOCK) { cf = p; }
        Stack.appLog(c, "cloudflared started for " + url);
        follow(c, p, log(c, "cloudflared"), line -> {
            if (line.contains("Registered tunnel connection")) {
                cfUrl = url;
                cfState = "已连接";
            }
        }, () -> {
            boolean current;
            synchronized (LOCK) { current = cf == p; if (current) cf = null; }
            if (!current) return;
            cfUrl = "";
            if (cfWanted) {
                cfState = "连接断开，10 秒后重建（地址会变）";
                sleep(10000);
                if (cfWanted) {
                    try { startCloudflare(c); } catch (IOException e) { cfState = "重建失败：" + e.getMessage(); }
                }
            } else {
                cfState = "";
            }
        });
    }

    static void stopCloudflare(boolean forget) {
        cfWanted = false;
        Process p;
        synchronized (LOCK) { p = cf; cf = null; }
        kill(p);
        cfUrl = "";
        cfState = "";
    }

    // ------------------------------------------------------------------ tailscale

    /** Tailscale IPv4 while the node is Running, else "". Kept fresh by a background poll. */
    static String tailscaleIp() { return tailscaleRunning() ? tsIp : ""; }

    static boolean tailscaleRunning() { synchronized (LOCK) { return tsd != null && tsd.isAlive(); } }

    static void startTailscale(Context ctx) throws IOException {
        final Context c = ctx.getApplicationContext();
        tsWanted = true;
        if (tailscaleRunning()) return;
        File dir = Stack.dir(c, "tailscale");
        tsSocket(c).delete();
        List<String> cmd = new ArrayList<>();
        cmd.add(tailscaled(c).getAbsolutePath());
        cmd.add("--tun=userspace-networking");
        cmd.add("--statedir=" + dir.getAbsolutePath());
        cmd.add("--socket=" + tsSocket(c).getAbsolutePath());
        cmd.add("--port=0");
        tailscaled(c).setExecutable(true, false);
        tailscale(c).setExecutable(true, false);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Stack.common(c, pb.environment());
        Process p = pb.start();
        synchronized (LOCK) { tsd = p; }
        Stack.appLog(c, "tailscaled started (userspace networking)");
        follow(c, p, log(c, "tailscaled"), null, () -> {
            boolean current;
            synchronized (LOCK) { current = tsd == p; if (current) tsd = null; }
            if (current && tsWanted) {
                sleep(5000);
                if (tsWanted) {
                    try { startTailscale(c); } catch (IOException ignored) { }
                }
            }
        });
        // wait for the socket, then bring the node up
        for (int i = 0; i < 40 && !tsSocket(c).exists(); i++) sleep(250);
        Thread poll = new Thread(() -> {
            while (true) {
                synchronized (LOCK) { if (tsd != p) break; }
                tailscaleStatus(c);
                sleep(5000);
            }
            tsIp = "";
        }, "omc-ts-poll");
        poll.setDaemon(true);
        poll.start();
        up(c);
    }

    /** `tailscale up` with the saved auth key and host name; returns its output. Blocking. */
    static String up(Context c) {
        List<String> a = new ArrayList<>();
        a.add("up");
        a.add("--reset");
        a.add("--accept-dns=false");
        a.add("--timeout=12s");
        String host = Prefs.tsHostname(c).trim();
        if (!host.isEmpty()) a.add("--hostname=" + host);
        String key = Prefs.tsAuthKey(c).trim();
        if (!key.isEmpty()) a.add("--auth-key=" + key);
        return ts(c, 20, a.toArray(new String[0]));
    }

    static String ts(Context c, long timeout, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(tailscale(c).getAbsolutePath());
        cmd.add("--socket=" + tsSocket(c).getAbsolutePath());
        for (String s : args) cmd.add(s);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Stack.common(c, pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) >= 0) synchronized (buf) { buf.write(b, 0, n); }
                } catch (IOException ignored) { }
            });
            t.start();
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) p.destroyForcibly();
            t.join(1000);
            synchronized (buf) { return buf.toString("UTF-8"); }
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    /** {state, ips, dnsName, authUrl} from `tailscale status --json`. */
    static String[] tailscaleStatus(Context c) {
        if (!tailscaleRunning()) return new String[]{"Stopped", "", "", ""};
        String out = ts(c, 6, "status", "--json");
        try {
            JSONObject j = new JSONObject(out.substring(out.indexOf('{')));
            JSONObject self = j.optJSONObject("Self");
            String ips = "";
            String dns = "";
            if (self != null) {
                JSONArray a = self.optJSONArray("TailscaleIPs");
                if (a != null) for (int i = 0; i < a.length(); i++) if (a.getString(i).indexOf(':') < 0) ips = a.getString(i);
                dns = self.optString("DNSName").replaceAll("\\.$", "");
            }
            String state = j.optString("BackendState");
            tsIp = "Running".equals(state) ? ips : "";
            return new String[]{state, ips, dns, j.optString("AuthURL")};
        } catch (Exception e) {
            return new String[]{"Starting", "", "", ""};
        }
    }

    static void stopTailscale(boolean forget) {
        tsWanted = false;
        Process p;
        synchronized (LOCK) { p = tsd; tsd = null; }
        kill(p);
        tsIp = "";
    }

    // ------------------------------------------------------------------ mDNS

    static synchronized void startMdns(Context ctx) {
        stopMdns(ctx);
        Context c = ctx.getApplicationContext();
        NsdManager nsd = c.getSystemService(NsdManager.class);
        String model = android.os.Build.MODEL.replaceAll("[^A-Za-z0-9-]", "");
        String[] names = {"Oh My CPA " + model, "CPA API " + model};
        int[] ports = {Prefs.omcPort(c), Prefs.cpaPort(c)};
        String[] paths = {"/", "/v1"};
        mdns = new NsdManager.RegistrationListener[names.length];
        for (int i = 0; i < names.length; i++) {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(names[i]);
            info.setServiceType("_http._tcp");
            info.setPort(ports[i]);
            info.setAttribute("path", paths[i]);
            info.setAttribute("app", "oh-my-cpa");
            if (i == 1) info.setAttribute("api", "openai");
            mdns[i] = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { Stack.appLog(c, "mDNS register failed " + s.getServiceName() + " " + err); }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) { }
                @Override public void onServiceRegistered(NsdServiceInfo s) { Stack.appLog(c, "mDNS: " + s.getServiceName() + " " + s.getServiceType()); }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, mdns[i]);
            } catch (RuntimeException e) {
                Stack.appLog(c, "mDNS: " + e);
            }
        }
    }

    static synchronized void stopMdns(Context ctx) {
        if (mdns == null) return;
        NsdManager nsd = ctx.getApplicationContext().getSystemService(NsdManager.class);
        for (NsdManager.RegistrationListener l : mdns) {
            try { nsd.unregisterService(l); } catch (RuntimeException ignored) { }
        }
        mdns = null;
    }

    static synchronized boolean mdnsOn() { return mdns != null; }

    // ------------------------------------------------------------------ lifecycle

    /** Called when the gateway service starts: bring back whatever the user left switched on. */
    static void resume(Context ctx) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            if (Prefs.mdns(c) && Prefs.lan(c)) startMdns(c);
            if (Prefs.tsOn(c) && hasTailscale(c) && !tailscaleRunning()) {
                try { startTailscale(c); } catch (IOException e) { Stack.appLog(c, "tailscale: " + e.getMessage()); }
            }
            if (Prefs.cfOn(c) && hasCloudflared(c) && !cloudflareRunning()) {
                try { startCloudflare(c); } catch (IOException e) { cfState = "启动失败：" + e.getMessage(); }
            }
        }, "omc-tunnels").start();
    }

    static void stopAll(Context c) {
        stopCloudflare(false);
        stopTailscale(false);
        stopMdns(c);
    }

    // ------------------------------------------------------------------ process helpers

    interface LineSink { void line(String s); }

    private static void follow(Context c, Process p, File log, LineSink sink, Runnable onExit) {
        Thread t = new Thread(() -> {
            if (log.length() > 1024 * 1024) log.delete();
            try (FileOutputStream out = new FileOutputStream(log, true);
                 java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                    if (sink != null) sink.line(l);
                }
            } catch (IOException ignored) {
            }
            try { p.waitFor(); } catch (InterruptedException ignored) { }
            if (onExit != null) onExit.run();
        }, "omc-follow");
        t.setDaemon(true);
        t.start();
    }

    private static void kill(Process p) {
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    static String tail(File f, int lines) {
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) { q.addLast(l); if (q.size() > lines) q.removeFirst(); }
        } catch (IOException ignored) { }
        return String.join("\n", q);
    }
}
