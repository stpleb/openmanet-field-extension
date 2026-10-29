package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings look: icon tiles, menu cards and section cards in the theme colours. */
final class Cards {
    enum Icon { LINK, PERSON, MIC, MAP, PULSE, RADIO, CHIP, INFO, NODES, CHAT, GEAR, MESH, PLUS, TRASH, EDIT, KEY, PIN, FENCE, AREA, TRAIL }

    private Cards() {}

    /** A simple line icon in a rounded tile. */
    static View tile(Context ctx, Icon icon, int sizeDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx) {
            @Override
            protected void onDraw(Canvas c) {
                float s = getWidth();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0x1F00E5FF);
                c.drawRoundRect(new RectF(0, 0, s, s), 8 * d, 8 * d, p);
                p.setColor(Ui.CYAN);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Cards.drawIcon(c, icon, s, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (sizeDp * d), (int) (sizeDp * d));
        v.setLayoutParams(lp);
        return v;
    }

    static void drawIcon(Canvas c, Icon icon, float s, Paint p) {
        float m = s * 0.28f, a = s - m;                   // icon box m..a
        Path path = new Path();
        switch (icon) {
            case LINK:                                     // up/down arrows
                c.drawLine(s * 0.4f, m, s * 0.4f, a, p);
                c.drawLine(s * 0.4f, m, s * 0.3f, m + s * 0.1f, p);
                c.drawLine(s * 0.4f, m, s * 0.5f, m + s * 0.1f, p);
                c.drawLine(s * 0.6f, m, s * 0.6f, a, p);
                c.drawLine(s * 0.6f, a, s * 0.5f, a - s * 0.1f, p);
                c.drawLine(s * 0.6f, a, s * 0.7f, a - s * 0.1f, p);
                break;
            case PERSON:
                c.drawCircle(s / 2, s * 0.4f, s * 0.11f, p);
                path.addArc(new RectF(s * 0.3f, s * 0.56f, s * 0.7f, s * 0.92f), 180, 180);
                c.drawPath(path, p);
                break;
            case MIC:
                c.drawRoundRect(new RectF(s * 0.41f, m, s * 0.59f, s * 0.58f), s * 0.09f, s * 0.09f, p);
                path.addArc(new RectF(s * 0.33f, s * 0.38f, s * 0.67f, s * 0.66f), 0, 180);
                c.drawPath(path, p);
                c.drawLine(s / 2, s * 0.66f, s / 2, a, p);
                break;
            case MAP:                                      // folded map
                path.moveTo(m, m + s * 0.05f);
                path.lineTo(s * 0.4f, m);
                path.lineTo(s * 0.6f, m + s * 0.05f);
                path.lineTo(a, m);
                path.lineTo(a, a - s * 0.05f);
                path.lineTo(s * 0.6f, a);
                path.lineTo(s * 0.4f, a - s * 0.05f);
                path.lineTo(m, a);
                path.close();
                c.drawPath(path, p);
                c.drawLine(s * 0.4f, m, s * 0.4f, a - s * 0.05f, p);
                c.drawLine(s * 0.6f, m + s * 0.05f, s * 0.6f, a, p);
                break;
            case PULSE:
                path.moveTo(m, s / 2);
                path.lineTo(s * 0.4f, s / 2);
                path.lineTo(s * 0.47f, m);
                path.lineTo(s * 0.55f, a);
                path.lineTo(s * 0.62f, s / 2);
                path.lineTo(a, s / 2);
                c.drawPath(path, p);
                break;
            case RADIO:                                    // waves around a dot
                c.drawCircle(s / 2, s / 2, s * 0.05f, p);
                c.drawArc(new RectF(s * 0.32f, s * 0.32f, s * 0.68f, s * 0.68f), -50, 100, false, p);
                c.drawArc(new RectF(s * 0.32f, s * 0.32f, s * 0.68f, s * 0.68f), 130, 100, false, p);
                c.drawArc(new RectF(m - s * 0.04f, m - s * 0.04f, a + s * 0.04f, a + s * 0.04f), -45, 90, false, p);
                c.drawArc(new RectF(m - s * 0.04f, m - s * 0.04f, a + s * 0.04f, a + s * 0.04f), 135, 90, false, p);
                break;
            case CHIP:
                c.drawRoundRect(new RectF(s * 0.34f, s * 0.34f, s * 0.66f, s * 0.66f), s * 0.04f, s * 0.04f, p);
                for (float t : new float[]{0.42f, 0.5f, 0.58f}) {
                    c.drawLine(s * t, m, s * t, s * 0.34f, p);
                    c.drawLine(s * t, s * 0.66f, s * t, a, p);
                    c.drawLine(m, s * t, s * 0.34f, s * t, p);
                    c.drawLine(s * 0.66f, s * t, a, s * t, p);
                }
                break;
            case NODES:                                    // our triangle web, small
                c.save();
                c.translate(s * 0.15f, s * 0.15f);
                Ui.drawLogo(c, s * 0.7f, Ui.CYAN);
                c.restore();
                break;
            case CHAT:                                     // speech bubble
                c.drawRoundRect(new RectF(m, m, a, s * 0.62f), s * 0.1f, s * 0.1f, p);
                path.moveTo(s * 0.38f, s * 0.62f);
                path.lineTo(s * 0.33f, a);
                path.lineTo(s * 0.5f, s * 0.62f);
                c.drawPath(path, p);
                break;
            case GEAR:
                c.drawCircle(s / 2, s / 2, s * 0.09f, p);
                c.drawCircle(s / 2, s / 2, s * 0.19f, p);
                for (int i = 0; i < 8; i++) {
                    double t = Math.PI * i / 4;
                    c.drawLine((float) (s / 2 + Math.cos(t) * s * 0.19f), (float) (s / 2 + Math.sin(t) * s * 0.19f),
                            (float) (s / 2 + Math.cos(t) * s * 0.25f), (float) (s / 2 + Math.sin(t) * s * 0.25f), p);
                }
                break;
            case MESH:                                     // fork: two nodes joining one (like a mesh branch)
                c.drawCircle(s * 0.34f, s * 0.32f, s * 0.07f, p);
                c.drawCircle(s * 0.66f, s * 0.32f, s * 0.07f, p);
                c.drawCircle(s / 2, s * 0.72f, s * 0.07f, p);
                path.moveTo(s * 0.34f, s * 0.39f);
                path.quadTo(s * 0.34f, s * 0.55f, s / 2, s * 0.55f);
                path.quadTo(s * 0.66f, s * 0.55f, s * 0.66f, s * 0.39f);
                c.drawPath(path, p);
                c.drawLine(s / 2, s * 0.55f, s / 2, s * 0.65f, p);
                break;
            case PLUS:
                c.drawLine(s / 2, m, s / 2, a, p);
                c.drawLine(m, s / 2, a, s / 2, p);
                break;
            case TRASH:
                c.drawLine(m, s * 0.34f, a, s * 0.34f, p);
                c.drawRoundRect(new RectF(s * 0.34f, s * 0.34f, s * 0.66f, a), s * 0.04f, s * 0.04f, p);
                c.drawLine(s * 0.43f, m, s * 0.57f, m, p);
                break;
            case EDIT:                                     // pencil
                c.drawLine(m, a, a - s * 0.06f, m + s * 0.06f, p);
                c.drawLine(m, a, m + s * 0.12f, a, p);
                break;
            case KEY:
                c.drawCircle(s * 0.38f, s * 0.42f, s * 0.12f, p);
                c.drawLine(s * 0.47f, s * 0.5f, a, a - s * 0.05f, p);
                c.drawLine(a - s * 0.1f, a - s * 0.12f, a - s * 0.02f, a - s * 0.2f, p);
                break;
            case PIN:                                      // map pin
                path.moveTo(s / 2, a);
                path.cubicTo(s * 0.28f, s * 0.5f, s * 0.3f, m, s / 2, m);
                path.cubicTo(s * 0.7f, m, s * 0.72f, s * 0.5f, s / 2, a);
                c.drawPath(path, p);
                c.drawCircle(s / 2, s * 0.42f, s * 0.06f, p);
                break;
            case FENCE: {                                  // dashed ring around a dot
                Paint q = new Paint(p);
                q.setPathEffect(new android.graphics.DashPathEffect(new float[]{s * 0.07f, s * 0.05f}, 0));
                c.drawCircle(s / 2, s / 2, s * 0.24f, q);
                c.drawCircle(s / 2, s / 2, s * 0.04f, p);
                break;
            }
            case AREA: {                                   // polygon with corner dots
                float[][] pt = {{m, s * 0.4f}, {s * 0.48f, m}, {a, s * 0.45f}, {s * 0.6f, a}, {s * 0.36f, a - s * 0.04f}};
                path.moveTo(pt[0][0], pt[0][1]);
                for (int i = 1; i < pt.length; i++) path.lineTo(pt[i][0], pt[i][1]);
                path.close();
                Paint q = new Paint(p);
                q.setPathEffect(new android.graphics.DashPathEffect(new float[]{s * 0.06f, s * 0.04f}, 0));
                c.drawPath(path, q);
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                for (float[] x : pt) c.drawCircle(x[0], x[1], s * 0.04f, f);
                break;
            }
            case TRAIL: {                                  // dotted path ending in a dot
                Paint q = new Paint(p);
                q.setPathEffect(new android.graphics.DashPathEffect(new float[]{s * 0.01f, s * 0.07f}, 0));
                path.moveTo(m, a);
                path.cubicTo(s * 0.55f, a, s * 0.3f, s * 0.45f, s * 0.6f, s * 0.38f);
                c.drawPath(path, q);
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                c.drawCircle(a - s * 0.04f, m + s * 0.06f, s * 0.06f, f);
                break;
            }
            default:                                       // INFO
                c.drawCircle(s / 2, s / 2, s * 0.24f, p);
                c.drawLine(s / 2, s * 0.46f, s / 2, s * 0.62f, p);
                c.drawPoint(s / 2, s * 0.38f, p);
        }
    }

