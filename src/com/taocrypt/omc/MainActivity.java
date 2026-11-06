package com.taocrypt.omc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The console fills the screen exactly as it does in a phone browser - its own header and its
 * own left-hand navigation drawer stay untouched. The app adds one thing on top: a small
 * floating button at the bottom right (status dot on it) that slides the native panel in from
 * the RIGHT, so the two drawers never compete for the same edge. The panel is drawn with the
 * console's live design tokens and copies its navigation rail: wordmark, quiet group captions,
 * soft 8px rows, the stronger wash for the selected entry, and cards/dialogs for sub-pages.
 */
public final class MainActivity extends Activity {
    public static final String ACTION_LOGS = "com.taocrypt.omc.LOGS";
    public static final String ACTION_FLOAT = "com.taocrypt.omc.FLOAT";
    public static final String ACTION_RESTART = "com.taocrypt.omc.RESTART";
    private static final String PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    public static final String ACTION_UPDATES = "com.taocrypt.omc.UPDATES";
    public static final String ACTION_SHELL_INSTALL = "com.taocrypt.omc.SHELL_INSTALL";
    public static final String EXTRA_APK = "apk";
    static final String APP_VERSION = "0.4.6";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private volatile boolean polling;
    private volatile boolean consoleUp, cpaUp;

    private Palette pal;
    private Kit k;

    private FrameLayout root, content;
    private WebView web;
    private boolean webLoaded, autoLoginTried;
    private String bridgeJs;
    private long lastBack;

    // first-run / stopped screen
    private ScrollView setup;
    private TextView stageText, detailText, errorText, introText, setupTitle;
    private ProgressBar bar;
    private TextView retry;
    private LinearLayout mirrorRow;

    // floating button
    private FrameLayout fab;
    private View fabDot;
    private boolean imeOpen;
    private final Runnable fabIdle = () -> { if (fab != null) fab.animate().alpha(0.62f).setDuration(240).start(); };

    // right-hand panel
    private View scrim;
    private SwipePanel panel;
    private LinearLayout panelColumn;
    private boolean panelOpen;
    private String page;          // null = the menu
    private String lastPage;
    private Runnable pageTick;
    private Runnable pageCleanup;
    private TextView statusLine;
    private View statusDot;

