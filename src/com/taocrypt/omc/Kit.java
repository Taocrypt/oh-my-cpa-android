package com.taocrypt.omc;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The native widgets, drawn with the console's tokens: type scale 10–20 px, corners
 * 2/4/6/8/12, hairline borders, soft foreground washes for hover/selected rows, the accent
 * only on filled controls, links and switches. Everything is built in code because the
 * palette is the console's live one, not a fixed resource.
 */
final class Kit {
    final Context c;
    final Palette p;
    private final float density;

    static final Typeface FONT = Typeface.MONOSPACE;
    static final Typeface FONT_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    Kit(Context c, Palette p) {
        this.c = c;
        this.p = p;
        this.density = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) { return Math.round(v * density); }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ------------------------------------------------------------------ drawables

    GradientDrawable shape(int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    GradientDrawable oval(int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** a row/control that answers a press with the console's hover wash */
    Drawable press(Drawable base, float radiusDp) {
        GradientDrawable mask = shape(0xFFFFFFFF, radiusDp, 0);
        return new RippleDrawable(ColorStateList.valueOf(p.wash(10)), base, mask);
    }

    Drawable press(int fill, float radiusDp, int stroke) {
        return press(shape(fill, radiusDp, stroke), radiusDp);
    }

    /** the brand mark (ring in ink, the spent corner in accent) as one drawable */
    Drawable mark(int ink, int accent) {
        Drawable a = c.getDrawable(R.drawable.brand_mark_ink).mutate();
        a.setTint(ink);
        Drawable b = c.getDrawable(R.drawable.brand_mark_accent).mutate();
        b.setTint(accent);
        return new LayerDrawable(new Drawable[]{a, b});
    }

    Drawable wordmark() {
        Drawable a = c.getDrawable(R.drawable.brand_word_ink).mutate();
        a.setTint(p.fg);
        Drawable b = c.getDrawable(R.drawable.brand_word_accent).mutate();
        b.setTint(p.accent);
        return new LayerDrawable(new Drawable[]{a, b});
    }

    Drawable icon(int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    // ------------------------------------------------------------------ text

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(FONT);
        t.setIncludeFontPadding(true);
        return t;
    }

    TextView bold(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(FONT_BOLD);
        return t;
    }

    TextView caption(CharSequence s) {
        TextView t = text(s, 12, p.muted);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    ImageView image(Drawable d, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    View dot(int color, float sizeDp) {
        View v = new View(c);
        v.setBackground(oval(color, 0, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    LinearLayout.LayoutParams lpm(int w, int h, float top, float bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
        l.topMargin = dp(top);
        l.bottomMargin = dp(bottom);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, WRAP, w); }

    // ------------------------------------------------------------------ navigation rail

    /** a group's caption over its entries (.ant-menu-item-group-title) */
    TextView groupTitle(String s, boolean first) {
        TextView t = text(s, 12, p.meta);
        t.setPadding(dp(10), dp(first ? 4 : 16), dp(10), dp(4));
        return t;
    }

    static final class NavItem {
        LinearLayout view;
        ImageView icon;
        TextView label, trailing;
        View flag;
    }

    /** an entry: icon in --muted, label in --fg-2, soft 8px row; trailing is a quiet value on the right */
    NavItem nav(int iconRes, CharSequence label, CharSequence trailing, Runnable onClick) {
        NavItem n = new NavItem();
        LinearLayout r = row();
        r.setPadding(dp(10), 0, dp(10), 0);
        r.setMinimumHeight(dp(40));
        r.setBackground(press(0x00000000, 8, 0));
        r.setClickable(true);
        r.setFocusable(true);
        FrameLayout iconBox = new FrameLayout(c);
        n.icon = new ImageView(c);
        n.icon.setImageDrawable(icon(iconRes, p.muted));
        iconBox.addView(n.icon, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        n.flag = new View(c);
        n.flag.setBackground(oval(p.warn, 0, 0));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP | Gravity.END);
        iconBox.addView(n.flag, fl);
        n.flag.setVisibility(View.GONE);
        r.addView(iconBox, new LinearLayout.LayoutParams(dp(21), dp(21)));
        n.label = text(label, 14, p.fg2);
        n.label.setSingleLine(true);
        n.label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ll.setMarginStart(dp(10));
        r.addView(n.label, ll);
        n.trailing = text(trailing == null ? "" : trailing, 12, p.meta);
        n.trailing.setSingleLine(true);
        n.trailing.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        n.trailing.setMaxWidth(dp(140));
        n.trailing.setVisibility(trailing == null || trailing.length() == 0 ? View.GONE : View.VISIBLE);
        r.addView(n.trailing);
        r.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(1);
        rl.bottomMargin = dp(1);
        r.setLayoutParams(rl);
        n.view = r;
        return n;
    }

    /** the selected entry: the stronger wash in the full text colour, never a coloured bar */
    void select(NavItem n, boolean on) {
        n.view.setBackground(press(on ? p.wash(8) : 0x00000000, 8, 0));
        n.label.setTextColor(on ? p.fg : p.fg2);
        n.label.setTypeface(on ? FONT_BOLD : FONT);
        n.icon.setImageTintList(ColorStateList.valueOf(on ? p.fg : p.muted));
    }

    // ------------------------------------------------------------------ cards & controls

    /** a card: the page ground inside a hairline, 12px corners (.ant-card) */
    LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(shape(p.bg, 12, p.border));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setLayoutParams(lpm(MATCH, WRAP, 0, 12));
        return l;
    }

    TextView cardTitle(LinearLayout card, String s) {
        TextView t = bold(s, 14, p.fg);
        card.addView(t, lpm(MATCH, WRAP, 0, 4));
        return t;
    }

    /** a form label over a value (.ant-form-item-label: 13px muted) */
    TextView label(String s) {
        TextView t = text(s, 13, p.muted);
        t.setLetterSpacing(0.04f);
        return t;
    }

    TextView value(String s) {
        TextView t = text(s, 13, p.fg);
        t.setTextIsSelectable(true);
        return t;
    }

    void kv(LinearLayout parent, String k, String v) {
        TextView a = label(k);
        parent.addView(a, lpm(MATCH, WRAP, 8, 0));
        TextView b = value(v);
        parent.addView(b, lpm(MATCH, WRAP, 2, 0));
    }

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(p.borderSoft);
        v.setLayoutParams(lpm(MATCH, Math.max(1, dp(1)), 10, 10));
        return v;
    }

    static final int PRIMARY = 1, DEFAULT = 0, DANGER = 2, TEXT = 3;

    TextView button(String s, int kind, Runnable onClick) {
        TextView b = text(s, 14, p.fg);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(FONT_BOLD);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setClickable(true);
        b.setFocusable(true);
        switch (kind) {
            case PRIMARY:
                b.setBackground(press(p.accent, 6, 0));
                b.setTextColor(p.accentOn);
                break;
            case DANGER:
                b.setBackground(press(0x00000000, 6, p.danger));
                b.setTextColor(p.danger);
                break;
            case TEXT:
                b.setBackground(press(0x00000000, 6, 0));
                b.setTextColor(p.accent);
                b.setTypeface(FONT);
                break;
            default:
                b.setBackground(press(p.bg, 6, p.border));
                b.setTextColor(p.fg);
        }
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** buttons in a row, right-aligned like a dialog footer */
    LinearLayout buttons(View... bs) {
        LinearLayout r = row();
        r.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, dp(36));
            if (i > 0) l.setMarginStart(dp(8));
            r.addView(bs[i], l);
        }
        r.setLayoutParams(lpm(MATCH, WRAP, 12, 0));
        return r;
    }

    EditText field(String hint, String value, boolean number) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        e.setTypeface(FONT);
        e.setTextColor(p.fg);
        e.setHintTextColor(p.meta);
        e.setSingleLine(true);
        e.setBackground(shape(p.wash(5), 6, 0));
        e.setPadding(dp(11), dp(8), dp(11), dp(8));
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutParams(lpm(MATCH, WRAP, 6, 2));
        return e;
    }

    interface Toggle { void on(boolean v); }

    /** antd's switch: 36×20 pill, accent when on, a 25% wash when off, white knob */
    Switch toggle(boolean checked) {
        Switch s = new Switch(c);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(10));
        track.setSize(dp(36), dp(20));
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
        track.setColor(new ColorStateList(states, new int[]{p.wash(12), p.accent, p.wash(25)}));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(20), dp(20));
        thumb.setColor(0xFFFFFFFF);
        thumb.setStroke(dp(2), 0x00000000);
        s.setTrackDrawable(track);
        s.setThumbDrawable(thumb);
        s.setShowText(false);
        s.setText("");
        s.setChecked(checked);
        return s;
    }

