package com.taocrypt.omc;

import android.content.Context;
import android.graphics.Color;
import org.json.JSONObject;

/**
 * The console's design tokens (web/src/index.css :root and [data-theme-mode=light]).
 * The defaults are OMC Dark / OMC Light; while the console is open its live values
 * (including a custom palette the operator picked) are reported through the JS bridge
 * and replace these, so the native chrome always wears the same colours as the page.
 */
public final class Palette {
    public boolean dark;
    public int bg, surface, canvas, elevated, fg, fg2, muted, meta, border, borderSoft, accent, accentOn, hover,
            success, warn, danger, mask;

    public static Palette omcDark() {
        Palette p = new Palette();
        p.dark = true;
        p.bg = 0xFF121214; p.surface = 0xFF1C1C1F; p.canvas = 0xFF09090A; p.elevated = 0xFF222226;
        p.fg = 0xFFF4F4F6; p.fg2 = 0xFFA1A1AA; p.muted = 0xFF71717A; p.meta = 0xFF52525B;
        p.border = 0xFF2C2C30; p.borderSoft = 0xFF212124; p.accent = 0xFF00A2FB; p.accentOn = 0xFFFFFFFF;
        p.hover = 0xFF272729; p.success = 0xFF10B981; p.warn = 0xFFF59E0B; p.danger = 0xFFEF4444;
        p.mask = 0x80000000;
        return p;
    }

    public static Palette omcLight() {
        Palette p = new Palette();
        p.dark = false;
        p.bg = 0xFFFFFFFF; p.surface = 0xFFF1F1F3; p.canvas = 0xFFF5F5F5; p.elevated = 0xFFFFFFFF;
        p.fg = 0xFF1C1C1E; p.fg2 = 0xFF505055; p.muted = 0xFF787880; p.meta = 0xFF98989F;
        p.border = 0xFFDCDCE1; p.borderSoft = 0xFFEBEBEE; p.accent = 0xFF005D8F; p.accentOn = 0xFFFFFFFF;
        p.hover = 0xFFF3F3F3; p.success = 0xFF059669; p.warn = 0xFFB45309; p.danger = 0xFFDC2626;
        p.mask = 0x1F14141E;
        return p;
    }

    private static volatile Palette current;

    public static Palette get(Context c) {
        Palette p = current;
        if (p == null) {
            p = parse(Prefs.palette(c));
            current = p;
        }
        return p;
    }

    /** Applies a report from the page. Returns true when anything visible changed. */
    public static boolean report(Context c, String json) {
        Palette p = parse(json);
        Palette old = get(c);
        if (old != null && p.same(old)) return false;
        current = p;
        Prefs.setPalette(c, json);
        return true;
    }

    private boolean same(Palette o) {
        return dark == o.dark && bg == o.bg && surface == o.surface && canvas == o.canvas && elevated == o.elevated
                && fg == o.fg && fg2 == o.fg2 && muted == o.muted && meta == o.meta && border == o.border
                && accent == o.accent && accentOn == o.accentOn && hover == o.hover && success == o.success
                && warn == o.warn && danger == o.danger;
    }

    static Palette parse(String json) {
        if (json == null || json.isEmpty()) return omcDark();
        try {
            JSONObject j = new JSONObject(json);
            boolean dark = !"light".equals(j.optString("mode", "dark"));
            Palette p = dark ? omcDark() : omcLight();
            p.bg = col(j, "--bg", p.bg);
            p.surface = col(j, "--surface", p.surface);
            p.canvas = col(j, "--canvas", p.canvas);
            p.elevated = col(j, "--elevated", p.elevated);
            p.fg = col(j, "--fg", p.fg);
            p.fg2 = col(j, "--fg-2", p.fg2);
            p.muted = col(j, "--muted", p.muted);
            p.meta = col(j, "--meta", p.meta);
            p.border = col(j, "--border", p.border);
            p.borderSoft = col(j, "--border-soft", p.borderSoft);
            p.accent = col(j, "--accent", p.accent);
            p.accentOn = col(j, "--accent-on", p.accentOn);
            p.hover = col(j, "--hover", p.hover);
            p.success = col(j, "--success", p.success);
            p.warn = col(j, "--warn", p.warn);
            p.danger = col(j, "--danger", p.danger);
            // trust the measured background over the mode flag: a custom palette may be either
            p.dark = luminance(p.bg) < 0.4;
            p.mask = p.dark ? 0x80000000 : 0x1F14141E;
            return p;
        } catch (Exception e) {
            return omcDark();
        }
    }

    private static int col(JSONObject j, String k, int def) {
        String s = j.optString(k, "").trim();
        if (s.isEmpty()) return def;
        try {
            if (s.startsWith("#")) {
                if (s.length() == 4) s = "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
                return Color.parseColor(s);
            }
            if (s.startsWith("rgb")) {
                String[] f = s.substring(s.indexOf('(') + 1, s.lastIndexOf(')')).replace("/", " ").replace(",", " ").trim().split("\\s+");
                int r = Integer.parseInt(f[0]), g = Integer.parseInt(f[1]), b = Integer.parseInt(f[2]);
                return Color.rgb(r, g, b);
            }
        } catch (RuntimeException ignored) {
        }
        return def;
    }

    static double luminance(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }

    /** foreground washed over a ground, like color-mix(in srgb, var(--fg) N%, transparent) */
    public int wash(int percent) {
        return (fg & 0x00FFFFFF) | ((percent * 255 / 100) << 24);
    }

    public static int alpha(int color, int percent) {
        return (color & 0x00FFFFFF) | ((percent * 255 / 100) << 24);
    }
}