    /** The icon alone (no tile), for buttons and tabs. */
    static android.graphics.drawable.Drawable icon(Context ctx, Icon icon, int sizeDp, int colour) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int px = Math.round(sizeDp * d);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.6f * d, px * 0.08f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Canvas c = new Canvas(b);
        c.scale(1.35f, 1.35f, px / 2f, px / 2f);              // tiles leave a margin; bare icons fill more
        drawIcon(c, icon, px, p);
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
    }

    /** Put an icon in front of a button's text. */
    static android.widget.Button withIcon(Context ctx, android.widget.Button b, Icon icon) {
        android.graphics.drawable.Drawable dr = icon(ctx, icon, 18, b.getCurrentTextColor());
        dr.setBounds(0, 0, dr.getIntrinsicWidth(), dr.getIntrinsicHeight());
        b.setCompoundDrawables(dr, null, null, null);
        b.setCompoundDrawablePadding((int) (8 * ctx.getResources().getDisplayMetrics().density));
        return b;
    }

    /** Big panel heading with an icon, like "SETTINGS" / "MESH TOPOLOGY". */
    static View heading(Ui u, Icon icon, String title, String right) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(10));
        View t = tile(u.ctx(), icon, 30);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).rightMargin = u.dp(10);
        r.addView(t);
        TextView h = u.text(title.toUpperCase(java.util.Locale.US), 19, Ui.TEXT, true, false);
        h.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        h.setLetterSpacing(0.14f);
        r.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) r.addView(u.text(right, 11, Ui.SUB, false, false));
        return r;
    }

    static GradientDrawable box(float d, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, (int) d), stroke);
        g.setCornerRadius(8 * d);
        return g;
    }

    /** Shrinks a single-line TextView's text (down to minSp) until it fits its width. */
    static void fitOneLine(TextView t, float maxSp, float minSp) {
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            int w = r - l - t.getPaddingLeft() - t.getPaddingRight();
            if (w <= 0 || r - l == or - ol) return;
            float sp = maxSp;
            android.text.TextPaint p = new android.text.TextPaint(t.getPaint());
            float scale = t.getResources().getDisplayMetrics().scaledDensity;
            String txt = t.getText().toString();
            while (sp > minSp) {
                p.setTextSize(sp * scale);
                if (p.measureText(txt) <= w) break;
                sp -= 0.5f;
            }
            final float size = sp;
            if (Math.abs(t.getTextSize() - size * scale) > 0.5f) t.post(() -> t.setTextSize(size));
        });
    }

    /** Menu card: icon tile, title, one-line summary, chevron. */
    static LinearLayout menuCard(Ui u, Icon icon, String title, String sub, int subColour, Runnable click) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        float d = u.dp(1);
        r.setBackground(box(d, 0xE607100F, 0x6600E5FF));
        r.setPadding(u.dp(12), u.dp(9), u.dp(12), u.dp(9));      // compact: the whole menu fits without scrolling
        View t = tile(u.ctx(), icon, 40);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).rightMargin = u.dp(12);
        r.addView(t);
        LinearLayout col = u.column();
        col.addView(u.text(title, 16, Ui.TEXT, false, true));
        TextView s = u.text(sub, 12, subColour, false, true);
        s.setMaxLines(1);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fitOneLine(s, 12, 8);                                     // shrink long subtitles to stay on one line
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(u.text("›", 26, Ui.SUB, false, false));
        r.setOnClickListener(v -> click.run());
        return r;
    }

    /** Section card with an icon + UPPERCASE heading, optional badge; add rows to the returned body. */
    static LinearLayout section(Ui u, LinearLayout parent, Icon icon, String heading, String badge) {
        LinearLayout card = u.column();
        float d = u.dp(1);
        card.setBackground(box(d, 0xE607100F, 0x6600E5FF));
        card.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14));
        LinearLayout head = u.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        View t = tile(u.ctx(), icon, 28);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).rightMargin = u.dp(10);
        head.addView(t);
        TextView h = u.text(heading.toUpperCase(java.util.Locale.US), 15, Ui.TEXT, true, false);
        h.setLetterSpacing(0.12f);
        head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        if (badge != null) {
            TextView b = u.text(badge, 10, Ui.OK, true, true);
            b.setBackground(box(d, 0x1A00E676, 0x6600E676));
            b.setPadding(u.dp(8), u.dp(3), u.dp(8), u.dp(3));
            head.addView(b);
        }
        card.addView(head);
        LinearLayout body = u.column();
        body.setPadding(0, u.dp(10), 0, 0);
        card.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = u.dp(12);
        parent.addView(card, lp);
        return body;
    }

    /** "● Name ........ value" status row. */
    static View statusRow(Ui u, int dotColour, String name, String value) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, u.dp(7), 0, u.dp(7));
        View dt = u.dot(dotColour, 9);
        ((LinearLayout.LayoutParams) dt.getLayoutParams()).rightMargin = u.dp(10);
        r.addView(dt);
        r.addView(u.text(name, 15, Ui.TEXT, false, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = u.text(value, 13, Ui.SUB, false, true);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1.3f));
        return r;
    }

    /** Small grey note under a section. */
    static TextView note(Ui u, String s) {
        TextView t = u.text("ⓘ  " + s, 12, Ui.MUTED, false, false);
        t.setPadding(0, u.dp(8), 0, 0);
        return t;
    }
}