    Switch switchRow(LinearLayout parent, String title, String hint, boolean checked, Toggle cb) {
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout texts = column();
        texts.addView(text(title, 14, p.fg));
        if (hint != null && !hint.isEmpty()) {
            TextView h = caption(hint);
            texts.addView(h, lpm(MATCH, WRAP, 2, 0));
        }
        r.addView(texts, weight(1));
        Switch s = toggle(checked);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(WRAP, WRAP);
        sl.setMarginStart(dp(12));
        r.addView(s, sl);
        s.setOnCheckedChangeListener((b, on) -> cb.on(on));
        r.setOnClickListener(v -> { if (s.isEnabled()) s.toggle(); });
        parent.addView(r, lpm(MATCH, WRAP, 0, 0));
        return s;
    }

    /** a tappable line inside a card: icon, title, hint, chevron */
    LinearLayout actionRow(LinearLayout parent, int iconRes, String title, String hint, Runnable onClick) {
        LinearLayout r = row();
        r.setPadding(dp(8), dp(9), dp(6), dp(9));
        r.setBackground(press(0x00000000, 8, 0));
        r.setClickable(true);
        if (iconRes != 0) {
            ImageView i = image(icon(iconRes, p.muted), 17);
            r.addView(i);
        }
        LinearLayout texts = column();
        texts.addView(text(title, 14, p.fg));
        if (hint != null && !hint.isEmpty()) texts.addView(caption(hint), lpm(MATCH, WRAP, 2, 0));
        LinearLayout.LayoutParams tl = weight(1);
        tl.setMarginStart(dp(iconRes != 0 ? 10 : 0));
        r.addView(texts, tl);
        r.addView(image(icon(R.drawable.ic_chevron, p.meta), 14));
        r.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams l = lpm(MATCH, WRAP, 0, 0);
        l.setMarginStart(-dp(8));
        l.setMarginEnd(-dp(6));
        parent.addView(r, l);
        return r;
    }

