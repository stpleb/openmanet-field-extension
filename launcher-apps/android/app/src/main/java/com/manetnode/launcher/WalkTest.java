package com.manetnode.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Walk test: while recording, a sample every few seconds of where the phone is and how the node it is on
 * reaches the rest of the mesh (best HaLow / 2.4 GHz link and its speed, else LoRa, else nothing). The
 * track is drawn on the map coloured by link quality and saved as KML (Google Earth, ATAK) and CSV.
 */
final class WalkTest {
    enum Quality {
        GOOD("Good mesh link (10 Mb/s or more)", 0xFF00E676, "ff76e600"),
        OK("Fair mesh link (2 to 10 Mb/s)", 0xFFFFD600, "ff00d6ff"),
        WEAK("Weak mesh link (under 2 Mb/s)", 0xFFFF3B4D, "ff4d3bff"),
        LORA("Mesh lost, LoRa only", 0xFF4D8BFF, "ffff8b4d"),
        NONE("No link", 0xFF8FA7B2, "ffb2a78f");

        final String label;
        final int colour;
        final String kml;   // aabbggrr

        Quality(String label, int colour, String kml) {
            this.label = label;
            this.colour = colour;
            this.kml = kml;
        }
    }

    static final class Sample {
        long time;
        double lat, lon;
        float accuracy;
        String node, link = "";
        double mbps = Double.NaN;
        int neighbours, rssi = Integer.MIN_VALUE;
        double loraSnr = Double.NaN;
        boolean has24, hasHalow, hasLora;   // each link type present at this point
        double fromStart;                   // metres from where the test started (next to the base node)
        Quality quality;
    }

    /** Furthest distance from the start at which each link type still worked. */
    static final class Ranges {
        double wifi24 = -1, halow = -1, lora = -1, total;
        long start, secs;
        int points;
    }

    static final long INTERVAL_MS = 3000;
    private static final long LORA_FRESH_S = 120;

    private final List<Sample> samples = new ArrayList<>();
    private boolean recording;
    private long started;

    boolean recording() { return recording; }

    List<Sample> samples() { return samples; }

    void start() {
        samples.clear();
        recording = true;
        started = System.currentTimeMillis();
    }

    void stop() { recording = false; }

    void clear() {
        if (!recording) samples.clear();
    }