    private int insetLeft, insetTop, insetRight, insetBottom;
    private boolean pendingFloatHome;

    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final PathInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        Updater.onJobEnded = () -> { if (!Updater.anyRunning()) consoleWillRestart(); };
        pal = Palette.get(this);
        k = new Kit(this, pal);
        Window w = getWindow();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        buildUi();
        applySystemBars();
        OmcService.start(this);
        handleIntent(getIntent());
        autoCheck();
        if (b == null) ui.postDelayed(this::autoCheckShell, 2500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** launcher shortcuts and notification taps */
    private void handleIntent(Intent i) {
        String a = i == null ? null : i.getAction();
        if (a == null) return;
        i.setAction(Intent.ACTION_MAIN);
        if (ACTION_LOGS.equals(a)) ui.postDelayed(() -> openPanel("logs"), 300);
        else if (ACTION_FLOAT.equals(a)) ui.postDelayed(this::floatAndGoHome, 300);
        else if (ACTION_RESTART.equals(a)) restartServices();
        else if (ACTION_UPDATES.equals(a)) ui.postDelayed(() -> openPanel("updates"), 300);
        else if (ACTION_SHELL_INSTALL.equals(a)) {
            final String path = i.getStringExtra(EXTRA_APK);
            ui.postDelayed(() -> reinstallShell(path), 300);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
        if (pendingFloatHome) {
            pendingFloatHome = false;
            if (Settings.canDrawOverlays(this)) ui.postDelayed(this::floatAndGoHome, 150);
        }
        if (panelOpen) showPage(page);
    }

    @Override
    protected void onPause() {
        polling = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Updater.onJobEnded != null) Updater.onJobEnded = null;
        polling = false;
        bg.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (web != null) web.evaluateJavascript(SchemeShim.js(SchemeShim.systemDark(this)), null);
        if (panelOpen) {
            panel.getLayoutParams().width = panelWidth(page != null);
            panel.requestLayout();
        }
        placeFab();
    }

    // ================================================================== layout

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH));
        buildSetup();

        fab = new FrameLayout(this);
        root.addView(fab, new FrameLayout.LayoutParams(k.dp(48), k.dp(48), Gravity.BOTTOM | Gravity.END));
        styleFab();
        fabTouch();

        scrim = new View(this);
        scrim.setBackgroundColor(pal.mask);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closePanel());
        root.addView(scrim, new FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH));

        panel = new SwipePanel(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setElevation(k.dp(16));
        root.addView(panel, new FrameLayout.LayoutParams(panelWidth(false), Kit.MATCH, Gravity.END));

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetLeft = ins.getSystemWindowInsetLeft();
            insetTop = ins.getSystemWindowInsetTop();
            insetRight = ins.getSystemWindowInsetRight();
            insetBottom = ins.getSystemWindowInsetBottom();
            imeOpen = insetBottom > root.getHeight() / 4 && root.getHeight() > 0;
            content.setPadding(insetLeft, insetTop, insetRight, insetBottom);
            panel.setPadding(0, insetTop, insetRight, insetBottom);
            fab.setVisibility(imeOpen ? View.GONE : View.VISIBLE);
            placeFab();
            return ins.consumeSystemWindowInsets();
        });
        setContentView(root);
    }

    private void applySystemBars() {
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!pal.dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        root.setSystemUiVisibility(f);
    }

    /** the console reported a new palette (theme switch, custom palette): repaint everything native */
    private void onTheme(String json) {
        if (!Palette.report(this, json)) return;
        pal = Palette.get(this);
        k = new Kit(this, pal);
        root.setBackgroundColor(pal.bg);
        if (web != null) web.setBackgroundColor(pal.bg);
        scrim.setBackgroundColor(pal.mask);
        applySystemBars();
        styleFab();
        boolean setupVisible = setup.getVisibility() == View.VISIBLE;
        content.removeView(setup);
        buildSetup();
        setup.setVisibility(setupVisible ? View.VISIBLE : View.GONE);
        if (panelOpen) showPage(page);
    }

    private int panelWidth(boolean wide) {
        int w = getResources().getDisplayMetrics().widthPixels;
        // the console's own sheet is min(320px, 86vw): a sliver of the page stays visible behind the mask
        return wide ? Math.min(Math.round(w * 0.94f), k.dp(440)) : Math.min(Math.round(w * 0.86f), k.dp(320));
    }

    // ================================================================== floating button

    private void styleFab() {
        fab.removeAllViews();
        fab.setBackground(k.oval(pal.elevated, pal.border, 1));
        fab.setElevation(k.dp(6));
        fab.setAlpha(0.62f);
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(k.mark(pal.fg, pal.accent));
        fab.addView(mark, new FrameLayout.LayoutParams(k.dp(22), k.dp(22), Gravity.CENTER));
        fabDot = new View(this);
        fabDot.setBackground(k.oval(pal.meta, pal.elevated, 2));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(k.dp(12), k.dp(12), Gravity.TOP | Gravity.END);
        dl.topMargin = k.dp(1);
        dl.rightMargin = k.dp(1);
        fab.addView(fabDot, dl);
        fab.setContentDescription("Oh My CPA 菜单");
        paintHealth();
    }

    private void placeFab() {
        if (fab == null) return;
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) fab.getLayoutParams();
        int h = Math.max(root.getHeight() - insetTop - insetBottom, k.dp(400));
        l.rightMargin = insetRight + k.dp(14);
        l.bottomMargin = insetBottom + k.dp(16) + Math.round(Prefs.fabY(this) * (h - k.dp(80)));
        fab.setLayoutParams(l);
    }

    private void fabTouch() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Runnable[] longPress = {null};
        fab.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startMargin;
            boolean dragging, longDone;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) fab.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ui.removeCallbacks(fabIdle);
                        fab.animate().alpha(1f).setDuration(90).start();
                        downY = e.getRawY();
                        startMargin = l.bottomMargin;
                        dragging = false;
                        longDone = false;
                        longPress[0] = () -> { longDone = true; fab.performHapticFeedback(0); floatAndGoHome(); };
                        ui.postDelayed(longPress[0], ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY;
                        if (!dragging && Math.abs(dy) > slop) {
                            dragging = true;
                            ui.removeCallbacks(longPress[0]);
                        }
                        if (dragging) {
                            int max = root.getHeight() - insetTop - k.dp(64);
                            l.bottomMargin = Math.max(insetBottom + k.dp(8), Math.min(max, startMargin - Math.round(dy)));
                            fab.setLayoutParams(l);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress[0]);
                        if (dragging) {
                            int h = Math.max(root.getHeight() - insetTop - insetBottom, k.dp(400)) - k.dp(80);
                            Prefs.setFabY(MainActivity.this, (l.bottomMargin - insetBottom - k.dp(16)) / (float) Math.max(1, h));
                        } else if (!longDone && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            openPanel(null);
                        }
                        ui.postDelayed(fabIdle, 2500);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** green: both up · amber: starting / one side down · red: a process gave up or the download failed · grey: stopped */
    private int healthColor() {
        if (!Stack.problem.isEmpty() || (!Installer.installed(this) && !Installer.error.isEmpty())) return pal.danger;
        if (consoleUp && cpaUp) return pal.success;
        if (!OmcService.isUp()) return pal.meta;
        return pal.warn;
    }

    private String healthText() {
        if (!Stack.problem.isEmpty()) return Stack.problem;
        if (consoleUp && cpaUp) return "运行中";
        if (consoleUp) return "控制台运行中 · CPA 未就绪";
        if (!OmcService.isUp()) return "已停止";
        return Stack.state();
    }

    private void paintHealth() {
        int c = healthColor();
        if (fabDot != null) fabDot.setBackground(k.oval(c, pal.elevated, 2));
        if (statusDot != null) statusDot.setBackground(k.oval(c, 0, 0));
        if (statusLine != null) statusLine.setText(healthText());
    }

    // ================================================================== first-run / stopped screen

    private void buildSetup() {
        setup = new ScrollView(this);
        setup.setFillViewport(true);
        setup.setBackgroundColor(pal.bg);
        LinearLayout col = k.column();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(k.dp(20), k.dp(48), k.dp(20), k.dp(56));

        ImageView word = new ImageView(this);
        word.setImageDrawable(k.wordmark());
        word.setAdjustViewBounds(true);
        col.addView(word, new LinearLayout.LayoutParams(Kit.WRAP, k.dp(22)));

        setupTitle = k.bold("本机运行 CLIProxyAPI + oh-my-cpa", 20, pal.fg);
        setupTitle.setGravity(Gravity.CENTER);
        col.addView(setupTitle, k.lpm(Kit.MATCH, Kit.WRAP, 28, 8));

        introText = k.text("首次启动会从 GitHub Releases 下载两个 linux arm64 程序的最新版：CLIProxyAPI（约 21 MB）和 oh-my-cpa（约 24 MB），"
                + "按官方 checksums.txt 校验 SHA-256 后解压到应用私有目录；查不到最新版时用内置的 v" + Installer.CPA.version + " / v" + Installer.OMC.version + "。建议连 Wi-Fi。", 13, pal.muted);
        introText.setGravity(Gravity.CENTER);
        introText.setLineSpacing(k.dp(3), 1f);
        col.addView(introText, k.lpm(Kit.MATCH, Kit.WRAP, 0, 24));

        LinearLayout card = k.card();
        card.setPadding(k.dp(20), k.dp(18), k.dp(20), k.dp(18));
        stageText = k.text("准备中…", 14, pal.fg);
        card.addView(stageText);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(pal.accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(pal.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(pal.wash(12)));
        card.addView(bar, k.lpm(Kit.MATCH, k.dp(6), 12, 0));
        detailText = k.text("", 12, pal.muted);
        card.addView(detailText, k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        errorText = k.text("", 13, pal.danger);
        errorText.setLineSpacing(k.dp(2), 1f);
        errorText.setVisibility(View.GONE);
        card.addView(errorText, k.lpm(Kit.MATCH, Kit.WRAP, 10, 0));
        mirrorRow = k.column();
        mirrorRow.addView(k.divider());
        k.switchRow(mirrorRow, "下载加速镜像", "通过 ghfast.top 下载 GitHub 文件，SHA-256 校验不变", Prefs.mirror(this), on -> Prefs.setMirror(this, on));
        card.addView(mirrorRow);
        retry = k.button("重试", Kit.PRIMARY, () -> {
            Installer.error = "";
            Stack.problem = "";
            OmcService.start(this);
        });
        retry.setVisibility(View.GONE);
        TextView logs = k.button("查看日志", Kit.DEFAULT, () -> openPanel("logs"));
        card.addView(k.buttons(logs, retry));
        col.addView(card, k.lpm(Kit.MATCH, Kit.WRAP, 0, 0));

        TextView foot = k.text("右下角的圆形按钮打开 App 面板 · Oh My CPA " + APP_VERSION, 11, pal.meta);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, k.lpm(Kit.MATCH, Kit.WRAP, 16, 0));

        setup.addView(col);
        content.addView(setup, new FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH));
    }

    // ================================================================== state polling

    private void startPolling() {
        if (polling) return;
        polling = true;
        Thread t = new Thread(() -> {
            while (polling) {
                consoleUp = Stack.portOpen(Prefs.omcPort(this), 400);
                cpaUp = Stack.portOpen(Prefs.cpaPort(this), 400);
                ui.post(this::render);
                Stack.sleep(700);
            }
        }, "omc-poll");
        t.setDaemon(true);
        t.start();
    }

    private void render() {
        if (isDestroyed()) return;
        paintHealth();
        boolean installed = Installer.installed(this);
        if (consoleUp) {
            showConsole();
            return;
        }
        if (web != null && webLoaded && OmcService.isUp()) webLoaded = false; // reload when the port is back
        setup.setVisibility(View.VISIBLE);
        if (web != null && !webLoaded) web.setVisibility(View.INVISIBLE);
        introText.setVisibility(installed ? View.GONE : View.VISIBLE);
        mirrorRow.setVisibility(installed ? View.GONE : View.VISIBLE);
        setupTitle.setText(installed ? "Oh My CPA" : "本机运行 CLIProxyAPI + oh-my-cpa");
        if (Installer.busy) {
            stageText.setText(Installer.stage);
            int p = Installer.percent;
            bar.setIndeterminate(p < 0);
            if (p >= 0) bar.setProgress(p);
            detailText.setText(Installer.detail);
            errorText.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
        } else if (!installed && !Installer.error.isEmpty()) {
            stageText.setText("下载没有完成");
            retry.setText("重试");
            bar.setIndeterminate(false);
            bar.setProgress(0);
            detailText.setText("");
            errorText.setText(Installer.error + "\n\n可以打开“下载加速镜像”后重试；已下载的部分会续传。");
            errorText.setVisibility(View.VISIBLE);
            retry.setVisibility(View.VISIBLE);
        } else if (!Stack.problem.isEmpty()) {
            stageText.setText(Stack.problem);
            bar.setIndeterminate(false);
            bar.setProgress(0);
            detailText.setText("");
            errorText.setText("进程连续退出后已停止自动重启。查看日志找原因，修好后点「重启服务」。");
            errorText.setVisibility(View.VISIBLE);
            retry.setText("重启服务");
            retry.setVisibility(View.VISIBLE);
        } else if (!OmcService.isUp()) {
            stageText.setText("服务已停止");
            bar.setIndeterminate(false);
            bar.setProgress(0);
            detailText.setText("");
            errorText.setVisibility(View.GONE);
            retry.setText("启动服务");
            retry.setVisibility(View.VISIBLE);
        } else {
            stageText.setText(Stack.state() + "…");
            bar.setIndeterminate(true);
            detailText.setText(installed ? "CLIProxyAPI " + Prefs.installed(this, Installer.CPA.name) + " · oh-my-cpa " + Prefs.installed(this, Installer.OMC.name) : "");
            errorText.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
        }
    }

    private void showConsole() {
        if (web == null) createWeb();
        if (!webLoaded) {
            webLoaded = true;
            web.loadUrl(Stack.consoleUrl(this));
        }
        web.setVisibility(View.VISIBLE);
        setup.setVisibility(View.GONE);
    }

    // ================================================================== WebView

    public final class Bridge {
        @JavascriptInterface
        public void theme(String json) { ui.post(() -> onTheme(json)); }

        @JavascriptInterface
        public void openPanel() { ui.post(() -> MainActivity.this.openPanel(null)); }
    }

    private void createWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setBackgroundColor(pal.bg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "OmcApp");
        WebFiles.attach(this, web);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { return true; }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                return WebFiles.choose(MainActivity.this, cb, params, null);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                String host = r.getUrl().getHost();
                String scheme = r.getUrl().getScheme();
                if (scheme == null || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) return false;
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                openExternal(r.getUrl().toString());
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return SchemeShim.intercept(MainActivity.this, r, Stack.consoleUrl(MainActivity.this));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                if (url == null || !url.startsWith(Stack.consoleUrl(MainActivity.this))) return;
                v.evaluateJavascript(WebFiles.HOOK_JS, null);
                v.evaluateJavascript("window.__omcInject=" + Prefs.injectEntry(MainActivity.this) + ";" + bridgeScript(), null);
                v.evaluateJavascript(pluginKeyJs(MainActivity.this), null);
                if (!autoLoginTried) {
                    autoLoginTried = true;
                    v.evaluateJavascript(autoLoginJs(MainActivity.this), null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                CrashLog.write(MainActivity.this, "console renderer gone (crashed=" + d.didCrash() + ")", new RuntimeException("renderer gone"));
                content.removeView(web);
                web.destroy();
                web = null;
                webLoaded = false;
                ui.post(() -> { if (consoleUp) showConsole(); });
                return true;
            }
        });
        content.addView(web, 0, new FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH));
    }

    private String bridgeScript() {
        if (bridgeJs == null) {
            try (InputStream in = getAssets().open("omc-app.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                bridgeJs = out.toString("UTF-8");
            } catch (IOException e) {
                bridgeJs = "";
            }
        }
        return bridgeJs;
    }

    /**
     * Plugin pages (e.g. 降智测试) read the CPA management key the way CPA's own management centre
     * stores it, localStorage "cli-proxy-auth" {state:{managementKey}}, and otherwise ask for it.
     * oh-my-cpa never puts it there, so inside the app we do (app-private WebView storage, same
     * origin as the console only). Off: the entry is removed and plugin pages ask again.
     */
    static String pluginKeyJs(Context c) {
        if (!Prefs.pluginKey(c)) {
            return "try{var a=localStorage.getItem('cli-proxy-auth');if(a&&a.indexOf('omcApp')>=0)localStorage.removeItem('cli-proxy-auth');}catch(e){}";
        }
        String v = "{\"state\":{\"managementKey\":\"" + Prefs.managementKey(c) + "\"},\"version\":0,\"omcApp\":true}";
        return "try{if(localStorage.getItem('cli-proxy-auth')!=='" + v + "')localStorage.setItem('cli-proxy-auth','" + v + "');}catch(e){}";
    }

    /** Signs the console in with the generated management key, if it is not signed in. */
    static String autoLoginJs(Context c) {
        String key = Prefs.managementKey(c);
        return "(async()=>{try{"
                + "const s=await fetch('/api/auth/session',{credentials:'same-origin'}).then(r=>r.json());"
                + "if(s&&s.authenticated)return 'already';"
                + "const r=await fetch('/api/auth/login',{method:'POST',credentials:'same-origin',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify({password:'" + key + "'})});"
                + "if(r.ok){location.reload();return 'ok';}return 'http '+r.status;"
                + "}catch(e){return 'err '+e;}})();";
    }

    private void reloadConsole() {
        if (web != null && consoleUp) web.reload();
        else webLoaded = false;
    }

    // ================================================================== back

    @Override
    public void onBackPressed() {
        if (panelOpen) {
            if (page != null) showPage(null);
            else closePanel();
            return;
        }
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            moveTaskToBack(true); // services keep running
            return;
        }
        lastBack = now;
        toast("再按一次返回桌面（服务继续在后台运行）");
    }

    // ================================================================== the panel

    /** The panel container: a hairline on its leading edge, and a rightward swipe closes it. */
    private final class SwipePanel extends LinearLayout {
        private float downX, downY;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();

        SwipePanel(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (dx > slop * 1.5f && dx > Math.abs(dy) * 1.6f) {
                        dragging = true;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float dx = Math.max(0, e.getRawX() - downX);
                        setTranslationX(dx);
                        scrim.setAlpha(1f - Math.min(1f, dx / Math.max(1, getWidth())));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        float dx = e.getRawX() - downX;
                        float vx = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); }
                        if (dx > getWidth() / 3f || vx > 1200) closePanel();
                        else {
                            animate().translationX(0).setDuration(160).setInterpolator(EASE_OUT).start();
                            scrim.animate().alpha(1f).setDuration(160).start();
                        }
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    private void openPanel(String target) {
        if (!panelOpen) {
            panelOpen = true;
            panel.setVisibility(View.VISIBLE);
            scrim.setVisibility(View.VISIBLE);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180).setListener(null).start();
            showPage(target);
            int w = panel.getLayoutParams().width;
            panel.setTranslationX(w);
            panel.animate().translationX(0).setDuration(240).setInterpolator(EASE_OUT).setListener(null).start();
        } else {
            showPage(target);
        }
    }

    private void closePanel() {
        settingsDraft = null;
        if (!panelOpen) return;
        panelOpen = false;
        stopPageTick();
        scrim.animate().alpha(0f).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (!panelOpen) scrim.setVisibility(View.GONE); }
        }).start();
        panel.animate().translationX(panel.getWidth()).setDuration(180).setInterpolator(EASE_IN).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (!panelOpen) {
                    panel.setVisibility(View.GONE);
                    page = null;
                }
            }
        }).start();
    }

    private void stopPageTick() {
        if (pageTick != null) ui.removeCallbacks(pageTick);
        pageTick = null;
        if (pageCleanup != null) pageCleanup.run();
        pageCleanup = null;
    }

    /** builds the panel for a page; null is the navigation menu */
    private void showPage(String target) {
        if (!"settings".equals(target)) settingsDraft = null;
        stopPageTick();
        if (page != null) lastPage = page;
        page = target;
        statusLine = null;
        statusDot = null;
        panel.removeAllViews();
        panel.setBackgroundColor(pal.elevated);
        View edge = new View(this);
        edge.setBackgroundColor(pal.border);
        panel.addView(edge, new LinearLayout.LayoutParams(Math.max(1, k.dp(1)), Kit.MATCH));
        panelColumn = k.column();
        panel.addView(panelColumn, new LinearLayout.LayoutParams(0, Kit.MATCH, 1f));
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) panel.getLayoutParams();
        pl.width = panelWidth(target != null);
        panel.setLayoutParams(pl);
        if (target == null) {
            buildMenu();
            return;
        }
        LinearLayout body = pageScaffold(titleOf(target));
        switch (target) {
            case "float": pageFloat(body); break;
            case "remote": pageRemote(body); break;
            case "addresses": pageAddresses(body); break;
            case "components": pageComponents(body); break;
            case "settings": pageSettings(body); break;
            case "logs": pageLogs(body); break;
            case "updates": pageUpdates(body); break;
            case "storage": pageStorage(body); break;
            default: break;
        }
    }

    private static String titleOf(String p) {
        switch (p) {
            case "float": return "悬浮窗设置";
            case "remote": return "远程访问";
            case "addresses": return "地址与复制";
            case "components": return "组件信息";
            case "settings": return "应用设置";
            case "logs": return "查看日志";
            case "updates": return "检查更新";
            case "storage": return "空间管理";
            default: return p;
        }
    }

    /** a sub-page: the console's side-sheet header (title 16/600, hairline under it) over a scrolling body */
    private LinearLayout pageScaffold(String title) {
        LinearLayout head = k.row();
        head.setPadding(k.dp(8), 0, k.dp(8), 0);
        head.addView(iconButton(R.drawable.ic_back, "返回", () -> showPage(null)));
        TextView t = k.bold(title, 16, pal.fg);
        LinearLayout.LayoutParams tl = Kit.weight(1);
        tl.setMarginStart(k.dp(4));
        head.addView(t, tl);
        head.addView(iconButton(R.drawable.ic_close, "关闭", this::closePanel));
        panelColumn.addView(head, new LinearLayout.LayoutParams(Kit.MATCH, k.dp(56)));
        View line = new View(this);
        line.setBackgroundColor(pal.border);
        panelColumn.addView(line, new LinearLayout.LayoutParams(Kit.MATCH, Math.max(1, k.dp(1))));
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);
        LinearLayout body = k.column();
        body.setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(24));
        sv.addView(body);
        panelColumn.addView(sv, new LinearLayout.LayoutParams(Kit.MATCH, 0, 1f));
        return body;
    }

    private View iconButton(int res, String desc, Runnable r) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(k.press(0x00000000, 6, 0));
        b.setClickable(true);
        b.setContentDescription(desc);
        ImageView i = new ImageView(this);
        i.setImageDrawable(k.icon(res, pal.fg2));
        b.addView(i, new FrameLayout.LayoutParams(k.dp(18), k.dp(18), Gravity.CENTER));
        b.setOnClickListener(v -> r.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(k.dp(36), k.dp(36)));
        return b;
    }

    // ------------------------------------------------------------------ menu

    private void buildMenu() {
        // brand row: the console's .app-brand (60px, wordmark on the text axis)
        LinearLayout brand = k.row();
        brand.setPadding(k.dp(20), 0, k.dp(8), 0);
        ImageView word = new ImageView(this);
        word.setImageDrawable(k.wordmark());
        word.setAdjustViewBounds(true);
        word.setScaleType(ImageView.ScaleType.FIT_START);
        word.setClickable(true);
        word.setContentDescription("打开悬浮窗并回到桌面");
        word.setOnClickListener(v -> floatAndGoHome());
        brand.addView(word, new LinearLayout.LayoutParams(Kit.WRAP, k.dp(20)));
        View spacer = new View(this);
        brand.addView(spacer, Kit.weight(1));
        brand.addView(iconButton(R.drawable.ic_website, "官网", () -> { closePanel(); openExternal(ShellUpdater.SITE); }));
        brand.addView(iconButton(R.drawable.ic_github, "GitHub 项目主页", () -> { closePanel(); openExternal(ShellUpdater.HOME); }));
        brand.addView(iconButton(R.drawable.ic_close, "关闭", this::closePanel));
        panelColumn.addView(brand, new LinearLayout.LayoutParams(Kit.MATCH, k.dp(60)));

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = k.column();
        list.setPadding(k.dp(10), 0, k.dp(10), k.dp(12));
        sv.addView(list);
        panelColumn.addView(sv, new LinearLayout.LayoutParams(Kit.MATCH, 0, 1f));

        boolean running = OmcService.isUp();

        list.addView(k.groupTitle("界面", true));
        add(list, R.drawable.ic_reload, "重新加载界面", null, () -> { closePanel(); reloadConsole(); });
        add(list, R.drawable.ic_external, "用浏览器打开", null, () -> { closePanel(); openExternal(Stack.consoleUrl(this)); });
        add(list, R.drawable.ic_float, FloatService.isShowing() ? "关闭悬浮窗" : "打开悬浮窗", FloatService.isShowing() ? "已打开" : null,
                () -> { closePanel(); toggleFloat(); });
        add(list, R.drawable.ic_sliders, "悬浮窗设置", Prefs.floatAlpha(this) + "% · " + Prefs.floatWidth(this) + "×" + Prefs.floatHeight(this),
                () -> showPage("float"));

        list.addView(k.groupTitle("服务", false));
        LinearLayout st = k.row();
        st.setPadding(k.dp(12), k.dp(6), k.dp(10), k.dp(8));
        statusDot = k.dot(healthColor(), 8);
        st.addView(statusDot);
        statusLine = k.text(healthText(), 13, pal.fg);
        statusLine.setSingleLine(true);
        LinearLayout.LayoutParams sl = Kit.weight(1);
        sl.setMarginStart(k.dp(12));
        st.addView(statusLine, sl);
        st.addView(k.text(":" + Prefs.cpaPort(this) + " · :" + Prefs.omcPort(this), 12, pal.meta));
        list.addView(st, new LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP));
        add(list, R.drawable.ic_restart, "重启服务", "CPA + oh-my-cpa", () -> { closePanel(); restartServices(); });
        add(list, R.drawable.ic_power, running ? "停止服务" : "启动服务", null, () -> {
            if (OmcService.isUp()) {
                k.confirm("停止服务？", "CLIProxyAPI 和 oh-my-cpa 都会停止，本机其他应用将连不上 127.0.0.1:" + Prefs.cpaPort(this)
                        + "，远程访问也会断开。", "停止", true, () -> { OmcService.stop(this); showPage(null); }, null);
            } else {
                Stack.problem = "";
                OmcService.start(this);
                closePanel();
            }
        });

        list.addView(k.groupTitle("连接", false));
        add(list, R.drawable.ic_globe, "远程访问", remoteHint(), () -> showPage("remote"));
        add(list, R.drawable.ic_link, "地址与复制", shortAddress(), () -> showPage("addresses"));

        list.addView(k.groupTitle("系统", false));
        add(list, R.drawable.ic_package, "组件信息", Prefs.installed(this, Installer.CPA.name) + " · " + Prefs.installed(this, Installer.OMC.name),
                () -> showPage("components"));
        add(list, R.drawable.ic_settings, "应用设置", null, () -> showPage("settings"));
        add(list, R.drawable.ic_storage, "空间管理", null, () -> showPage("storage"));
        add(list, R.drawable.ic_logs, "查看日志", null, () -> showPage("logs"));
        Kit.NavItem upd = add(list, R.drawable.ic_download, "检查更新", null, () -> showPage("updates"));
        if (!Prefs.updatesSeen(this).isEmpty() || Updater.newer(Prefs.shellSeen(this), APP_VERSION)) {
            upd.flag.setVisibility(View.VISIBLE);
            upd.trailing.setText("有新版本");
            upd.trailing.setTextColor(pal.warn);
            upd.trailing.setVisibility(View.VISIBLE);
        }

        TextView foot = k.text("Oh My CPA " + APP_VERSION + " · CLIProxyAPI + oh-my-cpa", 11, pal.meta);
        foot.setPadding(k.dp(20), k.dp(10), k.dp(20), k.dp(14));
        panelColumn.addView(foot);
    }

    private final java.util.Map<String, Kit.NavItem> navItems = new java.util.HashMap<>();

    private Kit.NavItem add(LinearLayout list, int icon, String label, String trailing, Runnable r) {
        Kit.NavItem n = k.nav(icon, label, trailing, r);
        list.addView(n.view);
        String key = keyOf(label);
        if (key != null && key.equals(lastPage)) k.select(n, true);
        return n;
    }

    private static String keyOf(String label) {
        switch (label) {
            case "悬浮窗设置": return "float";
            case "远程访问": return "remote";
            case "地址与复制": return "addresses";
            case "组件信息": return "components";
            case "应用设置": return "settings";
            case "查看日志": return "logs";
            case "检查更新": return "updates";
            case "空间管理": return "storage";
            default: return null;
        }
    }

    // ------------------------------------------------------------------ addresses

    /** the phone's LAN IPv4 (192.168.x.x preferred, then 10.x / 172.16-31.x), or null */
    private static String lanIp() {
        String best = null;
        int bestRank = 99;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                boolean tunnel = name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec");
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    int rank = ip.startsWith("192.168.") ? 0 : a.isSiteLocalAddress() ? 1 : 3;
                    if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))) rank += 1;
                    if (tunnel) rank += 5;
                    if (rank < bestRank) { bestRank = rank; best = ip; }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    /** what the menu shows: Cloudflare, then Tailscale, then LAN, then loopback */
    private String shortAddress() {
        String cf = Tunnels.cloudflareUrl();
        if (!cf.isEmpty()) return cf.replaceFirst("^https?://", "");
        String ts = Tunnels.tailscaleIp();
        if (!ts.isEmpty()) return ts + ":" + Prefs.omcPort(this);
        if (Prefs.lan(this)) {
            String ip = lanIp();
            if (ip != null) return ip + ":" + Prefs.omcPort(this);
        }
        return "127.0.0.1:" + Prefs.omcPort(this);
    }

    private void pageAddresses(LinearLayout body) {
        int cp = Prefs.cpaPort(this), op = Prefs.omcPort(this);
        TextView hint = k.caption("点按复制。API 地址都已带 /v1，客户端 API Key 在控制台「密钥管理」里创建。");
        body.addView(hint, k.lpm(Kit.MATCH, Kit.WRAP, 0, 12));
        String cf = Tunnels.cloudflareUrl();
        if (!cf.isEmpty()) {
            LinearLayout c = k.card();
            k.cardTitle(c, "Cloudflare 公网");
            copyRow(c, "控制台", cf + "/");
            if (Prefs.cfApi(this)) copyRow(c, "CPA API", cf + "/v1");
            body.addView(c);
        }
        String ts = Tunnels.tailscaleIp();
        if (!ts.isEmpty()) {
            LinearLayout c = k.card();
            k.cardTitle(c, "Tailscale");
            copyRow(c, "控制台", "http://" + ts + ":" + op + "/");
            copyRow(c, "CPA API", "http://" + ts + ":" + cp + "/v1");
            body.addView(c);
        }
        if (Prefs.lan(this)) {
            String ip = lanIp();
            LinearLayout c = k.card();
            k.cardTitle(c, "局域网");
            if (ip == null) c.addView(k.caption("没有找到局域网 IP（Wi-Fi 未连接？）"));
            else {
                copyRow(c, "控制台", "http://" + ip + ":" + op + "/");
                copyRow(c, "CPA API", "http://" + ip + ":" + cp + "/v1");
            }
            body.addView(c);
        }
        LinearLayout c = k.card();
        k.cardTitle(c, "本机");
        copyRow(c, "控制台", Stack.consoleUrl(this));
        copyRow(c, "CPA API", Stack.apiUrl(this));
        body.addView(c);
        LinearLayout s = k.card();
        k.cardTitle(s, "登录");
        k.actionRow(s, R.drawable.ic_key, "复制控制台密码", "也是 CPA 管理密钥；App 内会自动登录", () -> copy("控制台密码", Prefs.managementKey(this)));
        body.addView(s);
    }

    private void copyRow(LinearLayout card, String label, String url) {
        k.actionRow(card, R.drawable.ic_copy, label, url, () -> copy(label, url));
    }

    // ------------------------------------------------------------------ float settings

    private void pageFloat(LinearLayout body) {
        body.addView(k.caption("拖动滑块立即生效；悬浮窗右下角的 ◢ 也能直接拖拽调大小。长按右下角按钮或点面板顶部的标志，会收起成悬浮球并回到桌面。"),
                k.lpm(Kit.MATCH, Kit.WRAP, 0, 12));
        LinearLayout win = k.card();
        k.cardTitle(win, "悬浮窗");
        final SeekBar[] wnd = new SeekBar[3];
        Kit.Slide look = (v, user) -> {
            if (!user || wnd[2] == null) return;
            Prefs.saveFloatLook(this, wnd[0].getProgress(), wnd[1].getProgress(), wnd[2].getProgress());
            FloatService.apply(this);
        };
        wnd[0] = k.slider(win, "不透明度", 20, 100, Prefs.floatAlpha(this), "%", look);
        wnd[1] = k.slider(win, "宽度", 30, 100, Prefs.floatWidth(this), "%", look);
        wnd[2] = k.slider(win, "高度", 20, 95, Prefs.floatHeight(this), "%", look);
        k.switchRow(win, "点击穿透（只看不点）", "触摸穿过悬浮窗交给下面的应用，不透明度最高 80%", Prefs.floatLock(this), on -> {
            Prefs.saveFloatLock(this, on);
            FloatService.apply(this);
        });
        body.addView(win);

        LinearLayout bub = k.card();
        k.cardTitle(bub, "悬浮球");
        final SeekBar[] bb = new SeekBar[3];
        Kit.Slide bl = (v, user) -> {
            if (!user || bb[2] == null) return;
            Prefs.saveBubbleLook(this, bb[0].getProgress(), bb[1].getProgress());
            Prefs.saveBubbleHideDelay(this, bb[2].getProgress());
            FloatService.apply(this);
        };
        bb[0] = k.slider(bub, "大小", 32, 96, Prefs.bubbleSize(this), "dp", bl);
        bb[1] = k.slider(bub, "不透明度", 20, 100, Prefs.bubbleAlpha(this), "%", bl);
        bb[2] = k.slider(bub, "隐藏延迟", 1, 15, Prefs.bubbleHideDelay(this), "s", bl);
        k.switchRow(bub, "自动贴边", "松手后吸附到最近的屏幕左右边缘", Prefs.bubbleSnap(this), on -> { Prefs.saveBubbleSnap(this, on); FloatService.apply(this); });
        k.switchRow(bub, "自动隐藏", "一段时间不碰就缩进屏幕边缘一半并变淡", Prefs.bubbleHide(this), on -> { Prefs.saveBubbleHide(this, on); FloatService.apply(this); });
        body.addView(bub);

        TextView reset = k.button("恢复默认", Kit.DEFAULT, () -> {
            Prefs.saveFloatLook(this, 90, 90, 45);
            Prefs.saveFloatPos(this, 24, 200);
            Prefs.saveFloatLock(this, false);
            Prefs.saveBubbleLook(this, 36, 90);
            Prefs.saveBubbleHideDelay(this, 3);
            Prefs.saveBubbleSnap(this, true);
            Prefs.saveBubbleHide(this, true);
            Prefs.saveBubblePos(this, -1, -1);
            FloatService.apply(this);
            showPage("float");
        });
        TextView open = k.button(FloatService.isShowing() ? "关闭悬浮窗" : "打开悬浮窗", Kit.PRIMARY, () -> { closePanel(); toggleFloat(); });
        body.addView(k.buttons(reset, open));
    }

    // ------------------------------------------------------------------ remote access

    private String remoteHint() {
        List<String> on = new ArrayList<>();
        if (Tunnels.cloudflareRunning()) on.add("Cloudflare");
        if (Tunnels.tailscaleRunning()) on.add("Tailscale");
        return on.isEmpty() ? "未开启" : String.join(" · ", on);
    }

    private void pageRemote(LinearLayout body) {
        final int op = Prefs.omcPort(this), cp = Prefs.cpaPort(this);
        body.addView(k.caption("不用公网 IP 也能在外面打开控制台、调用 API。cloudflared 和 Tailscale 第一次开启时下载，之后直接用。控制台始终需要登录密码。"),
                k.lpm(Kit.MATCH, Kit.WRAP, 0, 12));

        LinearLayout cf = k.card();
        LinearLayout cfHead = k.row();
        cfHead.addView(k.image(k.icon(R.drawable.ic_cloud, pal.muted), 17));
        TextView cft = k.bold("Cloudflare 临时公网地址", 14, pal.fg);
        LinearLayout.LayoutParams cftl = Kit.weight(1);
        cftl.setMarginStart(k.dp(10));
        cfHead.addView(cft, cftl);
        cf.addView(cfHead);
        final TextView cfStatus = k.caption("");
        cfStatus.setTextIsSelectable(true);
        cf.addView(cfStatus, k.lpm(Kit.MATCH, Kit.WRAP, 6, 4));
        final Switch[] cfSw = {null};
        cfSw[0] = k.switchRow(cf, "开启 HTTPS 公网地址", "生成 xxx.trycloudflare.com 指向控制台（:" + op + "）。每次开启地址都会变。", Tunnels.cloudflareRunning(), on -> {
            if (!on) {
                Prefs.setCfOn(this, false);
                bg.execute(() -> Tunnels.stopCloudflare(true));
                return;
            }
            if (Tunnels.cloudflareRunning()) return;
            k.confirm("暴露到公网？", "开启后控制台会出现在一个公开的 HTTPS 地址上，任何拿到地址的人都能打开登录页；登录靠控制台密码保护。"
                            + (Prefs.cfApi(this) ? "\n\n/v1 也会转发到 CPA API，请确认已在控制台创建 API Key。" : ""),
                    "开启", false, () -> {
                        Prefs.setCfOn(this, true);
                        bg.execute(() -> {
                            try {
                                if (!Tunnels.hasCloudflared(this)) {
                                    Tunnels.downloadCloudflared(this, (s, pc) -> ui.post(() -> cfStatus.setText(pc >= 0 ? s + " " + pc + "%" : s)));
                                }
                                Tunnels.startCloudflare(this);
                            } catch (IOException e) {
                                Prefs.setCfOn(this, false);
                                ui.post(() -> { cfStatus.setText("失败：" + e.getMessage()); cfSw[0].setChecked(false); });
                            }
                        });
                    }, () -> cfSw[0].setChecked(false));
        });
        k.switchRow(cf, "同一地址转发 CPA API", "把 /v1、/v1beta 交给 CLIProxyAPI（:" + cp + "），其他路径仍是控制台。未在真机验证。", Prefs.cfApi(this), on -> {
            Prefs.setCfApi(this, on);
            if (Tunnels.cloudflareRunning()) {
                toast("正在重建隧道，公网地址会变");
                bg.execute(() -> { try { Tunnels.startCloudflare(this); } catch (IOException e) { ui.post(() -> cfStatus.setText("失败：" + e.getMessage())); } });
            }
        });
        k.actionRow(cf, R.drawable.ic_copy, "复制公网地址", Prefs.cfApi(this) ? "控制台 / 与 API /v1" : "控制台", () -> {
            String u = Tunnels.cloudflareUrl();
            if (u.isEmpty()) toast("还没有公网地址");
            else copy("公网地址", Prefs.cfApi(this) ? u + "/v1" : u + "/");
        });
        body.addView(cf);

        LinearLayout tsc = k.card();
        LinearLayout tsHead = k.row();
        tsHead.addView(k.image(k.icon(R.drawable.ic_nodes, pal.muted), 17));
        TextView tst = k.bold("Tailscale 虚拟局域网", 14, pal.fg);
        LinearLayout.LayoutParams tstl = Kit.weight(1);
        tstl.setMarginStart(k.dp(10));
        tsHead.addView(tst, tstl);
        tsc.addView(tsHead);
        final TextView tsStatus = k.caption("");
        tsStatus.setTextIsSelectable(true);
        tsc.addView(tsStatus, k.lpm(Kit.MATCH, Kit.WRAP, 6, 4));
        tsc.addView(k.label("AuthKey（可空，空着就用浏览器登录）"), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        final EditText key = k.field("tskey-auth-…", Prefs.tsAuthKey(this), false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTypeface(Kit.FONT);
        tsc.addView(key);
        tsc.addView(k.label("主机名"), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        final EditText host = k.field("oh-my-cpa-android", Prefs.tsHostname(this), false);
        tsc.addView(host);
        final String[] authUrl = {""};
        final String[] tsIp = {""};
        final Switch[] tsSw = {null};
        tsSw[0] = k.switchRow(tsc, "开启 Tailscale（用户态，免 Root）", "不占系统 VPN；同一 tailnet 的设备用 100.x.x.x:" + op + " 打开控制台、:" + cp + "/v1 调用 API",
                Tunnels.tailscaleRunning(), on -> {
                    Prefs.setTs(this, key.getText().toString().trim(), host.getText().toString().trim());
                    Prefs.setTsOn(this, on);
                    if (!on) {
                        bg.execute(() -> Tunnels.stopTailscale(true));
                        return;
                    }
                    if (Tunnels.tailscaleRunning()) return;
                    bg.execute(() -> {
                        try {
                            if (!Tunnels.hasTailscale(this)) {
                                Tunnels.downloadTailscale(this, (s, pc) -> ui.post(() -> tsStatus.setText(pc >= 0 ? s + " " + pc + "%" : s)));
                            }
                            ui.post(() -> tsStatus.setText("正在启动 tailscaled…"));
                            Tunnels.startTailscale(this);
                        } catch (IOException e) {
                            Prefs.setTsOn(this, false);
                            ui.post(() -> { tsStatus.setText("失败：" + e.getMessage()); tsSw[0].setChecked(false); });
                        }
                    });
                });
        k.actionRow(tsc, R.drawable.ic_external, "登录 / 应用设置", "保存 AuthKey 和主机名并重新连接；没有 AuthKey 时打开浏览器登录", () -> {
            Prefs.setTs(this, key.getText().toString().trim(), host.getText().toString().trim());
            if (!authUrl[0].isEmpty()) { openExternal(authUrl[0]); return; }
            if (!Tunnels.tailscaleRunning()) { toast("先打开上面的 Tailscale 开关"); return; }
            tsStatus.setText("正在重新连接…");
            bg.execute(() -> Tunnels.up(this));
        });
        k.actionRow(tsc, R.drawable.ic_close, "退出 Tailscale 登录", "从 tailnet 移除这台设备的登录状态", () -> {
            if (!Tunnels.tailscaleRunning()) return;
            bg.execute(() -> Tunnels.ts(this, 15, "logout"));
            toast("已退出登录");
        });
        k.actionRow(tsc, R.drawable.ic_copy, "复制 Tailscale 地址", "控制台与 API", () -> {
            if (tsIp[0].isEmpty()) toast("还没有 Tailscale IP");
            else copy("Tailscale 地址", "控制台 http://" + tsIp[0] + ":" + op + "/\nAPI http://" + tsIp[0] + ":" + cp + "/v1");
        });
        body.addView(tsc);

        LinearLayout more = k.card();
        k.cardTitle(more, "组件与日志");
        k.actionRow(more, R.drawable.ic_download, "检查 cloudflared / Tailscale 更新", "只检查已下载的组件，有新版本由你决定是否更新", this::checkTunnelUpdates);
        k.actionRow(more, R.drawable.ic_terminal, "查看隧道日志", "cloudflared 和 tailscaled 的输出", () -> { logTab = 3; showPage("logs"); });
        body.addView(more);

        pageTick = new Runnable() {
            @Override public void run() {
                if (!"remote".equals(page)) return;
                bg.execute(() -> {
                    String cfUrl = Tunnels.cloudflareUrl(), cfSt = Tunnels.cloudflareState();
                    String[] ts = Tunnels.tailscaleStatus(MainActivity.this);
                    ui.post(() -> {
                        if (!"remote".equals(page)) return;
                        String cur = cfStatus.getText().toString();
                        if (!cfUrl.isEmpty()) cfStatus.setText("已连接：" + cfUrl);
                        else if (!cfSt.isEmpty()) cfStatus.setText(cfSt);
                        else if (!cur.startsWith("失败") && !cur.startsWith("正在下载"))
                            cfStatus.setText(Tunnels.hasCloudflared(MainActivity.this) ? "未开启" : "未开启（首次开启会下载约 40 MB）");
                        authUrl[0] = ts[3];
                        tsIp[0] = ts[1];
                        String tcur = tsStatus.getText().toString();
                        switch (ts[0]) {
                            case "Running":
                                tsStatus.setText("已连接：" + ts[1] + (ts[2].isEmpty() ? "" : "\n" + ts[2]));
                                break;
                            case "NeedsLogin":
                                tsStatus.setText(ts[3].isEmpty() ? "需要登录：填 AuthKey 后点「登录 / 应用设置」" : "需要登录：点「登录 / 应用设置」在浏览器里完成授权");
                                break;
                            case "Stopped":
                                if (!tcur.startsWith("失败") && !tcur.startsWith("正在"))
                                    tsStatus.setText(Tunnels.hasTailscale(MainActivity.this) ? "未开启" : "未开启（首次开启会下载约 30 MB）");
                                break;
                            default:
                                tsStatus.setText("状态：" + ts[0]);
                        }
                    });
                });
                ui.postDelayed(this, 2000);
            }
        };
        pageTick.run();
    }

    private void checkTunnelUpdates() {
        final String[] names = {"cloudflared", "tailscale"};
        final boolean[] have = {Tunnels.hasCloudflared(this), Tunnels.hasTailscale(this)};
        if (!have[0] && !have[1]) {
            toast("还没有下载过 cloudflared 或 Tailscale，第一次打开开关时会下载最新版");
            return;
        }
        toast("正在检查更新…");
        bg.execute(() -> {
            final StringBuilder msg = new StringBuilder();
            final List<String> outdated = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                String label = i == 0 ? "cloudflared" : "Tailscale";
                if (!have[i]) { msg.append(label).append("：未下载\n"); continue; }
                String cur = Tunnels.installedVersion(this, names[i]);
                String latest;
                try { latest = Tunnels.latestVersion(this, names[i]); } catch (IOException e) { latest = ""; }
                msg.append(label).append("：当前 ").append(cur.isEmpty() ? "?" : cur).append("，最新 ").append(latest.isEmpty() ? "查询失败" : latest);
                if (Tunnels.newer(latest, cur)) { msg.append("（可更新）"); outdated.add(names[i]); }
                else if (!latest.isEmpty()) msg.append("（已是最新）");
                msg.append("\n");
            }
            ui.post(() -> {
                if (outdated.isEmpty()) {
                    k.message("组件更新", msg.toString().trim());
                    return;
                }
                k.confirm("组件更新", msg.toString().trim(), "更新", false, () -> {
                    toast("正在后台更新，运行中的隧道会短暂断开");
                    bg.execute(() -> {
                        StringBuilder r = new StringBuilder();
                        for (String n : outdated) {
                            try {
                                Tunnels.updateComponent(this, n, null);
                                r.append(n).append(" 已更新到 ").append(Tunnels.installedVersion(this, n)).append("\n");
                            } catch (IOException e) {
                                r.append(n).append(" 更新失败：").append(e.getMessage()).append("\n");
                            }
                        }
                        ui.post(() -> k.message("组件更新", r.toString().trim()));
                    });
                }, null);
            });
        });
    }

    // ------------------------------------------------------------------ components

    private void pageComponents(LinearLayout body) {
        String cpaV = Prefs.installed(this, Installer.CPA.name), omcV = Prefs.installed(this, Installer.OMC.name);
        LinearLayout a = k.card();
        k.cardTitle(a, "CLIProxyAPI");
        k.kv(a, "版本", (cpaV.isEmpty() ? "未安装" : "v" + cpaV) + (cpaV.equals(Installer.CPA.version) ? "（与内置版本相同）" : "（内置回退 v" + Installer.CPA.version + "）"));
        k.kv(a, "文件", Installer.bin(this, Installer.CPA).getAbsolutePath() + "  " + size(Installer.bin(this, Installer.CPA)));
        k.kv(a, "配置", Stack.cpaConfig(this).getAbsolutePath());
        k.kv(a, "API", "http://" + Stack.bindHost(this) + ":" + Prefs.cpaPort(this) + "/v1");
        boolean pOn = Plugins.enabledInConfig(this);
        String pv = Prefs.installed(this, Plugins.BIN);
        String mode = Stack.pluginMode ? "插件版（glibc " + Plugins.GLIBC_VERSION + " 加载）"
                : pOn ? (Stack.pluginBroken ? "无插件版（插件版无法运行，已回退）" : "无插件版（正在切换到插件版）") : "无插件版（控制台未启用插件系统）";
        k.kv(a, "运行", mode);
        if (!pv.isEmpty()) k.kv(a, "插件版", "v" + pv + "  " + size(Plugins.bin(this)));
        if (!Stack.pluginNote.isEmpty()) a.addView(k.caption(Stack.pluginNote), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        a.addView(k.caption("在控制台打开「启用插件系统」后，App 会自动下载插件版 CLIProxyAPI（约 20 MB）并切换；关闭后切回无插件版。各插件自己的启用状态不变。"), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        body.addView(a);

        LinearLayout b = k.card();
        k.cardTitle(b, "oh-my-cpa");
        k.kv(b, "版本", (omcV.isEmpty() ? "未安装" : "v" + omcV) + (omcV.equals(Installer.OMC.version) ? "（与内置版本相同）" : "（内置回退 v" + Installer.OMC.version + "）"));
        k.kv(b, "文件", Installer.bin(this, Installer.OMC).getAbsolutePath() + "  " + size(Installer.bin(this, Installer.OMC)));
        k.kv(b, "数据", new File(getFilesDir(), "omc/data").getAbsolutePath());
        k.kv(b, "控制台", "http://" + Stack.bindHost(this) + ":" + Prefs.omcPort(this) + "/");
        body.addView(b);

        LinearLayout h = k.card();
        k.cardTitle(h, "健康检查");
        h.addView(k.caption("GET http://127.0.0.1:" + Prefs.omcPort(this) + "/api/healthz"));
        final TextView out = k.outputBox(h, 150);
        final Runnable check = () -> {
            out.setText("正在检查…");
            bg.execute(() -> {
                String r = healthz();
                ui.post(() -> out.setText(r));
            });
        };
        h.addView(k.buttons(k.button("重新检查", Kit.DEFAULT, check)));
        body.addView(h);
        check.run();

        LinearLayout n = k.card();
        k.cardTitle(n, "网络");
        k.switchRow(n, "网络兼容（DNS 代理）", "两个程序没有 Android 的 DNS，开启后由 App 代为解析域名并转发（建议开启）。切换后重启服务。", Prefs.compatNet(this), on -> {
            Prefs.setCompatNet(this, on);
            k.confirm("现在重启服务？", "网络兼容" + (on ? "已开启" : "已关闭") + "，重启后生效。", "重启", false, this::restartServices, null);
        });
        n.addView(k.label("GitHub 下载镜像（可空）"), k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
        final EditText mirror = k.field("例如 https://ghfast.top/", Prefs.mirrorPrefix(this), false);
        n.addView(mirror);
        n.addView(k.caption("首次下载、组件更新和 cloudflared 都走这个前缀；SHA-256 照样校验。"));
        n.addView(k.buttons(
                k.button("ghfast.top", Kit.TEXT, () -> mirror.setText(Installer.MIRROR)),
                k.button("保存", Kit.PRIMARY, () -> { Prefs.setMirrorPrefix(this, mirror.getText().toString()); mirror.setText(Prefs.mirrorPrefix(this)); toast("已保存下载镜像"); })));
        body.addView(n);

        LinearLayout s = k.card();
        k.cardTitle(s, "管理密钥 / 控制台密码");
        s.addView(k.caption("CPA 管理密钥同时是 oh-my-cpa 控制台的登录密码。App 内会自动登录；在浏览器或其他设备登录时用它。"));
        final String key = Prefs.managementKey(this);
        final TextView val = k.value(mask(key));
        s.addView(val, k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
        final boolean[] shown = {false};
        s.addView(k.buttons(
                k.button("显示", Kit.DEFAULT, () -> { shown[0] = !shown[0]; val.setText(shown[0] ? key : mask(key)); }),
                k.button("复制", Kit.PRIMARY, () -> copy("控制台密码", key))));
        body.addView(s);
    }

    private static String mask(String s) {
        return s.length() <= 8 ? "••••••••" : s.substring(0, 4) + "••••••••••••••••••••" + s.substring(s.length() - 4);
    }

    private static String size(File f) {
        return f.isFile() ? String.format(Locale.US, "%.1f MB", f.length() / 1048576.0) : "";
    }

    private String healthz() {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + Prefs.omcPort(this) + "/api/healthz").openConnection();
            h.setConnectTimeout(3000);
            h.setReadTimeout(5000);
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String body = "";
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0 && out.size() < 65536) out.write(b, 0, n);
                in.close();
                body = out.toString("UTF-8");
            }
            try {
                body = new org.json.JSONObject(body).toString(2);
            } catch (Exception ignored) {
            }
            return "HTTP " + code + "\n" + body;
        } catch (IOException e) {
            return "连接失败：" + e.getMessage() + "\n（服务没有运行，或控制台端口被占用）";
        } finally {
            if (h != null) h.disconnect();
        }
    }

    // ------------------------------------------------------------------ app settings

    /** unsaved settings-page state, kept across page rebuilds (resume, theme change) until save/cancel */
    private android.os.Bundle settingsDraft;

    private boolean draftB(String key, boolean saved) {
        return settingsDraft != null && settingsDraft.containsKey(key) ? settingsDraft.getBoolean(key) : saved;
    }

    private String draftS(String key, String saved) {
        return settingsDraft != null && settingsDraft.containsKey(key) ? settingsDraft.getString(key) : saved;
    }

    private void draftPut(String key, boolean v) {
        if (settingsDraft == null) settingsDraft = new android.os.Bundle();
        settingsDraft.putBoolean(key, v);
    }

    private void pageSettings(LinearLayout body) {
        LinearLayout ports = k.card();
        k.cardTitle(ports, "端口");
        ports.addView(k.label("CLIProxyAPI 端口"), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        final EditText cpaF = k.field(String.valueOf(Prefs.DEFAULT_CPA_PORT), draftS("cpaPort", String.valueOf(Prefs.cpaPort(this))), true);
        ports.addView(cpaF);
        ports.addView(k.label("控制台端口（oh-my-cpa）"), k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
        final EditText omcF = k.field(String.valueOf(Prefs.DEFAULT_OMC_PORT), draftS("omcPort", String.valueOf(Prefs.omcPort(this))), true);
        android.text.TextWatcher portWatch = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c2, int d) { }
            public void onTextChanged(CharSequence a, int b, int c2, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                if (settingsDraft == null) settingsDraft = new android.os.Bundle();
                settingsDraft.putString("cpaPort", cpaF.getText().toString());
                settingsDraft.putString("omcPort", omcF.getText().toString());
            }
        };
        cpaF.addTextChangedListener(portWatch);
        omcF.addTextChangedListener(portWatch);
        ports.addView(omcF);
        ports.addView(k.caption("保存时只改写 CPA 配置里 server 段的 host/port 两行，其他内容（含已哈希的密钥）不动。"), k.lpm(Kit.MATCH, Kit.WRAP, 6, 0));
        body.addView(ports);

        LinearLayout net = k.card();
        k.cardTitle(net, "网络");
        final Switch[] mdnsSw = {null};
        final boolean lanStart = draftB("lan", Prefs.lan(this));
        final Switch lan = k.switchRow(net, "局域网访问（0.0.0.0）", "同一网络的设备可以打开控制台（需要密码）并调用 CPA API（需要控制台里创建的 API Key）。管理接口仍只允许本机。改完点「保存并应用」生效。", lanStart, on -> { });
        final boolean[] confirming = {false};
        lan.setOnCheckedChangeListener((b, on) -> {
            Stack.appLog(this, "settings: LAN switch -> " + on);
            if (confirming[0]) return;
            draftPut("lan", on);
            if (mdnsSw[0] != null) { mdnsSw[0].setEnabled(on); if (!on) mdnsSw[0].setChecked(false); }
            if (!on || Prefs.lan(this) || (settingsDraft != null && settingsDraft.getBoolean("lanConfirmed", false))) return;
            k.confirm("开启局域网访问？", "同一 Wi-Fi 里的其他设备将能访问控制台登录页和 CPA API。控制台由登录密码保护，API 需要 API Key。只在家里、公司这类可信网络中开启。开关打开后，点「保存并应用」才会生效。",
                    "开启", false,
                    () -> { draftPut("lanConfirmed", true); },
                    () -> { confirming[0] = true; b.setChecked(false); confirming[0] = false; draftPut("lan", false);
                            if (mdnsSw[0] != null) { mdnsSw[0].setEnabled(false); mdnsSw[0].setChecked(false); } });
        });
        mdnsSw[0] = k.switchRow(net, "局域网自发现（mDNS）", "以 _http._tcp 广播控制台和 CPA API，支持 Bonjour 的设备可直接发现。需要局域网访问。", draftB("mdns", Prefs.mdns(this) && Prefs.lan(this)), on -> draftPut("mdns", on));
        mdnsSw[0].setEnabled(lanStart);
        final Switch compat = k.switchRow(net, "网络兼容（DNS 代理）", "建议开启，关闭后两个程序可能解析不了域名", draftB("compat", Prefs.compatNet(this)), on -> draftPut("compat", on));
        body.addView(net);

        LinearLayout sys = k.card();
        k.cardTitle(sys, "启动与集成");
        final Switch auto = k.switchRow(sys, "开机自启", "开机后不打开 App 也会启动两个服务", draftB("auto", Prefs.autostart(this)), on -> draftPut("auto", on));
        final Switch pkey = k.switchRow(sys, "插件页自动填入管理密钥", "插件页面（如「降智测试」）会要求输入 CPA 管理密钥。开启后 App 像 CPA 管理中心一样把密钥放进 WebView 的本地存储，插件页直接连接；插件代码能读到它，只给信任的插件用。", draftB("pkey", Prefs.pluginKey(this)), on -> draftPut("pkey", on));
        final Switch inject = k.switchRow(sys, "控制台侧边栏显示「App 设置」", "在控制台自己的导航里加一个入口，点它打开这个面板；右下角按钮始终可用", draftB("inject", Prefs.injectEntry(this)), on -> draftPut("inject", on));
        boolean ignoring = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        k.actionRow(sys, R.drawable.ic_battery, "忽略电池优化", ignoring ? "已忽略，后台不会被系统限制" : "让系统不要在熄屏后冻结服务", this::askForBatteryExemption);
        body.addView(sys);

        TextView cancel = k.button("取消", Kit.DEFAULT, () -> { settingsDraft = null; showPage(null); });
        TextView save = k.button("保存并应用", Kit.PRIMARY, () -> {
            int cp = parse(cpaF), op = parse(omcF);
            if (!Prefs.validPort(cp) || !Prefs.validPort(op)) { toast("端口需要是 1024 到 65535 之间的数字"); return; }
            if (cp == op) { toast("两个端口不能相同"); return; }
            boolean l = lan.isChecked();
            settingsDraft = null;
            Stack.appLog(this, "settings saved: lan=" + l + " ports " + cp + "/" + op);
            boolean changed = cp != Prefs.cpaPort(this) || op != Prefs.omcPort(this) || l != Prefs.lan(this) || compat.isChecked() != Prefs.compatNet(this);
            boolean injectChanged = inject.isChecked() != Prefs.injectEntry(this) || pkey.isChecked() != Prefs.pluginKey(this);
            Prefs.setPluginKey(this, pkey.isChecked());
            Prefs.setPorts(this, cp, op);
            Prefs.setLan(this, l);
            Prefs.setCompatNet(this, compat.isChecked());
            Prefs.setAutostart(this, auto.isChecked());
            Prefs.setInjectEntry(this, inject.isChecked());
            boolean m = l && mdnsSw[0].isChecked();
            Prefs.setMdns(this, m);
            if (m) Tunnels.startMdns(this); else Tunnels.stopMdns(this);
            if (l) requestLocalNetworkPermissionIfNeeded();
            if (changed) {
                restartServices();
                closePanel();
            } else {
                if (injectChanged) reloadConsole();
                toast("已保存");
                showPage(null);
            }
        });
        body.addView(k.buttons(cancel, save));
    }

    private static int parse(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException x) { return 0; }
    }

    private void requestLocalNetworkPermissionIfNeeded() {
        if (checkSelfPermission(PERMISSION_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return;
        try {
            getPackageManager().getPermissionInfo(PERMISSION_LOCAL_NETWORK, 0);
            toast("新版 Android 访问局域网需要单独授权，请在系统弹窗里允许。");
            requestPermissions(new String[]{PERMISSION_LOCAL_NETWORK}, 2);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
    }

    private void askForBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (ActivityNotFoundException ignored) { }
        }
    }

    // ------------------------------------------------------------------ logs

    private int logTab = 0;

    private File logFile(int tab) {
        switch (tab) {
            case 1: return Stack.log(this, "cpa");
            case 2: return Stack.log(this, "app");
            case 4: return CrashLog.file(this);
            default: return Stack.log(this, "omc");
        }
    }

    private String logText(int tab) {
        if (tab == 3) {
            return "== cloudflared ==\n" + Tunnels.tail(Tunnels.log(this, "cloudflared"), 120)
                    + "\n\n== tailscaled ==\n" + Tunnels.tail(Tunnels.log(this, "tailscaled"), 120);
        }
        String t = Stack.tail(logFile(tab), 96 * 1024);
        return t.isEmpty() ? "（暂无日志）" : t;
    }

    private void pageLogs(LinearLayout body) {
        final String[] names = {"oh-my-cpa", "CPA", "App", "隧道", "崩溃"};
        final TextView[] out = {null};
        final Runnable load = () -> {
            String t = logText(logTab);
            out[0].setText(t);
            ScrollView sv = (ScrollView) out[0].getTag();
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        };
        body.addView(k.segmented(names, logTab, i -> { logTab = i; load.run(); }));
        int h = Math.max(260, (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density) - 300);
        out[0] = k.outputBox(body, h);
        load.run();
        body.addView(k.buttons(
                k.button("刷新", Kit.DEFAULT, load),
                k.button("复制", Kit.DEFAULT, () -> copy("日志", tailChars(out[0].getText(), 60000))),
                k.button("分享", Kit.DEFAULT, () -> {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Oh My CPA " + names[logTab] + " 日志")
                            .putExtra(Intent.EXTRA_TEXT, redact(tailChars(out[0].getText(), 60000)));
                    try { startActivity(Intent.createChooser(s, "分享日志")); } catch (ActivityNotFoundException ignored) { }
                })));
        LinearLayout diag = k.card();
        diag.setLayoutParams(k.lpm(Kit.MATCH, Kit.WRAP, 14, 0));
        k.actionRow(diag, R.drawable.ic_share, "分享诊断包", "全部日志、崩溃记录和设备信息打成 zip（已去掉密钥），存到 下载/OhMyCPA 并分享", this::shareDiagnostics);
        body.addView(diag);
    }

    private static String tailChars(CharSequence s, int max) {
        return s.length() <= max ? s.toString() : s.subSequence(s.length() - max, s.length()).toString();
    }

    private String redact(String s) {
        return s.replace(Prefs.managementKey(this), "<管理密钥>").replace(Prefs.masterKey(this), "<主密钥>");
    }

    private void shareDiagnostics() {
        bg.execute(() -> {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(buf)) {
                    z.putNextEntry(new java.util.zip.ZipEntry("device.txt"));
                    z.write(CrashLog.deviceInfo(this).getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                    File logs = Stack.dir(this, "logs");
                    String[] files = logs.list();
                    if (files != null) for (String n : files) {
                        File f = new File(logs, n);
                        if (!f.isFile()) continue;
                        z.putNextEntry(new java.util.zip.ZipEntry(n));
                        z.write(redact(Stack.tail(f, 2 * 1024 * 1024)).getBytes(StandardCharsets.UTF_8));
                        z.closeEntry();
                    }
                }
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                final Uri uri = WebFiles.store(this, new java.io.ByteArrayInputStream(buf.toByteArray()), "oh-my-cpa-diagnostics-" + stamp + ".zip", "application/zip");
                ui.post(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(send, "分享诊断包")); } catch (ActivityNotFoundException ignored) { }
                });
            } catch (IOException e) {
                ui.post(() -> toast("打包失败：" + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ updates

    /** at most every 6 h: look for newer releases and mark 检查更新 */
    private void autoCheck() {
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 6 * 3600_000L || !Installer.installed(this)) return;
        bg.execute(() -> {
            StringBuilder seen = new StringBuilder();
            boolean ok = false;
            for (Installer.Pkg p : Installer.ALL) {
                try {
                    Updater.Release r = Updater.latest(this, p);
                    ok = true;
                    if (Updater.newer(r.version, Prefs.installed(this, p.name))) {
                        if (seen.length() > 0) seen.append(',');
                        seen.append(p.name).append('=').append(r.version);
                    }
                } catch (IOException ignored) {
                }
            }
            if (ok) {
                Prefs.setLastUpdateCheck(this, System.currentTimeMillis());
                Prefs.setUpdatesSeen(this, seen.toString());
            }
        });
    }

    /** a section caption inside a sub-page, like the panel's group titles */
    private void section(LinearLayout body, String title, String hint) {
        TextView t = k.bold(title, 13, pal.fg2);
        body.addView(t, k.lpm(Kit.MATCH, Kit.WRAP, body.getChildCount() == 0 ? 0 : 8, hint == null ? 6 : 2));
        if (hint != null) body.addView(k.caption(hint), k.lpm(Kit.MATCH, Kit.WRAP, 0, 8));
    }

    private void pageUpdates(LinearLayout body) {
        body.addView(k.caption("内核是 CLIProxyAPI 和 oh-my-cpa 两个程序，外壳是这个安卓应用，两者各自更新。"), k.lpm(Kit.MATCH, Kit.WRAP, 0, 12));

        // ---- 内核
        section(body, "内核", "从 GitHub Releases 查询 CLIProxyAPI（linux_aarch64_no-plugin）和 oh-my-cpa（linux_arm64）。"
                + "下载后按发布自带的 checksums.txt 校验 SHA-256，确认后才替换，旧版本保留一份可一键回退。");
        final List<Runnable> checks = new ArrayList<>();
        for (final Installer.Pkg p : Installer.ALL) {
            LinearLayout c = k.card();
            k.cardTitle(c, "内核 · " + Updater.label(p));
            final String cur = Prefs.installed(this, p.name);
            final TextView info = k.text("当前 v" + cur + " · 正在检查…", 13, pal.fg2);
            info.setLineSpacing(k.dp(2), 1f);
            c.addView(info, k.lpm(Kit.MATCH, Kit.WRAP, 2, 0));
            final Updater.Release[] found = {null};
            final TextView install = k.button("更新", Kit.PRIMARY, () -> { });
            install.setVisibility(View.GONE);
            final List<View> row = new ArrayList<>();
            row.add(install);
            if (Updater.canRollback(this, p)) {
                String pv = Prefs.prevVersion(this, p.name);
                row.add(k.button("回退到 " + (pv.isEmpty() ? "上一版" : "v" + pv), Kit.DEFAULT, () ->
                        k.confirm("回退 " + Updater.label(p) + "？", "换回上一版并重启两个服务。", "回退", false, () -> bg.execute(() -> {
                            String v = Updater.rollback(this, p);
                            ui.post(() -> {
                                toast(v == null ? "回退失败，上一版文件不在了" : "已回退到 v" + v + "，正在重启服务");
                                if (v != null) restartServices();
                                if ("updates".equals(page)) showPage("updates");
                            });
                        }), null)));
            }
            c.addView(k.buttons(row.toArray(new View[0])));
            // the update itself runs in Updater (not in this page), so a rebuilt page picks it back up
            final boolean[] watching = {false};
            final Runnable watch = new Runnable() {
                @Override public void run() {
                    Updater.Job j = Updater.job(p);
                    if (j == null || !info.isAttachedToWindow() && watching[0]) { watching[0] = false; return; }
                    watching[0] = true;
                    if (j.running) {
                        info.setText("正在更新到 v" + j.r.version + "\n" + j.line());
                        install.setVisibility(View.VISIBLE);
                        install.setText("更新中…");
                        install.setEnabled(false);
                        install.setAlpha(0.5f);
                        for (View x : row) if (x != install) { x.setEnabled(false); x.setAlpha(0.5f); }
                        ui.postDelayed(this, 400);
                        return;
                    }
                    watching[0] = false;
                    for (View x : row) { x.setEnabled(true); x.setAlpha(1f); }
                    if (j.ok) {
                        info.setText("已更新到 v" + j.r.version + "，"
                                + (Updater.anyRunning() ? "等另一个内核更新完成后一起重启。" : "服务正在重启。"));
                        install.setVisibility(View.GONE);
                    } else {
                        found[0] = j.r;
                        info.setText("更新失败：" + j.error + "\nGitHub 慢可以在下面的「下载设置」里设置镜像。");
                        install.setText("重试更新到 v" + j.r.version);
                        install.setVisibility(View.VISIBLE);
                    }
                }
            };
            install.setOnClickListener(v -> {
                final Updater.Release r = found[0];
                if (r == null) return;
                final String now = Prefs.installed(this, p.name);
                k.confirm("更新到 v" + r.version + "？", Updater.label(p) + " v" + now + " → v" + r.version
                        + "\n\n下载 " + r.archive + "，校验通过后替换并重启两个服务，正在进行的请求会断开。旧版本保留可回退。"
                        + "\n\n可以离开这个页面，进度在通知栏。", "下载并更新", false, () -> {
                    Updater.start(this, r);
                    if (!watching[0]) watch.run();
                }, null);
            });
            Runnable check = () -> {
                Updater.Job j = Updater.job(p);
                if (j != null && j.running) { if (!watching[0]) watch.run(); return; }
                final String now = Prefs.installed(this, p.name);
                info.setText("当前 v" + now + " · 正在检查…");
                bg.execute(() -> {
                    try {
                        Updater.Release r = Updater.latest(this, p);
                        boolean newer = Updater.newer(r.version, now);
                        ui.post(() -> {
                            Updater.Job j2 = Updater.job(p);
                            if (j2 != null && j2.running) return; // an update started meanwhile; watch shows it
                            found[0] = r;
                            info.setText("当前 v" + now + " · 最新 v" + r.version + "\n" + (newer ? "有新版本可以更新。" : "已经是最新版本。"));
                            install.setText("更新到 v" + r.version);
                            install.setEnabled(true);
                            install.setAlpha(1f);
                            install.setVisibility(newer ? View.VISIBLE : View.GONE);
                        });
                    } catch (IOException e) {
                        ui.post(() -> {
                            Updater.Job j2 = Updater.job(p);
                            if (j2 != null && j2.running) return;
                            info.setText("当前 v" + now + "\n检查失败：" + e.getMessage());
                        });
                    }
                });
            };
            checks.add(check);
            body.addView(c);
        }
        body.addView(k.buttons(k.button("重新检查内核", Kit.DEFAULT, () -> { for (Runnable r : checks) r.run(); })),
                k.lpm(Kit.MATCH, Kit.WRAP, 0, 16));

        // ---- 外壳
        section(body, "外壳", "Oh My CPA 安卓应用，来自 GitHub Releases（" + ShellUpdater.REPO + "）。"
                + "下载后校验包名、签名（以及发布页公布的 SHA-256），一致才交给系统安装，设置和数据不会丢。");
        final String cur = APP_VERSION;
        LinearLayout sc = k.card();
        k.cardTitle(sc, "外壳 · Oh My CPA");
        final TextView sInfo = k.text("当前 v" + cur + " · 正在检查…", 13, pal.fg2);
        sInfo.setLineSpacing(k.dp(2), 1f);
        sc.addView(sInfo, k.lpm(Kit.MATCH, Kit.WRAP, 2, 0));
        final TextView sNotes = k.outputBox(sc, 150);
        final View notesBox = (View) sNotes.getTag();
        notesBox.setVisibility(View.GONE);
        final TextView sStatus = k.text("", 12, pal.accent);
        sStatus.setLineSpacing(k.dp(2), 1f);
        sStatus.setVisibility(View.GONE);
        sc.addView(sStatus, k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
        final ShellUpdater.Info[] sFound = {null};
        final TextView browserDl = k.button("浏览器下载", Kit.DEFAULT, () -> {
            if (sFound[0] != null) openExternal(Installer.wrap(this, sFound[0].apkUrl));
        });
        final TextView directDl = k.button("直接下载", Kit.PRIMARY, () -> {
            if (sFound[0] != null) runShellInstall(sFound[0]);
        });
        final LinearLayout sActions = k.buttons(browserDl, directDl);
        sActions.setVisibility(View.GONE);
        sc.addView(sActions);
        final Runnable shellCheck = () -> {
            sInfo.setText("当前 v" + cur + " · 正在检查…");
            notesBox.setVisibility(View.GONE);
            sActions.setVisibility(View.GONE);
            bg.execute(() -> {
                try {
                    ShellUpdater.Info r = ShellUpdater.latest(this);
                    boolean newer = Updater.newer(r.version, cur);
                    Prefs.setShellSeen(this, r.version);
                    String notes = ShellUpdater.plainNotes(r.notes);
                    ui.post(() -> {
                        sFound[0] = r;
                        sInfo.setText("当前 v" + cur + " · 最新 v" + r.version + "\n" + (newer
                                ? "有新版本" + (r.size > 0 ? "，安装包 " + Storage.human(r.size) : "") + "。"
                                : "已经是最新版本。"));
                        if (newer) {
                            sNotes.setText(notes.isEmpty() ? "这个版本没有写更新日志。" : notes);
                            notesBox.setVisibility(View.VISIBLE);
                        }
                        sActions.setVisibility(newer && !ShellUpdater.busy ? View.VISIBLE : View.GONE);
                    });
                } catch (IOException e) {
                    ui.post(() -> sInfo.setText("当前 v" + cur + "\n检查失败：" + e.getMessage()));
                }
            });
        };
        sc.addView(k.buttons(
                k.button("发布页", Kit.TEXT, () -> openExternal(sFound[0] != null && !sFound[0].page.isEmpty() ? sFound[0].page : ShellUpdater.RELEASES)),
                k.button("重新检查", Kit.TEXT, shellCheck)));
        body.addView(sc);
        pageTick = new Runnable() {
            @Override public void run() {
                String st = ShellUpdater.status;
                sStatus.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
                sStatus.setText(st);
                if (ShellUpdater.busy) sActions.setVisibility(View.GONE);
                ui.postDelayed(this, 500);
            }
        };
        pageTick.run();

        // ---- 下载设置
        section(body, "下载设置", "下面的镜像同时用于内核、外壳（包括浏览器下载）和远程访问组件的下载，文件照样校验。");
        LinearLayout n = k.card();
        n.addView(k.label("GitHub 下载镜像（可空）"));
        final EditText mirror = k.field("例如 https://ghfast.top/，留空则直连 GitHub", Prefs.mirrorPrefix(this), false);
        n.addView(mirror);
        n.addView(k.buttons(
                k.button("ghfast.top", Kit.TEXT, () -> mirror.setText(Installer.MIRROR)),
                k.button("保存", Kit.PRIMARY, () -> {
                    Prefs.setMirrorPrefix(this, mirror.getText().toString());
                    mirror.setText(Prefs.mirrorPrefix(this));
                    toast(Prefs.mirrorPrefix(this).isEmpty() ? "已改为直连 GitHub" : "已保存，内核和外壳都会走这个镜像");
                })));
        body.addView(n);

        for (Runnable r : checks) r.run();
        shellCheck.run();
        Prefs.setLastUpdateCheck(this, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ shell update

    /** Once per launch: if the GitHub release is newer than this shell, offer it with its release notes. */
    private void autoCheckShell() {
        final String cur = APP_VERSION;
        bg.execute(() -> {
            final ShellUpdater.Info r;
            try {
                r = ShellUpdater.latest(this);
            } catch (IOException e) {
                Stack.appLog(this, "shell update check: " + e.getMessage());
                return;
            }
            Prefs.setShellSeen(this, r.version);
            if (!Updater.newer(r.version, cur)) return;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || ShellUpdater.busy) return;
                String notes = ShellUpdater.plainNotes(r.notes);
                if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
                String msg = "当前 v" + cur + "  →  最新 v" + r.version
                        + (r.size > 0 ? "（" + Storage.human(r.size) + "）" : "")
                        + "\n\n" + (notes.isEmpty() ? "这个版本没有写更新日志。" : notes);
                k.choice("外壳有新版本 v" + r.version, msg,
                        new String[]{"以后", "浏览器下载", "直接下载"},
                        new Runnable[]{null,
                                () -> openExternal(Installer.wrap(this, r.apkUrl)),
                                () -> { runShellInstall(r); openPanel("updates"); }});
            });
        });
    }

    /** Direct download: fetch, verify, hand to the system installer. Progress goes to ShellUpdater.status and the notification. */
    private void runShellInstall(final ShellUpdater.Info r) {
        if (ShellUpdater.busy) { toast("外壳更新已经在进行"); return; }
        if (!ShellUpdater.canInstall(this)) {
            k.confirm("需要安装权限", "第一次在应用内更新外壳，需要在系统设置里允许 Oh My CPA「安装未知应用」。打开后返回这里再点一次「直接下载」。",
                    "去设置", false, () -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            toast("打不开系统设置");
                        }
                    }, null);
            return;
        }
        ShellUpdater.busy = true;
        ShellUpdater.status = "准备下载外壳 v" + r.version + "…";
        bg.execute(() -> {
            try {
                File apk = ShellUpdater.download(this, r, null);
                ui.post(() -> {
                    ShellUpdater.status = "下载完成，请在系统界面确认安装";
                    try {
                        ShellUpdater.install(this, apk, msg -> ui.post(() -> toast(msg)));
                    } catch (IOException e) {
                        ShellUpdater.busy = false;
                        ShellUpdater.status = "安装失败：" + e.getMessage();
                        UpdateNotifier.shellRetry(this, "外壳安装失败", String.valueOf(e.getMessage()), apk);
                    }
                });
            } catch (IOException e) {
                ShellUpdater.busy = false;
                ShellUpdater.status = "下载失败：" + e.getMessage() + "\n可以设置下载镜像后重试，或改用浏览器下载。";
                UpdateNotifier.finish(this, UpdateNotifier.ID_SHELL, "外壳 v" + r.version + " 下载失败",
                        e.getMessage() + "\n点按打开更新页，可以换镜像重试或用浏览器下载", null);
            }
        });
    }

    /** Tapped 「点按重新安装」 on the notification: hand the already verified file to the installer again. */
    private void reinstallShell(String path) {
        File apk = path == null ? null : new File(path);
        if (apk == null || !apk.isFile()) { toast("安装包已被清理，请重新下载"); openPanel("updates"); return; }
        if (ShellUpdater.busy) { toast("外壳更新已经在进行"); return; }
        if (!ShellUpdater.canInstall(this)) { toast("请先允许 Oh My CPA 安装未知应用"); openPanel("updates"); return; }
        ShellUpdater.busy = true;
        try {
            ShellUpdater.install(this, apk, msg -> ui.post(() -> toast(msg)));
        } catch (IOException e) {
            ShellUpdater.busy = false;
            ShellUpdater.status = "安装失败：" + e.getMessage();
            UpdateNotifier.shellRetry(this, "外壳安装失败", String.valueOf(e.getMessage()), apk);
            toast("安装失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ storage

    private boolean storageAnimated;
    private int storageGen;

    private void pageStorage(LinearLayout body) {
        final int gen = ++storageGen;
        body.addView(k.caption("App 在手机上占用的空间，按大小从大到小排列。可以分类清除；程序本体、CPA 配置和 App 设置（含主密钥）不会提供删除。"),
                k.lpm(Kit.MATCH, Kit.WRAP, 0, 12));
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(pal.accent));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(k.dp(32), k.dp(32));
        pl.gravity = Gravity.CENTER_HORIZONTAL;
        pl.topMargin = k.dp(32);
        body.addView(pb, pl);
        TextView wait = k.caption("正在计算…");
        wait.setGravity(Gravity.CENTER);
        body.addView(wait, k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
        bg.execute(() -> {
            final List<Storage.Cat> cats = Storage.scan(getApplicationContext());
            long t = 0, f = 0;
            try {
                android.os.StatFs fs = new android.os.StatFs(getDataDir().getAbsolutePath());
                t = fs.getTotalBytes();
                f = fs.getAvailableBytes();
            } catch (RuntimeException ignored) {
            }
            final long devTotal = t, devFree = f;
            ui.post(() -> {
                if (gen != storageGen || !"storage".equals(page) || !panelOpen || body.getParent() == null) return;
                body.removeView(pb);
                body.removeView(wait);
                boolean animate = !storageAnimated;
                storageAnimated = true;
                renderStorage(body, cats, devTotal, devFree, animate);
            });
        });
    }

    private LinearLayout.LayoutParams dotLp(LinearLayout.LayoutParams l) {
        l.gravity = Gravity.CENTER_VERTICAL;
        return l;
    }

    private void renderStorage(LinearLayout body, List<Storage.Cat> all, long devTotal, long devFree, boolean animate) {
        final List<Storage.Cat> cats = new ArrayList<>();
        for (Storage.Cat c : all) if (c.size > 0 || c.key.equals(Storage.CPA_BIN)) cats.add(c);
        final long total = Storage.total(cats);
        long freeable = 0;
        for (Storage.Cat c : cats) if (c.safe()) freeable += c.size;
        final long safe = freeable;

        // ---- overview: donut + top-5 legend, then the phone's storage
        LinearLayout over = k.card();
        LinearLayout top = k.row();
        StorageViews.Donut donut = new StorageViews.Donut(this, pal);
        long[] vals = new long[cats.size()];
        int[] cols = new int[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            vals[i] = cats.get(i).size;
            cols[i] = StorageViews.color(pal, cats.get(i).key);
        }
        donut.set(vals, cols, Storage.human(total), "总占用", animate);
        top.addView(donut);
        LinearLayout legend = k.column();
        LinearLayout.LayoutParams ll = Kit.weight(1);
        ll.setMarginStart(k.dp(14));
        int shown = Math.min(cats.size(), 5);
        long rest = 0;
        for (int i = shown; i < cats.size(); i++) rest += cats.get(i).size;
        for (int i = 0; i < shown + (rest > 0 ? 1 : 0); i++) {
            boolean isRest = i == shown;
            LinearLayout r = k.row();
            r.setPadding(0, k.dp(3), 0, k.dp(3));
            r.addView(k.dot(isRest ? pal.meta : cols[i], 8));
            TextView name = k.text(isRest ? "其余 " + (cats.size() - shown) + " 项" : cats.get(i).title, 12, pal.fg2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = Kit.weight(1);
            nl.setMarginStart(k.dp(8));
            r.addView(name, nl);
            long v = isRest ? rest : cats.get(i).size;
            r.addView(k.bold(pct(v, total), 12, pal.fg));
            legend.addView(r);
        }
        top.addView(legend, ll);
        over.addView(top);
        if (devTotal > 0) {
            over.addView(k.divider(), k.lpm(Kit.MATCH, Math.max(1, k.dp(1)), 14, 12));
            LinearLayout head = k.row();
            head.addView(k.bold("手机存储", 13, pal.fg), Kit.weight(1));
            head.addView(k.text("剩余 " + Storage.human(devFree) + " / 共 " + Storage.human(devTotal), 12, pal.muted));
            over.addView(head);
            long used = Math.max(0, devTotal - devFree);
            StorageViews.Bar bar = new StorageViews.Bar(this, pal, 10);
            bar.set(new float[]{total / (float) devTotal, Math.max(0, used - total) / (float) devTotal},
                    new int[]{pal.accent, pal.wash(30)}, animate);
            over.addView(bar, k.lpm(Kit.MATCH, Kit.WRAP, 10, 0));
            LinearLayout keys = k.row();
            keys.setPadding(0, k.dp(10), 0, 0);
            keys.addView(k.dot(pal.accent, 8));
            TextView k1 = k.text("Oh My CPA " + Storage.human(total), 12, pal.fg2);
            k1.setPadding(k.dp(6), 0, k.dp(14), 0);
            keys.addView(k1);
            keys.addView(k.dot(pal.wash(30), 8));
            TextView k2 = k.text("其他应用 " + Storage.human(Math.max(0, used - total)), 12, pal.fg2);
            k2.setPadding(k.dp(6), 0, k.dp(14), 0);
            keys.addView(k2);
            keys.addView(k.dot(pal.surface, 8));
            TextView k3 = k.text("可用", 12, pal.fg2);
            k3.setPadding(k.dp(6), 0, 0, 0);
            keys.addView(k3);
            over.addView(keys);
        }
        body.addView(over);

        // ---- categories, pure size order
        section(body, "分类明细", cats.size() + " 个类别，按占用从大到小排列");
        LinearLayout list = k.card();
        list.setPadding(k.dp(4), k.dp(4), k.dp(4), k.dp(4));
        long max = cats.isEmpty() ? 1 : Math.max(1, cats.get(0).size);
        for (int i = 0; i < cats.size(); i++) {
            final Storage.Cat c = cats.get(i);
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(pal.borderSoft);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Kit.MATCH, Math.max(1, k.dp(1)));
                dl.setMarginStart(k.dp(30));
                dl.setMarginEnd(k.dp(12));
                list.addView(div, dl);
            }
            LinearLayout row = k.column();
            row.setBackground(k.press(0x00000000, 8, 0));
            row.setPadding(k.dp(12), k.dp(11), k.dp(12), k.dp(11));
            int col = StorageViews.color(pal, c.key);
            LinearLayout r1 = k.row();
            r1.addView(k.dot(col, 10));
            TextView title = k.bold(c.title, 14, pal.fg);
            LinearLayout.LayoutParams tl = Kit.weight(1);
            tl.setMarginStart(k.dp(10));
            r1.addView(title, tl);
            TextView pc = k.text(pct(c.size, total), 12, pal.muted);
            pc.setPadding(0, 0, k.dp(8), 0);
            r1.addView(pc);
            r1.addView(k.bold(Storage.human(c.size), 13, pal.fg));
            row.addView(r1);
            StorageViews.Bar bar = new StorageViews.Bar(this, pal, 6);
            bar.set(new float[]{c.size / (float) max}, new int[]{col}, animate);
            LinearLayout.LayoutParams bl = k.lpm(Kit.MATCH, Kit.WRAP, 9, 0);
            bl.setMarginStart(k.dp(20));
            row.addView(bar, bl);
            LinearLayout r3 = k.row();
            r3.setPadding(k.dp(20), k.dp(8), 0, 0);
            TextView desc = k.text(c.desc, 12, pal.muted);
            desc.setLineSpacing(k.dp(1), 1f);
            r3.addView(desc, Kit.weight(1));
            TextView act = k.pill(c.clearable ? "清除" : "系统必需", c.clearable);
            if (c.clearable && c.warn != null && c.restart) act.setTextColor(pal.danger);
            if (c.clearable) act.setOnClickListener(v -> confirmClear(c));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(Kit.WRAP, Kit.WRAP);
            al.setMarginStart(k.dp(12));
            r3.addView(act, al);
            row.addView(r3);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (c.clearable) confirmClear(c);
                else toast("这一项不能清除");
            });
            list.addView(row, new LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP));
        }
        body.addView(list);

        // ---- buttons
        TextView again = k.button("重新计算", Kit.DEFAULT, () -> showPage("storage"));
        TextView clean = k.button("一键清理 " + Storage.human(safe), Kit.PRIMARY, () ->
                k.confirm("一键清理", "清除日志、下载与临时文件、应用缓存和网页缓存，约 " + Storage.human(safe)
                        + "。不影响配置、账号凭据、数据和登录。", "清理", false, () -> {
                    List<Storage.Cat> todo = new ArrayList<>();
                    for (Storage.Cat c : cats) if (c.safe()) todo.add(c);
                    clearCats(todo);
                }, null));
        clean.setEnabled(safe > 0);
        clean.setAlpha(safe > 0 ? 1f : 0.45f);
        body.addView(k.buttons(again, clean));
        TextView foot = k.caption("一键清理包括：日志、下载与临时文件、应用缓存、网页缓存。");
        foot.setGravity(Gravity.END);
        body.addView(foot, k.lpm(Kit.MATCH, Kit.WRAP, 8, 0));
    }

    private static String pct(long v, long total) {
        if (total <= 0) return "0%";
        if (v > 0 && v * 100 < total) return "<1%";
        return Math.round(v * 100.0 / total) + "%";
    }

    private void confirmClear(final Storage.Cat c) {
        String msg = (c.warn == null ? "" : c.warn + "\n\n") + "将释放约 " + Storage.human(c.size) + "。";
        boolean heavy = c.warn != null && c.restart;
        k.confirm("清除" + c.title + "？", msg, "清除", c.warn != null, () -> {
            if (!heavy) {
                clearCats(java.util.Collections.singletonList(c));
                return;
            }
            // data that cannot be recreated: ask twice
            k.confirm("再次确认", "真的要删除「" + c.title + "」吗？删除后无法恢复。", "确认删除", true,
                    () -> clearCats(java.util.Collections.singletonList(c)), null);
        }, null);
    }

    private void clearCats(final List<Storage.Cat> todo) {
        boolean webCache = false, webData = false, restart = false;
        for (Storage.Cat c : todo) {
            if (c.key.equals(Storage.WEB_CACHE)) webCache = true;
            if (c.key.equals(Storage.WEB_DATA)) webData = true;
            if (c.restart) restart = true;
        }
        if ((webCache || webData) && web != null) web.clearCache(true);
        if (webData) {
            android.webkit.WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }
        final boolean reloadPage = webData, rs = restart;
        toast("正在清除…");
        final Context app = getApplicationContext();
        bg.execute(() -> {
            if (rs) Stack.stop(app);
            for (Storage.Cat c : todo) {
                switch (c.key) {
                    case Storage.CLOUDFLARED:
                        Tunnels.stopCloudflare(true);
                        Prefs.setCfOn(app, false);
                        break;
                    case Storage.TAILSCALE:
                        Tunnels.stopTailscale(true);
                        Prefs.setTsOn(app, false);
                        break;
                    default:
                }
                if (!c.key.equals(Storage.WEB_DATA)) Storage.clearFiles(app, c);
                Stack.appLog(app, "storage: cleared " + c.key + " (" + Storage.human(c.size) + ")");
            }
            ui.post(() -> {
                toast(Storage.downloading() && containsKey(todo, Storage.TMP) ? "已清除（正在下载，下载目录已跳过）" : "已清除");
                if (rs) {
                    restartServices();
                } else if (reloadPage) {
                    autoLoginTried = false;
                    if (web != null) web.reload();
                }
                if ("storage".equals(page) && panelOpen) showPage("storage");
            });
        });
    }

    private static boolean containsKey(List<Storage.Cat> l, String key) {
        for (Storage.Cat c : l) if (c.key.equals(key)) return true;
        return false;
    }

    // ================================================================== actions

    /** the stack is about to restart: drop the console so it reloads (and logs in again) once it is back */
    private void consoleWillRestart() {
        webLoaded = false;
        autoLoginTried = false;
        if (web != null) web.setVisibility(View.INVISIBLE);
        consoleUp = false;
        Stack.problem = "";
    }

    private void restartServices() {
        webLoaded = false;
        autoLoginTried = false;
        if (web != null) web.setVisibility(View.INVISIBLE);
        consoleUp = false;
        Stack.problem = "";
        OmcService.restart(this);
        toast("正在重启 CPA 和 oh-my-cpa…");
    }

    private void floatAndGoHome() {
        if (!Settings.canDrawOverlays(this)) {
            pendingFloatHome = true;
            toast("请允许 Oh My CPA「显示在其他应用上层」，然后再打开悬浮窗。");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                pendingFloatHome = false;
            }
            return;
        }
        if (panelOpen) closePanel();
        Prefs.saveFloatMini(this, true);
        FloatService.show(this);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            moveTaskToBack(true);
        }
    }

    private void toggleFloat() {
        if (FloatService.isShowing()) {
            FloatService.hide(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请允许 Oh My CPA「显示在其他应用上层」，然后再打开悬浮窗。");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }
        Prefs.saveFloatMini(this, false);
        FloatService.show(this);
        moveTaskToBack(true);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("没有可以打开网页的应用");
        }
    }

    private void copy(String label, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text));
        toast("已复制" + label);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
