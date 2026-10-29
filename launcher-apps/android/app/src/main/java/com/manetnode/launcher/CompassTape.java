package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;

/**
 * Heading tape across the top of the map: ticks every 5 deg, numbers every 10, N/E/S/W (N in red), and the
 * phone's heading in a box under the centre mark. Uses the rotation-vector sensor, smoothed.
 */
final class CompassTape extends View implements SensorEventListener {
    private static final float DEG_PER_DP = 0.55f;               // how much of the circle fits per dp of width
    private final float d;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            box = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
    private final SensorManager sm;
    private final float[] rot = new float[9], ori = new float[3];
    private float heading = Float.NaN;

    CompassTape(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        bg.setColor(0xCC07100F);
    }

    void start() {
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }

    void stop() { if (sm != null) sm.unregisterListener(this); }

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        float h = (float) ((Math.toDegrees(ori[0]) + 360) % 360);
        if (Float.isNaN(heading)) heading = h;
        else {
            float diff = ((h - heading + 540) % 360) - 180;            // shortest way round
            heading = (heading + diff * 0.15f + 360) % 360;
        }
        invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    @Override
    protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) (58 * d)); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, base = 26 * d;
        c.drawRect(0, 0, w, h, bg);
        float hd = Float.isNaN(heading) ? 0 : heading;
        float pxPerDeg = 1 / DEG_PER_DP * d;
        int from = (int) Math.floor(hd - cx / pxPerDeg) - 5, to = (int) Math.ceil(hd + cx / pxPerDeg) + 5;
        for (int deg = from - (from % 5 + 5) % 5; deg <= to; deg += 5) {
            float x = cx + (deg - hd) * pxPerDeg;
            int norm = ((deg % 360) + 360) % 360;
            boolean ten = norm % 10 == 0;
            tick.setColor(0x9900E5FF);
            tick.setStrokeWidth((ten ? 1.6f : 1f) * d);
            c.drawLine(x, 4 * d, x, (ten ? 16 : 10) * d, tick);
            if (ten) {
                String txt;
                int col = Ui.SUB;
                switch (norm) {
                    case 0: txt = "N"; col = Ui.BAD; break;
                    case 90: txt = "E"; col = Ui.TEXT; break;
                    case 180: txt = "S"; col = Ui.TEXT; break;
                    case 270: txt = "W"; col = Ui.TEXT; break;
                    default: txt = (norm % 30 == 0) ? String.valueOf(norm) : null;
                }
                if (txt != null) {
                    label.setColor(col);
                    label.setTextSize((txt.length() == 1 ? 14 : 11) * d);
                    label.setFakeBoldText(txt.length() == 1);
                    c.drawText(txt, x, base + 2 * d, label);
                }
            }
        }
        // centre mark and heading box
        tick.setColor(Ui.CYAN);
        tick.setStrokeWidth(2 * d);
        c.drawLine(cx, 0, cx, 20 * d, tick);
        Path tri = new Path();
        tri.moveTo(cx - 6 * d, 30 * d);
        tri.lineTo(cx + 6 * d, 30 * d);
        tri.lineTo(cx, 23 * d);
        tri.close();
        box.setColor(Ui.CYAN);
        c.drawPath(tri, box);
        RectF r = new RectF(cx - 30 * d, 31 * d, cx + 30 * d, 53 * d);
        box.setColor(0xF007100F);
        c.drawRoundRect(r, 6 * d, 6 * d, box);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(1 * d);
        box.setColor(Ui.CYAN);
        c.drawRoundRect(r, 6 * d, 6 * d, box);
        box.setStyle(Paint.Style.FILL);
        label.setColor(Ui.TEXT);
        label.setTextSize(14 * d);
        label.setFakeBoldText(true);
        c.drawText(Float.isNaN(heading) ? "---" : Math.round(hd) % 360 + "°", cx, r.centerY() + 5 * d, label);
        label.setFakeBoldText(false);
        tick.setColor(Ui.LINE);
        tick.setStrokeWidth(1 * d);
        c.drawLine(0, h - 0.5f * d, w, h - 0.5f * d, tick);
    }
}
