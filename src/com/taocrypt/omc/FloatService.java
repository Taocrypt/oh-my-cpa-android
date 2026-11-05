package com.taocrypt.omc;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.animation.DecelerateInterpolator;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The oh-my-cpa console in a floating window over other apps.
 * Opacity (20–100 %), width and height (percent of the screen) are adjustable from the
 * window's own slider panel, from the app's menu, or by dragging the corner handle.
 */
public final class FloatService extends Service {
    public static final String ACTION_SHOW = "com.taocrypt.omc.float.SHOW";
    public static final String ACTION_HIDE = "com.taocrypt.omc.float.HIDE";
    public static final String ACTION_TOGGLE = "com.taocrypt.omc.float.TOGGLE";
    public static final String ACTION_APPLY = "com.taocrypt.omc.float.APPLY";

    private static volatile boolean showing;

    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout panel;            // the full window
    private WindowManager.LayoutParams panelLp;
    private LinearLayout adjustBox;
    private WebView web;
    private SeekBar alphaBar, widthBar, heightBar;
    private TextView alphaVal, widthVal, heightVal;

    private ImageView bubble;             // the minimised window
    private WindowManager.LayoutParams bubbleLp;
    private SeekBar bubbleBar, bubbleAlphaBar;
    private TextView bubbleVal, bubbleAlphaVal;
    private boolean bubbleHidden;
    private ValueAnimator bubbleAnim;
    private final Runnable hideBubble = this::tuckBubble;

    private boolean pageLoaded, loginTried;
    private Kit kit;
    private int pollTries;

    public static boolean isShowing() {
        return showing;
    }

    public static void show(Context c) {
        c.startService(new Intent(c, FloatService.class).setAction(ACTION_SHOW));
    }

    public static void hide(Context c) {
        if (showing) {
            c.startService(new Intent(c, FloatService.class).setAction(ACTION_HIDE));
        }
    }

