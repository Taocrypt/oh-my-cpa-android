package com.taocrypt.omc;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** CLIProxyAPI (default 127.0.0.1:8317) first, then oh-my-cpa (default 127.0.0.1:8080) pointing at it. */
public final class Stack {
    public static final String HOST = "127.0.0.1";
    private static final String TAG = "OmcStack";

    private static Proc cpa, omc;
    /** which CPA build the last start used: the glibc plugin build (true) or the static one */
    public static volatile boolean pluginMode;
    /** shown on the status line while plugin mode is being prepared or after it failed */
    public static volatile String pluginNote = "";
    private static Thread pluginWatch;
    /** the plugin build kept failing this session: run the static one until the switch is flipped again */
    static volatile boolean pluginBroken;
    private static volatile String state = "未启动";
    /** set when a process gave up after repeated exits; shown as the red dot */
    public static volatile String problem = "";

    private Stack() {}

    public static String state() { return state; }
    public static int cpaPort(Context c) { return Prefs.cpaPort(c); }
    public static int omcPort(Context c) { return Prefs.omcPort(c); }
    /** the address both programs listen on: loopback, or every interface when LAN access is on */
    public static String bindHost(Context c) { return Prefs.lan(c) ? "0.0.0.0" : HOST; }
    public static String consoleUrl(Context c) { return "http://" + HOST + ":" + omcPort(c) + "/"; }
    public static String apiUrl(Context c) { return "http://" + HOST + ":" + cpaPort(c) + "/v1"; }

    static synchronized Proc cpa(Context c) {
        if (cpa == null) cpa = new Proc(c, "cpa", (cmd, env, cwd) -> {
            File dir = dir(c, "cpa");
            File cfg = cpaConfig(c);
            common(c, env);
            boolean plugin = Plugins.enabledInConfig(c) && !pluginBroken && Plugins.ready(c);
            if (plugin) Plugins.command(c, cmd, env);
            else cmd.add(Installer.bin(c, Installer.CPA).getAbsolutePath());
            if (plugin != pluginMode) appLog(c, "CPA mode: " + (plugin ? "plugin build via glibc loader" : "static no-plugin build"));
            pluginMode = plugin;
            cmd.add("-config");
            cmd.add(cfg.getAbsolutePath());
            cwd[0] = dir;
            return true;
        });
        return cpa;
    }

    static synchronized Proc omc(Context c) {
        if (omc == null) omc = new Proc(c, "omc", (cmd, env, cwd) -> {
            cmd.add(Installer.bin(c, Installer.OMC).getAbsolutePath());
            common(c, env);
            env.put("OMCPA_LISTEN_ADDR", bindHost(c) + ":" + omcPort(c));
            env.put("OMCPA_BASE_PATH", "/");
            env.put("OMCPA_DATA_DIR", dir(c, "omc/data").getAbsolutePath());
            env.put("OMCPA_MASTER_KEY", Prefs.masterKey(c));
            env.put("OMCPA_CPA_BASE_URL", "http://" + HOST + ":" + cpaPort(c));
            env.put("OMCPA_CPA_MANAGEMENT_KEY", Prefs.managementKey(c));
            env.put("OMCPA_USAGE_INGEST_MODE", "auto");
            cwd[0] = dir(c, "omc");
            return true;
        });
        return omc;
    }

    public static boolean running(Context c) {
        return cpa(c).alive() && omc(c).alive();
    }

    public static boolean anyAlive(Context c) {
        return cpa(c).alive() || omc(c).alive();
    }

    public static File log(Context c, String which) {
        if ("cpa".equals(which)) return cpa(c).log();
        if ("omc".equals(which)) return omc(c).log();
        return new File(dir(c, "logs"), "app.log");
    }

