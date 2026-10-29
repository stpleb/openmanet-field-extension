package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Field Extension web UI look (web/style.css): palette, text, outlined buttons, panels, logo. */
final class Ui {
    static final int BG = 0xFF05080A, PANEL = 0xFF0A0F13, LINE = 0x4700E5FF, LINE_SOFT = 0x1A00E5FF,
            TEXT = 0xFFD9E6ED, MUTED = 0xFF5C7682, SUB = 0xFF8FA7B2, CYAN = 0xFF00E5FF,
            OK = 0xFF00E676, WARN = 0xFFFFB300, BAD = 0xFFFF3B4D;

    static int ledColour(String name) {
        if (name == null) return MUTED;
        switch (name) {
            case "magenta": return 0xFFB56CFF;
            case "green": return OK;
            case "cyan": return CYAN;
            case "red": return BAD;
            case "amber": return WARN;
            case "blue": return 0xFF4D8BFF;
            case "white": return 0xFFFFFFFF;
            default: return MUTED;
        }
    }

    private final Context ctx;
    private final float d;

    Ui(Context ctx) {
        this.ctx = ctx;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    Context ctx() { return ctx; }

    int dp(float v) { return Math.round(v * d); }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    TextView text(String s, float sp, int colour, boolean bold, boolean mono) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 11, CYAN, true, false);
        t.setLetterSpacing(0.14f);
        return t;
    }

    GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(2));
        return g;
    }

    GradientDrawable circle(int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        return g;
    }

    /** Outlined web UI button; caps=false keeps names like "OpenManet" as written. */
    Button button(String label, boolean caps, boolean danger) {
        Button b = new Button(ctx);
        b.setAllCaps(caps);
        b.setText(caps ? label.toUpperCase(Locale.US) : label);
        b.setTextSize(13);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setLetterSpacing(caps ? 0.08f : 0.02f);
        b.setTextColor(danger ? BAD : CYAN);
        b.setBackground(box(danger ? 0x14FF3B4D : 0, danger ? BAD : LINE));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    View dot(int colour, int sizeDp) {
        View v = new View(ctx);
        v.setBackground(circle(colour));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** The "Triangle web" logo: network triangle with a Web of Wyrd style web inside. */
    View logo(int sizeDp) {
        View v = new View(ctx) {
            @Override
            protected void onDraw(Canvas c) { drawLogo(c, getWidth(), CYAN); }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    static void drawLogo(Canvas c, float s, int colour) {
        float m = s * 0.12f;
        float[] T = {s / 2, m}, L = {m, s - m * 1.3f}, R = {s - m, s - m * 1.3f};
        float[] M1 = mid(T, L), M2 = mid(L, R), M3 = mid(R, T);
        float[] cen = {(T[0] + L[0] + R[0]) / 3, (T[1] + L[1] + R[1]) / 3};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(colour);
        p.setAlpha(165);
        p.setStrokeWidth(s * 0.03f);
        float[][][] web = {{T, M2}, {L, M3}, {R, M1}, {M1, M2}, {M2, M3}, {M3, M1}};
        for (float[][] l : web) c.drawLine(l[0][0], l[0][1], l[1][0], l[1][1], p);
        for (float[] n : new float[][]{M1, M2, M3, cen}) c.drawCircle(n[0], n[1], s * 0.045f, p);
        p.setAlpha(255);
        p.setStrokeWidth(s * 0.06f);
        float[][] tri = {T, L, R};
        for (int i = 0; i < 3; i++) c.drawLine(tri[i][0], tri[i][1], tri[(i + 1) % 3][0], tri[(i + 1) % 3][1], p);
        for (float[] n : tri) c.drawCircle(n[0], n[1], s * 0.095f, p);
    }

    private static float[] mid(float[] a, float[] b) { return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2}; }
}