    /** re-read opacity/size/lock from prefs and apply them to the open window */
    public static void apply(Context c) {
        if (showing) {
            c.startService(new Intent(c, FloatService.class).setAction(ACTION_APPLY));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        CrashLog.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action) && showing) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_APPLY.equals(action)) {
            if (panel != null) {
                applyLook();
                syncBars();
                applyBubble(true);
            }
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许 Oh My CPA「显示在其他应用上层」，然后再打开悬浮窗。", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (panel == null) {
            build();
        }
        if (Prefs.floatMini(this)) {
            minimise();
        } else {
            expand();
        }
        showing = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        showing = false;
        handler.removeCallbacksAndMessages(null);
        removeQuietly(panel);
        removeQuietly(bubble);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        panel = null;
        bubble = null;
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (panel != null) {
            applyLook();
            applyBubble(true);
        }
    }

    // ------------------------------------------------------------------ building

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Rect screen() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds();
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new Rect(0, 0, m.widthPixels, m.heightPixels);
    }

    private WindowManager.LayoutParams baseLp() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void build() {
        Palette pal = Palette.get(this);
        kit = new Kit(this, pal);
        int bg = pal.bg;
        int line = pal.border;
        int textSecondary = pal.fg2;
        int accent = pal.accent;

        panel = new FrameLayout(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(bg);
        card.setCornerRadius(dp(12));
        card.setStroke(dp(1), line);
        panel.setBackground(card);
        panel.setClipToOutline(true);
        panel.setElevation(dp(10));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        panel.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // title bar: drag handle + buttons
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(pal.canvas);
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(kit.wordmark());
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(14), 1f);
        tlp.setMarginStart(dp(4));
        bar.addView(logo, tlp);
        bar.addView(iconButton(R.drawable.ic_sliders, "透明度和大小", v -> toggleAdjust()));
        bar.addView(iconButton(R.drawable.ic_reload, "刷新", v -> reloadPage()));
        bar.addView(iconButton(R.drawable.ic_minimize, "收起成悬浮球", v -> minimise()));
        bar.addView(iconButton(R.drawable.ic_external, "回到应用", v -> openApp()));
        bar.addView(iconButton(R.drawable.ic_close, "关闭", v -> stopSelf()));
        column.addView(bar, new LinearLayout.LayoutParams(-1, dp(40)));
        dragWith(bar, true);

        // slider panel
        adjustBox = new LinearLayout(this);
        adjustBox.setOrientation(LinearLayout.VERTICAL);
        adjustBox.setPadding(dp(14), dp(6), dp(14), dp(8));
        adjustBox.setBackgroundColor(pal.surface);
        alphaVal = new TextView(this);
        widthVal = new TextView(this);
        heightVal = new TextView(this);
        alphaBar = sliderRow(adjustBox, "不透明度", alphaVal, 20, 100, textSecondary, accent);
        widthBar = sliderRow(adjustBox, "宽度", widthVal, 30, 100, textSecondary, accent);
        heightBar = sliderRow(adjustBox, "高度", heightVal, 20, 95, textSecondary, accent);
        bubbleVal = new TextView(this);
        bubbleAlphaVal = new TextView(this);
        bubbleBar = sliderRow(adjustBox, "悬浮球", bubbleVal, 32, 96, textSecondary, accent);
        bubbleAlphaBar = sliderRow(adjustBox, "球透明", bubbleAlphaVal, 20, 100, textSecondary, accent);
        bubbleSwitch(adjustBox, "悬浮球自动贴边", Prefs.bubbleSnap(this), textSecondary, on -> Prefs.saveBubbleSnap(this, on));
        bubbleSwitch(adjustBox, "悬浮球自动隐藏", Prefs.bubbleHide(this), textSecondary, on -> Prefs.saveBubbleHide(this, on));
        LinearLayout lockRow = new LinearLayout(this);
        lockRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lockText = new TextView(this);
        lockText.setText("点击穿透（只看不点）");
        lockText.setTextColor(textSecondary);
        lockText.setTextSize(12);
        lockRow.addView(lockText, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch lock = kit.toggle(false);
        lock.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                b.setChecked(false);
                Prefs.saveFloatLock(this, true);
                Toast.makeText(this, "已开启点击穿透：悬浮窗不再接收触摸，不透明度最高 80%。在应用面板「悬浮窗设置」里关闭。", Toast.LENGTH_LONG).show();
                applyLook();
            }
        });
        lockRow.addView(lock);
        adjustBox.addView(lockRow);
        adjustBox.setVisibility(View.GONE);
        column.addView(adjustBox, new LinearLayout.LayoutParams(-1, -2));
        syncBars();

        // the page
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(90);
        web.setBackgroundColor(pal.bg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptCookie(true);
        WebFiles.attach(this, web);
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                // the picker opens under the overlay, so fold the window into the bubble until it is done
                final boolean wasMini = Prefs.floatMini(FloatService.this);
                if (!wasMini) minimise();
                return WebFiles.choose(FloatService.this, cb, params, () -> handler.post(() -> {
                    if (!wasMini && panel != null) expand();
                }));
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                CrashLog.write(FloatService.this, "floating window page renderer gone (crashed=" + detail.didCrash() + ")", new RuntimeException("renderer gone"));
                final Context app = getApplicationContext();
                stopSelf();
                new Handler(Looper.getMainLooper()).postDelayed(() -> show(app), 600L);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                view.evaluateJavascript(WebFiles.HOOK_JS, null);
                if (!loginTried && url != null && url.startsWith(Stack.consoleUrl(FloatService.this))) {
                    loginTried = true;
                    view.evaluateJavascript(MainActivity.autoLoginJs(FloatService.this), null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) {
                    return false;
                }
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                boolean own = scheme == null || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)
                        || ("http".equals(scheme) && "127.0.0.1".equals(u.getHost()) && u.getPort() == Prefs.omcPort(FloatService.this));
                if (own) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        // a tap inside the page lets the keyboard in; a tap outside the window gives focus back
        web.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                setFocusable(true);
            }
            return false;
        });
        panel.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                setFocusable(false);
            }
            return false;
        });
        column.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        // corner resize handle
        TextView grip = new TextView(this);
        grip.setText("◢");
        grip.setTextColor(accent);
        grip.setTextSize(18);
        grip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.END);
        panel.addView(grip, glp);
        resizeWith(grip);

        panelLp = baseLp();
        panelLp.x = Prefs.floatX(this);
        panelLp.y = Prefs.floatY(this);

        // the bubble
        bubble = new ImageView(this);
        bubble.setImageDrawable(kit.mark(pal.fg, pal.accent));
        bubble.setBackground(kit.oval(pal.elevated, pal.border, 1));
        bubble.setElevation(dp(8));
        bubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubbleLp = baseLp();
        bubbleLp.width = dp(56);
        bubbleLp.height = dp(56);
        dragWith(bubble, false);

        loadWhenReady();
    }

    private ImageButton iconButton(int icon, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(kit.p.fg2));
        b.setBackground(kit.press(0x00000000, 6, 0));
        b.setContentDescription(desc);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMarginStart(dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private SeekBar sliderRow(LinearLayout parent, String label, TextView value, final int min, int max, int color, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(12);
        row.addView(t, new LinearLayout.LayoutParams(dp(54), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(32), 1f));
        value.setTextColor(color);
        value.setTextSize(12);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(40), -2));
        parent.addView(row);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                if (s == bubbleBar || s == bubbleAlphaBar) {
                    Prefs.saveBubbleLook(FloatService.this, bubbleBar.getProgress(), bubbleAlphaBar.getProgress());
                    applyBubble(false);
                } else {
                    Prefs.saveFloatLook(FloatService.this, alphaBar.getProgress(), widthBar.getProgress(), heightBar.getProgress());
                    applyLook();
                }
                labels();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return bar;
    }

    private void syncBars() {
        if (alphaBar == null) {
            return;
        }
        alphaBar.setProgress(Prefs.floatAlpha(this));
        widthBar.setProgress(Prefs.floatWidth(this));
        heightBar.setProgress(Prefs.floatHeight(this));
        bubbleBar.setProgress(Prefs.bubbleSize(this));
        bubbleAlphaBar.setProgress(Prefs.bubbleAlpha(this));
        labels();
    }

    private void labels() {
        alphaVal.setText(alphaBar.getProgress() + "%");
        widthVal.setText(widthBar.getProgress() + "%");
        heightVal.setText(heightBar.getProgress() + "%");
        bubbleVal.setText(bubbleBar.getProgress() + "dp");
        bubbleAlphaVal.setText(bubbleAlphaBar.getProgress() + "%");
    }

    // ------------------------------------------------------------------ look & state

    private void applyLook() {
        Rect sc = screen();
        int w = Math.max(dp(220), sc.width() * Prefs.floatWidth(this) / 100);
        int h = Math.max(dp(160), sc.height() * Prefs.floatHeight(this) / 100);
        panelLp.width = Math.min(w, sc.width());
        panelLp.height = Math.min(h, sc.height());
        float alpha = Prefs.floatAlpha(this) / 100f;
        boolean locked = Prefs.floatLock(this);
        if (locked) {
            // Android 12+ only lets touches through a window from another app at <= 80 % opacity
            alpha = Math.min(alpha, 0.8f);
            panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        panelLp.alpha = alpha;
        clampPosition(panelLp, panelLp.width, panelLp.height);
        update(panel, panelLp);
    }

    private void clampPosition(WindowManager.LayoutParams lp, int w, int h) {
        Rect sc = screen();
        int minVisible = dp(48);
        lp.x = Math.max(-w + minVisible, Math.min(lp.x, sc.width() - minVisible));
        lp.y = Math.max(0, Math.min(lp.y, sc.height() - minVisible));
    }

    private void expand() {
        handler.removeCallbacks(hideBubble);
        if (bubbleAnim != null) {
            bubbleAnim.cancel();
        }
        bubbleHidden = false;
        removeQuietly(bubble);
        Prefs.saveFloatMini(this, false);
        if (panel.getParent() == null) {
            panelLp.x = Prefs.floatX(this);
            panelLp.y = Prefs.floatY(this);
            applyLook();
            wm.addView(panel, panelLp);
        } else {
            applyLook();
        }
    }

    private void minimise() {
        setFocusable(false);
        removeQuietly(panel);
        Prefs.saveFloatMini(this, true);
        if (bubble.getParent() == null) {
            int bx = Prefs.bubbleX(this), by = Prefs.bubbleY(this);
            bubbleLp.x = bx >= 0 || by >= 0 ? bx : Prefs.floatX(this);
            bubbleLp.y = by >= 0 ? by : Prefs.floatY(this);
            bubbleHidden = false;
            sizeBubble();
            wm.addView(bubble, bubbleLp);
            applyBubble(true);
        }
    }

    // ------------------------------------------------------------------ the bubble

    private int bubblePx() {
        return dp(Prefs.bubbleSize(this));
    }

    private void sizeBubble() {
        int size = bubblePx();
        bubbleLp.width = size;
        bubbleLp.height = size;
        int pad = Math.round(size * 0.16f);
        bubble.setPadding(pad, pad, pad, pad);
        bubbleLp.alpha = Prefs.bubbleAlpha(this) / 100f;
    }

    /** size/opacity changed, or the screen turned: re-measure, keep it on screen, re-snap, restart the hide timer */
    private void applyBubble(boolean snap) {
        if (bubble == null) {
            return;
        }
        sizeBubble();
        bubbleHidden = false;
        int size = bubblePx();
        Rect sc = screen();
        bubbleLp.x = Math.max(0, Math.min(bubbleLp.x, sc.width() - size));
        bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, sc.height() - size));
        if (bubble.getParent() == null) {
            return;
        }
        if (Prefs.bubbleSnap(this)) {
            bubbleLp.x = edgeX();
        }
        update(bubble, bubbleLp);
        scheduleTuck();
    }

    /** the x of the nearest side for the bubble's current centre */
    private int edgeX() {
        int size = bubblePx();
        Rect sc = screen();
        return bubbleLp.x + size / 2 < sc.width() / 2 ? 0 : sc.width() - size;
    }

    private void scheduleTuck() {
        handler.removeCallbacks(hideBubble);
        if (Prefs.bubbleHide(this) && bubble != null && bubble.getParent() != null) {
            handler.postDelayed(hideBubble, Prefs.bubbleHideDelay(this) * 1000L);
        }
    }

    /** slide half the bubble off the nearest side and fade it */
    private void tuckBubble() {
        if (bubble == null || bubble.getParent() == null || bubbleHidden || !Prefs.bubbleHide(this)) {
            return;
        }
        int size = bubblePx();
        Rect sc = screen();
        boolean left = bubbleLp.x + size / 2 < sc.width() / 2;
        bubbleHidden = true;
        float a = Prefs.bubbleAlpha(this) / 100f;
        slideBubble(left ? -size / 2 : sc.width() - size / 2, Math.max(0.2f, a * 0.45f));
    }

    /** bring a tucked bubble back to its edge */
    private void revealBubble() {
        handler.removeCallbacks(hideBubble);
        if (!bubbleHidden) {
            return;
        }
        bubbleHidden = false;
        if (bubbleAnim != null) {
            bubbleAnim.cancel();
        }
        int size = bubblePx();
        Rect sc = screen();
        bubbleLp.x = bubbleLp.x < sc.width() / 2 - size / 2 ? 0 : sc.width() - size;
        bubbleLp.alpha = Prefs.bubbleAlpha(this) / 100f;
        update(bubble, bubbleLp);
    }

    private void slideBubble(int toX, float toAlpha) {
        if (bubbleAnim != null) {
            bubbleAnim.cancel();
        }
        final int fromX = bubbleLp.x;
        final float fromA = bubbleLp.alpha;
        bubbleAnim = ValueAnimator.ofFloat(0f, 1f);
        bubbleAnim.setDuration(220);
        bubbleAnim.setInterpolator(new DecelerateInterpolator());
        bubbleAnim.addUpdateListener(an -> {
            if (bubble == null) {
                return;
            }
            float f = (float) an.getAnimatedValue();
            bubbleLp.x = Math.round(fromX + (toX - fromX) * f);
            bubbleLp.alpha = fromA + (toAlpha - fromA) * f;
            update(bubble, bubbleLp);
        });
        bubbleAnim.start();
    }

    private void bubbleSwitch(LinearLayout parent, String label, boolean on, int color, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(12);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = kit.toggle(on);
        sw.setOnCheckedChangeListener((b, v) -> {
            change.accept(v);
            applyBubble(true);
        });
        row.addView(sw);
        parent.addView(row);
    }

    private void toggleAdjust() {
        adjustBox.setVisibility(adjustBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        syncBars();
    }

    private void setFocusable(boolean focusable) {
        if (panel == null || panel.getParent() == null || Prefs.floatLock(this)) {
            return;
        }
        boolean now = (panelLp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (now == focusable) {
            return;
        }
        if (focusable) {
            panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        update(panel, panelLp);
    }

    private void openApp() {
        minimise();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void reloadPage() {
        pageLoaded = false;
        pollTries = 0;
        loadWhenReady();
    }

    private void loadWhenReady() {
        if (web == null || pageLoaded) {
            return;
        }
        final Context c = getApplicationContext();
        new Thread(() -> {
            boolean up = Stack.portOpen(Prefs.omcPort(c), 400);
            handler.post(() -> {
                if (web == null) {
                    return;
                }
                if (up) {
                    pageLoaded = true;
                    web.loadUrl(Stack.consoleUrl(c));
                } else {
                    if (pollTries++ == 0) {
                        String col = String.format("#%06X", kit.p.fg2 & 0xFFFFFF), bgc = String.format("#%06X", kit.p.bg & 0xFFFFFF);
                        web.loadData("<html><body style='font-family:monospace;background:" + bgc + ";color:" + col + ";padding:16px'>正在启动服务…</body></html>", "text/html; charset=utf-8", "utf-8");
                        OmcService.start(c);
                    }
                    if (pollTries < 120) {
                        handler.postDelayed(this::loadWhenReady, 800);
                    }
                }
            });
        }, "omc-float-poll").start();
    }

    // ------------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private void dragWith(View handle, final boolean isPanel) {
        final int slop = dp(6);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved, wasHidden;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams lp = isPanel ? panelLp : bubbleLp;
                View win = isPanel ? panel : bubble;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!isPanel) {
                            wasHidden = bubbleHidden;
                            revealBubble();
                        }
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) < slop) {
                            return true;
                        }
                        moved = true;
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        if (isPanel) {
                            clampPosition(lp, lp.width, lp.height);
                        } else {
                            Rect sc = screen();
                            int size = bubblePx();
                            lp.x = Math.max(0, Math.min(lp.x, sc.width() - size));
                            lp.y = Math.max(0, Math.min(lp.y, sc.height() - size));
                        }
                        update(win, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (isPanel) {
                            if (moved) {
                                Prefs.saveFloatPos(FloatService.this, lp.x, lp.y);
                            }
                        } else if (moved) {
                            int to = Prefs.bubbleSnap(FloatService.this) ? edgeX() : lp.x;
                            Prefs.saveBubblePos(FloatService.this, to, lp.y);
                            if (to != lp.x) {
                                slideBubble(to, Prefs.bubbleAlpha(FloatService.this) / 100f);
                            }
                            scheduleTuck();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP && !wasHidden) {
                            expand();
                        } else {
                            scheduleTuck();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void resizeWith(View grip) {
        grip.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startW, startH;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                Rect sc = screen();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startW = panelLp.width;
                        startH = panelLp.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        int w = startW + Math.round(e.getRawX() - downX);
                        int h = startH + Math.round(e.getRawY() - downY);
                        int wp = Math.round(w * 100f / sc.width());
                        int hp = Math.round(h * 100f / sc.height());
                        Prefs.saveFloatLook(FloatService.this, Prefs.floatAlpha(FloatService.this), wp, hp);
                        applyLook();
                        syncBars();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void update(View v, WindowManager.LayoutParams lp) {
        if (v != null && v.getParent() != null) {
            try {
                wm.updateViewLayout(v, lp);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void removeQuietly(View v) {
        if (v != null && v.getParent() != null) {
            try {
                wm.removeView(v);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
