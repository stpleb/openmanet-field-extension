package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

/**
 * Talk-group list, like OpenMANET's channel tiles: CH 1-5. Solid = the group you talk on, outlined =
 * listening, grey = off, green dot = someone talking there now. Tap = talk on it; long-press = listen on/off.
 */
final class TalkGroupBar extends View {
    private final PttEngine ptt;
    private final float d;
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;

    TalkGroupBar(Context ctx, PttEngine ptt) {
        super(ctx);
        this.ptt = ptt;
        d = ctx.getResources().getDisplayMetrics().density;
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        label.setTextSize(12 * d);
        label.setLetterSpacing(0.1f);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                int g = groupAt(e.getX());
                ptt.setTalkGroup(g);
                Toast.makeText(getContext(), "Talking on CH " + g, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                int g = groupAt(e.getX());
                if (g == ptt.group()) { Toast.makeText(getContext(), "Always listening on your talk channel", Toast.LENGTH_SHORT).show(); return; }
                boolean on = !ptt.listening(g);
                ptt.setListening(g, on);
                performHapticFeedback(HAPTIC_FEEDBACK_ENABLED);
                Toast.makeText(getContext(), (on ? "Listening on CH " : "Stopped listening on CH ") + g, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int groupAt(float x) {
        int n = PttEngine.GROUP_PORTS.length;
        return Math.max(1, Math.min(n, (int) (x / (getWidth() / (float) n)) + 1));
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (44 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = PttEngine.GROUP_PORTS.length;
        float cw = getWidth() / (float) n, gap = 4 * d;
        boolean anyActive = false;
        for (int i = 0; i < n; i++) {
            int g = i + 1;
            boolean tx = g == ptt.group(), rx = ptt.listening(g) || tx, act = ptt.active(g);
            anyActive |= act;
            RectF r = new RectF(i * cw + gap, 4 * d, (i + 1) * cw - gap, getHeight() - 4 * d);
            box.setStyle(Paint.Style.FILL);
            box.setColor(tx ? 0x3300E5FF : 0xFF07100F);
            c.drawRoundRect(r, 6 * d, 6 * d, box);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth((tx ? 2 : 1) * d);
            box.setColor(tx ? Ui.CYAN : rx ? 0xAA00E5FF : Ui.LINE);
            c.drawRoundRect(r, 6 * d, 6 * d, box);
            label.setColor(tx ? Ui.CYAN : rx ? Ui.TEXT : Ui.MUTED);
            c.drawText("CH " + g, r.centerX() + (act ? 5 * d : 0), r.centerY() + 4 * d, label);
            if (act) {
                dot.setColor(Ui.OK);
                c.drawCircle(r.left + 10 * d, r.centerY(), 4 * d, dot);
            }
        }
        if (anyActive) postInvalidateDelayed(200);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = gestures.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_UP) performClick();
        return r;
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
