package com.manetnode.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Which nodes the app knows (the Wi-Fi node plus saved ones), reading them, and the "Me" settings. */
final class Nodes {
    static final int FE_PORT = 8787, OPENMANET_PORT = 8080;   // Field Extension; openmanetd web UI
    private static final String PREFS = "nodes", KEY = "saved";

    private Nodes() {}

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Wi-Fi node first (it is the DHCP server on a node's own Wi-Fi), then saved nodes. */
    static List<String> hosts(Context ctx) {
        LinkedHashSet<String> h = new LinkedHashSet<>();
        String auto = wifiNode(ctx);
        if (auto != null) h.add(auto);
        h.addAll(saved(ctx));
        return new ArrayList<>(h);
    }

    static List<String> saved(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String s : prefs(ctx).getString(KEY, "").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static void save(Context ctx, List<String> hosts) { prefs(ctx).edit().putString(KEY, String.join("\n", hosts)).apply(); }

    @SuppressWarnings("deprecation")
    static String wifiNode(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null) return null;
        int a = wm.getDhcpInfo().serverAddress;
        if (a == 0) return null;
        String ip = (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
        return ip.startsWith("10.41.") ? ip : null;                // only a mesh node's Wi-Fi counts
    }

    /** A node's /api/field/summary, or null if it does not answer. Call off the UI thread. */
    static JSONObject fetch(String host) { return get(host, "summary"); }

    /** Any read-only /api/field/<name> on a node, or null. Call off the UI thread. */
    static JSONObject get(String host, String name) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + FE_PORT + "/api/field/" + name).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0 && buf.size() < 512 * 1024) buf.write(chunk, 0, n);
                    return new JSONObject(buf.toString("UTF-8"));
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Callsign if the node has one (node dev26+), else its hostname. */
    static String name(JSONObject s, String fallback) {
        JSONObject id = s == null ? null : s.optJSONObject("identity");
        String c = id == null ? "" : id.optString("callsign", "");
        return !c.isEmpty() ? c : s == null ? fallback : s.optString("node", fallback);
    }

    static void addNode(Activity a, Runnable after) {
        EditText in = new EditText(a);
        in.setHint("Node address, e.g. 10.41.0.1");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(a).setTitle("Add a node").setView(in)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> {
                    String host = in.getText().toString().trim().replaceFirst("^https?://", "").replaceFirst("[:/].*$", "");
                    if (host.isEmpty()) return;
                    List<String> s = saved(a);
                    if (!s.contains(host)) s.add(host);
                    save(a, s);
                    if (after != null) after.run();
                }).show();
    }

    static void removeNode(Activity a, String host, String name, Runnable after) {
        new AlertDialog.Builder(a).setMessage("Remove " + name + " (" + host + ") from the list?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    List<String> s = saved(a);
                    s.remove(host);
                    save(a, s);
                    if (after != null) after.run();
                }).show();
    }

    /** Callsign and position sharing for chat and the map (like ATAK's own callsign). */
    static void editMe(Activity a, CotHub hub, Runnable after) {
        Ui u = new Ui(a);
        LinearLayout form = u.column();
        form.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        EditText cs = new EditText(a);
        cs.setHint("Callsign");
        cs.setText(hub.callsign());
        cs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        form.addView(cs);
        CheckBox share = new CheckBox(a);
        share.setText("Share my position with the team");
        share.setChecked(hub.sharing());
        form.addView(share);
        new AlertDialog.Builder(a).setTitle("Me").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String c = cs.getText().toString().trim().replaceAll("[^A-Za-z0-9 ._-]", "");
                    hub.setMe(c.length() > 40 ? c.substring(0, 40) : c, share.isChecked());
                    if (after != null) after.run();
                }).show();
    }

    /** Talk group for PTT: multicast address from the node's comms config, and group 1-5. */
    static void editTalkGroup(Activity a, PttEngine ptt, Runnable after) {
        Ui u = new Ui(a);
        LinearLayout form = u.column();
        form.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        EditText addr = new EditText(a);
        addr.setHint("Multicast address from the node's comms settings");
        addr.setText(ptt.address());
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(addr);
        EditText grp = new EditText(a);
        grp.setHint("Talk group 1-5");
        grp.setText(String.valueOf(ptt.group()));
        grp.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(grp);
        new AlertDialog.Builder(a).setTitle("Talk group").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    int g;
                    try { g = Integer.parseInt(grp.getText().toString().trim()); } catch (NumberFormatException e) { g = 1; }
                    ptt.configure(addr.getText().toString(), Math.max(1, Math.min(5, g)));
                    if (after != null) after.run();
                }).show();
    }
}
