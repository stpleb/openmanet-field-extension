package com.fieldext.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Field Extension icon in ATAK's bottom-right status corner (beside the TAK server icon),
 * with a dot in the node's light colour; grey when there is no reading (node not answering or not checked yet).
 * Tapping it opens the panel.
 */
final class StatusWidget {
    static final int UNKNOWN = 0xFF5C7682, OFFLINE = UNKNOWN;   // no reading: grey, never a colour the light could show
    private static final int PX = 96;

    private final Context pluginContext;
    private final MarkerIconWidget widget = new MarkerIconWidget();
    private LinearLayoutWidget corner;
    private int shown;

    StatusWidget(Context pluginContext, Runnable onTap) {
        this.pluginContext = pluginContext;
        widget.setName("Field Extension status");
        widget.addOnClickListener((com.atakmap.android.widgets.MapWidget.OnClickListener) (w, e) -> onTap.run());
        setDot(UNKNOWN);
    }

    boolean attached() { return corner != null; }

    void attach() {
        MapView mv = MapView.getMapView();
        if (mv == null || corner != null) return;
        RootLayoutWidget root = (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
        if (root == null) return;
        corner = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
        corner.addWidget(widget);
    }

    void detach() {
        if (corner != null) corner.removeWidget(widget);
        corner = null;
    }

    /** Redraws only when the colour changes. */
    void setDot(int colour) {
        if (colour == shown) return;
        shown = colour;
        int size = 36;   // ATAK scales widget icons by screen density itself; matches the TAK server icon
        Icon.Builder b = new Icon.Builder();
        b.setAnchor(0, 0);
        b.setSize(size, size);
        String uri = render(colour);
        if (uri == null) return;
        b.setImageUri(Icon.STATE_DEFAULT, uri);
        widget.setIcon(b.build());
    }

    /** Draws logo + dot to a PNG in ATAK's cache folder (one file per colour) and returns its file:// URI. */
    /** White network-triangle logo on transparent, for ATAK's toolbar (which tints icons). */
    static Bitmap toolbarIcon() {
        int n = 96;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        drawLogo(new Canvas(b), 0, 0, n, 0xFFFFFFFF, 1.3f);
        return b;
    }

    /**
     * Logo "Triangle web": the network triangle with a Web of Wyrd style web inside
     * (staves from each corner to the opposite side, an inner triangle, nodes where they meet).
     * weight thickens the lines for small sizes.
     */
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

    private String render(int colour) {
        MapView mv = MapView.getMapView();
        File dir = mv != null ? mv.getContext().getCacheDir() : pluginContext.getCacheDir();
        File f = new File(dir, String.format("fieldext_status6_%08x.png", colour));
        if (f.length() > 0) return Uri.fromFile(f).toString();
        // Logo in web UI cyan, no box, with a solid dot bottom-right sized like the TAK server icon's dot.
        Bitmap out = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        drawLogo(c, -PX * 0.06f, -PX * 0.06f, PX * 0.86f, FieldPanel.CYAN, 1.35f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = PX * 0.24f;
        p.setColor(colour);
        c.drawCircle(PX - r, PX - r, r, p);
        try (FileOutputStream png = new FileOutputStream(f)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, png);
        } catch (IOException e) {
            return null;
        }
        return Uri.fromFile(f).toString();
    }
}