    /** a read-only monospaced box (log, health output) on the surface step */
    TextView outputBox(LinearLayout parent, int heightDp) {
        TextView tv = text("", 11, p.fg2);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(1), 1f);
        ScrollView sv = new ScrollView(c);
        sv.setBackground(shape(p.surface, 8, p.borderSoft));
        sv.setPadding(dp(12), dp(10), dp(12), dp(10));
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(tv);
        parent.addView(sv, lpm(MATCH, dp(heightDp), 6, 0));
        tv.setTag(sv);
        return tv;
    }

    interface Pick { void picked(int index); }

    /** a segmented control (.ant-segmented): surface track, the chosen item lifted on the elevated step */
    LinearLayout segmented(String[] items, int selected, Pick cb) {
        LinearLayout r = row();
        r.setBackground(shape(p.surface, 8, 0));
        r.setPadding(dp(2), dp(2), dp(2), dp(2));
        final TextView[] views = new TextView[items.length];
        final Runnable[] paint = new Runnable[1];
        final int[] sel = {selected};
        paint[0] = () -> {
            for (int i = 0; i < views.length; i++) {
                boolean on = i == sel[0];
                views[i].setBackground(on ? shape(p.elevated, 6, p.border) : press(0x00000000, 6, 0));
                views[i].setTextColor(on ? p.fg : p.muted);
            }
        };
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            TextView t = text(items[i], 12, p.muted);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(6), 0, dp(6), 0);
            t.setOnClickListener(v -> { sel[0] = k; paint[0].run(); cb.picked(k); });
            views[i] = t;
            r.addView(t, new LinearLayout.LayoutParams(0, dp(28), 1f));
        }
        paint[0].run();
        r.setLayoutParams(lpm(MATCH, WRAP, 0, 8));
        return r;
    }

    interface Slide { void moved(int value, boolean fromUser); }

    SeekBar slider(LinearLayout parent, String label, int min, int max, int value, String unit, Slide cb) {
        LinearLayout r = row();
        TextView t = text(label, 13, p.fg2);
        r.addView(t, new LinearLayout.LayoutParams(dp(78), WRAP));
        SeekBar bar = new SeekBar(c);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(ColorStateList.valueOf(p.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(p.wash(30)));
        bar.setThumbTintList(ColorStateList.valueOf(p.accent));
        r.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1f));
        final TextView v = text(value + unit, 12, p.muted);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(dp(46), WRAP));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean fromUser) { v.setText(pr + unit); cb.moved(pr, fromUser); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(r, lpm(MATCH, WRAP, 0, 0));
        return bar;
    }

    // ------------------------------------------------------------------ dialog (.ant-modal)

    Dialog confirm(String title, CharSequence message, String ok, boolean danger, Runnable onOk, Runnable onCancel) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setBackground(shape(p.elevated, 12, p.border));
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        box.addView(bold(title, 16, p.fg));
        if (message != null) {
            TextView m = text(message, 14, p.fg2);
            m.setLineSpacing(dp(3), 1f);
            box.addView(m, lpm(MATCH, WRAP, 10, 0));
        }
        final boolean[] done = {false};
        TextView cancel = button("取消", DEFAULT, () -> { done[0] = true; d.dismiss(); if (onCancel != null) onCancel.run(); });
        TextView yes = button(ok, danger ? DANGER : PRIMARY, () -> { done[0] = true; d.dismiss(); if (onOk != null) onOk.run(); });
        if (danger) {
            yes.setBackground(press(p.danger, 6, 0));
            yes.setTextColor(0xFFFFFFFF);
        }
        LinearLayout bs = buttons(cancel, yes);
        bs.setLayoutParams(lpm(MATCH, WRAP, 20, 0));
        box.addView(bs);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), 0, dp(20), 0);
        wrap.addView(box, new FrameLayout.LayoutParams(MATCH, WRAP));
        d.setContentView(wrap);
        d.setOnCancelListener(x -> { if (!done[0] && onCancel != null) onCancel.run(); });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(MATCH, WRAP);
            w.setDimAmount(p.dark ? 0.5f : 0.18f);
        }
        d.show();
        return d;
    }

    /** a dialog that only shows text, with one button */
    Dialog message(String title, CharSequence message) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setBackground(shape(p.elevated, 12, p.border));
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        box.addView(bold(title, 16, p.fg));
        TextView m = text(message, 13, p.fg2);
        m.setTextIsSelectable(true);
        m.setLineSpacing(dp(3), 1f);
        box.addView(m, lpm(MATCH, WRAP, 10, 0));
        LinearLayout bs = buttons(button("好", PRIMARY, d::dismiss));
        bs.setLayoutParams(lpm(MATCH, WRAP, 20, 0));
        box.addView(bs);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), 0, dp(20), 0);
        wrap.addView(box, new FrameLayout.LayoutParams(MATCH, WRAP));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(MATCH, WRAP);
            w.setDimAmount(p.dark ? 0.5f : 0.18f);
        }
        d.show();
        return d;
    }

    /**
     * a dialog with up to three buttons in the .ant-modal footer (e.g. 以后 / 浏览器下载 / 直接下载);
     * the last label is the primary one, a null action just closes. A long message scrolls.
     */
    Dialog choice(String title, CharSequence message, String[] labels, Runnable[] actions) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setBackground(shape(p.elevated, 12, p.border));
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        box.addView(bold(title, 16, p.fg));
        if (message != null) {
            TextView m = text(message, 13, p.fg2);
            m.setTextIsSelectable(true);
            m.setLineSpacing(dp(3), 1f);
            final int maxH = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.5f);
            ScrollView sv = new ScrollView(c) {
                @Override protected void onMeasure(int w, int h) {
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                }
            };
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.addView(m);
            box.addView(sv, lpm(MATCH, WRAP, 10, 0));
        }
        View[] bs = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = actions != null && i < actions.length ? actions[i] : null;
            boolean last = i == labels.length - 1;
            TextView b = button(labels[i], last ? PRIMARY : i == 0 ? TEXT : DEFAULT, () -> { d.dismiss(); if (a != null) a.run(); });
            if (i == 0 && labels.length > 2) b.setTextColor(p.fg2);
            b.setPadding(dp(10), 0, dp(10), 0);
            bs[i] = b;
        }
        LinearLayout row = buttons(bs);
        row.setLayoutParams(lpm(MATCH, WRAP, 20, 0));
        box.addView(row);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), 0, dp(20), 0);
        wrap.addView(box, new FrameLayout.LayoutParams(MATCH, WRAP));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(MATCH, WRAP);
            w.setDimAmount(p.dark ? 0.5f : 0.18f);
        }
        d.show();
        return d;
    }

    /** a small rounded tag: a soft accent pill (清除) or a quiet surface tag (系统必需) */
    TextView pill(String s, boolean action) {
        TextView t = text(s, 12, action ? p.accent : p.muted);
        if (action) t.setTypeface(FONT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(action ? press(Palette.alpha(p.accent, 14), 12, 0) : shape(p.surface, 12, 0));
        t.setPadding(dp(action ? 12 : 9), dp(4), dp(action ? 12 : 9), dp(4));
        return t;
    }
}
