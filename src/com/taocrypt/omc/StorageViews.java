package com.taocrypt.omc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small hand-drawn views for 空间管理, in the console's palette: a donut chart and a segmented bar. */
final class StorageViews {
    private StorageViews() {}

    private static final String[] DARK = {"#38bdf8", "#34d399", "#fbbf24", "#a78bfa", "#f472b6", "#2dd4bf", "#fb923c",
            "#818cf8", "#a3e635", "#f87171", "#94a3b8", "#c084fc", "#67e8f9", "#71717a"};
    private static final String[] LIGHT = {"#0369a1", "#059669", "#b45309", "#7c3aed", "#db2777", "#0f766e", "#c2410c",
            "#4f46e5", "#4d7c0f", "#dc2626", "#64748b", "#9333ea", "#0e7490", "#a1a1aa"};

    /** Stable colour per category; the first one is the console accent. */
    static int color(Palette p, String key) {
        int i;
        switch (key) {
            case Storage.CPA_BIN: i = 0; break;
            case Storage.OMC_BIN: i = 1; break;
            case Storage.CPA_PLUGIN: i = 2; break;
            case Storage.GLIBC: i = 3; break;
            case Storage.APK: i = 4; break;
            case Storage.CLOUDFLARED: i = 5; break;
            case Storage.TAILSCALE: i = 6; break;
            case Storage.OMC_DATA: i = 7; break;
            case Storage.CPA_AUTH: i = 8; break;
            case Storage.CPA_PLUGINS: i = 9; break;
            case Storage.WEB_DATA: i = 10; break;
            case Storage.WEB_CACHE: i = 11; break;
            case Storage.CACHE: i = 12; break;
            case Storage.LOGS: return p.warn;
            case Storage.ROLLBACK: return Palette.alpha(p.accent, 55);
            case Storage.TMP: return Palette.alpha(p.success, 60);
            case Storage.CPA_CONFIG: return Palette.alpha(p.danger, 70);
            default: i = 13;
        }
        if (i == 0) return p.accent;
        return Color.parseColor((p.dark ? DARK : LIGHT)[i]);
    }

    static final class Donut extends View {
        private long[] values = new long[0];
        private int[] colors = new int[0];
        private float progress = 1f;
        private String center = "", caption = "";
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int sizePx;

        Donut(Context c, Palette p) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            float sd = c.getResources().getDisplayMetrics().scaledDensity;
            sizePx = Math.round(124 * d);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.BUTT);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(p.surface);
            big.setColor(p.fg);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(Kit.FONT_BOLD);
            big.setTextSize(16 * sd);
            small.setColor(p.muted);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Kit.FONT);
            small.setTextSize(11 * sd);
            ring.setStrokeWidth(14 * d);
            track.setStrokeWidth(14 * d);
        }

        void set(long[] v, int[] col, String centerText, String captionText, boolean animate) {
            values = v;
            colors = col;
            center = centerText;
            caption = captionText;
            if (animate) {
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(650);
                a.setInterpolator(new DecelerateInterpolator(1.6f));
                a.addUpdateListener(x -> {
                    progress = (float) x.getAnimatedValue();
                    invalidate();
                });
                a.start();
            } else {
                progress = 1f;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(sizePx, sizePx);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float sw = ring.getStrokeWidth();
            box.set(sw / 2 + 2, sw / 2 + 2, getWidth() - sw / 2 - 2, getHeight() - sw / 2 - 2);
            cv.drawArc(box, 0, 360, false, track);
            long total = 0;
            for (long v : values) total += v;
            if (total > 0) {
                float start = -90f, gap = values.length > 1 ? 1.6f : 0f;
                float sweepAll = 360f * progress;
                for (int i = 0; i < values.length; i++) {
                    float sweep = 360f * values[i] / total;
                    if (sweep <= 0) continue;
                    float drawn = Math.min(sweep, Math.max(0, sweepAll - (start + 90f)));
                    if (drawn > 0) {
                        ring.setColor(colors[i]);
                        float g = sweep > gap * 2 ? gap : 0;
                        cv.drawArc(box, start + g / 2, Math.max(0.6f, drawn - g), false, ring);
                    }
                    start += sweep;
                }
            }
            float cy = getHeight() / 2f;
            cv.drawText(center, getWidth() / 2f, cy + big.getTextSize() * 0.15f, big);
            cv.drawText(caption, getWidth() / 2f, cy + big.getTextSize() * 0.15f + small.getTextSize() * 1.6f, small);
        }
    }

    /** A rounded bar with one or more coloured segments over a track. */
    static final class Bar extends View {
        private float[] parts = new float[0];
        private int[] colors = new int[0];
        private float progress = 1f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private final int heightPx;
        private final RectF r = new RectF();
        private final Path clip = new Path();

        Bar(Context c, Palette p, int heightDp) {
            super(c);
            trackColor = p.surface;
            heightPx = Math.round(heightDp * c.getResources().getDisplayMetrics().density);
        }

        /** parts are fractions of the full width (0..1), drawn left to right */
        void set(float[] fractions, int[] cols, boolean animate) {
            parts = fractions;
            colors = cols;
            if (animate) {
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(600);
                a.setInterpolator(new DecelerateInterpolator(1.6f));
                a.addUpdateListener(x -> {
                    progress = (float) x.getAnimatedValue();
                    invalidate();
                });
                a.start();
            } else {
                progress = 1f;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), heightPx);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float rad = getHeight() / 2f;
            r.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            paint.setColor(trackColor);
            cv.drawRect(r, paint);
            float x = 0;
            for (int i = 0; i < parts.length; i++) {
                float w = Math.max(0, parts[i]) * getWidth() * progress;
                if (w <= 0) continue;
                if (w < rad && parts.length == 1) w = rad;
                paint.setColor(colors[i]);
                cv.drawRect(x, 0, x + w, getHeight(), paint);
                x += w;
            }
            cv.restore();
        }
    }
}
