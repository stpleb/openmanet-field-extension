package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * The PTT button: a large ring of rounded segments around a circle with "PTT" and the state, in the
 * theme colours. Ready = cyan, talking = red with the ring pulsing, receiving = green with the ring
 * sweeping, off = grey. Hold to talk.
 */
final class PttButton extends View {
    interface Press { boolean down(); void up(); }

    private static final int SEGMENTS = 36;
    private final float d;
    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int colour = Ui.MUTED;
    private String state = "";
    private boolean animate, held;
    private Press press;

    PttButton(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        seg.setStyle(Paint.Style.STROKE);
        seg.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        sub.setLetterSpacing(0.12f);
    }

    void setPress(Press p) { press = p; }

    /** colour of the ring and text, the line under "PTT", and whether the ring moves. */
    void show(int colour, String state, boolean animate) {
        this.colour = colour;
        this.state = state;
        this.animate = animate;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? width : MeasureSpec.getSize(h);
        setMeasuredDimension(width, height);                     // drawn as large a circle as fits
    }

    @Override
    protected void onDraw(Canvas c) {
        // Everything is a fraction of the button size, so it keeps the OpenMANET proportions at any size:
        // thick segmented outer ring, inner ring at ~78% of it, "PTT" and the state line inside.
        float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        float segW = s * 0.06f, outer = s / 2 - segW / 2 - s * 0.01f, inner = outer * 0.78f;
        long now = System.currentTimeMillis();
        glow.setColor(colour);                                       // soft glow inside the inner ring
        glow.setAlpha(held ? 60 : 22);
        c.drawCircle(cx, cy, inner + s * 0.02f, glow);
        glow.setColor(0xFF07100F);
        glow.setAlpha(255);
        c.drawCircle(cx, cy, inner - s * 0.01f, glow);
        seg.setStrokeWidth(segW);
        float sweep = 360f / SEGMENTS, gap = 360f * (segW * 0.55f) / (float) (2 * Math.PI * outer);
        RectF box = new RectF(cx - outer, cy - outer, cx + outer, cy + outer);
        double phase = (now % 1200) / 1200.0;
        for (int i = 0; i < SEGMENTS; i++) {
            int a;
            if (!animate) a = colour == Ui.MUTED ? 90 : 220;
            else if (colour == Ui.BAD) a = (int) (150 + 105 * Math.abs(Math.sin(Math.PI * (phase * 2))));   // pulse
            else {                                                                          // sweep
                double pos = ((double) i / SEGMENTS - phase + 1) % 1.0;
                a = (int) (60 + 195 * Math.pow(1 - pos, 3));
            }
            seg.setColor(colour);
            seg.setAlpha(a);
            c.drawArc(box, -90 + i * sweep + gap / 2, Math.max(1, sweep - gap), false, seg);
        }
        ring.setColor(colour);
        ring.setAlpha(colour == Ui.MUTED ? 120 : 255);
        ring.setStrokeWidth(Math.max(2 * d, s * 0.014f));
        c.drawCircle(cx, cy, inner, ring);
        big.setColor(colour == Ui.MUTED ? Ui.SUB : colour);
        big.setTextSize(inner * 0.5f);
        c.drawText("PTT", cx, cy + inner * 0.12f, big);
        sub.setColor(Ui.SUB);
        sub.setTextSize(inner * 0.13f);
        float w = sub.measureText(state);                            // keep the state line inside the ring
        if (w > inner * 1.5f) sub.setTextSize(sub.getTextSize() * inner * 1.5f / w);
        c.drawText(state, cx, cy + inner * 0.45f, sub);
        if (animate) postInvalidateDelayed(40);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f;
        boolean inside = Math.hypot(dx, dy) <= Math.min(getWidth(), getHeight()) / 2f;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside) return false;
                held = press != null && press.down();
                if (held) performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (held && press != null) press.up();
                held = false;
                invalidate();
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
