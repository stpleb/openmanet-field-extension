package com.manetnode.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The team picture over the mesh, the way ATAK shares it: positions (CoT on 239.2.3.1:6969) and
 * GeoChat "All Chat Rooms" (224.10.10.1:17012). ATAK users and app users see each other, and the
 * nodes relay both over LoRa when the mesh is down. One per app process.
 */
final class CotHub {
    static final String SA_GROUP = "239.2.3.1", CHAT_GROUP = "224.10.10.1", ALL_CHAT = "All Chat Rooms";
    static final int SA_PORT = 6969, CHAT_PORT = 17012;
    private static final long PLI_MS = 30000, STALE_MS = 10 * 60000;
    private static final int MAX_CHAT = 200;
    private static final String PREFS = "me", K_CALLSIGN = "callsign", K_SHARE = "share", K_UID = "uid";

    static final class Contact {
        String uid, callsign, type, group;
        double lat, lon;
        long seen;
    }

    static final class Chat {
        String fromUid, callsign, text;
        long time;
        boolean mine;
    }

    interface Listener { void changed(); }

    private static CotHub hub;

    static synchronized CotHub get(Context ctx) {
        if (hub == null) hub = new CotHub(ctx.getApplicationContext());
        return hub;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Contact> contacts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Chat> chats = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private WifiManager.MulticastLock lock;
    private boolean started;
    private volatile Location here;

    private final MapItems items;

    private CotHub(Context ctx) {
        this.ctx = ctx;
        loadChats();
        items = new MapItems(ctx, this::sendLater, this::uid, this::callsign);
    }

    /** Points, geofences and breadcrumb trails. */
    MapItems items() { return items; }

    // ------------------------------------------------------------------ me
    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    String uid() {
        String u = prefs().getString(K_UID, null);
        if (u == null) {
            u = "ANDROID-ManetNode-" + UUID.randomUUID().toString().substring(0, 8);
            prefs().edit().putString(K_UID, u).apply();
        }
        return u;
    }

    String callsign() {
        String c = prefs().getString(K_CALLSIGN, "").trim();
        if (!c.isEmpty()) return c;
        String model = Build.MODEL == null ? "Phone" : Build.MODEL.replaceAll("[^A-Za-z0-9 _.-]", "");
        return model.isEmpty() ? "Phone" : model;
    }

    boolean sharing() { return prefs().getBoolean(K_SHARE, true); }

    /** ATAK's team colours and roles, as ATAK users see them on this phone's marker. */
    static final String[] TEAMS = {"White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple", "Dark Blue", "Blue",
            "Cyan", "Teal", "Green", "Dark Green", "Brown"};
    static final int[] TEAM_RGB = {0xFFFFFFFF, 0xFFFFFF00, 0xFFFF7700, 0xFFFF00FF, 0xFFFF0000, 0xFF7F0000, 0xFF7F007F,
            0xFF00007F, 0xFF0000FF, 0xFF00FFFF, 0xFF007F7F, 0xFF00FF00, 0xFF007F00, 0xFFA0714F};
    static final String[] ROLES = {"Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9"};

    String team() { return prefs().getString("team", "Cyan"); }

    String role() { return prefs().getString("role", "Team Member"); }

    void setTeam(String team, String role) {
        prefs().edit().putString("team", team).putString("role", role).apply();
        sendPliSoon();
    }

    void setMe(String callsign, boolean share) {
        prefs().edit().putString(K_CALLSIGN, callsign.trim()).putBoolean(K_SHARE, share).apply();
        sendPliSoon();
    }

    Location here() { return here; }

    // ------------------------------------------------------------------ state
    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() { ui.post(() -> { for (Listener l : listeners) l.changed(); }); }

    List<Contact> contacts() {
        long now = System.currentTimeMillis();
        List<Contact> out = new ArrayList<>();
        synchronized (contacts) {
            for (Contact c : contacts.values()) if (now - c.seen < STALE_MS) out.add(c);
        }
        return out;
    }

    List<Chat> chats() { return new ArrayList<>(chats); }

    // ------------------------------------------------------------------ start
    synchronized void start() {
        if (started) return;
        started = true;
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("manetnode-cot");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        listen(SA_GROUP, SA_PORT);
        listen(CHAT_GROUP, CHAT_PORT);
        listen(MapItems.APP_GROUP, MapItems.APP_PORT);
        startLocation();
        ui.postDelayed(pliTick, 3000);
    }

    @SuppressLint("MissingPermission")
    void startLocation() {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        LocationListener l = loc -> { here = loc; items.track("me", loc.getLatitude(), loc.getLongitude()); changed(); };
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(p)) continue;
                Location last = lm.getLastKnownLocation(p);
                if (last != null && (here == null || last.getTime() > here.getTime())) here = last;
                lm.requestLocationUpdates(p, 5000, 5, l, Looper.getMainLooper());
            } catch (Exception ignored) {
            }
        }
    }

    /** The Wi-Fi interface, so multicast goes to the node's Wi-Fi even with cellular data on. */
    private static NetworkInterface wifi() {
        try {
            for (String n : new String[]{"wlan0", "wlan1"}) {
                NetworkInterface i = NetworkInterface.getByName(n);
                if (i != null && i.isUp()) return i;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void listen(String group, int port) {
        Thread t = new Thread(() -> {
            while (true) {
                try (MulticastSocket s = new MulticastSocket(null)) {
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(port));
                    NetworkInterface ni = wifi();
                    InetSocketAddress g = new InetSocketAddress(InetAddress.getByName(group), port);
                    if (ni != null) s.joinGroup(g, ni);
                    else s.joinGroup(InetAddress.getByName(group));
                    byte[] buf = new byte[64 * 1024];
                    while (true) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }   // Wi-Fi changed: rejoin
                }
            }
        }, "cot-" + port);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ receive
    private void handle(String xml) {
        int start = xml.indexOf("<event");
        if (start < 0) return;
        try {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(new StringReader(xml.substring(start)));
            String uid = null, type = null, callsign = null, group = null, text = null, chatFrom = null, sender = null;
            boolean chat = false;
            double lat = Double.NaN, lon = Double.NaN;
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e != XmlPullParser.START_TAG) continue;
                switch (x.getName()) {
                    case "event":
                        uid = x.getAttributeValue(null, "uid");
                        type = x.getAttributeValue(null, "type");
                        chat = type != null && type.startsWith("b-t-f");
                        break;
                    case "point":
                        lat = num(x.getAttributeValue(null, "lat"));
                        lon = num(x.getAttributeValue(null, "lon"));
                        break;
                    case "contact":
                        callsign = x.getAttributeValue(null, "callsign");
                        break;
                    case "__group":
                        group = x.getAttributeValue(null, "name");
                        break;
                    case "__chat":
                        sender = x.getAttributeValue(null, "senderCallsign");
                        break;
                    case "chatgrp":
                        chatFrom = x.getAttributeValue(null, "uid0");
                        break;
                    case "link":
                        if (chatFrom == null) chatFrom = x.getAttributeValue(null, "uid");
                        break;
                    case "remarks":
                        text = x.nextText();
                        break;
                    default:
                        break;
                }
            }
            if (uid == null) return;
            if (!chat && items.handle(xml)) { changed(); return; }      // a point, geofence or delete
            if (chat) {
                if (text == null || text.trim().isEmpty() || uid().equals(chatFrom)) return;
                Chat c = new Chat();
                c.fromUid = chatFrom == null ? uid : chatFrom;
                c.callsign = sender != null ? sender : callsign != null ? callsign : "Unknown";
                c.text = text.trim();
                c.time = System.currentTimeMillis();
                if (duplicate(c)) return;
                add(c);
            } else if (type != null && type.startsWith("a-") && !uid.equals(uid())
                    && !Double.isNaN(lat) && !Double.isNaN(lon) && !(lat == 0 && lon == 0) && Math.abs(lat) <= 90) {
                Contact c = new Contact();
                c.uid = uid;
                c.type = type;
                c.callsign = callsign != null ? callsign : uid;
                c.group = group;
                c.lat = lat;
                c.lon = lon;
                c.seen = System.currentTimeMillis();
                contacts.put(uid, c);
                items.track(uid, lat, lon);
            } else {
                return;
            }
            changed();
        } catch (Exception ignored) {
        }
    }

    /** The same message can arrive twice (mesh and LoRa relay): ignore a repeat within a minute. */
    private boolean duplicate(Chat n) {
        for (int i = chats.size() - 1; i >= 0 && i >= chats.size() - 20; i--) {
            Chat c = chats.get(i);
            if (c.callsign.equals(n.callsign) && c.text.equals(n.text) && n.time - c.time < 60000) return true;
        }
        return false;
    }

    private void add(Chat c) {
        chats.add(c);
        while (chats.size() > MAX_CHAT) chats.remove(0);
        saveChats();
    }

    /** Chat survives app restarts: the last MAX_CHAT messages are kept in the app's own storage. */
    private void saveChats() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Chat c : chats) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("u", c.fromUid).put("c", c.callsign).put("t", c.text).put("at", c.time).put("m", c.mine);
            } catch (org.json.JSONException ignored) { }
            a.put(o);
        }
        ctx.getSharedPreferences("chat", Context.MODE_PRIVATE).edit().putString("log", a.toString()).apply();
    }

    private void loadChats() {
        try {
            org.json.JSONArray a = new org.json.JSONArray(ctx.getSharedPreferences("chat", Context.MODE_PRIVATE).getString("log", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Chat c = new Chat();
                c.fromUid = o.optString("u");
                c.callsign = o.optString("c");
                c.text = o.optString("t");
                c.time = o.optLong("at");
                c.mine = o.optBoolean("m");
                chats.add(c);
            }
        } catch (Exception ignored) { }
    }

    /** Fleet clear seen on a node: drop chat from before then (ms). */
    void clearChatsBefore(long ms) {
        boolean any = chats.removeIf(c -> c.time <= ms);
        if (any) { saveChats(); changed(); }
    }

    void clearChats() {
        chats.clear();
        saveChats();
        changed();
    }

    private static double num(String s) {
        try { return s == null ? Double.NaN : Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ send
    private final Runnable pliTick = new Runnable() {
        @Override
        public void run() {
            sendPliSoon();
            items.repeat();
            ui.postDelayed(this, PLI_MS);
        }
    };

    private void sendPliSoon() {
        Location l = here;
        if (!sharing() || l == null) return;
        String now = iso(0), stale = iso(2 * PLI_MS + 30000);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + esc(uid()) + "\" type=\"a-f-G-U-C\" time=\"" + now + "\" start=\"" + now
                + "\" stale=\"" + stale + "\" how=\"m-g\">"
                + "<point lat=\"" + l.getLatitude() + "\" lon=\"" + l.getLongitude() + "\" hae=\""
                + (l.hasAltitude() ? l.getAltitude() : 9999999.0) + "\" ce=\"" + (l.hasAccuracy() ? l.getAccuracy() : 9999999.0)
                + "\" le=\"9999999.0\"/>"
                + "<detail><contact callsign=\"" + esc(callsign()) + "\" endpoint=\"*:-1:stcp\"/>"
                + "<__group name=\"" + esc(team()) + "\" role=\"" + esc(role()) + "\"/><precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\"/>"
                + "<takv device=\"" + esc(Build.MODEL) + "\" platform=\"ManetNode\" os=\"" + Build.VERSION.SDK_INT + "\" version=\"2.1\"/>"
                + "</detail></event>";
        send(SA_GROUP, SA_PORT, xml);
    }

    /** All Chat Rooms, as ATAK's GeoChat. Returns false if there is no Wi-Fi to send on. */
    boolean sendChat(String text) {
        text = text.trim();
        if (text.isEmpty()) return false;
        String id = UUID.randomUUID().toString(), now = iso(0), stale = iso(24L * 3600000);
        Location l = here;
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"GeoChat." + esc(uid()) + "." + ALL_CHAT + "." + id + "\" type=\"b-t-f\" time=\""
                + now + "\" start=\"" + now + "\" stale=\"" + stale + "\" how=\"h-g-i-g-o\">"
                + "<point lat=\"" + (l == null ? 0.0 : l.getLatitude()) + "\" lon=\"" + (l == null ? 0.0 : l.getLongitude())
                + "\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                + "<detail><__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"" + id + "\" chatroom=\""
                + ALL_CHAT + "\" id=\"" + ALL_CHAT + "\" senderCallsign=\"" + esc(callsign()) + "\">"
                + "<chatgrp uid0=\"" + esc(uid()) + "\" uid1=\"" + ALL_CHAT + "\" id=\"" + ALL_CHAT + "\"/></__chat>"
                + "<link uid=\"" + esc(uid()) + "\" type=\"a-f-G-U-C\" relation=\"p-p\"/>"
                + "<remarks source=\"BAO.F.ATAK." + esc(uid()) + "\" to=\"" + ALL_CHAT + "\" time=\"" + now + "\">" + esc(text)
                + "</remarks></detail></event>";
        boolean ok = send(CHAT_GROUP, CHAT_PORT, xml);
        Chat c = new Chat();
        c.fromUid = uid();
        c.callsign = callsign();
        c.text = text;
        c.time = System.currentTimeMillis();
        c.mine = true;
        add(c);
        changed();
        return ok;
    }

    /** Fire and forget, for callers on the UI thread. */
    void sendLater(String group, int port, String xml) {
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            try (MulticastSocket s = new MulticastSocket()) {
                NetworkInterface ni = wifi();
                if (ni != null) s.setNetworkInterface(ni);
                s.setTimeToLive(4);
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName(group), port));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private boolean send(String group, int port, String xml) {
        final byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        final boolean[] ok = {false};
        Thread t = new Thread(() -> {
            try (MulticastSocket s = new MulticastSocket()) {
                NetworkInterface ni = wifi();
                if (ni != null) s.setNetworkInterface(ni);
                s.setTimeToLive(4);
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName(group), port));
                ok[0] = true;
            } catch (Exception ignored) {
            }
        });
        t.start();
        try { t.join(3000); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private static String iso(long plusMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(System.currentTimeMillis() + plusMs));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
