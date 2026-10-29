package com.fieldext.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mesh nodes as map markers: one per node the connected node lists (/api/field/nodes), coloured
 * like its status light, with battery, time left, mesh and LoRa in the details. A node with a GPS
 * fix is placed there; the node this phone is joined to, without a fix, is placed at the phone
 * ("near you"); other nodes without a fix are not shown. Local only: never sent as CoT.
 */
final class NodeMarkers {
    static final String PREF = "fieldext_node_markers";
    private static final String GROUP = "Field Extension Nodes";
    private static final long POLL_MS = 30000;

    private final Context ctx;
    private final NodeUrl nodeUrl;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MapGroup group;

    interface NodeUrl { String get(); }

    NodeMarkers(Context ctx, NodeUrl nodeUrl) {
        this.ctx = ctx;
        this.nodeUrl = nodeUrl;
    }

    static boolean enabled(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF, true);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            ui.postDelayed(this, Math.min(POLL_MS, FieldPanel.pollSeconds(ctx) * 3000L));
        }
    };

    void start() { ui.post(tick); }

    void stop() {
        ui.removeCallbacks(tick);
        io.shutdownNow();
        ui.post(this::clear);
    }

    /** Re-read now (after the setting changes). */
    void poke() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private static final class Node {
        String uid, name, ip, details;
        final List<String> macs = new ArrayList<>();
        final List<JSONObject> neighbors = new ArrayList<>();
        int colour;
        double lat = Double.NaN, lon = Double.NaN;
        boolean self, nearYou;
    }

    private void poll() {
        if (!enabled(ctx)) { clear(); return; }
        final String base = nodeUrl.get();
        if (base == null) return;
        io.execute(() -> {
            List<Node> out = new ArrayList<>();
            try {
                FieldClient.Result r = new FieldClient(base).get("nodes");
                JSONArray list = r.ok() ? r.body.optJSONArray("nodes") : null;
                if (list == null) return;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject n = list.optJSONObject(i);
                    String fe = n == null ? null : n.optString("field_extension", null);
                    if (fe == null) continue;
                    Node node = read(fe, n.optString("ip", ""), n.optBoolean("self"));
                    if (node != null) out.add(node);
                }
            } catch (Exception e) {
                return;                                // node not answering: keep the last markers
            }
            ui.post(() -> show(out));
        });
    }

    /** One node's summary (and LoRa status, when it has a radio). null if it does not answer. */
    private static Node read(String url, String ip, boolean self) {
        JSONObject s;
        try {
            FieldClient c = new FieldClient(url);
            FieldClient.Result r = c.get("summary");
            if (!r.ok()) return null;
            s = r.body;
            Node node = new Node();
            node.ip = ip;
            node.self = self;
            node.name = FieldPanel.callsign(s, ip);
            node.uid = "fieldext-node-" + ip;
            node.colour = FieldPanel.ledColour(s.optJSONObject("led"));
            JSONObject g = s.optJSONObject("gps");
            JSONObject pos = s.optJSONObject("position");
            for (JSONObject p : new JSONObject[]{pos, g}) {
                if (p == null || !Double.isNaN(node.lat)) continue;
                double la = p.optDouble("lat", p.optDouble("latitude", Double.NaN));
                double lo = p.optDouble("lon", p.optDouble("longitude", Double.NaN));
                if (!Double.isNaN(la) && !Double.isNaN(lo) && GeoPoint.isValid(la, lo) && !(la == 0 && lo == 0)) {
                    node.lat = la;
                    node.lon = lo;
                }
            }
            JSONObject meshInfo = s.optJSONObject("mesh");
            JSONArray ifs = meshInfo == null ? null : meshInfo.optJSONArray("interfaces");
            for (int i = 0; ifs != null && i < ifs.length(); i++)
                if (ifs.optJSONObject(i) != null) node.macs.add(ifs.optJSONObject(i).optString("mac").toLowerCase(java.util.Locale.US));
            JSONArray nbs = meshInfo == null ? null : meshInfo.optJSONArray("neighbors");
            for (int i = 0; nbs != null && i < nbs.length(); i++) if (nbs.optJSONObject(i) != null) node.neighbors.add(nbs.optJSONObject(i));
            List<String> d = new ArrayList<>();
            JSONObject id = s.optJSONObject("identity");
            if (id != null && id.has("role"))
                d.add("Team: " + id.optString("color", "--") + " · " + id.optString("role") + " · " + s.optString("node", ""));
            JSONObject led = s.optJSONObject("led");
            if (led != null && led.has("pattern")) d.add("Light: " + led.optString("color", "--") + " · " + led.optString("pattern"));
            JSONObject p = s.optJSONObject("power");
            JSONObject nested = p == null ? null : p.optJSONObject("battery");
            JSONObject b = nested != null && nested.has("available") ? nested : p;
            if (b != null && b.optBoolean("available")) {
                String bt = "Battery: " + (b.has("battery_percent") && !b.isNull("battery_percent") ? b.optInt("battery_percent") + "%" : "--");
                if (b.optBoolean("external_power")) bt += " · on power";
                if (b.has("time_left_min") && !b.isNull("time_left_min")) bt += " · " + hm(b.optInt("time_left_min")) + " left";
                d.add(bt);
            }
            JSONObject m = s.optJSONObject("mesh");
            if (m != null && !m.isNull("nodes")) {
                JSONArray nb = m.optJSONArray("neighbors");
                d.add("Mesh: " + m.optInt("nodes") + " nodes · " + (nb == null ? 0 : nb.length()) + " direct");
            }
            try {
                FieldClient.Result lr = c.get("lora");
                JSONObject lo = lr.ok() ? lr.body : null;
                if (lo != null && lo.optBoolean("available")) {
                    JSONObject radio = lo.optJSONObject("radio");
                    boolean conn = radio != null && radio.optBoolean("connected");
                    d.add("LoRa: " + (conn ? "connected" : "not connected") + " · fallback " + lo.optString("mode", "--")
                            + (lo.optBoolean("sending_over_lora") ? " · sending" : ""));
                }
            } catch (Exception ignored) {
            }
            if (g != null) d.add("GPS: " + g.optString("state", "--"));
            d.add("Address: " + ip);
            node.details = android.text.TextUtils.join("\n", d);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hm(int min) {
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private MapGroup group() {
        if (group != null) return group;
        MapView mv = MapView.getMapView();
        if (mv == null) return null;
        MapGroup root = mv.getRootGroup();
        group = root.findMapGroup(GROUP);
        if (group == null) group = root.addGroup(new DefaultMapGroup(GROUP));
        return group;
    }

    private void show(List<Node> nodes) {
        MapGroup g = group();
        MapView mv = MapView.getMapView();
        if (g == null || mv == null) return;
        Set<String> keep = new HashSet<>();
        for (Node n : nodes) {
            if (Double.isNaN(n.lat)) {
                Marker me = n.self ? mv.getSelfMarker() : null;
                GeoPoint p = me == null ? null : me.getPoint();
                if (p == null || !p.isValid()) continue;   // no fix and not ours: can't place it
                n.lat = p.getLatitude();
                n.lon = p.getLongitude();
                n.nearYou = true;
            }
            keep.add(n.uid);
            MapItem existing = g.deepFindUID(n.uid);
            Marker mk = existing instanceof Marker ? (Marker) existing : null;
            if (mk == null) {
                mk = new Marker(new GeoPoint(n.lat, n.lon), n.uid);
                mk.setType("b-m-p-s-m");
                mk.setMetaBoolean("nevercot", true);       // local display only
                mk.setMetaBoolean("archive", false);
                mk.setMetaBoolean("editable", false);
                mk.setMovable(false);
                mk.setClickable(true);
                g.addItem(mk);
            } else {
                mk.setPoint(new GeoPoint(n.lat, n.lon));
            }
            int colour = n.colour == FieldPanel.ORB_OFF ? FieldPanel.MUTED : n.colour;
            mk.setTitle(n.name + (n.nearYou ? " (near you)" : ""));
            mk.setMetaInteger("color", colour);
            mk.setMetaString("callsign", n.name);
            mk.setMetaString("remarks", n.details
                    + (n.nearYou ? "\nPlaced at your phone: the node has no GPS fix." : ""));
        }
        if (linksOn(ctx)) links(nodes, g, keep);
        List<MapItem> stale = new ArrayList<>();
        for (MapItem it : g.getItems()) if (!keep.contains(it.getUID())) stale.add(it);
        for (MapItem it : stale) g.removeItem(it);
    }

    static final String LINKS_PREF = "fieldext_map_links";

    static boolean linksOn(Context c) { return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(LINKS_PREF, true); }

    /** A line per mesh link between placed nodes, coloured like the far node's light: HaLow solid, 2.4 GHz dashed (node dev40+ reports MACs). */
    private void links(List<Node> nodes, MapGroup g, Set<String> keep) {
        java.util.Map<String, Node> byMac = new java.util.HashMap<>();
        for (Node n : nodes) for (String m : n.macs) byMac.put(m, n);
        Set<String> done = new HashSet<>();
        for (Node a : nodes) {
            if (Double.isNaN(a.lat)) continue;
            for (JSONObject e : a.neighbors) {
                Node b = byMac.get(e.optString("mac").toLowerCase(java.util.Locale.US));
                if (b == null || b == a || Double.isNaN(b.lat)) continue;
                String tr = e.optString("transport", "Wi-Fi");
                String uid = "fieldext-link-" + (a.ip.compareTo(b.ip) < 0 ? a.ip + "-" + b.ip : b.ip + "-" + a.ip) + "-" + tr;
                if (!done.add(uid)) continue;
                keep.add(uid);
                MapItem old = g.deepFindUID(uid);
                com.atakmap.android.maps.Polyline line = old instanceof com.atakmap.android.maps.Polyline
                        ? (com.atakmap.android.maps.Polyline) old : new com.atakmap.android.maps.Polyline(uid);
                line.setPoints(new GeoPoint[]{new GeoPoint(a.lat, a.lon), new GeoPoint(b.lat, b.lon)});
                boolean halow = "HaLow".equals(tr);
                Node far = a.self ? b : b.self ? a : b;                 // colour = that node's status light
                line.setStrokeColor(far.colour == FieldPanel.ORB_OFF ? (halow ? 0xFF00E676 : FieldPanel.CYAN) : far.colour);
                line.setStrokeWeight(halow ? 4 : 3);
                line.setStrokeStyle(halow ? com.atakmap.android.maps.Shape.BASIC_LINE_STYLE_SOLID : com.atakmap.android.maps.Shape.BASIC_LINE_STYLE_DASHED);
                double mbps = e.optDouble("throughput_mbps", Double.NaN);
                line.setTitle((halow ? "HaLow" : "2.4 GHz") + (Double.isNaN(mbps) ? "" : String.format(java.util.Locale.US, " %.0f Mb/s", mbps)));
                line.setMetaBoolean("nevercot", true);
                line.setMetaBoolean("editable", false);
                line.setClickable(false);
                if (old == null) g.addItem(line);
            }
        }
    }

    private void clear() {
        if (group != null) group.clearItems();
    }
}