    Sample last() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }

    /** One sample. here: phone fix (skipped if null or poor); summary/lora: the node this phone is on (may be null). */
    Sample record(Location here, String node, JSONObject summary, JSONObject lora, int rssi) {
        if (!recording || here == null || (here.hasAccuracy() && here.getAccuracy() > 50)) return null;
        Sample s = new Sample();
        s.time = System.currentTimeMillis();
        s.lat = here.getLatitude();
        s.lon = here.getLongitude();
        s.accuracy = here.hasAccuracy() ? here.getAccuracy() : -1;
        s.node = node;
        s.rssi = rssi;
        JSONObject mesh = summary == null ? null : summary.optJSONObject("mesh");
        JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
        for (int i = 0; nb != null && i < nb.length(); i++) {
            JSONObject n = nb.optJSONObject(i);
            if (n == null) continue;
            s.neighbours++;
            if ("HaLow".equals(n.optString("transport"))) s.hasHalow = true; else s.has24 = true;
            double m = n.optDouble("throughput_mbps", Double.NaN);
            if (s.link.isEmpty() || (!Double.isNaN(m) && (Double.isNaN(s.mbps) || m > s.mbps))) {
                s.mbps = m;
                s.link = "HaLow".equals(n.optString("transport")) ? "HaLow" : "2.4 GHz";
            }
        }
        JSONObject radio = lora == null ? null : lora.optJSONObject("radio");
        JSONArray heard = radio == null ? null : radio.optJSONArray("heard");
        long now = System.currentTimeMillis() / 1000, newest = 0;
        for (int i = 0; heard != null && i < heard.length(); i++) {
            JSONObject h = heard.optJSONObject(i);
            long t = h == null ? 0 : h.optLong("last_heard");
            if (t > newest && now - t <= LORA_FRESH_S) {
                newest = t;
                s.loraSnr = h.optDouble("snr", Double.NaN);
            }
        }
        s.hasLora = newest > 0;
        if (!samples.isEmpty()) {
            float[] d = new float[1];
            Location.distanceBetween(samples.get(0).lat, samples.get(0).lon, s.lat, s.lon, d);
            s.fromStart = d[0];
        }
        if (summary == null) s.quality = Quality.NONE;
        else if (s.neighbours > 0) {
            s.quality = Double.isNaN(s.mbps) ? Quality.OK : s.mbps >= 10 ? Quality.GOOD : s.mbps >= 2 ? Quality.OK : Quality.WEAK;
        } else if (newest > 0) {
            s.quality = Quality.LORA;
            s.link = "LoRa";
        } else s.quality = Quality.NONE;
        samples.add(s);
        return s;
    }

    Ranges ranges() {
        Ranges r = new Ranges();
        r.points = samples.size();
        if (samples.isEmpty()) return r;
        r.start = samples.get(0).time;
        r.secs = (last().time - r.start) / 1000;
        float[] d = new float[1];
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (s.has24) r.wifi24 = Math.max(r.wifi24, s.fromStart);
            if (s.hasHalow) r.halow = Math.max(r.halow, s.fromStart);
            if (s.hasLora) r.lora = Math.max(r.lora, s.fromStart);
            if (i > 0) {
                Location.distanceBetween(samples.get(i - 1).lat, samples.get(i - 1).lon, s.lat, s.lon, d);
                r.total += d[0];
            }
        }
        return r;
    }

    static String dist(double m) {
        if (m < 0) return "not seen";
        return m >= 1000 ? String.format(Locale.US, "%.2f km", m / 1000) : String.format(Locale.US, "%.0f m", m);
    }

    String summary() {
        if (samples.isEmpty()) return recording ? "Recording: waiting for a GPS fix…" : "No track yet";
        Sample l = last();
        long secs = (l.time - samples.get(0).time) / 1000;
        float metres = 0;
        float[] d = new float[1];
        for (int i = 1; i < samples.size(); i++) {
            Location.distanceBetween(samples.get(i - 1).lat, samples.get(i - 1).lon, samples.get(i).lat, samples.get(i).lon, d);
            metres += d[0];
        }
        return String.format(Locale.US, "%s%d points · %.0f m · %d:%02d · now: %s",
                recording ? "Recording · " : "", samples.size(), metres, secs / 60, secs % 60, describe(l));
    }

    static String describe(Sample s) {
        if (s.quality == Quality.NONE) return "no link";
        if (s.quality == Quality.LORA) return "LoRa only" + (Double.isNaN(s.loraSnr) ? "" : String.format(Locale.US, " (SNR %.1f)", s.loraSnr));
        return s.link + (Double.isNaN(s.mbps) ? "" : String.format(Locale.US, " %.1f Mb/s", s.mbps));
    }

    // ---------------------------------------------------------------- files

    /** Saves KML and CSV to Downloads/ManetNode (Android 10+) or the app's own files folder. Returns where. */
    String save(Context ctx) throws Exception {
        if (samples.isEmpty()) throw new IllegalStateException("Nothing recorded");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date(started));
        String base = "walktest-" + stamp;
        write(ctx, base + ".kml", "application/vnd.google-earth.kml+xml", kml(base).getBytes(StandardCharsets.UTF_8));
        String where = write(ctx, base + ".csv", "text/csv", csv().getBytes(StandardCharsets.UTF_8));
        return where + "/" + base + ".kml and .csv";
    }

    private static String write(Context ctx, String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ManetNode");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("Could not create " + name);
            try (OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                o.write(data);
            }
            return "Download/ManetNode";
        }
        File dir = ctx.getExternalFilesDir("walktests");
        if (dir == null) dir = new File(ctx.getFilesDir(), "walktests");
        dir.mkdirs();
        try (OutputStream o = new FileOutputStream(new File(dir, name))) {
            o.write(data);
        }
        return dir.getAbsolutePath();
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String csv() {
        StringBuilder b = new StringBuilder("time_utc,lat,lon,accuracy_m,node,link,mbps,mesh_neighbours,phone_wifi_rssi_dbm,lora_snr,quality\n");
        for (Sample s : samples) {
            b.append(iso(s.time)).append(',')
                    .append(String.format(Locale.US, "%.6f,%.6f,%.0f,", s.lat, s.lon, s.accuracy))
                    .append(s.node == null ? "" : s.node).append(',').append(s.link).append(',')
                    .append(Double.isNaN(s.mbps) ? "" : String.format(Locale.US, "%.1f", s.mbps)).append(',')
                    .append(s.neighbours).append(',')
                    .append(s.rssi == Integer.MIN_VALUE ? "" : String.valueOf(s.rssi)).append(',')
                    .append(Double.isNaN(s.loraSnr) ? "" : String.format(Locale.US, "%.1f", s.loraSnr)).append(',')
                    .append(s.quality.name().toLowerCase(Locale.US)).append('\n');
        }
        return b.toString();
    }

    private String kml(String name) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n");
        b.append("<name>").append(esc(name)).append("</name>\n");
        for (Quality q : Quality.values()) {
            b.append("<Style id=\"").append(q.name()).append("\"><LineStyle><color>").append(q.kml)
                    .append("</color><width>5</width></LineStyle><IconStyle><color>").append(q.kml)
                    .append("</color><scale>0.6</scale></IconStyle></Style>\n");
        }
        // Track: one line per run of the same quality, joined end to end.
        int i = 0;
        while (i < samples.size()) {
            Quality q = samples.get(i).quality;
            int j = i;
            while (j + 1 < samples.size() && samples.get(j + 1).quality == q) j++;
            int end = Math.min(j + 1, samples.size() - 1);
            b.append("<Placemark><name>").append(esc(q.label)).append("</name><styleUrl>#").append(q.name())
                    .append("</styleUrl><LineString><tessellate>1</tessellate><coordinates>");
            for (int k = i; k <= end; k++)
                b.append(String.format(Locale.US, "%.6f,%.6f,0 ", samples.get(k).lon, samples.get(k).lat));
            b.append("</coordinates></LineString></Placemark>\n");
            i = j + 1;
        }
        b.append("<Folder><name>Points</name>\n");
        for (Sample s : samples) {
            b.append("<Placemark><name>").append(esc(describe(s))).append("</name><TimeStamp><when>").append(iso(s.time))
                    .append("</when></TimeStamp><styleUrl>#").append(s.quality.name()).append("</styleUrl><description>")
                    .append(esc(String.format(Locale.US, "node %s · %d mesh neighbours%s",
                            s.node == null ? "-" : s.node, s.neighbours,
                            s.rssi == Integer.MIN_VALUE ? "" : " · phone Wi-Fi " + s.rssi + " dBm")))
                    .append("</description><Point><coordinates>")
                    .append(String.format(Locale.US, "%.6f,%.6f,0", s.lon, s.lat)).append("</coordinates></Point></Placemark>\n");
        }
        b.append("</Folder></Document></kml>\n");
        return b.toString();
    }
}
