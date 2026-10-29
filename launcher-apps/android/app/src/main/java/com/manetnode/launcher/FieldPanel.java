package com.manetnode.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The node web UI as native screens (shared with the ATAK plugin). STATUS = web UI header and tiles,
 * HEALTH = Services and Node Health, SETTINGS = links and controls, LORA = Meshtastic radio and fallback.
 * Missing readings show as unavailable, never as healthy (same rule as the web UI).
 */
public final class FieldPanel {
    // web/style.css palette
    static final int BG = 0xFF05080A, PANEL = 0xFF0A0F13, LINE = 0x4700E5FF, LINE_SOFT = 0x1A00E5FF,
            TEXT = 0xFFD9E6ED, MUTED = 0xFF5C7682, SUB = 0xFF8FA7B2, CYAN = 0xFF00E5FF,
            OK = 0xFF00E676, WARN = 0xFFFFB300, BAD = 0xFFFF3B4D,
            ORB_OFF = 0xFF1A242B;
    private static final Map<String, Integer> LED_COLOURS = new HashMap<>();
    private static final Map<String, String> LED_MEANING = new HashMap<>();
    private static final Map<String, String> SERVICE_INFO = new HashMap<>();
    static {
        LED_COLOURS.put("magenta", 0xFFB56CFF); LED_COLOURS.put("green", OK); LED_COLOURS.put("cyan", CYAN);
        LED_COLOURS.put("red", BAD); LED_COLOURS.put("amber", WARN); LED_COLOURS.put("blue", 0xFF4D8BFF);
        LED_COLOURS.put("white", 0xFFFFFFFF);
        String[] m = {"MESH_AND_INTERNET", "Mesh + internet", "MESH_ONLY", "Mesh only", "INTERNET_ONLY", "Internet only",
                "NO_LINKS", "No mesh, no internet", "BOOTING", "Starting", "INITIALIZING", "Starting", "FAULT", "Fault",
                "BATTERY_CRITICAL", "Battery critical", "BATTERY_LOW", "Battery low", "SHUTTING_DOWN", "Shutting down",
                "ADMIN", "Maintenance", "LORA_ONLY", "LoRa only"};
        for (int i = 0; i < m.length; i += 2) LED_MEANING.put(m[i], m[i + 1]);
        String[] s = {"openmanetd", "OpenMANET daemon and web UI", "gpsd", "GPS receiver service",
                "meshtastic", "LoRa / Meshtastic radio", "pisugar-server", "PiSugar battery service",
                "link_monitor", "L3 probe / link health", "qos-rate-adapt", "Adaptive link rate",
                "mcs-latch-watchdog", "Radio watchdog", "link-demote", "Mesh link management",
                "reboot-tracker", "System reboot tracking", "gateway", "Internet gateway sharing"};
        for (int i = 0; i < s.length; i += 2) SERVICE_INFO.put(s[i], s[i + 1]);
    }
    private static final int[] BRIGHTNESS = {10, 25, 50, 75, 100};
    private static final String[] LORA_MODES = {"auto", "always", "off"};
    private static final String[] PROFILES = {"open", "covert", "dark"};
    private static final String[] PROFILE_MEANING = {
            "Full interop with the public Meshtastic mesh. Public radios relay for you and see this radio's position.",
            "Public mesh relay, but position hidden. Only the team sees where this radio is.",
            "Team only, on a private frequency. Invisible to the public mesh."};
    private static final int FULL_EVERY = 4;
    private static final String[] POS_SOURCES = {"auto", "node", "phone"}, POS_NAMES = {"Auto", "Node GPS", "Phone"},
            POS_MEANING = {"Node GPS, else the connected phone's position", "The node's own GPS only",
                    "The connected phone's position, even when the node has a fix"};
    private Button[] posBtns;
    private TextView posNote;   // network/services/system every 4th summary poll

    private final Context ctx;
    private final FieldClient client;
    private final LinearLayout statusBox, healthBox, loraBox, settingsBox;
    private final Runnable editNodeAddress, markersChanged;
    /** Where this phone is (lat, lon, alt), for "set node position here"; null hides that row. */
    static java.util.function.Supplier<double[]> here;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;

    private JSONObject summary, net, sys, services, lora;
    private boolean loraFleet = true;                    // "Change every node": sent with mode changes
    private EditText loraMsg;                            // kept across refreshes so typing is not lost
    private boolean online;
    private String checked = "Connecting…";
    private int polls;
    private Boolean laidOutNarrow;

    // SETTINGS controls, built once and updated in place
    private Button ledOn, ledOff, eudAuto, eudCell, apoOn, apoOff, lpClean, lpCut, mtOn, mtOff;
    private TextView mtNote;
    private final Button[] bright = new Button[BRIGHTNESS.length];
    private TextView brightValue, eudNow, ledState;
    private Button signOut, meshKeep;
    private TextView calStatus;
    private LinearLayout meshJoinRow;

