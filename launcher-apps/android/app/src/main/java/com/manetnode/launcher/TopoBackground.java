package com.manetnode.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Topographic-map background for the panels: faint contour lines in the theme cyan over the dark panel
 * colour, with every fifth line a little brighter and one dashed "trail". The terrain is a fixed, smooth
 * height field, so the pattern is the same every time; it is drawn once per size and cached.
 */
final class TopoBackground extends Drawable {
    private static final int LEVELS = 16;
    private final int base, line;
    private final float density;
    private Bitmap cache;

    TopoBackground(int base, int line, float density) {
        this.base = base;
        this.line = line;
        this.density = density;
    }

    /** Smooth terrain from a few overlapping waves and two hills; values roughly 0..1. */
    private static double height(double x, double y) {
        double h = 0.5
                + 0.18 * Math.sin(x * 1.7 + 0.6) * Math.cos(y * 1.3 - 0.4)
                + 0.12 * Math.sin(x * 3.1 - y * 2.2 + 1.3)
                + 0.08 * Math.cos(x * 5.3 + y * 4.1)
                + 0.30 * Math.exp(-((x - 2.1) * (x - 2.1) + (y - 1.2) * (y - 1.2)) * 1.6)
                - 0.22 * Math.exp(-((x - 0.7) * (x - 0.7) + (y - 3.0) * (y - 3.0)) * 1.2);
        return Math.max(0, Math.min(1, h));
    }

    private Bitmap render(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(base);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        float step = 6 * density;                              // marching-squares grid
        int cols = (int) (w / step) + 2, rows = (int) (h / step) + 2;
        double scale = 1.0 / (140 * density);                  // terrain units per pixel
        double[][] v = new double[rows][cols];
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++) v[j][i] = height(i * step * scale, j * step * scale);
        for (int k = 1; k < LEVELS; k++) {
            double iso = (double) k / LEVELS;
            boolean major = k % 5 == 0;
            p.setColor(line);
            p.setAlpha(major ? 70 : 34);
            p.setStrokeWidth((major ? 1.4f : 1f) * density);
            for (int j = 0; j < rows - 1; j++) {
                for (int i = 0; i < cols - 1; i++) {
                    double a = v[j][i], bb = v[j][i + 1], cc = v[j + 1][i + 1], d = v[j + 1][i];
                    int idx = (a > iso ? 8 : 0) | (bb > iso ? 4 : 0) | (cc > iso ? 2 : 0) | (d > iso ? 1 : 0);
                    if (idx == 0 || idx == 15) continue;
                    float x = i * step, y = j * step;
                    float tx = x + step * (float) ((iso - a) / (bb - a)), ty = y;                 // top edge
                    float rx = x + step, ry = y + step * (float) ((iso - bb) / (cc - bb));       // right
                    float bx = x + step * (float) ((iso - d) / (cc - d)), by = y + step;        // bottom
                    float lx = x, ly = y + step * (float) ((iso - a) / (d - a));                 // left
                    switch (idx) {
                        case 1: case 14: c.drawLine(lx, ly, bx, by, p); break;
                        case 2: case 13: c.drawLine(bx, by, rx, ry, p); break;
                        case 3: case 12: c.drawLine(lx, ly, rx, ry, p); break;
                        case 4: case 11: c.drawLine(tx, ty, rx, ry, p); break;
                        case 6: case 9: c.drawLine(tx, ty, bx, by, p); break;
                        case 7: case 8: c.drawLine(lx, ly, tx, ty, p); break;
                        case 5: c.drawLine(lx, ly, tx, ty, p); c.drawLine(bx, by, rx, ry, p); break;
                        case 10: c.drawLine(tx, ty, rx, ry, p); c.drawLine(lx, ly, bx, by, p); break;
                        default: break;
                    }
                }
            }
        }
        // A dashed trail across the terrain, like on a topo map.
        p.setColor(line);
        p.setAlpha(60);
        p.setStrokeWidth(1.6f * density);
        p.setPathEffect(new DashPathEffect(new float[]{10 * density, 8 * density}, 0));
        android.graphics.Path trail = new android.graphics.Path();
        trail.moveTo(-10, h * 0.15f);
        trail.cubicTo(w * 0.25f, h * 0.05f, w * 0.15f, h * 0.55f, w * 0.45f, h * 0.6f);
        trail.cubicTo(w * 0.75f, h * 0.65f, w * 0.7f, h * 0.95f, w + 10, h * 0.9f);
        c.drawPath(trail, p);
        return b;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect r = getBounds();
        if (r.width() <= 0 || r.height() <= 0) return;
        if (cache == null || cache.getWidth() != r.width() || cache.getHeight() != r.height()) cache = render(r.width(), r.height());
        canvas.drawBitmap(cache, r.left, r.top, null);
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