    /** Blocking: downloads on first run, then starts both. Call off the main thread. */
    public static synchronized void start(Context context) {
        Context c = context.getApplicationContext();
        if (!Installer.installed(c)) {
            state = "正在下载组件";
            if (!Installer.ensure(c)) {
                state = "下载失败";
                return;
            }
        }
        if (running(c)) {
            state = "运行中";
            return;
        }
        problem = "";
        pluginBroken = false;
        state = "正在启动 CLIProxyAPI";
        killStale(c);
        writeConfigOnce(c);
        syncConfig(c);
        final int CPA_PORT = cpaPort(c), OMC_PORT = omcPort(c);
        cpa(c).resetBackoff();
        omc(c).resetBackoff();
        if (!cpa(c).alive()) {
            if (portOpen(CPA_PORT, 300)) appLog(c, "warning: something already listens on " + CPA_PORT);
            cpa(c).start();
        }
        long end = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < end && !portOpen(CPA_PORT, 300)) {
            sleep(300);
        }
        if (!portOpen(CPA_PORT, 300)) appLog(c, "CPA did not open " + CPA_PORT + " within 30 s; starting oh-my-cpa anyway");
        state = "正在启动 oh-my-cpa";
        if (!omc(c).alive()) {
            if (portOpen(OMC_PORT, 300)) appLog(c, "warning: something already listens on " + OMC_PORT);
            omc(c).start();
        }
        savePids(c);
        end = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < end && !portOpen(OMC_PORT, 300)) {
            sleep(300);
        }
        state = portOpen(OMC_PORT, 300) ? "运行中" : "oh-my-cpa 未就绪，请查看日志";
        watchPlugins(c);
    }

    /**
     * Follows the console's "启用插件系统" switch: CPA writes plugins.enabled into its config file;
     * when that no longer matches the running build, CPA alone is restarted on the other one
     * (preparing the plugin build first the first time). oh-my-cpa reconnects by itself.
     */
    private static synchronized void watchPlugins(Context c) {
        if (pluginWatch != null && pluginWatch.isAlive()) return;
        pluginWatch = new Thread(() -> {
            long failedAt = 0;
            boolean lastWanted = Plugins.enabledInConfig(c);
            while (true) {
                sleep(2500);
                if (!OmcService.isUp() && !cpa(c).alive()) continue;
                boolean on = Plugins.enabledInConfig(c);
                if (on != lastWanted) { failedAt = 0; pluginBroken = false; } // a fresh flip of the switch retries at once
                lastWanted = on;
                if (pluginMode && !cpa(c).alive() && problem.startsWith("cpa")) {
                    // the plugin build keeps dying (e.g. a syscall Android blocks): keep the console usable
                    pluginBroken = true;
                    problem = "";
                    pluginNote = "插件版 CLIProxyAPI 无法运行，已切回无插件版，详见 CPA 日志";
                    appLog(c, "plugin build keeps exiting; falling back to the static build");
                    OmcService.notifyProblem(c, "插件模式无法运行", "已切回无插件版 CLIProxyAPI，打开 App 查看 CPA 日志");
                }
                boolean want = on && !pluginBroken;
                if (want == pluginMode) continue;
                if (!cpa(c).alive() && problem.startsWith("cpa")) continue; // static build gave up too: wait for a manual restart
                if (want && !Plugins.ready(c)) {
                    if (failedAt > 0 && System.currentTimeMillis() - failedAt < 10 * 60_000L) continue;
                    try {
                        pluginNote = "正在准备插件模式（下载插件版 CLIProxyAPI）";
                        appLog(c, "plugin system switched on in the console; preparing the plugin build");
                        Plugins.ensure(c);
                    } catch (Exception e) {
                        failedAt = System.currentTimeMillis();
                        pluginNote = "插件模式准备失败：" + e.getMessage();
                        appLog(c, "plugin mode setup failed: " + e);
                        OmcService.notifyProblem(c, "插件模式准备失败", String.valueOf(e.getMessage()));
                        continue;
                    }
                    if (!Plugins.enabledInConfig(c)) { pluginNote = ""; continue; }
                }
                pluginNote = "";
                appLog(c, "plugin system " + (want ? "on" : "off") + " in the console; restarting CPA on the "
                        + (want ? "plugin" : "static") + " build");
                synchronized (Stack.class) {
                    cpa(c).stop();
                    cpa(c).resetBackoff();
                    cpa(c).start();
                    savePids(c);
                }
                long end = System.currentTimeMillis() + 20_000L;
                while (System.currentTimeMillis() < end && !portOpen(cpaPort(c), 300)) sleep(300);
                if (want && pluginMode && !portOpen(cpaPort(c), 300)) {
                    appLog(c, "plugin build did not open " + cpaPort(c) + " in 20 s; see the CPA log");
                }
            }
        }, "omc-plugins");
        pluginWatch.setDaemon(true);
        pluginWatch.start();
    }

    public static synchronized void stop(Context context) {
        Context c = context.getApplicationContext();
        omc(c).stop();
        cpa(c).stop();
        new File(dir(c, "run"), "pids").delete();
        state = "已停止";
    }

    public static void restart(Context c) {
        stop(c);
        start(c);
    }

    /** The environment both Go programs get. */
    static void common(Context c, Map<String, String> env) {
        env.put("HOME", dir(c, "home").getAbsolutePath());
        env.put("TMPDIR", dir(c, "tmp").getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin:/product/bin");
        env.put("LANG", "C.UTF-8");
        env.put("TZ", TimeZone.getDefault().getID());
        // static linux/arm64 builds have no trust store path of their own on Android
        env.put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
        if (Prefs.compatNet(c)) {
            // No /etc/resolv.conf on Android: Go's resolver would ask [::1]:53 and fail.
            // Both programs honour HTTPS_PROXY, so outbound names are resolved by Android via DnsProxy.
            int port = DnsProxy.start();
            if (port > 0) {
                String proxy = "http://127.0.0.1:" + port;
                env.put("HTTPS_PROXY", proxy);
                env.put("HTTP_PROXY", proxy);
                env.put("https_proxy", proxy);
                env.put("http_proxy", proxy);
                String no = "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,.local";
                env.put("NO_PROXY", no);
                env.put("no_proxy", no);
            }
        }
    }

    public static File cpaConfig(Context c) {
        return new File(dir(c, "cpa"), "config.yaml");
    }

    /** First run only: CPA hashes the plaintext key in place on its first start, and the console may edit the file later. */
    static void writeConfigOnce(Context c) {
        File f = cpaConfig(c);
        if (f.isFile() && f.length() > 0) return;
        String auth = dir(c, "cpa/auths").getAbsolutePath();
        String plugins = dir(c, "cpa/plugins").getAbsolutePath();
        String y = "# Written by the Oh My CPA Android app on first launch.\n"
                + "config-version: 8\n"
                + "server:\n"
                + "  host: \"" + bindHost(c) + "\"\n"
                + "  port: " + cpaPort(c) + "\n"
                + "management:\n"
                + "  allow-remote: false\n"
                + "  secret-key: \"" + Prefs.managementKey(c) + "\"\n"
                + "  disable-control-panel: true\n"
                + "access:\n"
                + "  api-keys: []\n"
                + "oauth:\n"
                + "  auth-dir: \"" + auth + "\"\n"
                + "observability:\n"
                + "  logs:\n"
                + "    logging-to-file: false\n"
                + "  usage:\n"
                + "    usage-statistics-enabled: true\n"
                + "    redis-usage-queue-retention-seconds: 60\n"
                + "plugins:\n"
                + "  enabled: false\n"
                + "  dir: \"" + plugins + "\"\n";
        File tmp = new File(f.getParentFile(), "config.yaml.new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(y.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (IOException e) {
            appLog(c, "could not write CPA config: " + e);
            return;
        }
        tmp.renameTo(f);
        appLog(c, "wrote " + f);
    }

    /**
     * The config is written once and then owned by CPA (it hashes the key in place, the console
     * edits it), so a port or LAN change only rewrites the host/port lines of the server block.
     */
    static void syncConfig(Context c) {
        File f = cpaConfig(c);
        if (!f.isFile()) return;
        String text = read(f, 4 * 1024 * 1024);
        if (text.isEmpty()) return;
        String updated = rewriteServer(text, bindHost(c), cpaPort(c));
        if (updated == null) return;
        String outText = updated;
        File bak = new File(f.getParentFile(), "config.yaml.bak");
        try (FileOutputStream o = new FileOutputStream(bak)) { o.write(text.getBytes("UTF-8")); } catch (IOException ignored) { }
        File tmp = new File(f.getParentFile(), "config.yaml.new");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(outText.getBytes("UTF-8"));
            o.getFD().sync();
        } catch (IOException e) {
            appLog(c, "could not update CPA config: " + e);
            return;
        }
        if (tmp.renameTo(f)) appLog(c, "CPA config: server " + bindHost(c) + ":" + cpaPort(c));
    }


    /**
     * Returns the config with the server block's host/port set, or null when nothing changes.
     * CPA re-saves the file with 4-space indents whenever the console edits it, so the child indent is
     * detected, never assumed. Any direct host:/port: line of the block (at or above the child indent) is
     * dropped and the pair re-inserted right after "server:"; this also repairs files that 0.2.0 broke by
     * appending 2-space lines under a 4-space block.
     */
    static String rewriteServer(String text, String host, int port) {
        String[] lines = text.split("\n", -1);
        int start = -1, end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            boolean top = !l.isEmpty() && !Character.isWhitespace(l.charAt(0)) && !l.startsWith("#");
            if (!top) continue;
            if (start < 0) {
                if (l.matches("^server:\\s*(#.*)?$")) start = i;
            } else {
                end = i;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        if (start < 0) {
            out.append(text);
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append("server:\n  host: \"").append(host).append("\"\n  port: ").append(port).append('\n');
            return out.toString();
        }
        int indent = -1;
        for (int i = start + 1; i < end; i++) {
            String l = lines[i];
            if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') n++;
            indent = n;
            break;
        }
        if (indent <= 0) indent = 2;
        String pad = " ".repeat(indent);
        String wantHost = pad + "host: \"" + host + "\"";
        String wantPort = pad + "port: " + port;
        java.util.List<String> body = new java.util.ArrayList<>();
        java.util.List<String> removed = new java.util.ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            String l = lines[i];
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') n++;
            String t = l.trim();
            if (n > 0 && n <= indent && (t.startsWith("host:") || t.startsWith("port:"))) {
                removed.add(l);
                continue;
            }
            body.add(l);
        }
        boolean same = removed.size() == 2 && lines[start + 1].equals(wantHost) && lines[start + 2].equals(wantPort);
        if (same) return null;
        for (int i = 0; i <= start; i++) out.append(lines[i]).append('\n');
        out.append(wantHost).append('\n').append(wantPort).append('\n');
        for (String l : body) out.append(l).append('\n');
        for (int i = end; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    // ---- stale children from a previous app process (same uid, so we may kill them) ----

    private static int pidOf(Proc p) {
        try {
            java.lang.reflect.Field pf = Proc.class.getDeclaredField("process");
            pf.setAccessible(true);
            Object proc = pf.get(p);
            if (proc == null) return -1;
            java.lang.reflect.Field f = proc.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(proc);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void savePids(Context c) {
        String s = pidOf(cpa(c)) + " " + pidOf(omc(c));
        try (FileOutputStream out = new FileOutputStream(new File(dir(c, "run"), "pids"))) {
            out.write(s.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private static void killStale(Context c) {
        File f = new File(dir(c, "run"), "pids");
        if (!f.isFile()) return;
        String s = read(f, 256).trim();
        f.delete();
        // children are files/bin/* or, in plugin mode, files/glibc/ld-linux-aarch64.so.1
        String binDir = c.getApplicationContext().getFilesDir().getAbsolutePath() + "/";
        int ownCpa = pidOf(cpa(c)), ownOmc = pidOf(omc(c));
        for (String part : s.split("\\s+")) {
            try {
                int pid = Integer.parseInt(part);
                if (pid <= 0 || pid == ownCpa || pid == ownOmc) continue;
                String cmd = read(new File("/proc/" + pid + "/cmdline"), 4096);
                if (cmd.startsWith(binDir)) {
                    android.os.Process.killProcess(pid);
                    appLog(c, "killed a leftover process " + pid);
                    sleep(500);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ---- helpers ----

    public static boolean portOpen(int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String read(File f, int max) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(b, n, max - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** The last {@code max} bytes of a file, starting at a line boundary. */
    public static String tail(File f, int max) {
        if (!f.isFile()) return "";
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - max);
            r.seek(from);
            byte[] b = new byte[(int) (len - from)];
            r.readFully(b);
            String s = new String(b, "UTF-8");
            if (from > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (IOException e) {
            return "";
        }
    }

    public static void appLog(Context c, String line) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        File f = new File(dir(c, "logs"), "app.log");
        if (f.length() > 1024 * 1024) {
            File old = new File(f.getParentFile(), "app.log.1");
            old.delete();
            f.renameTo(old);
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write((ts + "  " + line + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "log", e);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static File dir(Context c, String name) {
        File f = new File(c.getApplicationContext().getFilesDir(), name);
        if (!f.isDirectory() && !f.mkdirs()) Log.w(TAG, "could not make " + f);
        return f;
    }
}
