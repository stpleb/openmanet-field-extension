package com.manetnode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Points, geofences and breadcrumb trails on the team map. Items are CoT, the way ATAK draws them:
 * app users always share them on the app's own mesh group (APP_GROUP), and to ATAK (the SA group,
 * which the nodes also relay over LoRa) only while "Share to ATAK" is on under Settings → Me.
 * Points and geofences that ATAK users place show here too. One per app process, owned by CotHub.
 */
final class MapItems {
    static final String APP_GROUP = "239.2.3.2";
    static final int APP_PORT = 6970;
    private static final long REPEAT_MS = 120000, STALE_MS = 30L * 24 * 3600000, TOMB_MS = 7L * 24 * 3600000;
    static final long[] TRAIL_MS = {15 * 60000L, 3600000L, 4 * 3600000L};
    static final String[] TRAIL_NAMES = {"15 min", "1 hour", "4 hours"};

    /** Point types: name, CoT type, colour. ATAK draws a-* as 2525 symbols and b-m-p-* as map points. */
    static final String[][] TYPES = {
            {"Waypoint", "b-m-p-w"}, {"Rally point", "b-m-p-s-m"}, {"Friendly", "a-f-G"},
            {"Hostile", "a-h-G"}, {"Unknown", "a-u-G"}, {"Neutral", "a-n-G"}};
    static final int[] TYPE_RGB = {0xFF00E5FF, 0xFFFFD600, 0xFF4D8BFF, 0xFFFF3B4D, 0xFFFFE066, 0xFF00E676};

    static final class Item {
        String uid, type, name = "", by = "", byUid = "", remarks = "";
        boolean fence, polygon, alertIn, alertOut, mine;
        double lat, lon, radius;                       // circle fence: centre + radius (m)
        List<double[]> corners = new ArrayList<>();    // polygon fence
        long time, stale;

        String typeName() {
            if (fence) return polygon ? "Geofence (area)" : "Geofence (circle)";
            for (String[] t : TYPES) if (t[1].equals(type)) return t[0];
            if (type.startsWith("a-h")) return "Hostile";
            if (type.startsWith("a-f")) return "Friendly";
            if (type.startsWith("a-n")) return "Neutral";
            if (type.startsWith("a-u")) return "Unknown";
            return "Point";
        }

        int colour() {
            if (fence) return 0xFFFF7A1A;
            for (int i = 0; i < TYPES.length; i++) if (TYPES[i][1].equals(type)) return TYPE_RGB[i];
            if (type.startsWith("a-h")) return TYPE_RGB[3];
            if (type.startsWith("a-f")) return TYPE_RGB[2];
            if (type.startsWith("a-n")) return TYPE_RGB[5];
            if (type.startsWith("a-u")) return TYPE_RGB[4];
            return Ui.CYAN;
        }

