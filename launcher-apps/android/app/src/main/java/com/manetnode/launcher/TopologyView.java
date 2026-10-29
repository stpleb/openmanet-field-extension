package com.manetnode.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesh topology: each node as a circle in its status-light colour, with a line for every link, coloured
 * like the status light of the node it leads to; solid = HaLow, dashed = 2.4 GHz, dotted = LoRa only and labelled with batman's throughput estimate. The node
 * this phone is on sits on top. Links are matched to nodes by interface MAC when nodes report them
 * (summary.mesh.interfaces); with one other node every link must lead to it.
 */
final class TopologyView extends View {
    interface OnNode { void tapped(String host); }

    static final int HALOW = 0xFF00E676, WIFI = Ui.CYAN, LORA = 0xFF4D8BFF;   // HaLow green, 2.4 GHz cyan, LoRa blue

    private static final class N {
        String host, name, battery;
        int colour;
        boolean here;
        float x, y;
    }

    private static final class L {
        N a, b;
        String transport;
        Double mbps;
    }

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<N> nodes = new ArrayList<>();
    private final List<L> links = new ArrayList<>();
    private final List<String> loose = new ArrayList<>();       // links we could not place
    private OnNode onNode;

    TopologyView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        text.setColor(Ui.TEXT);
        text.setTextSize(14 * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        small.setColor(Ui.SUB);
        small.setTextSize(11 * d);
        small.setTextAlign(Paint.Align.CENTER);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void setOnNode(OnNode o) { onNode = o; }

    /** hosts in display order (this Wi-Fi's node first); summaries may be null for nodes not answering. */
    void set(List<String> hosts, Map<String, JSONObject> status, String wifiHost) { set(hosts, status, wifiHost, new HashMap<>()); }

    /** loraIds: host -> its LoRa radio id; the lora status (radio.heard) is read from status under host + "#lora". */
    void set(List<String> hosts, Map<String, JSONObject> status, String wifiHost, Map<String, JSONObject> lora) {
        nodes.clear();
        links.clear();
        loose.clear();
        Map<String, N> byMac = new HashMap<>();
        for (String h : hosts) {
            JSONObject s = status.get(h);
            if (s == null && !h.equals(wifiHost)) continue;
            N n = new N();
            n.host = h;
            n.name = Nodes.name(s, h);
            n.here = h.equals(wifiHost);
            JSONObject led = s == null ? null : s.optJSONObject("led");
            n.colour = s == null ? Ui.MUTED : Ui.ledColour(led == null ? null : led.optString("color", null));
            JSONObject p = s == null ? null : s.optJSONObject("power");
            if (p != null && p.optBoolean("available") && p.has("battery_percent") && !p.isNull("battery_percent"))
                n.battery = p.optInt("battery_percent") + "%";
            JSONObject mesh = s == null ? null : s.optJSONObject("mesh");
            JSONArray ifs = mesh == null ? null : mesh.optJSONArray("interfaces");
            for (int i = 0; ifs != null && i < ifs.length(); i++) {
                JSONObject f = ifs.optJSONObject(i);
                if (f != null && f.has("mac")) byMac.put(f.optString("mac").toLowerCase(Locale.US), n);
            }
            nodes.add(n);
        }
        for (N a : nodes) {
            JSONObject s = status.get(a.host);
            JSONObject mesh = s == null ? null : s.optJSONObject("mesh");
            JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
            for (int i = 0; nb != null && i < nb.length(); i++) {
                JSONObject e = nb.optJSONObject(i);
                if (e == null) continue;
                N b = byMac.get(e.optString("mac").toLowerCase(Locale.US));
                if (b == null && nodes.size() == 2) b = nodes.get(0) == a ? nodes.get(1) : nodes.get(0);
                String tr = e.optString("transport", "Wi-Fi");
                Double mbps = e.isNull("throughput_mbps") ? null : e.optDouble("throughput_mbps");
                if (b == null || b == a) { loose.add(a.name + " → " + e.optString("mac") + " (" + tr + ")"); continue; }
                L dup = null;
                for (L l : links) if (((l.a == b && l.b == a) || (l.a == a && l.b == b)) && l.transport.equals(tr)) dup = l;
                if (dup != null) { if (dup.mbps == null || (mbps != null && mbps > dup.mbps)) dup.mbps = mbps; continue; }
                L l = new L();
                l.a = a; l.b = b; l.transport = tr; l.mbps = mbps;
                links.add(l);
            }
        }
        // LoRa-only links: two nodes that heard each other's radio in the last 15 min and share no mesh link.
        Map<String, N> byRadio = new HashMap<>();
        for (N k : nodes) {
            JSONObject l = lora.get(k.host), r = l == null ? null : l.optJSONObject("radio");
            if (r != null && !r.optString("node_id").isEmpty()) byRadio.put(r.optString("node_id"), k);
        }
        long now = System.currentTimeMillis() / 1000;
        for (N a : nodes) {
            JSONObject l = lora.get(a.host), r = l == null ? null : l.optJSONObject("radio");
            JSONArray heard = r == null ? null : r.optJSONArray("heard");
            for (int i = 0; heard != null && i < heard.length(); i++) {
                JSONObject h = heard.optJSONObject(i);
                N b = h == null ? null : byRadio.get(h.optString("id"));
                if (b == null || b == a || now - h.optLong("last_heard") > 900) continue;
                boolean linked = false;
                for (L x : links) if ((x.a == a && x.b == b) || (x.a == b && x.b == a)) linked = true;
                if (linked) continue;
                L l2 = new L();
                l2.a = a; l2.b = b; l2.transport = "LoRa"; l2.mbps = null;
                links.add(l2);
            }
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h)
                : (int) ((nodes.size() <= 2 ? 300 : 260 + 50 * Math.min(nodes.size(), 8)) * d + 18 * d * loose.size());
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), legendH = 34 * d + 18 * d * loose.size();
        float top = 24 * d, bottom = getHeight() - legendH - 46 * d;     // room for names under the circles
        float cx = w / 2, cy = (top + bottom) / 2;
        if (nodes.isEmpty()) {
            small.setTextSize(13 * d);
            c.drawText("No mesh nodes. Join a node's Wi-Fi.", cx, getHeight() / 2f, small);
            small.setTextSize(11 * d);
            return;
        }
        // Layout: 1 node centred; 2 side by side; more around a circle, this node first (top).
        int n = nodes.size();
        float r = Math.min(w * 0.36f, (bottom - top) / 2);
        for (int i = 0; i < n; i++) {
            N k = nodes.get(i);
            if (n == 1) { k.x = cx; k.y = cy; }
            else if (n == 2) { k.x = i == 0 ? w * 0.2f : w * 0.8f; k.y = cy; }
            else {
                double a = -Math.PI / 2 + 2 * Math.PI * i / n;
                k.x = (float) (cx + r * Math.cos(a));
                k.y = (float) (cy + r * Math.sin(a));
            }
        }
        // Links: one gentle curve per radio, bowed apart so two radios between a pair never overlap;
        // the label sits at the outside of its curve.
        line.setStyle(Paint.Style.STROKE);
        for (L l : links) {
            int pair = 0, idx = 0;
            for (L o : links) if ((o.a == l.a && o.b == l.b) || (o.a == l.b && o.b == l.a)) { if (o == l) idx = pair; pair++; }
            boolean halow = "HaLow".equals(l.transport), lora = "LoRa".equals(l.transport);
            float bow = pair == 1 ? 0 : (halow ? -1 : 1) * 34 * d;          // HaLow above, Wi-Fi below
            N a = l.a.x <= l.b.x ? l.a : l.b, b = a == l.a ? l.b : l.a;
            float dx = b.x - a.x, dy = b.y - a.y, len = (float) Math.max(1, Math.hypot(dx, dy));
            float nx = -dy / len, ny = dx / len;                            // normal
            float mx = (a.x + b.x) / 2 + nx * bow * 2, my = (a.y + b.y) / 2 + ny * bow * 2;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(a.x, a.y);
            p.quadTo(mx, my, b.x, b.y);
            N far = l.a.here ? l.b : l.b.here ? l.a : l.b;          // colour = the light of the node the link leads to
            int col = far.colour == Ui.MUTED ? (halow ? HALOW : lora ? LORA : WIFI) : far.colour;
            line.setColor(col);
            line.setStrokeWidth((halow ? 3.5f : 3f) * d);
            line.setPathEffect(halow ? null : lora ? new android.graphics.DashPathEffect(new float[]{2 * d, 6 * d}, 0)
                    : new android.graphics.DashPathEffect(new float[]{9 * d, 6 * d}, 0));
            c.drawPath(p, line);
            line.setPathEffect(null);
            String lab = (halow ? "HaLow" : lora ? "LoRa only" : "2.4 GHz") + (l.mbps == null ? "" : String.format(Locale.US, " · %.0f Mb/s", l.mbps));
            float lx = (a.x + 2 * mx + b.x) / 4, ly = (a.y + 2 * my + b.y) / 4;   // curve midpoint
            float off = (bow <= 0 ? -12 : 20) * d;
            small.setColor(col);
            small.setFakeBoldText(true);
            c.drawText(lab, lx, ly + off, small);
            small.setFakeBoldText(false);
        }
        line.setStyle(Paint.Style.FILL);
        small.setColor(Ui.SUB);
        for (N k : nodes) {
            float nr = (k.here ? 26 : 22) * d;
            fill.setColor(k.colour);
            fill.setAlpha(40);
            c.drawCircle(k.x, k.y, nr + 8 * d, fill);                       // glow
            fill.setAlpha(255);
            fill.setColor(0xFF07100F);
            c.drawCircle(k.x, k.y, nr, fill);
            fill.setColor(k.colour);
            c.drawCircle(k.x, k.y, nr - 7 * d, fill);
            line.setColor(k.here ? Ui.CYAN : Ui.LINE);
            line.setStrokeWidth(2 * d);
            line.setStyle(Paint.Style.STROKE);
            c.drawCircle(k.x, k.y, nr, line);
            line.setStyle(Paint.Style.FILL);
            c.drawText(k.name, k.x, k.y + nr + 20 * d, text);
            c.drawText((k.here ? "you are here" : k.host) + (k.battery == null ? "" : " · " + k.battery), k.x, k.y + nr + 35 * d, small);
        }
        // Legend on its own line at the bottom, then any links we could not place.
        float ly = getHeight() - legendH + 18 * d;
        small.setTextAlign(Paint.Align.LEFT);
        line.setStrokeWidth(3 * d);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Ui.SUB);
        c.drawLine(16 * d, ly - 4 * d, 36 * d, ly - 4 * d, line);
        small.setColor(Ui.SUB);
        c.drawText("HaLow", 42 * d, ly, small);
        line.setColor(Ui.SUB);
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 4 * d}, 0));
        c.drawLine(w * 0.36f, ly - 4 * d, w * 0.36f + 20 * d, ly - 4 * d, line);
        line.setPathEffect(null);
        line.setStyle(Paint.Style.FILL);
        small.setColor(Ui.SUB);
        c.drawText("2.4 GHz", w * 0.36f + 26 * d, ly, small);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Ui.SUB);
        line.setPathEffect(new android.graphics.DashPathEffect(new float[]{2 * d, 5 * d}, 0));
        c.drawLine(w * 0.68f, ly - 4 * d, w * 0.68f + 20 * d, ly - 4 * d, line);
        line.setPathEffect(null);
        line.setStyle(Paint.Style.FILL);
        small.setColor(Ui.SUB);
        c.drawText("LoRa only", w * 0.68f + 26 * d, ly, small);
        small.setColor(Ui.SUB);
        for (int i = 0; i < loose.size(); i++) c.drawText(loose.get(i), 16 * d, ly + 18 * d * (i + 1), small);
        small.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        for (N k : nodes) {
            if (new RectF(k.x - 34 * d, k.y - 34 * d, k.x + 34 * d, k.y + 44 * d).contains(e.getX(), e.getY())) {
                if (onNode != null) onNode.tapped(k.host);
                performClick();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
