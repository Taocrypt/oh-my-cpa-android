package com.taocrypt.omc;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One supervised child process: output goes to its log, and it is restarted with backoff if it exits. */
final class Proc {
    interface Setup {
        /** fills command, environment and working directory; return false to skip starting */
        boolean prepare(List<String> cmd, Map<String, String> env, File[] cwd);
    }

    private static final long LOG_MAX = 2L * 1024 * 1024;
    final String name;
    private final Context c;
    private final Setup setup;
    private Process process;
    private volatile boolean stopping;
    private volatile long startedAt;
    private int restarts;
    volatile String lastExit = "";

    Proc(Context c, String name, Setup setup) {
        this.c = c.getApplicationContext();
        this.name = name;
        this.setup = setup;
    }

    File log() {
        return new File(Stack.dir(c, "logs"), name + ".log");
    }

    synchronized boolean alive() {
        return process != null && process.isAlive();
    }

    synchronized void start() {
        if (alive()) return;
        stopping = false;
        File log = log();
        if (log.length() > LOG_MAX) {
            File old = new File(log.getParentFile(), name + ".log.1");
            old.delete();
            log.renameTo(old);
        }
        List<String> cmd = new java.util.ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        File[] cwd = new File[1];
        if (!setup.prepare(cmd, pb.environment(), cwd)) return;
        if (cwd[0] != null) pb.directory(cwd[0]);
        pb.redirectErrorStream(true);
        File exe = new File(cmd.get(0));
        exe.setExecutable(true, false);
        try {
            Process p = pb.start();
            process = p;
            startedAt = System.currentTimeMillis();
            Stack.appLog(c, name + " started (" + exe.getName() + ")");
            pump(p, log);
            watch(p);
        } catch (IOException e) {
            lastExit = String.valueOf(e.getMessage());
            Stack.appLog(c, name + " could not start: " + e);
            append(log, "[shell] could not start: " + e + "\n");
        }
    }

    void stop() {
        Process p;
        synchronized (this) {
            stopping = true;
            p = process;
            process = null;
        }
        if (p == null) return;
        p.destroy(); // SIGTERM: both Go programs shut down cleanly on it
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Stack.appLog(c, name + " stopped");
    }

    void resetBackoff() {
        restarts = 0;
    }

    private static void append(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(s.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private void pump(final Process p, final File log) {
        Thread t = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(log, true); InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }, name + "-log");
        t.setDaemon(true);
        t.start();
    }

    private static long backoff(int i) {
        switch (i) {
            case 1: return 1000L;
            case 2: return 2000L;
            case 3: return 5000L;
            case 4: return 15000L;
            default: return 30000L;
        }
    }

    private void watch(final Process p) {
        Thread t = new Thread(() -> {
            try {
                int code = p.waitFor();
                synchronized (Proc.this) {
                    if (process == p) process = null;
                }
                lastExit = "退出码 " + code;
                if (stopping) return;
                if (System.currentTimeMillis() - startedAt > 60_000L) restarts = 0;
                restarts++;
                if (restarts > 6) {
                    Stack.appLog(c, name + " keeps exiting (code " + code + "); giving up until restarted by hand");
                    String last = "";
                    for (String ln : Stack.tail(log(), 4096).split("\n")) if (!ln.trim().isEmpty()) last = ln.trim();
                    if (last.length() > 160) last = last.substring(last.length() - 160);
                    Stack.appLog(c, name + " last output: " + last);
                    Stack.problem = name + " 多次启动失败（退出码 " + code + "）" + (last.isEmpty() ? "" : "\n" + last);
                    OmcService.notifyProblem(c, name + " 多次启动失败", "退出码 " + code + "，打开 App 查看日志");
                    restarts = 0;
                    return;
                }
                long wait = backoff(restarts);
                Stack.appLog(c, name + " exited (code " + code + "); restarting in " + wait + " ms");
                Thread.sleep(wait);
                if (!stopping) start();
            } catch (InterruptedException ignored) {
            }
        }, name + "-watch");
        t.setDaemon(true);
        t.start();
    }
}