        boolean inside(double la, double lo) {
            if (!polygon) {
                float[] d = new float[1];
                Location.distanceBetween(lat, lon, la, lo, d);
                return d[0] <= radius;
            }
            boolean in = false;                                    // ray casting, fine at fence sizes
            for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
                double[] a = corners.get(i), b = corners.get(j);
                if ((a[0] > la) != (b[0] > la) && lo < (b[1] - a[1]) * (la - a[0]) / (b[0] - a[0]) + a[1]) in = !in;
            }
            return in;
        }
    }

    interface Sender { void send(String group, int port, String xml); }

    private final Context ctx;
    private final Map<String, Item> items = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Long> tombs = Collections.synchronizedMap(new HashMap<>());   // deleted uid -> when
    private final Map<String, List<double[]>> trails = Collections.synchronizedMap(new HashMap<>());   // uid -> {lat, lon, ms}
    private final Map<String, Boolean> insideNow = new HashMap<>();                                  // fence|who -> inside
    private final Sender sender;
    private final java.util.function.Supplier<String> myUid, myCallsign;
    private long lastRepeat;

    MapItems(Context ctx, Sender sender, java.util.function.Supplier<String> uid, java.util.function.Supplier<String> callsign) {
        this.ctx = ctx;
        this.sender = sender;
        this.myUid = uid;
        this.myCallsign = callsign;
        load();
    }

    // ------------------------------------------------------------------ settings
    private SharedPreferences prefs() { return ctx.getSharedPreferences("mapitems", Context.MODE_PRIVATE); }

    boolean shareToAtak() { return ctx.getSharedPreferences("me", Context.MODE_PRIVATE).getBoolean("share_atak", false); }

    void setShareToAtak(boolean on) {
        ctx.getSharedPreferences("me", Context.MODE_PRIVATE).edit().putBoolean("share_atak", on).apply();
        if (on) for (Item i : mine()) sender.send(CotHub.SA_GROUP, CotHub.SA_PORT, cot(i));
    }

    boolean trailsOn() { return prefs().getBoolean("trails", true); }

    void setTrailsOn(boolean on) { prefs().edit().putBoolean("trails", on).apply(); }

    int trailLength() { return Math.min(prefs().getInt("trail_len", 1), TRAIL_MS.length - 1); }

    void setTrailLength(int i) { prefs().edit().putInt("trail_len", i).apply(); }

    boolean alertsOn() { return prefs().getBoolean("alerts", true); }

    void setAlertsOn(boolean on) { prefs().edit().putBoolean("alerts", on).apply(); }

    // ------------------------------------------------------------------ items
    List<Item> all() {
        long now = System.currentTimeMillis();
        List<Item> out = new ArrayList<>();
        synchronized (items) { for (Item i : items.values()) if (i.mine || i.stale > now) out.add(i); }
        return out;
    }

    List<Item> mine() {
        List<Item> out = new ArrayList<>();
        for (Item i : all()) if (i.mine) out.add(i);
        return out;
    }

    Item get(String uid) { return items.get(uid); }

    Item newPoint(double lat, double lon, String name, String type, String remarks) {
        Item i = blank();
        i.type = type;
        i.lat = lat;
        i.lon = lon;
        i.name = name;
        i.remarks = remarks;
        put(i);
        return i;
    }

    Item newCircle(double lat, double lon, double radius, String name, boolean in, boolean out) {
        Item i = blank();
        i.fence = true;
        i.type = "u-d-c-c";
        i.lat = lat;
        i.lon = lon;
        i.radius = radius;
        i.name = name;
        i.alertIn = in;
        i.alertOut = out;
        put(i);
        return i;
    }

    Item newPolygon(List<double[]> corners, String name, boolean in, boolean out) {
        Item i = blank();
        i.fence = true;
        i.polygon = true;
        i.type = "u-d-f";
        i.corners = new ArrayList<>(corners);
        double la = 0, lo = 0;
        for (double[] c : corners) { la += c[0]; lo += c[1]; }
        i.lat = la / corners.size();
        i.lon = lo / corners.size();
        i.name = name;
        i.alertIn = in;
        i.alertOut = out;
        put(i);
        return i;
    }

    /** After editing an item's fields: stamp it and send the new version. */
    void update(Item i) {
        i.time = System.currentTimeMillis();
        i.stale = i.time + STALE_MS;
        put(i);
    }

    void delete(Item i) {
        items.remove(i.uid);
        tombs.put(i.uid, System.currentTimeMillis());
        String xml = deleteCot(i);
        sender.send(APP_GROUP, APP_PORT, xml);
        if (shareToAtak() || !i.mine) sender.send(CotHub.SA_GROUP, CotHub.SA_PORT, xml);   // ATAK users' items: delete there too
        save();
    }

    void deleteMine() { for (Item i : mine()) delete(i); }

    private Item blank() {
        Item i = new Item();
        i.uid = UUID.randomUUID().toString();
        i.by = myCallsign.get();
        i.byUid = myUid.get();
        i.mine = true;
        i.time = System.currentTimeMillis();
        i.stale = i.time + STALE_MS;
        return i;
    }

    private void put(Item i) {
        items.put(i.uid, i);
        save();
        String xml = cot(i);
        sender.send(APP_GROUP, APP_PORT, xml);
        if (shareToAtak()) sender.send(CotHub.SA_GROUP, CotHub.SA_PORT, xml);
    }

    /** Every 2 min: my items again, so app users who join later get them (the nodes only relay changes over LoRa). */
    void repeat() {
        long now = System.currentTimeMillis();
        if (now - lastRepeat < REPEAT_MS) return;
        lastRepeat = now;
        boolean atak = shareToAtak();
        for (Item i : mine()) {
            String xml = cot(i);
            sender.send(APP_GROUP, APP_PORT, xml);
            if (atak) sender.send(CotHub.SA_GROUP, CotHub.SA_PORT, xml);
        }
    }

    // ------------------------------------------------------------------ trails
    void track(String uid, double lat, double lon) {
        if (Double.isNaN(lat) || (lat == 0 && lon == 0)) return;
        long now = System.currentTimeMillis();
        List<double[]> t = trails.get(uid);
        if (t == null) { t = Collections.synchronizedList(new ArrayList<>()); trails.put(uid, t); }
        synchronized (t) {
            if (!t.isEmpty()) {
                double[] last = t.get(t.size() - 1);
                float[] d = new float[1];
                Location.distanceBetween(last[0], last[1], lat, lon, d);
                if (d[0] < 4) return;                          // standing still (or GPS jitter)
            }
            t.add(new double[]{lat, lon, now});
            long keep = now - TRAIL_MS[TRAIL_MS.length - 1];
            while (!t.isEmpty() && t.get(0)[2] < keep) t.remove(0);
        }
    }

    /** uid -> points newer than the chosen trail length. */
    Map<String, List<double[]>> trails() {
        long from = System.currentTimeMillis() - TRAIL_MS[trailLength()];
        Map<String, List<double[]>> out = new HashMap<>();
        synchronized (trails) {
            for (Map.Entry<String, List<double[]>> e : trails.entrySet()) {
                List<double[]> pts = new ArrayList<>();
                synchronized (e.getValue()) { for (double[] p : e.getValue()) if (p[2] >= from) pts.add(p); }
                if (pts.size() > 1) out.put(e.getKey(), pts);
            }
        }
        return out;
    }

    List<double[]> myTrail() {
        List<double[]> t = trails.get("me");
        if (t == null) return new ArrayList<>();
        synchronized (t) { return new ArrayList<>(t); }
    }

    void clearTrails() { trails.clear(); }

    // ------------------------------------------------------------------ geofence alerts
    /** Who crossed which fence since the last check: "Bravo entered Gate" lines. who = uid -> {name, lat, lon}. */
    List<String> crossings(Map<String, Object[]> who) {
        List<String> out = new ArrayList<>();
        for (Item f : all()) {
            if (!f.fence) continue;
            for (Map.Entry<String, Object[]> w : who.entrySet()) {
                String key = f.uid + "|" + w.getKey();
                boolean in = f.inside((Double) w.getValue()[1], (Double) w.getValue()[2]);
                Boolean was = insideNow.put(key, in);
                if (was == null || was == in || !alertsOn()) continue;
                if (in && f.alertIn) out.add(w.getValue()[0] + " entered " + f.name);
                if (!in && f.alertOut) out.add(w.getValue()[0] + " left " + f.name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ CoT out
    String cot(Item i) {
        String now = iso(i.time), stale = iso(i.stale);
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        x.append("<event version=\"2.0\" uid=\"").append(esc(i.uid)).append("\" type=\"").append(esc(i.type))
                .append("\" time=\"").append(now).append("\" start=\"").append(now).append("\" stale=\"").append(stale)
                .append("\" how=\"h-g-i-g-o\"><point lat=\"").append(i.lat).append("\" lon=\"").append(i.lon)
                .append("\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/><detail>");
        x.append("<contact callsign=\"").append(esc(i.name)).append("\"/>");
        x.append("<link uid=\"").append(esc(i.byUid)).append("\" type=\"a-f-G-U-C\" parent_callsign=\"").append(esc(i.by))
                .append("\" relation=\"p-p\" production_time=\"").append(now).append("\"/>");
        x.append("<archive/>");
        if (i.fence) {
            int argb = 0xFFFF7A1A;
            if (i.polygon) {
                for (double[] c : i.corners) x.append("<link point=\"").append(c[0]).append(',').append(c[1]).append("\"/>");
                if (!i.corners.isEmpty()) x.append("<link point=\"").append(i.corners.get(0)[0]).append(',').append(i.corners.get(0)[1]).append("\"/>");
            } else {
                x.append("<shape><ellipse major=\"").append(i.radius).append("\" minor=\"").append(i.radius).append("\" angle=\"360\"/></shape>");
            }
            String trig = i.alertIn && i.alertOut ? "Both" : i.alertOut ? "Exit" : "Entry";
            x.append("<__geofence elevationMonitored=\"false\" minElevation=\"NaN\" maxElevation=\"NaN\" monitor=\"All\" trigger=\"")
                    .append(trig).append("\" tracking=\"").append(i.alertIn || i.alertOut).append("\"/>");
            x.append("<strokeColor value=\"").append(argb).append("\"/><strokeWeight value=\"3.0\"/>")
                    .append("<fillColor value=\"").append((argb & 0x00FFFFFF) | 0x33000000).append("\"/><labels_on value=\"true\"/>");
            x.append("<__manetnode alertIn=\"").append(i.alertIn).append("\" alertOut=\"").append(i.alertOut).append("\"/>");
        } else {
            x.append("<color argb=\"").append(i.colour()).append("\"/>");
        }
        x.append("<remarks>").append(esc(i.remarks)).append("</remarks>");
        x.append("</detail></event>");
        return x.toString();
    }

    private String deleteCot(Item i) {
        String now = iso(System.currentTimeMillis()), stale = iso(System.currentTimeMillis() + 60000);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><event version=\"2.0\" uid=\"" + esc(UUID.randomUUID().toString())
                + "\" type=\"t-x-d-d\" time=\"" + now + "\" start=\"" + now + "\" stale=\"" + stale + "\" how=\"h-g-i-g-o\">"
                + "<point lat=\"" + i.lat + "\" lon=\"" + i.lon + "\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                + "<detail><link uid=\"" + esc(i.uid) + "\" relation=\"none\" type=\"" + esc(i.type) + "\"/><__forcedelete/></detail></event>";
    }

    // ------------------------------------------------------------------ CoT in
    /** True if this CoT was a point, geofence or delete (handled here), false for anything else. */
    boolean handle(String xml) {
        int start = xml.indexOf("<event");
        if (start < 0) return false;
        try {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(new StringReader(xml.substring(start)));
            Item i = new Item();
            String how = "", linkUid = null;
            boolean archive = false, mn = false;
            double major = Double.NaN;
            String trig = null;
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e != XmlPullParser.START_TAG) continue;
                switch (x.getName()) {
                    case "event":
                        i.uid = x.getAttributeValue(null, "uid");
                        i.type = x.getAttributeValue(null, "type");
                        how = String.valueOf(x.getAttributeValue(null, "how"));
                        i.time = parseIso(x.getAttributeValue(null, "time"));
                        i.stale = parseIso(x.getAttributeValue(null, "stale"));
                        break;
                    case "point":
                        i.lat = num(x.getAttributeValue(null, "lat"));
                        i.lon = num(x.getAttributeValue(null, "lon"));
                        break;
                    case "contact":
                        String cs = x.getAttributeValue(null, "callsign");
                        if (cs != null) i.name = cs;
                        break;
                    case "link":
                        String pt = x.getAttributeValue(null, "point");
                        if (pt != null) {
                            String[] ll = pt.split(",");
                            if (ll.length >= 2) i.corners.add(new double[]{num(ll[0]), num(ll[1])});
                        } else if ("p-p".equals(x.getAttributeValue(null, "relation"))) {
                            i.byUid = String.valueOf(x.getAttributeValue(null, "uid"));
                            String pc = x.getAttributeValue(null, "parent_callsign");
                            if (pc != null) i.by = pc;
                        } else if (linkUid == null) {
                            linkUid = x.getAttributeValue(null, "uid");
                        }
                        break;
                    case "ellipse":
                        major = num(x.getAttributeValue(null, "major"));
                        break;
                    case "__geofence":
                        trig = x.getAttributeValue(null, "trigger");
                        break;
                    case "__manetnode":
                        mn = true;
                        i.alertIn = "true".equals(x.getAttributeValue(null, "alertIn"));
                        i.alertOut = "true".equals(x.getAttributeValue(null, "alertOut"));
                        break;
                    case "archive":
                        archive = true;
                        break;
                    case "remarks":
                        i.remarks = x.nextText();
                        break;
                    default:
                        break;
                }
            }
            if (i.uid == null || i.type == null) return false;
            if (i.time <= 0) i.time = System.currentTimeMillis();
            if (i.type.equals("t-x-d-d")) {
                if (linkUid == null) return false;
                Item gone = items.remove(linkUid);
                tombs.put(linkUid, System.currentTimeMillis());
                if (gone != null) save();
                return true;
            }
            boolean shape = i.type.equals("u-d-c-c") || i.type.equals("u-d-f") || i.type.equals("u-d-r");
            boolean point = i.type.startsWith("b-m-p") || (i.type.startsWith("a-") && archive && how.startsWith("h-"));
            if (!shape && !point) return false;
            if (Double.isNaN(i.lat) || Math.abs(i.lat) > 90) return true;
            Item have = items.get(i.uid);
            if (have != null && have.mine) return true;                                  // my own, echoed back
            if (have != null && have.time > i.time) return true;                         // older copy
            Long tomb = tombs.get(i.uid);
            if (tomb != null && i.time <= tomb) return true;                             // deleted; ignore repeats
            if (shape) {
                i.fence = true;
                i.polygon = !i.type.equals("u-d-c-c");
                if (i.polygon) {
                    if (i.corners.size() > 1) {                                          // closed shapes repeat the first corner
                        double[] a = i.corners.get(0), b = i.corners.get(i.corners.size() - 1);
                        if (a[0] == b[0] && a[1] == b[1]) i.corners.remove(i.corners.size() - 1);
                    }
                    if (i.corners.size() < 3) return true;
                } else {
                    if (Double.isNaN(major) || major <= 0) return true;
                    i.radius = major;
                }
                if (!mn && trig != null) {                                              // ATAK geofence: its trigger
                    i.alertIn = trig.equals("Entry") || trig.equals("Both");
                    i.alertOut = trig.equals("Exit") || trig.equals("Both");
                }
            }
            if (i.stale <= 0) i.stale = System.currentTimeMillis() + STALE_MS;
            items.put(i.uid, i);
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ storage
    private void save() {
        JSONArray a = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            for (Item i : all()) {
                JSONObject o = new JSONObject().put("u", i.uid).put("t", i.type).put("n", i.name).put("b", i.by).put("bu", i.byUid)
                        .put("r", i.remarks).put("f", i.fence).put("p", i.polygon).put("ai", i.alertIn).put("ao", i.alertOut)
                        .put("m", i.mine).put("la", i.lat).put("lo", i.lon).put("rad", i.radius).put("tm", i.time).put("st", i.stale);
                JSONArray c = new JSONArray();
                for (double[] p : i.corners) c.put(new JSONArray().put(p[0]).put(p[1]));
                o.put("c", c);
                a.put(o);
            }
            JSONObject t = new JSONObject();
            synchronized (tombs) { for (Map.Entry<String, Long> e : tombs.entrySet()) if (now - e.getValue() < TOMB_MS) t.put(e.getKey(), e.getValue()); }
            prefs().edit().putString("items", a.toString()).putString("tombs", t.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void load() {
        try {
            JSONArray a = new JSONArray(prefs().getString("items", "[]"));
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                Item i = new Item();
                i.uid = o.getString("u");
                i.type = o.getString("t");
                i.name = o.optString("n");
                i.by = o.optString("b");
                i.byUid = o.optString("bu");
                i.remarks = o.optString("r");
                i.fence = o.optBoolean("f");
                i.polygon = o.optBoolean("p");
                i.alertIn = o.optBoolean("ai");
                i.alertOut = o.optBoolean("ao");
                i.mine = o.optBoolean("m");
                i.lat = o.optDouble("la");
                i.lon = o.optDouble("lo");
                i.radius = o.optDouble("rad");
                i.time = o.optLong("tm");
                i.stale = o.optLong("st");
                JSONArray c = o.optJSONArray("c");
                for (int j = 0; c != null && j < c.length(); j++) i.corners.add(new double[]{c.getJSONArray(j).getDouble(0), c.getJSONArray(j).getDouble(1)});
                items.put(i.uid, i);
            }
            JSONObject t = new JSONObject(prefs().getString("tombs", "{}"));
            for (java.util.Iterator<String> it = t.keys(); it.hasNext(); ) { String k = it.next(); tombs.put(k, t.getLong(k)); }
        } catch (Exception ignored) { }
    }

    /** My breadcrumb trail as KML (Google Earth, ATAK). */
    String trailKml() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        List<double[]> t = myTrail();
        StringBuilder k = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>");
        k.append("<name>").append(esc(myCallsign.get())).append(" trail</name>");
        k.append("<Style id=\"t\"><LineStyle><color>ffff8b4d</color><width>4</width></LineStyle></Style>");
        k.append("<Placemark><name>").append(t.isEmpty() ? "" : f.format(new java.util.Date((long) t.get(0)[2])))
                .append("</name><styleUrl>#t</styleUrl><LineString><tessellate>1</tessellate><coordinates>");
        for (double[] p : t) k.append(p[1]).append(',').append(p[0]).append(",0 ");
        k.append("</coordinates></LineString></Placemark></Document></kml>");
        return k.toString();
    }

    // ------------------------------------------------------------------ helpers
    private static double num(String s) {
        try { return s == null ? Double.NaN : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private static long parseIso(String s) {
        if (s == null) return 0;
        for (String p : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SS'Z'", "yyyy-MM-dd'T'HH:mm:ss.S'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(s).getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
