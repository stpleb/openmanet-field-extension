package com.manetnode.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;

/** The triangle-web logo, drawn the same way as the ATAK plugin and the node web page. */
final class StatusWidget {
    private StatusWidget() {}

    static void drawLogo(Canvas c, float x, float y, float s, int colour, float weight) {
        float m = s * 0.12f;
        float[] T = {x + s / 2, y + m}, L = {x + m, y + s - m * 1.3f}, R = {x + s - m, y + s - m * 1.3f};
        float[] M1 = mid(T, L), M2 = mid(L, R), M3 = mid(R, T);
        float[] cen = {(T[0] + L[0] + R[0]) / 3, (T[1] + L[1] + R[1]) / 3};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(colour);
        p.setAlpha(165);                                     // inner web is dimmer than the frame
        p.setStrokeWidth(s * 0.03f * weight);
        float[][][] web = {{T, M2}, {L, M3}, {R, M1}, {M1, M2}, {M2, M3}, {M3, M1}};
        for (float[][] l : web) c.drawLine(l[0][0], l[0][1], l[1][0], l[1][1], p);
        for (float[] n : new float[][]{M1, M2, M3, cen}) c.drawCircle(n[0], n[1], s * 0.045f * weight, p);
        p.setAlpha(255);
        p.setStrokeWidth(s * 0.06f * weight);
        float[][] tri = {T, L, R};
        for (int i = 0; i < 3; i++) c.drawLine(tri[i][0], tri[i][1], tri[(i + 1) % 3][0], tri[(i + 1) % 3][1], p);
        for (float[] n : tri) c.drawCircle(n[0], n[1], s * 0.095f * Math.min(weight, 1.15f), p);
    }

    private static float[] mid(float[] a, float[] b) { return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2}; }
}
