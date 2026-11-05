package com.taocrypt.omc;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 空间管理: measures what the app keeps on disk, by category, and clears the categories that
 * can be cleared. Paths follow Installer / Stack / Plugins / Tunnels. The master key and the
 * management key live in SharedPreferences ("其他") and are never offered for deletion; the
 * CPA config file is listed as 系统必需.
 */
final class Storage {
    static final String CPA_BIN = "cpa", CPA_PLUGIN = "cpaPlugin", OMC_BIN = "omcBin", GLIBC = "glibc",
            ROLLBACK = "rollback", CLOUDFLARED = "cloudflared", TAILSCALE = "tailscale",
            OMC_DATA = "omcData", CPA_CONFIG = "cpaConfig", CPA_AUTH = "cpaAuth", CPA_PLUGINS = "cpaPlugins",
            LOGS = "logs", TMP = "tmp", CACHE = "cache", WEB_CACHE = "webcache", WEB_DATA = "webdata",
            APK = "apk", OTHER = "other";

    static final class Cat {
        final String key, title, desc;
        final boolean clearable;
        /** null = clear after a plain confirm (and part of 一键清理); otherwise the warning shown first */
        final String warn;
        /** restart CPA + oh-my-cpa around the clear */
        final boolean restart;
        final List<File> roots = new ArrayList<>();
        long size;

        Cat(String key, String title, String desc, boolean clearable, String warn, boolean restart) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.clearable = clearable;
            this.warn = warn;
            this.restart = restart;
        }

        Cat add(File f) {
            roots.add(f);
            return this;
        }