    public FieldPanel(Context ctx, View root, FieldClient client, Runnable editNodeAddress, Runnable markersChanged) {
        this.ctx = ctx;
        this.client = client;
        this.editNodeAddress = editNodeAddress;
        this.markersChanged = markersChanged;
        this.density = ctx.getResources().getDisplayMetrics().density;
        statusBox = root.findViewById(R.id.fe_status_box);
        // Re-lay out the tiles when the pane changes width (rotation, pane resize).
        statusBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            Boolean narrow = narrowPane();
            if (narrow != null && !narrow.equals(laidOutNarrow)) {
                laidOutNarrow = narrow;
                ui.post(this::renderStatus);
            }
        });
        healthBox = root.findViewById(R.id.fe_health_box);
        loraBox = root.findViewById(R.id.fe_lora_box);
        settingsBox = root.findViewById(R.id.fe_settings_box);
        buildSettings();
        render();
    }

    /** Summary every call; network, services and system on the first call and every FULL_EVERY-th. */
    public void refresh() { refresh(polls++ % FULL_EVERY == 0); }

    public void refresh(boolean full) {
        io.execute(() -> {
            JSONObject s = get("summary");
            JSONObject n = full ? get("network") : null, sv = full ? get("services") : null, sy = full ? get("system") : null;
            JSONObject lr = get("lora");
            boolean failed = s == null || (full && (n == null || sv == null || sy == null));
            ui.post(() -> {
                online = s != null;
                if (s != null) summary = s;
                if (n != null) net = n;
                if (sv != null) services = sv;
                if (sy != null) sys = sy;
                lora = lr;                                   // null = not answering; shown as unavailable
                checked = "Checked " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                        + (failed ? " · some readings failed" : "");
                render();
            });
        });
    }

    public void dispose() { io.shutdownNow(); }

    private JSONObject get(String name) {
        try {
            FieldClient.Result r = client.get(name);
            return r.ok() ? r.body : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void render() {
        renderStatus();
        renderHealth();
        renderLora();
        updateSettings();
    }

    // ---------------------------------------------------------------- STATUS: header, bar, tiles

    private void renderStatus() {
        statusBox.removeAllViews();
        JSONObject s = summary != null ? summary : new JSONObject();
        JSONObject led = s.optJSONObject("led");

        LinearLayout header = row();
        // Logo beside the node name, about as tall as the name plus subtitle.
        View logo = new View(ctx) {
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                StatusWidget.drawLogo(c, 0, 0, getWidth(), CYAN, 1f);
            }
        };
        LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(dp(40), dp(40));
        lg.rightMargin = dp(10);
        lg.gravity = Gravity.CENTER_VERTICAL;
        header.addView(logo, lg);
        LinearLayout brand = column();
        TextView name = text(callsign(s, "Node"), 22, TEXT, true, false);
        name.setMaxLines(1);                              // shrink, never wrap, beside the logo
        if (android.os.Build.VERSION.SDK_INT >= 26)
            name.setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        brand.addView(name);
        TextView sub = text("OPENMANET\nFIELD EXTENSION", 10, SUB, false, false);   // two fixed lines
        sub.setLetterSpacing(0.12f);
        sub.setMaxLines(2);
        brand.addView(sub);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = column();
        right.setGravity(Gravity.END);
        String up = uptime(sys == null ? null : sys.optString("uptime", null));
        right.addView(text("Uptime: " + (up == null ? "--" : up), 11, SUB, false, false));
        // "System Online": the dot takes the node's LED colour; the words stay green/red like the web UI.
        LinearLayout health = row();
        health.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        View dot = new View(ctx);
        dot.setBackground(circle(ledColour(led)));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dp.rightMargin = dp(6);
        health.addView(dot, dp);
        health.addView(text(online ? "System Online" : (summary == null ? "Connecting…" : "Not answering"),
                12, online ? OK : BAD, true, false));
        health.setPadding(0, dp(3), 0, dp(3));
        right.addView(health);
        if (online && led != null && has(led, "pattern")) {           // same line as the Settings tab
            TextView ls = text(ledMeaning(led), 11, ledColour(led), true, false);   // the dot above already shows the colour
            ls.setGravity(Gravity.END);
            right.addView(ls);
        }
        JSONObject mesh = s.optJSONObject("mesh");
        boolean meshKnown = mesh != null && !mesh.isNull("nodes");
        int nodes = meshKnown ? mesh.optInt("nodes") : 0;
        right.addView(text(meshKnown ? nodes + (nodes == 1 ? " node" : " nodes") + " on mesh" : "Mesh: unavailable",
                11, SUB, false, true));
        header.addView(right);
        statusBox.addView(header);

        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(8));
        bar.addView(text(checked, 11, SUB, false, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = button("Refresh", false);
        refresh.setOnClickListener(v -> refresh(true));
        bar.addView(refresh);
        statusBox.addView(bar);

        List<View> tiles = new ArrayList<>();
        JSONObject p = battery(s);
        if (p != null && p.optBoolean("available")) {
            boolean hasPct = has(p, "battery_percent"), ext = p.optBoolean("external_power");
            int pct = p.optInt("battery_percent");
            int tone = ext ? OK : !hasPct ? MUTED : pct < 20 ? BAD : pct < 50 ? WARN : OK;
            tiles.add(tile("B", "Battery", hasPct ? pct + "%" : null, tone,
                    join(has(p, "battery_voltage") ? fmt("%.2f V", p.optDouble("battery_voltage")) : null,
                            ext ? (p.optBoolean("charging") ? "Charging" : "External power") : p.optString("state", null)),
                    has(p, "time_left_min") ? "About " + hm(p.optInt("time_left_min")) + " left"
                            : has(p, "time_to_full_min") ? "Full in about " + hm(p.optInt("time_to_full_min")) : null,
                    "Auto power-on: " + (!has(p, "auto_power_on") ? "Unknown" : p.optBoolean("auto_power_on") ? "On" : "Off")));
        } else tiles.add(tile("B", "Battery", "Unavailable", MUTED, "No battery monitor detected"));

        JSONObject g = s.optJSONObject("gps");
        boolean gAvail = g != null && g.optBoolean("available");
        boolean fix = g != null && "FIX".equals(g.optString("state"));
        tiles.add(tile("G", "Physical GPS", gAvail ? (fix ? "Fix" : g.optString("state", "Unknown")) : "Not detected",
                fix ? OK : gAvail ? WARN : MUTED, g != null && has(g, "message") ? g.optString("message") : gAvail ? null : "No GPS receiver"));

        JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
        int nbn = nb == null ? 0 : nb.length();
        List<String> nsub = new ArrayList<>();
        if (meshKnown) {
            nsub.add(nbn > 0 ? nodes + " nodes on the mesh" : "No direct neighbors");
            for (int i = 0; i < Math.min(3, nbn); i++) {
                JSONObject x = nb.optJSONObject(i);
                if (x != null) nsub.add(join(x.optString("mac", null), x.optString("transport", null),
                        has(x, "throughput_mbps") ? x.optDouble("throughput_mbps") + " Mb/s" : null));
            }
        } else nsub.add("Mesh routing not answering");
        tiles.add(tile("M", "Mesh Neighbors", meshKnown ? String.valueOf(nbn) : "Unavailable",
                !meshKnown ? MUTED : nbn > 0 ? OK : WARN, nsub.toArray(new String[0])));

        JSONObject in = s.optJSONObject("internet");
        boolean inet = in != null && in.optBoolean("available");
        tiles.add(tile("I", "Internet", inet ? "Online" : "Offline", inet ? CYAN : WARN,
                inet ? "Default route present" : "No upstream route"));

        String[] mip = meshIp();
        tiles.add(tile("IP", "Mesh IP", mip == null ? null : mip[1].split("/")[0], mip == null ? MUTED : TEXT,
                mip == null ? "Waiting for network reading" : join(mip[1].contains("/") ? "/" + mip[1].split("/")[1] : null, mip[0]),
                "batman-adv"));

        JSONObject h = s.optJSONObject("halow");
        boolean hAvail = h != null && h.optBoolean("available");
        tiles.add(tile("RF", "HaLow Radio",
                hAvail ? (has(h, "frequency_mhz") ? fmt("%.1f MHz", h.optDouble("frequency_mhz")) : h.optString("state")) : "Not detected",
                hAvail && "ONLINE".equals(h.optString("state")) ? OK : hAvail ? WARN : MUTED,
                hAvail ? "Bandwidth: " + (has(h, "bandwidth_mhz") ? h.optInt("bandwidth_mhz") + " MHz" : "--")
                        + (has(h, "primary_bandwidth_mhz") ? " (" + h.optInt("primary_bandwidth_mhz") + " MHz primary)" : "") : null,
                hAvail ? "HaLow: " + h.optString("state", "--") + (has(h, "interface") ? " on " + h.optString("interface") : "") : null));

        JSONObject parsed = net == null ? null : net.optJSONObject("parsed");
        JSONObject ap = parsed == null ? null : parsed.optJSONObject("access_point");
        tiles.add(tile("AP", "EUD Wi-Fi", ap != null ? ap.optString("ssid", null) : net != null ? "No access point" : null,
                ap != null ? CYAN : MUTED,
                ap != null ? join(has(ap, "channel") ? "Channel " + ap.optString("channel") : null,
                        has(ap, "width_mhz") ? ap.optString("width_mhz") + " MHz" : null) : "Phones and ATAK connect here",
                ap != null ? ap.optString("name", null) : null));

        JSONObject dr = parsed == null ? null : parsed.optJSONObject("default_route");
        String dev = dr == null ? null : dr.optString("interface", null);
        tiles.add(dr != null
                ? tile("BH", "Backhaul", backhaulType(dev), ledColour(s.optJSONObject("led")) == ORB_OFF ? CYAN : ledColour(s.optJSONObject("led")),   // the status light's colour
                     "Interface: " + dev,
                    "IP: " + orDash(ifaceAddr(dev)), "Gateway: " + dr.optString("gateway", "--"))
                : tile("BH", "Backhaul", net != null ? "None" : null, MUTED, "No default route (mesh only)"));

        // Two tiles per row when the pane is wide (portrait); one per row in the narrow
        // landscape side pane, so readings never wrap mid-word.
        boolean twoUp = narrowPane() != Boolean.TRUE;
        for (int i = 0; i < tiles.size(); i += twoUp ? 2 : 1) {
            LinearLayout.LayoutParams rowp = new LinearLayout.LayoutParams(-1, -2);
            rowp.bottomMargin = dp(8);
            if (!twoUp) {
                statusBox.addView(tiles.get(i), rowp);
                continue;
            }
            LinearLayout r = row();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.rightMargin = dp(4);
            r.addView(tiles.get(i), lp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1);
            rp.leftMargin = dp(4);
            if (i + 1 < tiles.size()) r.addView(tiles.get(i + 1), rp);
            else r.addView(new View(ctx), rp);
            statusBox.addView(r, rowp);
        }
    }

    /** null until laid out; true when the pane is too narrow for two tiles side by side. */
    private Boolean narrowPane() {
        int w = statusBox.getWidth();
        return w == 0 ? null : w / density < 360;
    }

    /** One web UI status card: badge, label, big mono value, small sub-lines. */
    private View tile(String badge, String label, String value, int tone, String... subs) {
        LinearLayout c = row();
        c.setBackground(box(PANEL, LINE));
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView b = text(badge, 11, tone == MUTED ? SUB : tone, true, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(tone == MUTED ? 0 : (tone & 0x00FFFFFF) | 0x12000000, tone == MUTED ? MUTED : tone));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(28), dp(28));
        bp.rightMargin = dp(8);
        c.addView(b, bp);
        LinearLayout t = column();
        t.addView(text(label, 11, TEXT, true, false));
        // One line that shrinks to fit (16sp down to 9sp), so "10.41.0.1" never breaks mid-word.
        TextView v = text(value == null ? "--" : value, 16, tone == MUTED ? SUB : tone, true, true);
        v.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            v.setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        else v.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(v);
        for (String s : subs)
            if (s != null && !s.isEmpty()) {
                TextView sv = text(s, 10, SUB, false, false);
                sv.setPadding(0, dp(2), 0, 0);
                t.addView(sv);
            }
        c.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return c;
    }

    // ---------------------------------------------------------------- HEALTH: services, node health

    private void renderHealth() {
        healthBox.removeAllViews();
        LinearLayout svc = table("Services");
        if (services == null) trow(svc, "Services", "Unavailable", MUTED, "Service list unavailable");
        else {
            int shown = 0;
            for (Iterator<String> it = services.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = services.optString(k);
                if ("UNAVAILABLE".equals(v)) continue;           // not installed on this node: not listed
                shown++;
                trow(svc, k, "RUNNING".equals(v) ? "Running" : "UNKNOWN".equals(v) ? "Not running"
                                : v.isEmpty() ? "Unknown" : v.charAt(0) + v.substring(1).toLowerCase(Locale.US),
                        "RUNNING".equals(v) ? OK : "UNKNOWN".equals(v) ? WARN : BAD, SERVICE_INFO.get(k));
            }
            if (shown == 0) trow(svc, "None", "--", 0, "None of the known services are installed");
        }

        LinearLayout hl = table("Node Health");
        String tmc = sys == null ? null : sys.optString("temperature_millicelsius", null);
        double t = parse(tmc) / 1000.0;
        boolean tOk = !Double.isNaN(t);
        trow(hl, "CPU Temperature", tOk ? fmt("%.1f °C", t) : null, !tOk ? MUTED : t >= 80 ? BAD : t >= 70 ? WARN : OK,
                "Processor thermal state");
        String load = sys == null || !has(sys, "load") ? null : sys.optString("load").split(" ")[0];
        trow(hl, "CPU Load", load, load != null ? OK : MUTED, "1 minute load average");
        int[] mem = memory(sys == null ? null : sys.optString("memory", null));
        trow(hl, "Memory", mem == null ? null : mem[0] + "%", mem == null ? MUTED : mem[0] >= 90 ? BAD : mem[0] >= 75 ? WARN : OK,
                mem == null ? "RAM currently in use" : mem[1] + " of " + mem[2] + " MB in use");
        JSONObject h = summary == null ? null : summary.optJSONObject("halow");
        boolean hAvail = h != null && h.optBoolean("available");
        trow(hl, "HaLow Interface", hAvail ? h.optString("state", "Unknown") : "Not detected",
                hAvail && "ONLINE".equals(h.optString("state")) ? OK : hAvail ? WARN : MUTED,
                hAvail ? join(h.optString("interface", null), has(h, "frequency_mhz") ? fmt("%.1f MHz", h.optDouble("frequency_mhz")) : null)
                        : "No Morse Micro radio found");
        trow(hl, "Kernel", sys == null ? null : sys.optString("kernel", null), 0, sys == null ? null : sys.optString("architecture", null));
        // Node Health first, then Services, then how recent restarts ended (dev21+ recovery service).
        healthBox.addView(hl, spaced());
        healthBox.addView(svc, spaced());
        JSONObject rec = summary == null ? null : summary.optJSONObject("recovery");
        JSONArray reboots = rec == null ? null : rec.optJSONArray("reboots");
        if (reboots != null && reboots.length() > 0) {
            LinearLayout rl = table("Restart Log");
            for (int i = reboots.length() - 1; i >= 0 && i >= reboots.length() - 3; i--) {
                // "2026-10-11 21:57 clean (button long press) up 145s"
                String line = reboots.optString(i);
                String[] f = line.split(" ", 3);
                if (f.length < 3 || f[0].length() < 10) { trow(rl, "Restart", line, MUTED, null); continue; }
                String when = f[0].substring(5) + " " + f[1];
                String rest = f[2];
                int up = rest.lastIndexOf(" up ");
                String how = up > 0 ? rest.substring(0, up) : rest;
                String upTime = up > 0 ? uptime(rest.substring(up + 4).replace("s", "")) : null;
                String upFor = upTime == null ? null : "Was up " + upTime;
                boolean clean = how.startsWith("clean");
                String shown = clean && how.indexOf('(') >= 0 && how.lastIndexOf(')') > how.indexOf('(')
                        ? how.substring(how.indexOf('(') + 1, how.lastIndexOf(')')) : how;
                if (shown.isEmpty()) shown = how;
                trow(rl, when, Character.toUpperCase(shown.charAt(0)) + shown.substring(1),
                        clean ? OK : how.startsWith("unexpected") ? BAD : WARN, upFor);
            }
            healthBox.addView(rl, spaced());
        }
    }

    // ---------------------------------------------------------------- LORA: radio, fallback, mode, heard

    private void renderLora() {
        if (loraMsg != null && loraMsg.hasFocus()) return;   // don't rebuild under the keyboard
        loraBox.removeAllViews();
        JSONObject d = lora;
        if (d == null || !d.optBoolean("available")) {
            LinearLayout t = table("LoRa");
            trow(t, "LoRa", "Unavailable", MUTED, d == null ? "LoRa status not answering"
                    : !d.optString("managed_by", "").isEmpty() ? "LoRa is managed by other software on this node"
                    : "LoRa control needs a newer Field Extension node package");
            loraBox.addView(t, spaced());
            loraBox.addView(webLink(), spaced());
            return;
        }
        JSONObject r = d.optJSONObject("radio");
        if (r == null) r = new JSONObject();
        boolean conn = r.optBoolean("connected");
        JSONArray warns = d.optJSONArray("warnings");
        boolean warned = warns != null && warns.length() > 0;

        // 1. Radio
        LinearLayout radio = table("LoRa Radio");
        JSONObject h = d.optJSONObject("health");
        Button check = button("Check", false);
        check.setOnClickListener(v -> loraWrite("ping", new JSONObject(), "Radio answered"));
        TextView rnote = text(conn ? join(r.optString("name", null), r.optString("node_id", null),
                h != null && has(h, "ping_ms") ? "answered in " + h.optLong("ping_ms") + " ms" : null)
                : r.optString("error", "No radio answering"), 10, SUB, false, false);
        LinearLayout rleft = column();
        LinearLayout st = row();
        st.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(ctx);
        dot.setBackground(circle(!conn ? MUTED : warned ? WARN : OK));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dp.rightMargin = dp(6);
        st.addView(dot, dp);
        st.addView(text(conn ? "Connected" : "Not connected", 13, !conn ? SUB : warned ? WARN : OK, true, true));
        rleft.addView(st);
        rleft.addView(rnote);
        controlRow(radio, "Radio", rleft, check);
        if (warned)
            for (int i = 0; i < warns.length(); i++) {
                TextView w = text("⚠ " + warns.optString(i), 11, WARN, false, false);
                w.setPadding(dp(10), 0, dp(10), dp(6));
                radio.addView(w);
            }
        loraBox.addView(radio, spaced());

        // 2. Fallback
        LinearLayout fb = table("Fallback");
        String mode = d.optString("mode", "");
        Button[] mb = new Button[LORA_MODES.length];
        for (int i = 0; i < LORA_MODES.length; i++) {
            final String m = LORA_MODES[i];
            mb[i] = button(m, false);
            pressed(mb[i], m.equals(mode));
            mb[i].setOnClickListener(v -> loraWrite("mode", obj("mode", m), "Fallback set to " + m));
        }
        controlRow(fb, "Fallback", "off".equals(mode) ? "Off" : d.optBoolean("sending_over_lora")
                ? "Sending over LoRa now" : "Mesh is up: LoRa standing by", mb);
        loraBox.addView(fb, spaced());

        // 3 + 4. Change every node, LoRa mode
        LinearLayout lm = table("Team & Mode");
        Button fOn = button("On", false), fOff = button("Off", false);
        pressed(fOn, loraFleet);
        pressed(fOff, !loraFleet);
        fOn.setOnClickListener(v -> { loraFleet = true; renderLora(); });
        fOff.setOnClickListener(v -> { loraFleet = false; renderLora(); });
        controlRow(lm, "Change every node", loraFleet ? "Team channel and mode changes go to every node over LoRa"
                : "Only this node changes", fOn, fOff);
        String team = teamName(r);
        Button tc = button(team == null ? "Set" : "Change", false);
        tc.setEnabled(conn);
        pressed(tc, false);
        tc.setOnClickListener(v -> editTeamChannel(team));
        Button qr = button("QR", false);
        qr.setEnabled(conn && team != null);
        pressed(qr, false);
        qr.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setMessage("Show the team channel QR code?\n\nIt contains the team key. Only show it to people on your team.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Show", (dlg, w) -> showShareQr()).show());
        controlRow(lm, "Team channel", team == null ? "None: radios use the public channel only" : team + " · private", tc, qr);
        String prof = r.optString("profile", "");
        boolean publicOnly = "public".equals(prof);
        boolean known = java.util.Arrays.asList(PROFILES).contains(prof);
        String freq = has(r, "frequency_mhz") ? fmt("%.3f MHz", r.optDouble("frequency_mhz")) : null;
        controlRow(lm, "Mode", publicOnly ? "Set a team channel first"
                : known ? join(nice(prof), freq) : join("Custom: the radio's settings match none of these. Tap one to apply it", freq));
        for (int i = 0; i < PROFILES.length; i++) {
            final int k = i;
            View opt = modeOption(nice(PROFILES[i]), PROFILE_MEANING[i], PROFILES[i].equals(prof), conn && !publicOnly);
            lm.addView(opt);
            opt.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                    .setTitle(nice(PROFILES[k]) + (loraFleet ? " on every node?" : " on this node?"))
                    .setMessage(PROFILE_MEANING[k])
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Apply", (dlg, w) -> {
                        JSONObject body = obj("profile", PROFILES[k]);
                        putQuiet(body, "fleet", loraFleet);
                        loraWrite("profile", body, "LoRa mode set to " + PROFILES[k]);
                    }).show());
            opt.setClickable(conn && !publicOnly);         // setting a listener makes it clickable again
        }
        loraBox.addView(lm, spaced());

        // 5. Send
        LinearLayout sd = table("Send");
        LinearLayout srow = row();
        srow.setGravity(Gravity.CENTER_VERTICAL);
        srow.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (loraMsg == null) {
            loraMsg = new EditText(ctx);
            loraMsg.setHint("Message to the LoRa channel");
            loraMsg.setSingleLine(true);
            loraMsg.setTextSize(12);
            loraMsg.setTextColor(TEXT);
            loraMsg.setHintTextColor(MUTED);
            loraMsg.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        }
        EditText msg = loraMsg;
        if (msg.getParent() != null) ((android.view.ViewGroup) msg.getParent()).removeView(msg);
        srow.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = button("Send", false);
        send.setEnabled(conn);
        pressed(send, false);
        send.setOnClickListener(v -> {
            String t = msg.getText().toString().trim();
            if (t.isEmpty()) return;
            msg.setText("");
            msg.clearFocus();
            loraWrite("send", obj("text", t), "Sent");
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(34));
        sp.leftMargin = dp(6);
        srow.addView(send, sp);
        sd.addView(srow);
        loraBox.addView(sd, spaced());

        // 6. Radios heard
        LinearLayout heard = table("Radios Heard");
        JSONArray hl = r.optJSONArray("heard");
        JSONObject traces = d.optJSONObject("traces");
        int hn = hl == null ? 0 : Math.min(8, hl.length());
        if (hn == 0) trow(heard, "None", "--", MUTED, "No other LoRa radios heard yet");
        for (int i = 0; i < hn; i++) {
            JSONObject n = hl.optJSONObject(i);
            if (n == null) continue;
            final String id = n.optString("id", "");
            String nm = n.optString("long_name", "");
            if (nm.isEmpty()) nm = n.optString("short_name", "");
            if (nm.isEmpty()) nm = id;
            String hops = !has(n, "hops_away") ? null : n.optInt("hops_away") == 0 ? "direct" : n.optInt("hops_away") + "h";
            JSONObject tr = traces == null ? null : traces.optJSONObject(id);
            String ping = tr == null ? null : "running".equals(tr.optString("state")) ? "ping…"
                    : "done".equals(tr.optString("state")) ? fmt("ping %.1f s", tr.optLong("ms") / 1000.0)
                        + (has(tr, "hops_out") ? ", " + tr.optInt("hops_out") + (tr.optInt("hops_out") == 1 ? " hop" : " hops") : "")
                    : "ping failed";
            Button pg = button("Ping", false);
            pg.setEnabled(conn && !id.isEmpty());
            pressed(pg, false);
            pg.setOnClickListener(v -> loraWrite("trace", obj("node", id), "Ping sent"));
            LinearLayout left = column();
            left.addView(text(nm, 12, TEXT, false, false));
            left.addView(text(join(has(n, "snr") ? fmt("SNR %.1f", n.optDouble("snr")) : null, hops,
                    ago(n.optLong("last_heard")), ping), 10, SUB, false, true));
            LinearLayout hr = row();
            hr.setGravity(Gravity.CENTER_VERTICAL);
            hr.setPadding(dp(10), dp(6), dp(10), dp(6));
            hr.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            hr.addView(pg, new LinearLayout.LayoutParams(-2, dp(34)));
            divider(heard);
            heard.addView(hr);
        }
        loraBox.addView(heard, spaced());

        // Recent LoRa traffic; chats we sent get a delivered tick once the node reports acks.
        JSONArray rec = d.optJSONArray("recent");
        if (rec != null && rec.length() > 0) {
            LinearLayout act = table("Recent");
            for (int i = rec.length() - 1; i >= Math.max(0, rec.length() - 5); i--) {
                JSONObject m = rec.optJSONObject(i);
                if (m == null) continue;
                boolean sent = "sent".equals(m.optString("direction"));
                String ackKey = m.has("delivered") ? "delivered" : "acked";
                String tick = !sent || !m.has(ackKey) ? "" : m.isNull(ackKey) ? "  …" : m.optBoolean(ackKey) ? "  ✓ delivered" : "  ✗ not delivered";
                int tone = tick.contains("✓") ? OK : tick.contains("✗") ? WARN : 0;
                trow(act, (sent ? "Sent" : "Heard") + " · " + m.optString("callsign", "--"),
                        tick.isEmpty() ? m.optString("kind", "") : tick.trim(), tone,
                        join(m.optString("text", null), ago(m.optLong("time"))));
            }
            loraBox.addView(act, spaced());
        }

        // Channel airtime, when the node reports it (Meshtastic channel utilisation %).
        JSONObject air = r.optJSONObject("airtime");
        if (air == null) air = d.optJSONObject("airtime");
        double util = air != null ? air.optDouble("channel_util", Double.NaN) : r.optDouble("channel_util", Double.NaN);
        if (!Double.isNaN(util)) {
            LinearLayout at = table("Airtime");
            trow(at, "LoRa channel busy", fmt("%.0f%%", util), util > 50 ? BAD : util > 25 ? WARN : OK,
                    util >= 25 ? "Crowded: slower positions or fewer radios help" : "Room to spare");
            loraBox.addView(at, spaced());
        }

        // 7. Everything else lives on the web page.
        loraBox.addView(webLink(), spaced());
    }

    /** Name of the radio's private team channel (primary first), or null. */
    static String teamName(JSONObject r) {
        JSONArray chs = r.optJSONArray("channels");
        if (chs != null)
            for (int i = 0; i < chs.length(); i++) {
                JSONObject c = chs.optJSONObject(i);
                if (c != null && !"DISABLED".equals(c.optString("role")) && "private".equals(c.optString("security")))
                    return c.optString("name", "");
            }
        JSONObject ch = r.optJSONObject("channel");
        return ch != null && "private".equals(ch.optString("security")) ? ch.optString("name", "") : null;
    }

    /** Team channel name + passphrase; every node that should talk must use the same pair. */
    private void editTeamChannel(String current) {
        LinearLayout form = column();
        form.setPadding(dp(16), dp(8), dp(16), 0);
        EditText name = new EditText(ctx);
        name.setHint("Team name (up to 11 characters)");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(11)});
        if (current != null) name.setText(current);
        EditText words = new EditText(ctx);
        words.setHint("Team passphrase (8 or more characters)");
        words.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(name);
        form.addView(words);
        new AlertDialog.Builder(ctx).setTitle(current == null ? "Set team channel" : "Change team channel")
                .setMessage((loraFleet ? "Sent to every node over LoRa. " : "This node only. ")
                        + "Radios hear each other only with the same team name and passphrase. "
                        + "In Dark mode the name also sets the frequency.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim(), p = words.getText().toString();
                    if (n.isEmpty() || p.length() < 8) {
                        Toast.makeText(ctx, "A team name and a passphrase of at least 8 characters are needed",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    JSONObject body = obj("security", "private");
                    putQuiet(body, "name", n);
                    putQuiet(body, "passphrase", p);
                    putQuiet(body, "fleet", loraFleet);
                    loraWrite("channel", body, "Team channel saved");
                })
                .show();
    }

    /** Meshtastic channel-share code from the node (lora/share), shown as a QR to scan. */
    private void showShareQr() {
        if (!client.signedIn()) { signIn(this::showShareQr); return; }
        io.execute(() -> {
            String url = null, msg = null;
            try {
                FieldClient.Result r = client.loraWrite("share", new JSONObject());
                if (r.code == 401) { ui.post(() -> signIn(this::showShareQr)); return; }
                if (r.ok()) url = r.body.optString("url", null);
                else msg = r.error();
            } catch (Exception e) {
                msg = "Node not reachable";
            }
            final String u = url, m = msg;
            ui.post(() -> {
                if (u == null || u.isEmpty()) {
                    Toast.makeText(ctx, m != null ? m : "No share code", Toast.LENGTH_SHORT).show();
                    return;
                }
                android.widget.ImageView img = new android.widget.ImageView(ctx);
                try {
                    img.setImageBitmap(qrBitmap(u, dp(260)));
                } catch (Exception e) {
                    Toast.makeText(ctx, "Could not draw the QR code", Toast.LENGTH_SHORT).show();
                    return;
                }
                img.setPadding(dp(8), dp(8), dp(8), dp(8));
                new AlertDialog.Builder(ctx).setTitle("Team channel")
                        .setMessage("Scan with the Meshtastic app (Channels, then the QR button) or another node's setup. "
                                + "It also sets the scanner's LoRa region.")
                        .setView(img)
                        .setPositiveButton("Done", null).show();
            });
        });
    }

    static android.graphics.Bitmap qrBitmap(String text, int size) throws Exception {
        Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
        hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
    }

    /** Speed, team passphrase + QR and guest radios stay on the node web page. */
    private View webLink() {
        Button b = button("More LoRa settings", false);
        b.setTextColor(CYAN);
        b.setOnClickListener(v -> open(client.webUiUrl()));
        return b;
    }

    private void loraWrite(String path, JSONObject body, String done) {
        write(() -> client.loraWrite(path, body), done);
    }

    private static JSONObject obj(String k, Object v) {
        JSONObject o = new JSONObject();
        putQuiet(o, k, v);
        return o;
    }

    private static void putQuiet(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) { }
    }

    private static String hm(int min) {
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    /** open -> Open, LONG_FAST -> Long fast */
    private static String nice(String v) {
        String s = v.toLowerCase(Locale.US).replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String ago(long t) {
        if (t <= 0) return null;
        long s = Math.max(0, System.currentTimeMillis() / 1000 - t);
        return s < 60 ? s + " s ago" : s < 3600 ? s / 60 + " min ago" : s < 172800 ? s / 3600 + " h ago" : s / 86400 + " days ago";
    }

    // ---------------------------------------------------------------- SETTINGS

    private void buildSettings() {
        settingsBox.removeAllViews();
        // "WEBUI" with a bracket that bends down into an arrow at each button.
        settingsBox.addView(new WebUiBracket(ctx, density), new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout links = row();
        Button fe = button("FieldExtension", false), om = button("OpenManet", false);
        for (Button b : new Button[]{fe, om}) {   // names as written, not upper-cased like the other buttons
            b.setAllCaps(false);
            b.setLetterSpacing(0.02f);
        }
        fe.setText("FieldExtension");
        om.setText("OpenManet");
        fe.setTextColor(CYAN);
        om.setTextColor(CYAN);
        fe.setOnClickListener(v -> open(client.webUiUrl()));
        om.setOnClickListener(v -> open(client.openManetUrl()));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
        l.rightMargin = dp(4);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1);
        r.leftMargin = dp(4);
        links.addView(fe, l);                         // FieldExtension first, as on the node's Nodes page
        links.addView(om, r);
        settingsBox.addView(links, spaced());

        LinearLayout led = table("Status LED");
        ledState = text("--", 12, TEXT, true, false);
        ledState.setPadding(dp(10), dp(8), dp(10), 0);
        led.addView(ledState);
        ledOn = button("On", false);
        ledOff = button("Off", false);
        ledOn.setOnClickListener(v -> write(() -> client.setLed(true, null), "Status light on"));
        ledOff.setOnClickListener(v -> write(() -> client.setLed(false, null), "Status light off"));
        controlRow(led, "LED control", null, ledOn, ledOff);
        brightValue = text("--", 12, CYAN, true, true);
        LinearLayout seg = row();
        for (int i = 0; i < BRIGHTNESS.length; i++) {
            final int v = BRIGHTNESS[i];
            bright[i] = button(v + "%", false);
            bright[i].setPadding(0, 0, 0, 0);
            bright[i].setOnClickListener(x -> write(() -> client.setLed(null, v), "Brightness " + v + "%"));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (i > 0) bp.leftMargin = dp(4);
            seg.addView(bright[i], bp);
        }
        controlRow(led, "Brightness", brightValue);
        seg.setPadding(dp(10), 0, dp(10), dp(10));
        led.addView(seg);
        settingsBox.addView(led, spaced());

        LinearLayout eud = table("Phone Internet");
        eudAuto = button("Auto", false);
        eudCell = button("Cell", false);
        eudAuto.setOnClickListener(v -> write(() -> client.setPhoneInternet("auto"), "Auto: phones switch at their next Wi-Fi reconnect"));
        eudCell.setOnClickListener(v -> write(() -> client.setPhoneInternet("cellular"), "Cellular: phones switch at their next Wi-Fi reconnect"));
        controlRow(eud, "Internet source", "Auto: node internet when it has one, else phone cellular.", eudAuto, eudCell);
        eudNow = text("--", 12, TEXT, false, true);
        controlRow(eud, "Phones using", eudNow);
        settingsBox.addView(eud, spaced());

        LinearLayout maint = table("Maintenance");
        mtOn = button("On 30 min", false);
        mtOff = button("Off", false);
        mtOn.setOnClickListener(v -> write(() -> client.setMaintenance(true, 30), "Maintenance mode on: the light is white for 30 min"));
        mtOff.setOnClickListener(v -> write(() -> client.setMaintenance(false, 0), "Maintenance mode off"));
        mtNote = text("White light: tells the team this node is being worked on. Turns itself off after 30 min.", 10, SUB, false, false);
        controlRow(maint, "Maintenance mode", mtNote, mtOn, mtOff);
        apoOn = button("On", false);
        apoOff = button("Off", false);
        apoOn.setOnClickListener(v -> write(() -> client.setAutoPowerOn(true), null));
        apoOff.setOnClickListener(v -> write(() -> client.setAutoPowerOn(false), null));
        controlRow(maint, "Auto power-on", "Start the node when external power returns", apoOn, apoOff);
        lpClean = button("Clean", false);
        lpCut = button("Cut", false);
        lpClean.setOnClickListener(v -> write(() -> client.setLongPressShutdown(true), null));
        lpCut.setOnClickListener(v -> write(() -> client.setLongPressShutdown(false), null));
        controlRow(maint, "Button long press", "Clean: blinks red and shuts down safely. Cut: power off at once", lpClean, lpCut);
        Button reboot = button("Reboot", true), shut = button("Shutdown", true);
        reboot.setOnClickListener(v -> confirm("Reboot this node?\n\nThe mesh link and Wi-Fi drop for about 1 minute.", "reboot"));
        shut.setOnClickListener(v -> confirm("Shut down this node?\n\nIt stays off until the PiSugar power button is pressed.", "shutdown"));
        Button calGo = button("Calibrate", false), calStop = button("Cancel", false), calReset = button("Reset", false);
        calGo.setOnClickListener(v -> new AlertDialog.Builder(ctx).setTitle("Start battery calibration?")
                .setMessage("The battery must be full. Leave it on the charger and press Start: the run begins when you unplug. "
                        + "Then leave the node on until it switches itself off (about 12 h on 10,000 mAh). Plugging in cancels the run.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start", (dl, w) -> write(() -> client.calibration("start"), "Calibration started"))
                .show());
        calStop.setOnClickListener(v -> write(() -> client.calibration("cancel"), "Calibration cancelled"));
        calReset.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setMessage("Forget the calibrated curve and use the PiSugar chip's own estimate?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dl, w) -> write(() -> client.calibration("reset"), null)).show());
        calStatus = text("", 10, SUB, false, false);
        controlRow(maint, "Battery calibration", calStatus, calGo, calStop, calReset);
        Button mJoin = button("Join", false), mShare = button("Share", false);
        mJoin.setOnClickListener(v -> meshJoinDialog());
        mShare.setOnClickListener(v -> meshShare());
        controlRow(maint, "Other mesh", "Join another mesh from a QR code, or share this one", mJoin, mShare);
        Button nShow = button("Show code", false), nSet = button("Set up", false);
        nShow.setOnClickListener(v -> showSetupCode());
        nSet.setOnClickListener(v -> setupDialog());
        controlRow(maint, "Add a new node", "One QR sets up a new node: this mesh, the team channel and a callsign", nShow, nSet);
        posBtns = new Button[POS_SOURCES.length];
        for (int i = 0; i < POS_SOURCES.length; i++) {
            final int k = i;
            posBtns[i] = button(POS_NAMES[i], false);
            posBtns[i].setOnClickListener(v -> write(() -> client.setPositionSource(POS_SOURCES[k]), "Position source: " + POS_NAMES[k]));
        }
        posNote = text("", 10, SUB, false, false);
        controlRow(maint, "Position source", posNote, posBtns);
        Button fleetClear = button("Clear chat", true);
        fleetClear.setOnClickListener(v -> new AlertDialog.Builder(ctx).setTitle("Clear fleet chat?")
                .setMessage("Deletes the team chat stored on every node and every ManetNode phone, so the next operation starts "
                        + "clean. Runs fleet-wide on purpose; a node or phone that is off picks it up when it rejoins.\n\n"
                        + "Cannot be undone. ATAK keeps its own chat history.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear everywhere", (dl, w) -> write(() -> client.fleetClear("chat"), "Fleet chat cleared"))
                .show());
        controlRow(maint, "Clear fleet data", "Chat on every node and phone", fleetClear);
        meshKeep = button("Keep", false);
        meshKeep.setOnClickListener(v -> write(() -> client.meshJoinCancel(), "Staying on this mesh"));
        TextView joinNote = text("", 10, WARN, false, false);
        joinNote.setTag("note");
        meshJoinRow = controlRow(maint, "Temporary mesh", joinNote, meshKeep);
        meshJoinRow.setVisibility(View.GONE);
        controlRow(maint, "Node power", "Reboot: about 1 minute offline", reboot, shut);
        if (here != null) {
            Button posHere = button("Here", false), posClear = button("Clear", false);
            posHere.setOnClickListener(v -> {
                double[] p = here.get();
                if (p == null) { Toast.makeText(ctx, "No location on this phone yet", Toast.LENGTH_SHORT).show(); return; }
                new AlertDialog.Builder(ctx).setMessage("Set this node's position to where this phone is now?\n\n"
                        + String.format(Locale.US, "%.5f, %.5f", p[0], p[1]) + "\n\nUsed while the node has no GPS fix.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Set", (d, w) -> write(() -> client.setPosition(p), "Node position set"))
                        .show();
            });
            posClear.setOnClickListener(v -> write(() -> client.setPosition(null), "Node position cleared"));
            controlRow(maint, "Node position", "For a node without GPS: put it on the map where you stand next to it", posHere, posClear);
        }
        signOut = button("Sign out", false);
        signOut.setOnClickListener(v -> io.execute(() -> {
            client.signOut();
            ui.post(() -> { Toast.makeText(ctx, "Signed out of this node", Toast.LENGTH_SHORT).show(); updateSettings(); });
        }));
        Button signOutAll = button("All devices", false);
        signOutAll.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                .setMessage("Sign every phone and browser out of this node?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out all", (dlg, w) -> write(() -> client.signOutAll(), "All devices signed out"))
                .show());
        if (forgetLogin != null && savedLogin != null && savedLogin.get() != null) {
            Button forget = button("Forget password", false);
            forget.setOnClickListener(v -> new AlertDialog.Builder(ctx)
                    .setMessage("Forget the node password saved on this phone? You will be asked for it next time.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget", (dlg, w) -> { forgetLogin.run(); forget.setEnabled(false);
                        Toast.makeText(ctx, "Saved password forgotten", Toast.LENGTH_SHORT).show(); })
                    .show());
            controlRow(maint, "Node sign-in", "Password saved on this phone (encrypted): every node signs in by itself", signOut, signOutAll, forget);
        } else {
            controlRow(maint, "Node sign-in", "Kept on this phone for 30 days of use", signOut, signOutAll);
        }
        Button addr = button("Edit", false);
        addr.setOnClickListener(v -> editNodeAddress.run());
        controlRow(maint, "Node address", client.webUiUrl(), addr);
        settingsBox.addView(maint, spaced());
    }

    private void updateSettings() {
        JSONObject s = summary != null ? summary : new JSONObject();
        if (posNote != null) {
            String src = s.optString("position_source", "");
            for (int i = 0; i < POS_SOURCES.length; i++) pressed(posBtns[i], POS_SOURCES[i].equals(src));
            int k = java.util.Arrays.asList(POS_SOURCES).indexOf(src);
            posNote.setText(k < 0 ? "Needs a newer node package" : POS_MEANING[k]);
        }
        if (calStatus != null) {
            JSONObject pw = s.optJSONObject("power"), cal = pw == null ? null : pw.optJSONObject("calibration");
            String st = cal == null ? null : cal.optString("state", null);
            String line;
            if ("running".equals(st)) line = "Running: " + cal.optInt("samples") + " readings" + (cal.isNull("running_s") ? "" : ", " + cal.optLong("running_s") / 60 + " min") + ". Leave it until it switches off.";
            else if ("waiting_unplug".equals(st)) line = "Ready: unplug the charger to begin.";
            else if (cal != null && cal.optJSONArray("curve") != null) line = "Using a calibrated curve" + (cal.isNull("made") ? "" : " (" + cal.optString("made") + ")");
            else line = "Using the PiSugar chip's estimate. Charge to full, then Calibrate.";
            if (cal != null && !cal.isNull("message") && !"running".equals(st)) line += " " + cal.optString("message");
            calStatus.setText(line);
            calStatus.setTextColor("running".equals(st) || "waiting_unplug".equals(st) ? OK : SUB);
        }
        JSONObject mj = s.optJSONObject("mesh") == null ? null : s.optJSONObject("mesh").optJSONObject("join");
        if (meshJoinRow != null) {
            meshJoinRow.setVisibility(mj == null ? View.GONE : View.VISIBLE);
            if (mj != null) {
                TextView note = meshJoinRow.findViewWithTag("note");
                if (note != null) note.setText("On " + mj.optString("to_mesh_id", "another mesh") + " until "
                        + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(mj.optLong("until") * 1000))
                        + ", then back to " + mj.optString("from_mesh_id", "the old mesh") + ". Keep it?");
            }
        }
        JSONObject led = s.optJSONObject("led");
        boolean ledKnown = led != null && has(led, "pattern");
        if (ledKnown) {
            ledState.setText(ledText(led));
            ledState.setTextColor(ledColour(led));
        } else {
            ledState.setText("LED status unavailable");
            ledState.setTextColor(SUB);
        }
        pressed(ledOn, ledKnown && led.optBoolean("enabled", false) && has(led, "enabled"));
        pressed(ledOff, ledKnown && has(led, "enabled") && !led.optBoolean("enabled"));
        int b = ledKnown && has(led, "brightness") ? led.optInt("brightness") : -1;
        brightValue.setText(b < 0 ? "--" : b + "%");
        for (int i = 0; i < BRIGHTNESS.length; i++) pressed(bright[i], BRIGHTNESS[i] == b);

        JSONObject e = s.optJSONObject("eud");
        pressed(eudAuto, e != null && !"cellular".equals(e.optString("mode")));
        pressed(eudCell, e != null && "cellular".equals(e.optString("mode")));
        if (e == null) eudNow.setText("Unavailable");
        else {
            JSONArray cl = e.optJSONArray("clients");
            int n = cl == null ? 0 : cl.length();
            eudNow.setText(("cellular".equals(e.optString("active")) ? "Own cellular" : "Node internet")
                    + " · " + n + (n == 1 ? " phone" : " phones"));
        }

        signOut.setEnabled(client.signedIn());
        pressed(signOut, false);

        JSONObject mt = led == null ? null : led.optJSONObject("maintenance");
        mtOn.setEnabled(mt != null);
        mtOff.setEnabled(mt != null);
        boolean mtActive = mt != null && mt.optBoolean("active");
        pressed(mtOn, mtActive);
        pressed(mtOff, mt != null && !mtActive);
        if (mt == null) mtNote.setText("Needs node package 1.0.7 or newer");
        else if (!mtActive) mtNote.setText("White light: tells the team this node is being worked on. Turns itself off after 30 min.");
        else {
            String why = mt.optString("reason", "manual");
            long left = mt.isNull("until") ? -1 : mt.optLong("until") - System.currentTimeMillis() / 1000;
            mtNote.setText("On (" + ("update".equals(why) ? "updating" : "settings".equals(why) ? "applying settings"
                    : "diagnostics".equals(why) ? "running diagnostics" : "manual") + ")"
                    + (left > 0 ? String.format(java.util.Locale.US, " · %d min left", (left + 59) / 60) : "") + ". Light is white: don't unplug.");
        }
        JSONObject p = battery(s);
        boolean sugar = p != null && p.optBoolean("available") && p.optString("source", "").startsWith("pisugar");
        apoOn.setEnabled(sugar);
        apoOff.setEnabled(sugar);
        pressed(apoOn, sugar && has(p, "auto_power_on") && p.optBoolean("auto_power_on"));
        pressed(apoOff, sugar && has(p, "auto_power_on") && !p.optBoolean("auto_power_on"));
        // Only nodes that report the setting (dev23+, plain OpenMANET with a PiSugar) can change it.
        JSONObject lp = p == null ? null : p.optJSONObject("long_press_shutdown");
        lpClean.setEnabled(lp != null);
        lpCut.setEnabled(lp != null);
        pressed(lpClean, lp != null && lp.optBoolean("enabled"));
        pressed(lpCut, lp != null && !lp.optBoolean("enabled"));
    }

    // ---------------------------------------------------------------- writes

    private interface Call { FieldClient.Result run() throws Exception; }

    /** Writes need the node login; ask for it once, then retry. */
    private void write(Call call, String done) {
        if (!client.signedIn()) { signIn(() -> write(call, done)); return; }
        io.execute(() -> {
            String msg;
            try {
                FieldClient.Result r = call.run();
                if (r.code == 401) { ui.post(() -> signIn(() -> write(call, done))); return; }
                JSONObject b = r.body;
                if (b.has("verified")) msg = b.optBoolean("verified") ? "Verified: the PiSugar reads the new setting" : "Not verified: check the node";
                else if (r.ok() && b.has("note")) msg = b.optString("note");
                else if (r.ok() && b.has("ms")) msg = (done != null ? done : "Done") + " in " + b.optLong("ms") + " ms";
                else if (r.code == 409) msg = b.has("error") ? b.optString("error") : "Another service manages the PiSugar on this node";
                else msg = r.ok() ? (done != null ? done : "Done") : r.error();
            } catch (Exception ex) {
                msg = "Node not reachable";
            }
            final String m = msg;
            ui.post(() -> { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); refresh(false); });
        });
    }

    private static final java.util.Set<String> askedSignIn = new java.util.HashSet<>();

    /**
     * Full-panel sign-in, like the OpenMANET plugin's first screen: logo, user root, password, CONNECT.
     * Shown instead of the tabs while this phone has no session on the node; returns true if shown.
     */
    /** Host hooks for "enter the password once": the saved {user, password} (null = none), and save after a sign-in. */
    static java.util.function.Supplier<String[]> savedLogin;
    static java.util.function.BiConsumer<String, String> rememberLogin;
    static Runnable forgetLogin;

    /** Signs in with the saved password if there is one; otherwise (or if it fails) shows the sign-in screen. */
    public boolean loginScreen(View root) {
        if (client.signedIn()) return false;
        String[] saved = savedLogin == null ? null : savedLogin.get();
        if (saved == null) return loginForm(root);
        io.execute(() -> {
            boolean ok;
            try { ok = client.login(saved[0], saved[1]).ok(); } catch (Exception e) { ok = false; }
            final boolean done = ok;
            ui.post(() -> { if (done) { updateSettings(); refresh(true); } else loginForm(root); });
        });
        return true;
    }

    private boolean loginForm(View root) {
        View page = root.findViewById(R.id.fe_page_login);
        LinearLayout box = root.findViewById(R.id.fe_login_box);
        if (page == null || box == null || client.signedIn()) return false;
        View bar = root.findViewById(R.id.fe_tab_status) == null ? null : (View) root.findViewById(R.id.fe_tab_status).getParent();
        final java.util.List<View> hidden = new java.util.ArrayList<>();
        android.view.ViewGroup frame = (android.view.ViewGroup) page.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View c = frame.getChildAt(i);
            if (c != page && c.getVisibility() == View.VISIBLE) { hidden.add(c); c.setVisibility(View.GONE); }
        }
        if (bar != null && bar.getVisibility() == View.VISIBLE) { hidden.add(bar); bar.setVisibility(View.GONE); }
        page.setVisibility(View.VISIBLE);
        box.removeAllViews();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        View logo = new View(ctx) {
            @Override protected void onDraw(android.graphics.Canvas c) { StatusWidget.drawLogo(c, 0, 0, getWidth(), CYAN, 1f); }
        };
        box.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text("OpenMANET Field Extension", 22, TEXT, true, false);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, 0);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(ctx);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(60), dp(2));
        rp.topMargin = dp(12);
        rp.bottomMargin = dp(10);
        box.addView(rule, rp);
        TextView sub = text("OPENMANET", 13, SUB, true, false);
        sub.setLetterSpacing(0.3f);
        box.addView(sub);
        TextView ul = text("USERNAME", 11, SUB, true, false);
        ul.setLetterSpacing(0.14f);
        ul.setPadding(0, dp(22), 0, dp(6));
        box.addView(ul, new LinearLayout.LayoutParams(-1, -2));
        EditText user = new EditText(ctx);
        user.setText("root");
        user.setTextColor(TEXT);
        user.setBackground(fill(0xFF07100F, LINE));
        user.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(user, new LinearLayout.LayoutParams(-1, -2));
        TextView pl = text("PASSWORD", 11, SUB, true, false);
        pl.setLetterSpacing(0.14f);
        pl.setPadding(0, dp(16), 0, dp(6));
        box.addView(pl, new LinearLayout.LayoutParams(-1, -2));
        EditText pass = new EditText(ctx);
        pass.setHint("Same as the OpenMANET web page");
        pass.setHintTextColor(MUTED);
        pass.setTextColor(TEXT);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setBackground(fill(0xFF07100F, LINE));
        pass.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(pass, new LinearLayout.LayoutParams(-1, -2));
        Button go = button("Connect", true);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(22);
        box.addView(go, gp);
        TextView note = text("Node " + android.net.Uri.parse(client.webUiUrl()).getHost() + " found automatically", 12, MUTED, false, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(14), 0, 0);
        box.addView(note, new LinearLayout.LayoutParams(-1, -2));
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            io.execute(() -> {
                String msg = null;
                try {
                    FieldClient.Result r = client.login(user.getText().toString(), pass.getText().toString());
                    if (!r.ok()) msg = r.error();
                } catch (Exception ex) {
                    msg = "Node not reachable";
                }
                final String m = msg;
                ui.post(() -> {
                    go.setEnabled(true);
                    if (m != null) { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); return; }
                    if (rememberLogin != null) rememberLogin.accept(user.getText().toString(), pass.getText().toString());
                    page.setVisibility(View.GONE);
                    for (View h : hidden) h.setVisibility(View.VISIBLE);
                    updateSettings();
                    refresh(true);
                });
            });
        });
        return true;
    }

    private GradientDrawable fill(int colour, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(2));
        return g;
    }

    /** Like the OpenMANET plugin: ask for the node password once when the node is first opened,
     *  so later changes just work. Asks at most once per node per session; kept 30 days on the node. */
    public void signInOnOpen() {
        if (client.signedIn() || !askedSignIn.add(client.webUiUrl())) return;
        ui.post(() -> signIn(() -> { Toast.makeText(ctx, "Signed in to the node", Toast.LENGTH_SHORT).show(); updateSettings(); }));
    }

    private void signIn(Runnable then) {
        String[] saved = savedLogin == null ? null : savedLogin.get();
        if (saved != null) {
            io.execute(() -> {
                boolean ok;
                try { ok = client.login(saved[0], saved[1]).ok(); } catch (Exception e) { ok = false; }
                final boolean done = ok;
                ui.post(() -> { if (done) then.run(); else signInDialog(then); });
            });
            return;
        }
        signInDialog(then);
    }

    private void signInDialog(Runnable then) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText user = new EditText(ctx);
        user.setText("root");
        EditText pass = new EditText(ctx);
        pass.setHint("Node password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(user);
        form.addView(pass);
        new AlertDialog.Builder(ctx).setTitle("Sign in to the node")
                .setMessage("Same password as the OpenMANET web page. You stay signed in on this phone until you sign out.").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign in", (d, w) -> io.execute(() -> {
                    String msg = null;
                    try {
                        FieldClient.Result r = client.login(user.getText().toString(), pass.getText().toString());
                        if (!r.ok()) msg = r.error();
                    } catch (Exception ex) {
                        msg = "Node not reachable";
                    }
                    final String m = msg;
                    ui.post(() -> {
                        if (m != null) { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); return; }
                        if (rememberLogin != null) rememberLogin.accept(user.getText().toString(), pass.getText().toString());
                        then.run();
                    });
                })).show();
    }

    /** App hook for the camera: given a callback, scan a QR code and pass its text. null = no scanner (plugin). */
    static java.util.function.Consumer<java.util.function.Consumer<String>> scanner;

    private void meshShare() {
        if (!client.signedIn()) { signIn(this::meshShare); return; }
        io.execute(() -> {
            String code = null, id = null, msg = null;
            try {
                FieldClient.Result r = client.meshShare();
                if (r.code == 401) { ui.post(() -> signIn(this::meshShare)); return; }
                if (r.ok()) { code = r.body.optString("code", null); id = r.body.optString("mesh_id", ""); }
                else msg = r.error();
            } catch (Exception e) {
                msg = "Node not reachable";
            }
            final String c = code, mid = id, m = msg;
            ui.post(() -> {
                if (c == null) { Toast.makeText(ctx, m != null ? m : "No share code", Toast.LENGTH_SHORT).show(); return; }
                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.setPadding(dp(8), dp(8), dp(8), 0);
                android.widget.ImageView img = new android.widget.ImageView(ctx);
                try { img.setImageBitmap(qrBitmap(c, dp(280))); } catch (Exception e) { /* text below still works */ }
                box.addView(img);
                TextView t = new TextView(ctx);
                t.setText(c);
                t.setTextIsSelectable(true);
                t.setTextSize(10);
                t.setTypeface(Typeface.MONOSPACE);
                t.setPadding(dp(8), dp(8), dp(8), 0);
                box.addView(t);
                android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
                sv.addView(box);
                new AlertDialog.Builder(ctx).setTitle("Share mesh " + mid)
                        .setMessage("Scan this on another node's Join screen. It contains the mesh password: show it only to your team.")
                        .setView(sv)
                        .setNeutralButton("Copy", (dl, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("mesh", c));
                        })
                        .setPositiveButton("Done", null).show();
            });
        });
    }

    /** Join another mesh, like the OpenMANET plugin: Scan QR / Manual / Show QR, then how long, then confirm. */
    /** Setup code for a new node (node/setup-code), as a QR for the new node's phone to scan. */
    private void showSetupCode() {
        if (!client.signedIn()) { signIn(this::showSetupCode); return; }
        io.execute(() -> {
            String code = null, msg = null;
            try {
                FieldClient.Result r = client.setupCode();
                if (r.code == 401) { ui.post(() -> signIn(this::showSetupCode)); return; }
                if (r.ok()) code = r.body.optString("code", null);
                else msg = r.error();
            } catch (Exception e) {
                msg = "Node not reachable";
            }
            final String c = code, m = msg;
            ui.post(() -> {
                if (c == null || c.isEmpty()) { Toast.makeText(ctx, m != null ? m : "No setup code from this node", Toast.LENGTH_LONG).show(); return; }
                android.widget.ImageView img = new android.widget.ImageView(ctx);
                try { img.setImageBitmap(qrBitmap(c, dp(280))); } catch (Exception e) {
                    Toast.makeText(ctx, "Could not draw the QR code", Toast.LENGTH_SHORT).show();
                    return;
                }
                img.setPadding(dp(8), dp(8), dp(8), dp(8));
                new AlertDialog.Builder(ctx).setTitle("New node setup code")
                        .setMessage("On the new node's Wi-Fi, scan this with ManetNode (Node Settings → Add a new node → Set up), "
                                + "or paste it into the new node's web page. It contains the mesh and team keys: only show it to your team.")
                        .setView(img)
                        .setNeutralButton("Copy", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Node setup code", c));
                        })
                        .setPositiveButton("Done", null).show();
            });
        });
    }

    /** Set up the node this phone is on from a FENODE1 code (scanned or pasted). */
    private void setupDialog() {
        if (!client.signedIn()) { signIn(this::setupDialog); return; }
        java.util.List<String> opts = new java.util.ArrayList<>();
        if (scanner != null) opts.add("Scan QR");
        opts.add("Paste code");
        new AlertDialog.Builder(ctx).setTitle("Set up this node from a code")
                .setItems(opts.toArray(new String[0]), (d, i) -> {
                    if (opts.get(i).startsWith("Scan")) scanner.accept(code -> ui.post(() -> setupConfirm(code)));
                    else {
                        EditText in = new EditText(ctx);
                        in.setHint("FENODE1:… (paste the setup code)");
                        in.setMinLines(3);
                        new AlertDialog.Builder(ctx).setTitle("Paste setup code").setView(in)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Next", (d2, w2) -> setupConfirm(in.getText().toString())).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void setupConfirm(String code) {
        if (code == null) return;
        code = code.trim();
        if (!code.startsWith("FENODE1:")) {
            Toast.makeText(ctx, code.startsWith("FEMESH1:") ? "That is a mesh code: use Other mesh → Join" : "Not a node setup code", Toast.LENGTH_LONG).show();
            return;
        }
        final String c = code;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText cs = new EditText(ctx);
        cs.setHint("Callsign for this node (optional), e.g. Odin01");
        cs.setSingleLine(true);
        form.addView(cs);
        new AlertDialog.Builder(ctx).setTitle("Set up this node?")
                .setMessage("This node joins the mesh and the LoRa team channel in the code (and builds the 2.4 GHz mesh on a "
                        + "fresh USB stick), then reboots. Reconnect to its Wi-Fi in about 2 minutes.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set up", (d, w) -> write(() -> client.nodeSetup(c, cs.getText().toString()),
                        "Setting up: the node reboots now"))
                .show();
    }

    private void meshJoinDialog() {
        if (!client.signedIn()) { signIn(this::meshJoinDialog); return; }
        java.util.List<String> opts = new java.util.ArrayList<>();
        if (scanner != null) opts.add("Scan QR");
        opts.add("Manual");
        opts.add("Show this mesh's QR");
        new AlertDialog.Builder(ctx).setTitle("Join another mesh")
                .setItems(opts.toArray(new String[0]), (d, i) -> {
                    String o = opts.get(i);
                    if (o.startsWith("Scan")) scanner.accept(code -> ui.post(() -> meshJoinHowLong(code)));
                    else if (o.startsWith("Manual")) meshJoinManual();
                    else meshShare();
                }).setNegativeButton("Cancel", null).show();
    }

    private void meshJoinManual() {
        EditText in = new EditText(ctx);
        in.setHint("FEMESH1:… (paste the shared code)");
        in.setMinLines(3);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip != null && clip.toString().trim().startsWith("FEMESH")) in.setText(clip.toString().trim());
        }
        new AlertDialog.Builder(ctx).setTitle("Join another mesh").setView(in)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> {
                    String c = in.getText().toString().trim();
                    if (!c.startsWith("FEMESH")) { Toast.makeText(ctx, "That is not a mesh share code", Toast.LENGTH_SHORT).show(); return; }
                    meshJoinHowLong(c);
                }).show();
    }

    private void meshJoinHowLong(String code) {
        new AlertDialog.Builder(ctx).setTitle("How long should this join last?")
                .setItems(new String[]{"Permanent", "Temporary (switch back after 1 h)", "Temporary · 4 h", "Temporary · 8 h",
                        "Temporary · 24 h", "Temporary · other hours…"}, (d, i) -> {
                    if (i == 0) meshJoinConfirm(code, null);
                    else if (i < 5) meshJoinConfirm(code, (double) new int[]{1, 4, 8, 24}[i - 1]);
                    else {
                        EditText h = new EditText(ctx);
                        h.setHint("Hours, e.g. 2.5");
                        h.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                        new AlertDialog.Builder(ctx).setTitle("Switch back after").setView(h)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Next", (d2, w2) -> {
                                    try {
                                        double v = Double.parseDouble(h.getText().toString().trim());
                                        if (v <= 0 || v > 720) throw new NumberFormatException();
                                        meshJoinConfirm(code, v);
                                    } catch (NumberFormatException e) {
                                        Toast.makeText(ctx, "Enter 0.1 to 720 hours", Toast.LENGTH_SHORT).show();
                                    }
                                }).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void meshJoinConfirm(String code, Double hours) {
        String when = hours == null ? "for good" : "for " + (hours == Math.floor(hours) ? String.valueOf(hours.intValue()) : String.valueOf(hours))
                + " h, then it switches back by itself";
        new AlertDialog.Builder(ctx).setTitle("Join the other mesh?")
                .setMessage("This node moves to the shared mesh " + when + ".\n\nIt reboots in a few seconds and leaves this mesh, "
                        + "so this phone may lose it until you join that mesh's Wi-Fi.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Join", (d, w) -> write(() -> client.meshJoin(code, hours), "Joining: the node reboots now"))
                .show();
    }

    private void confirm(String question, String action) {
        boolean reboot = action.equals("reboot");
        new AlertDialog.Builder(ctx).setMessage(question).setNegativeButton("Cancel", null)
                .setPositiveButton(reboot ? "Reboot" : "Shut down", (d, w) -> write(() -> client.nodePower(action),
                        reboot ? "Rebooting. Back in about a minute." : "Shutting down. Use the PiSugar button to start it again."))
                .show();
    }

    private void open(String url) {
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- readings

    private static JSONObject battery(JSONObject s) {
        JSONObject p = s.optJSONObject("power");
        if (p == null) return null;
        // Older nodes nest the full reading under "battery"; dev20+ keep it flat and add a short "battery" summary.
        JSONObject b = p.optJSONObject("battery");
        return b != null && b.has("available") ? b : p;
    }

    /** "● Blue · LoRa only": the light's colour and what it means, as on the Settings tab. */
    private static String ledText(JSONObject led) {
        String c = led.optString("color", "");
        String name = c.isEmpty() ? "--" : "magenta".equals(c) ? "Purple" : Character.toUpperCase(c.charAt(0)) + c.substring(1);
        return "● " + name + " · " + ledMeaning(led) + (led.optBoolean("enabled", true) ? "" : " (light off)");
    }

    /** "Mesh + internet": what the light means, without its colour. */
    static String ledMeaning(JSONObject led) {
        String p = led.optString("pattern");
        return LED_MEANING.containsKey(p) ? LED_MEANING.get(p) : p;
    }

    /** The node's callsign (summary.identity, node builds dev26+), else its hostname. */
    static String callsign(JSONObject s, String fallback) {
        JSONObject id = s == null ? null : s.optJSONObject("identity");
        String c = id == null ? "" : id.optString("callsign", "");
        return c.isEmpty() ? (s == null ? fallback : s.optString("node", fallback)) : c;
    }

    static int ledColour(JSONObject led) {
        if (led == null || !has(led, "color")) return ORB_OFF;
        Integer c = LED_COLOURS.get(led.optString("color"));
        return c == null ? ORB_OFF : c;
    }

    private String ifaceAddr(String name) {
        JSONObject parsed = net == null ? null : net.optJSONObject("parsed");
        JSONArray ifs = parsed == null ? null : parsed.optJSONArray("interfaces");
        if (ifs == null || name == null) return null;
        for (int i = 0; i < ifs.length(); i++) {
            JSONObject f = ifs.optJSONObject(i);
            if (f == null || !name.equals(f.optString("name"))) continue;
            JSONArray a = f.optJSONArray("addresses");
            if (a == null || a.length() == 0) return null;
            for (int j = 0; j < a.length(); j++) if (!a.optString(j).contains(":")) return a.optString(j);
            return a.optString(0);
        }
        return null;
    }

    private String[] meshIp() {
        for (String n : new String[]{"bat0", "br-ahwlan", "br-lan"}) {
            String a = ifaceAddr(n);
            if (a != null) return new String[]{n, a};
        }
        return null;
    }

    private static String backhaulType(String dev) {
        if (dev == null) return null;
        if (dev.matches("^(eth|end|lan|wan).*")) return "Ethernet";
        if (dev.matches("^(wwan|usb|rmnet|ppp).*")) return "Cellular";
        if (dev.contains("sta")) return "Wi-Fi client";
        if (dev.matches("^(bat|br-ahwlan).*")) return "Mesh gateway";
        return dev;
    }

    private static String uptime(String u) {
        double s = parse(u == null ? null : u.trim().split(" ")[0]);
        if (Double.isNaN(s)) return null;
        long d = (long) (s / 86400), h = (long) (s % 86400 / 3600), m = (long) (s % 3600 / 60);
        return (d > 0 ? d + "d " : "") + h + "h " + m + "m";
    }

    /** {percent used, MB used, MB total} from /proc/meminfo text. */
    private static int[] memory(String m) {
        if (m == null) return null;
        long t = meminfo(m, "MemTotal"), a = meminfo(m, "MemAvailable");
        if (t <= 0 || a < 0) return null;
        return new int[]{(int) Math.round(100.0 * (t - a) / t), (int) ((t - a) / 1024), (int) (t / 1024)};
    }

    private static long meminfo(String m, String key) {
        Matcher x = Pattern.compile("(?m)^" + key + ":\\s+(\\d+)").matcher(m);
        return x.find() ? Long.parseLong(x.group(1)) : -1;
    }

    private static double parse(String s) {
        try { return s == null ? Double.NaN : Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static boolean has(JSONObject o, String k) { return o != null && o.has(k) && !o.isNull(k); }
    private static String orDash(String s) { return s == null ? "--" : s; }
    private static String fmt(String f, double v) { return String.format(Locale.US, f, v); }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return TextUtils.join(" · ", out);
    }

    // ---------------------------------------------------------------- web UI look

    private int dp(int v) { return Math.round(v * density); }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        return p;
    }

    private TextView text(String s, int sp, int colour, boolean bold, boolean mono) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(Math.max(1, dp(1)), stroke);
        d.setCornerRadius(dp(2));
        return d;
    }

    private static GradientDrawable circle(int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(colour);
        return d;
    }

    /** Web UI button: outlined, uppercase; danger = red; pressed state = solid cyan. */
    private Button button(String label, boolean danger) {
        Button b = new Button(ctx);
        b.setText(label.toUpperCase(Locale.US));
        b.setTextSize(11);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setLetterSpacing(0.08f);
        b.setAllCaps(true);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setTag(danger);
        pressed(b, false);
        return b;
    }

    private void pressed(Button b, boolean on) {
        boolean danger = Boolean.TRUE.equals(b.getTag());
        if (danger) {
            b.setBackground(box(0x14FF3B4D, BAD));
            b.setTextColor(BAD);
        } else if (on) {
            b.setBackground(box(CYAN, CYAN));
            b.setTextColor(0xFF001418);
        } else {
            b.setBackground(box(0, LINE));
            b.setTextColor(TEXT);
        }
        b.setAlpha(b.isEnabled() ? 1f : 0.45f);
    }

    /** Web UI table card with a cyan uppercase heading. */
    private LinearLayout table(String title) {
        LinearLayout t = column();
        t.setBackground(box(PANEL, LINE));
        TextView h = text(title.toUpperCase(Locale.US), 11, CYAN, true, false);
        h.setLetterSpacing(0.14f);
        h.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.addView(h);
        View line = new View(ctx);
        line.setBackgroundColor(LINE);
        t.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        return t;
    }

    /** name | dot + state, detail underneath. tone 0 = no dot. */
    private void trow(LinearLayout table, String name, String state, int tone, String detail) {
        LinearLayout r = column();
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(name, 12, TEXT, false, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (tone != 0) {
            View dot = new View(ctx);
            dot.setBackground(circle(tone));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dp.rightMargin = dp(6);
            top.addView(dot, dp);
        }
        top.addView(text(state == null ? "Unavailable" : state, 12, tone == 0 ? TEXT : tone, false, true));
        r.addView(top);
        if (detail != null && !detail.isEmpty()) r.addView(text(detail, 10, SUB, false, false));
        divider(table);
        table.addView(r);
    }

    /** Control row: label (+ optional value or note) on the left, buttons on the right. */
    private LinearLayout controlRow(LinearLayout table, String label, Object note, Button... buttons) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout left = column();
        left.addView(text(label, 12, TEXT, false, false));
        if (note instanceof String) left.addView(text((String) note, 10, SUB, false, false));
        else if (note instanceof View) left.addView((View) note);
        if (buttons.length >= 3) {                  // label on its own line, buttons share the row below
            r.setOrientation(LinearLayout.VERTICAL);
            r.setGravity(Gravity.START);
            r.addView(left, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout bs = row();
            for (int i = 0; i < buttons.length; i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1);
                if (i > 0) p.leftMargin = dp(6);
                buttons[i].setMinWidth(0);
                buttons[i].setMinimumWidth(0);
                buttons[i].setPadding(dp(4), 0, dp(4), 0);
                buttons[i].setMaxLines(1);
                bs.addView(buttons[i], p);
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(6);
            r.addView(bs, bp);
        } else {
            r.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            for (Button b : buttons) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(34));
                p.leftMargin = dp(6);
                r.addView(b, p);
            }
        }
        divider(table);
        table.addView(r);
        return r;
    }

    /** A LoRa mode as a card: name, what it means, a tick on the current one. */
    private View modeOption(String name, String meaning, boolean on, boolean enabled) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(on ? box(0x2600E5FF, 0xAA00E5FF) : box(0, 0x2200E5FF));
        LinearLayout col = column();
        col.addView(text(name, 14, on ? CYAN : TEXT, on, false));
        col.addView(text(meaning, 11, SUB, false, false));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tick = text(on ? "\u2713" : "", 13, 0xFF05080A, true, false);   // radio button: filled tick or empty ring
        tick.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(on ? CYAN : 0);
        ring.setStroke(dp(2), on ? CYAN : SUB);
        tick.setBackground(ring);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(22), dp(22));
        tp.leftMargin = dp(8);
        r.addView(tick, tp);
        r.setEnabled(enabled);
        r.setClickable(enabled);
        r.setAlpha(enabled ? 1f : 0.45f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(6), dp(4), dp(6), dp(2));
        r.setLayoutParams(lp);
        return r;
    }

    private void divider(LinearLayout table) {
        if (table.getChildCount() <= 2) return;   // heading + its line
        View d = new View(ctx);
        d.setBackgroundColor(LINE_SOFT);
        table.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    }
}
