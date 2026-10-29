package com.fieldext.plugin;

import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Field Extension node API. Call from a background thread. */
public final class FieldClient {
    public static final class Result {
        public final int code;
        public final JSONObject body;
        Result(int code, JSONObject body) { this.code = code; this.body = body; }
        public boolean ok() { return code >= 200 && code < 300; }
        public String error() { return body.optString("error", "Request failed (" + code + ")"); }
    }

    private static final int MAX_BYTES = 256 * 1024;
    private final String base;        // e.g. http://10.41.0.1:8787 (8080/8081 belong to openmanetd)
    private volatile String token;    // rpcd session from /api/field/login
    private final android.content.SharedPreferences store;   // keeps the session per node; null = memory only

    public FieldClient(String base) { this(base, null); }

    /** With a store, a sign-in is kept across ATAK restarts until the node rejects it (401) or Sign out. */
    public FieldClient(String base, android.content.SharedPreferences store) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.store = store;
        if (store != null) token = store.getString(tokenKey(), null);
    }

    private String tokenKey() { return "fieldext_session_" + Uri.parse(base).getHost(); }

    private void keep(String t) {
        token = t;
        if (store == null) return;
        if (t == null) store.edit().remove(tokenKey()).apply();
        else store.edit().putString(tokenKey(), t).apply();
    }

    /** Ends every remembered session on this node (node dev20+). */
    public Result signOutAll() throws IOException {
        JSONObject b = new JSONObject();
        put(b, "all", true);
        Result r = write("logout", b);
        if (r.ok()) keep(null);
        return r;
    }

    /** Forget this node's session on the phone (and end it on the node when it supports logout). */
    public void signOut() {
        String t = token;
        keep(null);
        if (t == null) return;
        try {
            JSONObject b = new JSONObject();
            put(b, "token", t);
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/field/logout").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Authorization", "Bearer " + t);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {
            // older node builds have no logout; the phone has forgotten the session either way
        }
    }

    public String webUiUrl() { return base + "/"; }
    /** The node's own OpenMANET page (openmanetd on :8080; / only redirects to LuCI). */
    public String openManetUrl() {
        Uri u = Uri.parse(base);
        return u.getScheme() + "://" + u.getHost() + ":8080/";
    }
    public boolean signedIn() { return token != null; }

    public Result summary() throws IOException { return call("GET", "summary", null); }
    /** Any read-only endpoint: summary, network, services, system, lora, ... */
    public Result get(String name) throws IOException { return call("GET", name, null); }

    public Result login(String user, String password) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "username", user);
        put(b, "password", password);
        put(b, "remember", true);                              // 30-day device session (node dev20+)
        put(b, "device", "ATAK " + android.os.Build.MODEL);
        Result r = call("POST", "login", b);
        if (r.ok()) keep(r.body.optString("token", null));
        return r;
    }

    public Result setLed(Boolean enabled, Integer brightness) throws IOException {
        JSONObject b = new JSONObject();
        if (enabled != null) put(b, "enabled", enabled);
        if (brightness != null) put(b, "brightness", brightness);
        return write("led", b);
    }

    /** Maintenance mode: the status light shows white until it is turned off or the minutes run out (node 1.0.7+). */
    public Result setMaintenance(boolean enabled, int minutes) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "enabled", enabled);
        if (enabled) put(b, "minutes", minutes);
        return write("maintenance", b);
    }

    public Result setAutoPowerOn(boolean enabled) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "enabled", enabled);
        return write("power/auto-power-on", b);
    }

    /** On: a long press of the PiSugar button is a clean shutdown (node 2.0.0~dev23+). Off: it cuts the power at once. */
    public Result setLongPressShutdown(boolean enabled) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "enabled", enabled);
        return write("battery/long-press", b);
    }

    /** mode is "auto" (node internet when it has some, else phone cellular) or "cellular". */
    public Result setPhoneInternet(String mode) throws IOException {
        if (!"auto".equals(mode) && !"cellular".equals(mode)) throw new IllegalArgumentException(mode);
        JSONObject b = new JSONObject();
        put(b, "mode", mode);
        return write("eud/internet", b);
    }

    /** action is "reboot" or "shutdown"; the caller has already confirmed with the user. */
    public Result nodePower(String action) throws IOException {
        if (!"reboot".equals(action) && !"shutdown".equals(action)) throw new IllegalArgumentException(action);
        JSONObject b = new JSONObject();
        put(b, "confirm", true);
        return write("node/" + action, b);
    }

    /** Node position set by hand for a node with no GPS (node 2.0.0~dev37+); null clears it. */
    public Result setPosition(double[] latLonAlt) throws IOException {
        JSONObject b = new JSONObject();
        if (latLonAlt == null) put(b, "clear", true);
        else {
            put(b, "lat", latLonAlt[0]);
            put(b, "lon", latLonAlt[1]);
            if (latLonAlt.length > 2 && !Double.isNaN(latLonAlt[2])) put(b, "alt", latLonAlt[2]);
        }
        return write("node/position", b);
    }

    /** The connected phone's position for a node with no GPS fix (node 2.0.0~dev44+). */
    public Result setPhonePosition(double lat, double lon, double alt) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "lat", lat);
        put(b, "lon", lon);
        if (!Double.isNaN(alt)) put(b, "alt", alt);
        put(b, "source", "eud");
        return write("node/position", b);
    }

    /** Where the node takes its position from: "auto", "node" or "phone" (node 2.0.0~dev48+). */
    public Result setPositionSource(String source) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "source", source);
        return write("node/position/source", b);
    }

    /** Clear fleet data: "chat", "photos" or "both"; spreads to every node, and phones clear on seeing it (node dev47+). */
    public Result fleetClear(String what) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "what", what);
        put(b, "confirm", true);
        return write("fleet/clear", b);
    }

    /** One code to set up a new node: mesh + LoRa team channel, "FENODE1:..." (node dev56+). Contains both keys. */
    public Result setupCode() throws IOException { return write("node/setup-code", new JSONObject()); }

    /** Set up this (new) node from a FENODE1 code: team channel, callsign, HaLow + 2.4 GHz mesh; then it reboots. */
    public Result nodeSetup(String code, String callsign) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "code", code.trim());
        if (callsign != null && !callsign.trim().isEmpty()) put(b, "callsign", callsign.trim());
        put(b, "confirm", true);
        return write("node/setup", b);
    }

    /** This node's mesh settings as a share code "FEMESH1:..." (includes the mesh password; node dev42+). */
    public Result meshShare() throws IOException { return write("mesh/share", new JSONObject()); }

    /** Join the mesh in a share code; revertHours null = permanent. The node reboots ~3 s later. */
    public Result meshJoin(String code, Double revertHours) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "code", code.trim());
        put(b, "revert_hours", revertHours == null ? JSONObject.NULL : revertHours);
        put(b, "confirm", true);
        return write("mesh/join", b);
    }

    /** Keep a temporary join (cancel the switch back). */
    public Result meshJoinCancel() throws IOException { return write("mesh/join/cancel", new JSONObject()); }

    /** Battery calibration: "start" (battery full; begins when unplugged), "cancel" or "reset". */
    public Result calibration(String action) throws IOException {
        JSONObject b = new JSONObject();
        put(b, "action", action);
        return write("battery/calibration", b);
    }

    /** LoRa writes (node 2.0.0~dev12+): mode, profile, send, ping, trace. Replies {ok, note?, error?}. */
    public Result loraWrite(String path, JSONObject body) throws IOException { return write("lora/" + path, body); }

    private Result write(String path, JSONObject body) throws IOException {
        Result r = call("POST", path, body);
        if (r.code == 401) keep(null);     // session expired: ask to sign in again
        return r;
    }

    private Result call(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/field/" + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !"login".equals(path)) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Result(code, parse(in));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject parse(InputStream in) throws IOException {
        if (in == null) return new JSONObject();
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = s.read(chunk)) > 0) {
                if (buf.size() + n > MAX_BYTES) throw new IOException("Response too large");
                buf.write(chunk, 0, n);
            }
            try {
                return new JSONObject(buf.toString("UTF-8"));
            } catch (org.json.JSONException e) {
                return new JSONObject();
            }
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) { }
    }
}