        boolean safe() { return clearable && warn == null; }
    }

    private Storage() {}

    static File webviewDir(Context c) { return new File(c.getDataDir(), "app_webview"); }

    static File shellUpdateDir(Context c) { return new File(c.getCacheDir(), "shell-update"); }

    private static List<File> webCacheParts(Context c) {
        List<File> l = new ArrayList<>();
        l.add(new File(c.getCacheDir(), "WebView"));
        l.add(new File(c.getCacheDir(), "org.chromium.android_webview"));
        File def = new File(webviewDir(c), "Default");
        for (String n : new String[]{"Cache", "Code Cache", "GPUCache", "Service Worker/CacheStorage", "Service Worker/ScriptCache", "blob_storage"}) {
            l.add(new File(def, n));
        }
        l.add(new File(webviewDir(c), "GrShaderCache"));
        l.add(new File(webviewDir(c), "ShaderCache"));
        return l;
    }

    /** true while something is downloading into files/dl or files/tmp */
    static boolean downloading() {
        return !Updater.stage.isEmpty() || !Plugins.stage.isEmpty() || ShellUpdater.busy;
    }

    /** Every category with its size, largest first. Blocking: run off the main thread. */
    static List<Cat> scan(Context ctx) {
        Context c = ctx.getApplicationContext();
        File bin = Stack.dir(c, "bin");
        boolean pluginsOn = Plugins.enabledInConfig(c);
        List<Cat> cats = new ArrayList<>();

        cats.add(new Cat(CPA_BIN, "CLIProxyAPI 程序", "无插件版 CPA，当前在用的网关程序，删掉服务就起不来", false, null, false)
                .add(Installer.bin(c, Installer.CPA)));
        cats.add(new Cat(OMC_BIN, "oh-my-cpa 程序", "控制台程序，删掉就打不开控制台", false, null, false)
                .add(Installer.bin(c, Installer.OMC)));
        if (pluginsOn) {
            cats.add(new Cat(CPA_PLUGIN, "CPA 插件版", "控制台已启用插件系统，正在使用；关掉「启用插件系统」后才能清除", false, null, false)
                    .add(Plugins.bin(c)));
            cats.add(new Cat(GLIBC, "glibc 运行库", "插件版 CPA 的加载器和运行库，插件模式使用中", false, null, false)
                    .add(Plugins.glibcDir(c)));
        } else {
            cats.add(new Cat(CPA_PLUGIN, "CPA 插件版", "插件模式用的 CPA（glibc 版）；控制台没开插件系统，可以清除，下次开启时重新下载", true,
                    "会删除插件版 CLIProxyAPI。下次在控制台打开「启用插件系统」时会重新下载（约 20 MB）。", false)
                    .add(Plugins.bin(c)));
            cats.add(new Cat(GLIBC, "glibc 运行库", "插件版 CPA 的加载器和运行库；清除后下次开启插件系统时从安装包里重新解压", true,
                    "会删除 glibc 运行库，下次开启插件系统时自动从安装包重新解压，不用联网。", false)
                    .add(Plugins.glibcDir(c)));
        }
        Cat rb = new Cat(ROLLBACK, "旧版本备份", "更新时留下的上一版和替换残留；清除后不能「回退到上一版」", true,
                "清除后 CLIProxyAPI 和 oh-my-cpa 都不能再回退到上一版。", false);
        File[] bins = bin.listFiles();
        if (bins != null) {
            for (File f : bins) {
                String n = f.getName();
                if (n.endsWith(".prev") || n.endsWith(".bad") || n.endsWith(".new") || n.endsWith(".download") || n.endsWith(".tmp")) rb.add(f);
            }
        }
        cats.add(rb);
        cats.add(new Cat(CLOUDFLARED, "cloudflared", "Cloudflare 公网地址用的组件和临时隧道凭据；清除后会关闭公网地址，下次开启时重新下载", true,
                "会关闭 Cloudflare 公网地址并删除 cloudflared，下次开启时重新下载（约 40 MB），公网地址也会换一个新的。", false)
                .add(Tunnels.cloudflared(c)).add(Stack.dir(c, "tunnel")));
        cats.add(new Cat(TAILSCALE, "Tailscale", "tailscale 组件和这台设备的登录状态；清除后需要重新下载并登录", true,
                "会关闭 Tailscale，删除组件和登录状态。下次开启要重新下载（约 30 MB）并重新登录，tailnet 里会出现一台新设备。", false)
                .add(Tunnels.tailscaled(c)).add(Tunnels.tailscale(c)).add(Stack.dir(c, "tailscale")));
        cats.add(new Cat(OMC_DATA, "oh-my-cpa 数据", "控制台的 SQLite 数据库：用量记录、控制台里的设置等，属于系统必需数据", true,
                "危险操作：会删除 oh-my-cpa 的全部数据库（用量统计、请求记录、控制台里保存的设置等），相当于重装 oh-my-cpa，无法撤销。"
                        + "\n\n主密钥和控制台密码保存在 App 设置里，不会被删除。CPA 的账号凭据和配置不受影响。服务会重启。", true)
                .add(Stack.dir(c, "omc")));
        cats.add(new Cat(CPA_AUTH, "CPA 账号凭据", "各服务商的 OAuth 登录凭据（auths 目录），删掉要在控制台里重新登录每个账号", true,
                "危险操作：会删除 CLIProxyAPI 里所有已登录账号的凭据，所有账号都要在控制台重新登录或导入，无法撤销。服务会重启。", true)
                .add(Stack.dir(c, "cpa/auths")));
        cats.add(new Cat(CPA_PLUGINS, "CPA 插件", "从插件商店安装的插件文件和数据（plugins 目录）", true,
                "会删除所有已安装的 CPA 插件，需要的话要在控制台插件商店里重新安装。服务会重启。", true)
                .add(Stack.dir(c, "cpa/plugins")));
        Cat cfg = new Cat(CPA_CONFIG, "CPA 配置", "config.yaml 和它的备份：端口、管理密钥、API Key 等，系统必需，不能清除", false, null, false);
        File[] cpaFiles = Stack.dir(c, "cpa").listFiles();
        if (cpaFiles != null) {
            for (File f : cpaFiles) {
                String n = f.getName();
                if (!n.equals("auths") && !n.equals("plugins")) cfg.add(f);
            }
        }
        cats.add(cfg);
        cats.add(new Cat(LOGS, "日志", "oh-my-cpa、CPA、App、隧道日志和崩溃记录", true, null, false).add(Stack.dir(c, "logs")));
        cats.add(new Cat(TMP, "下载与临时文件", "组件下载的断点续传残留（dl）和两个程序的临时目录（tmp）", true, null, false)
                .add(Stack.dir(c, "dl")).add(Stack.dir(c, "tmp")));
        Cat cache = new Cat(CACHE, "应用缓存", "应用缓存、外壳更新下载的安装包等，用到时会自动重建", true, null, false);
        cache.add(c.getCodeCacheDir());
        File[] cc = c.getCacheDir().listFiles();
        if (cc != null) {
            for (File f : cc) {
                String n = f.getName();
                if (!n.equals("WebView") && !n.equals("org.chromium.android_webview")) cache.add(f);
            }
        }
        cats.add(cache);
        Cat wc = new Cat(WEB_CACHE, "网页缓存", "WebView 的图片、脚本等缓存，不影响登录和设置", true, null, false);
        for (File f : webCacheParts(c)) wc.add(f);
        cats.add(wc);
        cats.add(new Cat(WEB_DATA, "网页存储", "WebView 的 Cookie 和本地存储：控制台登录状态、界面偏好", true,
                "会清掉控制台的登录状态和网页记住的选项（主题、语言、表格设置等）。App 会自动重新登录。CPA 和 oh-my-cpa 的数据不受影响。", false)
                .add(webviewDir(c)));
        Cat apk = new Cat(APK, "安装包", "APK 本体（含内置 glibc），由系统管理，不能清除", false, null, false);
        apk.add(new File(c.getApplicationInfo().sourceDir));
        String[] splits = c.getApplicationInfo().splitSourceDirs;
        if (splits != null) for (String s : splits) apk.add(new File(s));
        if (c.getApplicationInfo().nativeLibraryDir != null) apk.add(new File(c.getApplicationInfo().nativeLibraryDir));
        cats.add(apk);

        long counted = 0;
        String dataRoot = c.getDataDir().getAbsolutePath() + File.separator;
        for (Cat k : cats) {
            if (k.key.equals(WEB_DATA)) continue;
            k.size = sizeOf(k.roots);
            for (File f : k.roots) if (f.getAbsolutePath().startsWith(dataRoot)) counted += size(f);
        }
        Cat web = find(cats, WEB_DATA);
        Cat webc = find(cats, WEB_CACHE);
        long webInDir = 0;
        String wv = webviewDir(c).getAbsolutePath();
        for (File f : webc.roots) if (f.getAbsolutePath().startsWith(wv)) webInDir += size(f);
        web.size = Math.max(0, size(webviewDir(c)) - webInDir);
        counted += web.size;
        Cat other = new Cat(OTHER, "其他", "App 设置（含主密钥和控制台密码）、运行目录（home、run）等，不能清除", false, null, false);
        other.size = Math.max(0, size(c.getDataDir()) - counted);
        cats.add(other);
        Collections.sort(cats, (a, b) -> Long.compare(b.size, a.size));
        return cats;
    }

    static Cat find(List<Cat> cats, String key) {
        for (Cat k : cats) if (k.key.equals(key)) return k;
        return null;
    }

    static long total(List<Cat> cats) {
        long t = 0;
        for (Cat k : cats) t += k.size;
        return t;
    }

    /** Clears the files of a category. Stopping processes and WebView state is the caller's job. */
    static void clearFiles(Context ctx, Cat k) {
        Context c = ctx.getApplicationContext();
        switch (k.key) {
            case LOGS:
                File[] logs = Stack.dir(c, "logs").listFiles();
                if (logs != null) {
                    for (File f : logs) {
                        String n = f.getName();
                        if (n.endsWith(".1") || n.endsWith(".old") || f.isDirectory()) {
                            delete(f);
                        } else {
                            // running processes keep their log open: truncate instead of unlinking
                            try (FileOutputStream o = new FileOutputStream(f, false)) {
                                o.flush();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
                return;
            case TMP:
                if (downloading()) return;
                for (File r : k.roots) deleteChildren(r);
                return;
            case OMC_DATA:
            case CPA_AUTH:
            case CPA_PLUGINS:
            case GLIBC:
                for (File r : k.roots) deleteChildren(r);
                return;
            case CACHE:
                for (File r : k.roots) {
                    if (r.equals(c.getCodeCacheDir())) deleteChildren(r);
                    else if (r.equals(shellUpdateDir(c)) && ShellUpdater.busy) continue;
                    else delete(r);
                }
                return;
            case CPA_PLUGIN:
                for (File r : k.roots) delete(r);
                Prefs.setInstalled(c, Plugins.BIN, "");
                return;
            case ROLLBACK:
                for (File r : k.roots) delete(r);
                for (Installer.Pkg p : Installer.ALL) Prefs.setPrevVersion(c, p.name, "");
                return;
            default:
                if (k.clearable) for (File r : k.roots) delete(r);
        }
    }

    private static long sizeOf(List<File> roots) {
        long t = 0;
        for (File f : roots) t += size(f);
        return t;
    }

    static long size(File f) {
        if (f == null || !f.exists()) return 0;
        try {
            if (Files.isSymbolicLink(f.toPath())) return 0;
        } catch (RuntimeException ignored) {
        }
        if (f.isFile()) return f.length();
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t += size(k);
        return t;
    }

    private static void deleteChildren(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) delete(k);
    }

    static void delete(File f) {
        if (f == null || !f.exists()) return;
        boolean link = false;
        try {
            link = Files.isSymbolicLink(f.toPath());
        } catch (RuntimeException ignored) {
        }
        if (f.isDirectory() && !link) deleteChildren(f);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.ROOT, m < 10 ? "%.1f MB" : "%.0f MB", m);
        return String.format(Locale.ROOT, "%.2f GB", m / 1024.0);
    }
}
