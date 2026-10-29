package com.manetnode.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The field app: a status bar, the team map always on top, and a panel below with CHAT, PTT, NODES and
 * SETTINGS (MAP shows the map full height). Nodes open in NodeActivity with the same screens as the plugin.
 */
public class HomeActivity extends Activity implements CotHub.Listener, PttEngine.Listener {
    private static final int[] POLL_S = {5, 10, 30};
    private static final String[] TABS = {"MAP", "CHAT", "PTT", "NODES", "SETTINGS"};
    private static final Cards.Icon[] TAB_ICONS = {Cards.Icon.MAP, Cards.Icon.CHAT, Cards.Icon.MIC, Cards.Icon.MESH, Cards.Icon.GEAR};
    private static final String PREF_TAB = "home_tab";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Map<String, JSONObject> status = new HashMap<>();   // host -> summary (null = not answering)
    private final Map<String, JSONObject> loraStatus = new HashMap<>();   // host -> /api/field/lora
    private final WalkTest walk = new WalkTest();
    private TextView walkInfo;
    private LinearLayout capResults;
    private Button walkBtn, walkClear;
    private final java.util.LinkedHashSet<String> meshHosts = new java.util.LinkedHashSet<>();   // from /api/field/nodes
    private CotHub hub;
    private MapItems items;
    private java.util.List<double[]> drawing;          // geofence corners while drawing an area, else null
    private View drawBar;
    private TextView drawInfo;
    private PttEngine ptt;
    private Ui u;
    private MapView map;
    private View mapFrame, panelFrame;
    private LinearLayout panel;
    private final TextView[] tabViews = new TextView[TABS.length];
    private TextView topNodes, topMode, topLink, topBatt;
    private View topDot, topPill;
    private android.widget.ImageView topNodesIcon;
    private Button shadeBtn;
    private LinearLayout zoomBar;
    private TextView coordBox;
    private CompassTape compass;
    private int tab;
    private boolean centred, resumed;
    // CHAT
    private LinearLayout chatList;
    private ScrollView chatScroll;
    // PTT
    private TextView pttState, pttFoot;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refreshNodes();
            ui.postDelayed(this, walk.recording() ? WalkTest.INTERVAL_MS : pollSeconds() * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        File base = new File(getFilesDir(), "osmdroid");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        u = new Ui(this);
        hub = CotHub.get(this);
        hub.start();
        items = hub.items();
        ptt = PttEngine.get(this);
        ptt.start();
        FieldPanel.savedLogin = () -> Secret.load(this);                 // password once for every node
        FieldPanel.rememberLogin = (user, pass) -> Secret.save(this, user, pass);
        FieldPanel.forgetLogin = () -> Secret.clear(this);
        FieldPanel.scanner = cb -> ScanActivity.scan(this, cb);      // "Scan QR" in Join another mesh
        FieldPanel.here = () -> {
            Location l = hub.here();
            return l == null ? null : new double[]{l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : Double.NaN};
        };

        LinearLayout root = u.column();
        root.setBackgroundColor(Ui.BG);

        // ---- top status bar: node count | status pill | node + battery, evenly balanced
        LinearLayout top = u.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(12));
        LinearLayout left = u.row();
        left.setGravity(Gravity.CENTER_VERTICAL);
        topNodesIcon = new android.widget.ImageView(this);
        left.addView(topNodesIcon, new LinearLayout.LayoutParams(u.dp(18), u.dp(18)));
        topNodes = u.text("0 Nodes", 14, Ui.OK, false, true);
        topNodes.setMaxLines(1);
        topNodes.setPadding(u.dp(6), 0, 0, 0);
        left.addView(topNodes);
        top.addView(left, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout pill = u.row();
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(u.dp(10), u.dp(7), u.dp(10), u.dp(7));
        topDot = u.dot(Ui.WARN, 8);
        ((LinearLayout.LayoutParams) topDot.getLayoutParams()).rightMargin = u.dp(8);
        pill.addView(topDot);
        topMode = u.text("STANDALONE", 13, Ui.WARN, true, false);
        topMode.setLetterSpacing(0.08f);
        topMode.setMaxLines(1);
        topMode.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        topMode.setIncludeFontPadding(false);
        topMode.setGravity(Gravity.CENTER);
        pill.addView(topMode, new LinearLayout.LayoutParams(0, -2, 1));      // fixed width so the text can shrink to fit
        topPill = pill;
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1);
        pp.leftMargin = u.dp(10);
        pp.rightMargin = u.dp(10);
        top.addView(pill, pp);
        LinearLayout right = u.column();
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topLink = u.text("", 14, Ui.CYAN, true, false);
        topLink.setMaxLines(1);
        topLink.setGravity(Gravity.END);
        right.addView(topLink, new LinearLayout.LayoutParams(-1, -2));
        topBatt = u.text("", 12, Ui.SUB, false, true);
        topBatt.setVisibility(View.GONE);
        topBatt.setGravity(Gravity.END);
        right.addView(topBatt, new LinearLayout.LayoutParams(-1, -2));
        top.addView(right, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(-1, u.dp(1)));

        // ---- map (always there) + panel
        LinearLayout body = u.column();
        FrameLayout mf = new FrameLayout(this);
        map = new MapView(this, new FieldTiles(this));
        map.setTileSource(MapStyles.source(mapStyle()));
        map.getOverlayManager().getTilesOverlay().setColorFilter(MapStyles.filter(mapStyle(), mapDark()));
        map.setMultiTouchControls(true);
        map.getController().setZoom(16.0);
        mf.addView(map, new FrameLayout.LayoutParams(-1, -1));
        // Crosshair in the middle and a coordinate box (lat/long + MGRS of the crosshair), like ATAK.
        View cross = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, gap = u.dp(5), arm = u.dp(14);
                for (int pass = 0; pass < 2; pass++) {                 // dark outline, then cyan
                    p.setColor(pass == 0 ? 0xAA000000 : Ui.CYAN);
                    p.setStrokeWidth(u.dp(pass == 0 ? 4 : 2));
                    c.drawLine(cx - gap - arm, cy, cx - gap, cy, p);
                    c.drawLine(cx + gap, cy, cx + gap + arm, cy, p);
                    c.drawLine(cx, cy - gap - arm, cx, cy - gap, p);
                    c.drawLine(cx, cy + gap, cx, cy + gap + arm, p);
                }
                p.setColor(Ui.CYAN);
                c.drawCircle(cx, cy, u.dp(1.5f), p);
            }
        };
        mf.addView(cross, new FrameLayout.LayoutParams(u.dp(48), u.dp(48), Gravity.CENTER));
        coordBox = u.text("", 10, Ui.TEXT, false, true);
        coordBox.setPadding(u.dp(8), u.dp(4), u.dp(8), u.dp(4));
        coordBox.setLineSpacing(0, 1.05f);
        android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
        cb.setCornerRadius(u.dp(8));
        cb.setColor(0xD907100F);
        cb.setStroke(u.dp(1), 0x6600E5FF);
        coordBox.setBackground(cb);
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cbp.setMargins(u.dp(10), 0, 0, u.dp(14));
        mf.addView(coordBox, cbp);
        // Zoom buttons in the theme (replacing osmdroid's grey ones), stacked on the right.
        map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        LinearLayout zoom = u.column();
        zoomBar = zoom;
        for (String z : new String[]{"+", "−"}) {
            Button zb = u.button(z, false, false);
            zb.setTextSize(22);
            zb.setPadding(0, 0, 0, 0);
            zb.setMinWidth(0);
            zb.setMinimumWidth(0);
            zb.setMinHeight(0);
            zb.setMinimumHeight(0);
            zb.setBackground(u.circle(0xE60A2A2A));
            zb.setOnClickListener(v -> { if (z.equals("+")) map.getController().zoomIn(); else map.getController().zoomOut(); });
            zoom.addView(zb, new LinearLayout.LayoutParams(u.dp(46), u.dp(46)));
        }
        mf.addView(zoom, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM));
        placeZoom(true);
        map.addMapListener(new org.osmdroid.events.MapListener() {
            @Override public boolean onScroll(org.osmdroid.events.ScrollEvent e) { updateCoords(); return false; }
            @Override public boolean onZoom(org.osmdroid.events.ZoomEvent e) { updateCoords(); return false; }
        });
        compass = new CompassTape(this);
        mf.addView(compass, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        Button centre = u.button("◎", false, false);
        centre.setTextSize(20);
        centre.setBackground(u.circle(0xE60A2A2A));
        centre.setOnClickListener(v -> { centred = false; changed(); });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(u.dp(52), u.dp(52), Gravity.BOTTOM | Gravity.END);
        cp.setMargins(0, 0, u.dp(14), u.dp(14));
        mf.addView(centre, cp);
        Button layers = u.button(MapStyles.NAMES[mapStyle()], true, false);     // cycles Street / Satellite / Hybrid / Topo
        chip(layers);
        layers.setOnClickListener(v -> {
            int next = (mapStyle() + 1) % MapStyles.NAMES.length;
            setMapStyle(next);
            layers.setText(MapStyles.NAMES[next]);
        });
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(-2, u.dp(34), Gravity.TOP | Gravity.END);
        lp2.setMargins(0, u.dp(66), u.dp(10), 0);      // below the compass tape
        mf.addView(layers, lp2);
        Button shade = u.button(mapDark() ? "Dark" : "Light", true, false);      // street / topo light or dark
        chip(shade);
        shade.setOnClickListener(v -> {
            boolean dark = !mapDark();
            getPreferences(MODE_PRIVATE).edit().putBoolean("map_dark", dark).apply();
            shade.setText(dark ? "Dark" : "Light");
            setMapStyle(mapStyle());
        });
        FrameLayout.LayoutParams lp3 = new FrameLayout.LayoutParams(-2, u.dp(34), Gravity.TOP | Gravity.START);
        lp3.setMargins(u.dp(10), u.dp(66), 0, 0);
        mf.addView(shade, lp3);
        shadeBtn = shade;
        shade.setVisibility(MapStyles.canDarken(mapStyle()) ? View.VISIBLE : View.GONE);
        // Map tools: points, geofences, breadcrumbs. Long-press the map also drops a point.
        Button tools = u.button("", false, false);
        tools.setBackground(u.circle(0xE60A2A2A));
        Drawable plus = Cards.icon(this, Cards.Icon.PLUS, 22, Ui.CYAN);
        plus.setBounds(0, 0, plus.getIntrinsicWidth(), plus.getIntrinsicHeight());
        tools.setCompoundDrawables(plus, null, null, null);
        tools.setPadding(u.dp(12), 0, 0, 0);
        tools.setContentDescription("Map tools");
        tools.setOnClickListener(v -> mapTools());
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(u.dp(46), u.dp(46), Gravity.TOP | Gravity.START);
        tp.setMargins(u.dp(10), u.dp(110), 0, 0);
        mf.addView(tools, tp);
        LinearLayout db = u.row();
        db.setGravity(Gravity.CENTER_VERTICAL);
        db.setPadding(u.dp(12), u.dp(8), u.dp(8), u.dp(8));
        db.setBackground(u.box(0xF207100F, Ui.CYAN));
        drawInfo = u.text("", 13, Ui.TEXT, false, false);
        db.addView(drawInfo, new LinearLayout.LayoutParams(0, -2, 1));
        for (String lbl : new String[]{"Undo", "Cancel", "Done"}) {
            Button bt = u.button(lbl, true, lbl.equals("Cancel"));
            bt.setTextSize(12);
            bt.setMinWidth(0);
            bt.setMinimumWidth(0);
            bt.setPadding(u.dp(10), 0, u.dp(10), 0);
            bt.setOnClickListener(v -> drawAction(lbl));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, u.dp(38));
            bp.leftMargin = u.dp(6);
            db.addView(bt, bp);
        }
        drawBar = db;
        db.setVisibility(View.GONE);
        FrameLayout.LayoutParams dbp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        dbp.setMargins(u.dp(10), u.dp(110), u.dp(10), 0);
        mf.addView(db, dbp);
        mapFrame = mf;
        body.addView(mf, new LinearLayout.LayoutParams(-1, 0, 1));

        panel = u.column();
        FrameLayout pf = new FrameLayout(this);
        pf.setBackground(new TopoBackground(Ui.PANEL, Ui.CYAN, getResources().getDisplayMetrics().density));
        pf.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        panelFrame = pf;
        View pl = new View(this);
        pl.setBackgroundColor(Ui.LINE);
        body.addView(pl, new LinearLayout.LayoutParams(-1, u.dp(1)));
        body.addView(pf, new LinearLayout.LayoutParams(-1, 0, 1.4f));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- bottom tabs
        View bl = new View(this);
        bl.setBackgroundColor(Ui.LINE);
        root.addView(bl, new LinearLayout.LayoutParams(-1, u.dp(1)));
        LinearLayout nav = u.row();
        nav.setBackgroundColor(0xFF040A0A);
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            TextView tv = u.text(TABS[i], 11, Ui.SUB, true, false);
            tv.setLetterSpacing(0.08f);
            tv.setMaxLines(1);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, u.dp(10), 0, u.dp(10));
            tv.setCompoundDrawablePadding(u.dp(3));
            tv.setOnClickListener(v -> select(t));
            tabViews[i] = tv;
            nav.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(nav);
        setContentView(root);

        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String p : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.RECORD_AUDIO})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);

        select(getPreferences(MODE_PRIVATE).getInt(PREF_TAB, 0));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) { hub.startLocation(); }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        map.onResume();
        if (compass != null) compass.start();
        updateCoords();
        hub.startLocation();
        hub.addListener(this);
        ptt.addListener(this);
        ui.removeCallbacks(tick);
        ui.post(tick);
        changed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        map.onPause();
        if (compass != null) compass.stop();
        hub.removeListener(this);
        ptt.removeListener(this);
        if (ptt.state() == PttEngine.State.TALKING) ptt.released();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ================================================================ tabs
    private void select(int t) {
        tab = t;
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_TAB, t).apply();
        for (int i = 0; i < TABS.length; i++) {
            tabViews[i].setTextColor(i == t ? Ui.CYAN : Ui.SUB);
            android.graphics.drawable.Drawable ic = Cards.icon(this, TAB_ICONS[i], 24, i == t ? Ui.CYAN : Ui.SUB);
            ic.setBounds(0, 0, ic.getIntrinsicWidth(), ic.getIntrinsicHeight());
            tabViews[i].setCompoundDrawables(null, ic, null, null);
            tabViews[i].setBackgroundColor(i == t ? 0x1400E5FF : 0);
        }
        boolean mapOnly = t == 0, panelOnly = t == 4;             // SETTINGS uses the whole screen
        panelFrame.setVisibility(mapOnly ? View.GONE : View.VISIBLE);
        mapFrame.setVisibility(panelOnly ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) mapFrame.getLayoutParams()).weight = 1;
        panel.removeAllViews();
        chatList = null;
        pttState = null;
        topo = null;
        if (settingsPanel != null) { settingsPanel.dispose(); settingsPanel = null; }
        switch (t) {
            case 1: buildChat(); break;
            case 2: buildPtt(); break;
            case 3: buildNodes(); break;
            case 4: buildSettings(); break;
            default: break;
        }
        placeZoom(mapOnly);
        mapFrame.requestLayout();
        changed();
    }

    /** Full map: + and - stacked above the re-centre button. Short map (chat, PTT, nodes): in a row beside it,
     *  so neither hides under the map-style chips. */
    private void placeZoom(boolean tall) {
        if (zoomBar == null) return;
        zoomBar.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < zoomBar.getChildCount(); i++) {
            LinearLayout.LayoutParams zp = (LinearLayout.LayoutParams) zoomBar.getChildAt(i).getLayoutParams();
            zp.bottomMargin = tall ? u.dp(8) : 0;
            zp.rightMargin = tall ? 0 : u.dp(8);
        }
        FrameLayout.LayoutParams zlp = (FrameLayout.LayoutParams) zoomBar.getLayoutParams();
        if (tall) zlp.setMargins(0, 0, u.dp(17), u.dp(78));
        else zlp.setMargins(0, 0, u.dp(14 + 52 + 2), u.dp(17));
        zoomBar.setLayoutParams(zlp);
    }

    @Override
    public void changed() {
        checkFences();
        drawMap();
        renderTop();
        if (chatList != null) renderChat();
        if (pttState != null) renderPtt();
        if (tab == 4 && settingsMenuNoNode && wifiSummary() != null) select(4);   // node found after the menu was built
    }

    private boolean settingsMenuNoNode;

    // ================================================================ status bar
    private JSONObject wifiSummary() {
        String w = Nodes.wifiNode(this);
        return w == null ? null : status.get(w);
    }

    private void renderTop() {
        JSONObject s = wifiSummary();
        JSONObject mesh = s == null ? null : s.optJSONObject("mesh");
        int nodes = mesh == null || mesh.isNull("nodes") ? 0 : mesh.optInt("nodes");
        int nc = nodes > 1 ? Ui.OK : Ui.SUB;
        topNodes.setText(nodes + (nodes == 1 ? " Node" : " Nodes"));
        topNodes.setTextColor(nc);
        topNodesIcon.setImageDrawable(Cards.icon(this, Cards.Icon.MESH, 18, nc));
        String mode;
        int colour;
        JSONObject led = s == null ? null : s.optJSONObject("led");
        String pattern = led == null ? "" : led.optString("pattern", "");
        if (s == null) { mode = "STANDALONE"; colour = Ui.WARN; }
        else if (pattern.isEmpty()) { mode = "NODE"; colour = Ui.CYAN; }
        else {                                       // what the node's light says, in the light's colour
            mode = FieldPanel.ledMeaning(led).toUpperCase(Locale.US);
            colour = Ui.ledColour(led.optString("color", null));
        }
        topMode.setText(mode);
        topMode.setTextColor(colour);
        topDot.setBackground(u.circle(colour));
        android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
        pg.setCornerRadius(u.dp(18));
        pg.setColor((colour & 0x00FFFFFF) | 0x1F000000);
        pg.setStroke(u.dp(1), (colour & 0x00FFFFFF) | 0x88000000);
        topPill.setBackground(pg);
        JSONObject pw = s == null ? null : s.optJSONObject("power");
        String batt = "";
        int bc = Ui.SUB;
        if (pw != null && pw.optBoolean("available") && pw.has("battery_percent") && !pw.isNull("battery_percent")) {
            int pc = pw.optInt("battery_percent");
            batt = (pw.optBoolean("external_power") ? "⚡ " : "") + pc + "%";
            bc = pc <= 10 ? Ui.BAD : pc <= 25 ? Ui.WARN : Ui.OK;
        }
        if (s == null) {
            topLink.setText("No node");
            topLink.setTextColor(Ui.WARN);
        } else {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(Nodes.name(s, "node"));
            if (!batt.isEmpty()) {
                int st = sb.length();
                sb.append(" - ").append(batt);
                sb.setSpan(new android.text.style.ForegroundColorSpan(bc), st, sb.length(), 0);
            }
            topLink.setText(sb);
            topLink.setTextColor(Ui.CYAN);
        }
    }

    /** Node Wi-Fi often has no internet; keep the app's node traffic on it (tiles go over cellular). */
    private void pinToNodeWifi() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        android.net.Network wifi = null;
        if (Nodes.wifiNode(this) != null)
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = n;
            }
        cm.bindProcessToNetwork(wifi);                     // null = back to the system default
    }

    private List<String> allHosts() {
        java.util.LinkedHashSet<String> h = new java.util.LinkedHashSet<>(Nodes.hosts(this));
        h.addAll(meshHosts);
        return new java.util.ArrayList<>(h);
    }

    private void refreshNodes() {
        pinToNodeWifi();
        String w = Nodes.wifiNode(this);
        if (w != null) io.execute(() -> {
            JSONObject list = Nodes.get(w, "nodes");
            JSONArray arr = list == null ? null : list.optJSONArray("nodes");
            ui.post(() -> {
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject n = arr.optJSONObject(i);
                    String ip = n == null ? "" : n.optString("ip");
                    if (n != null && n.has("field_extension") && !ip.isEmpty() && meshHosts.add(ip)) fetchHost(ip);   // new node: read it now
                }
            });
        });
        if (settingsPanel != null) settingsPanel.refresh();
        for (String host : allHosts()) fetchHost(host);
    }

    private void fetchHost(String host) {
        {
            io.execute(() -> {
                JSONObject s = Nodes.fetch(host);
                JSONObject lr = s == null ? null : Nodes.get(host, "lora");
                if (s != null && host.equals(Nodes.wifiNode(this))) { givePosition(host, s); fleetClearSeen(s); }
                ui.post(() -> {
                    status.put(host, s);
                    if (lr != null) loraStatus.put(host, lr); else loraStatus.remove(host);
                    changed();
                    if (tab == 3 && topo != null) topo.set(allHosts(), status, Nodes.wifiNode(this), loraStatus);
                });
            });
        }
    }

    // ================================================================ map
    private static GeoPoint nodePoint(JSONObject s) {
        for (String k : new String[]{"gps", "position"}) {
            JSONObject p = s.optJSONObject(k);
            if (p == null) continue;
            double la = p.optDouble("lat", p.optDouble("latitude", Double.NaN));
            double lo = p.optDouble("lon", p.optDouble("longitude", Double.NaN));
            if (!Double.isNaN(la) && !Double.isNaN(lo) && !(la == 0 && lo == 0) && Math.abs(la) <= 90) return new GeoPoint(la, lo);
        }
        return null;
    }

    private int pollSeconds() { return getPreferences(MODE_PRIVATE).getInt("poll_s", 10); }

    /** Small rounded map control. */
    private void chip(Button b) {
        b.setTextSize(11);
        b.setLetterSpacing(0.1f);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(u.dp(72));
        b.setMinimumWidth(u.dp(72));
        b.setPadding(u.dp(14), 0, u.dp(14), 0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(u.dp(17));
        g.setColor(0xD907100F);
        g.setStroke(u.dp(1), 0x6600E5FF);
        b.setBackground(g);
    }

    /** Lat/long and MGRS of the map centre (the crosshair). */
    private void updateCoords() {
        if (coordBox == null || map == null) return;
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        GeoPoint here = new GeoPoint(c.getLatitude(), c.getLongitude());
        if (addrAt != null && here.distanceToAsDouble(addrAt) > 100) addrLine = null;      // moved away: don't show a stale address
        coordBox.setText((addrLine != null && showAddr() ? addrLine + "\n" : "") + String.format(Locale.US, "%.5f, %.5f%n%s",
                c.getLatitude(), c.getLongitude(), Mgrs.of(c.getLatitude(), c.getLongitude())));
        ui.removeCallbacks(addrLookup);
        ui.postDelayed(addrLookup, 900);                         // once the map stops moving
    }

    private String addrLine;
    private GeoPoint addrAt, addrAsked;
    private boolean showAddr() { return getPreferences(MODE_PRIVATE).getBoolean("show_addr", true); }

    /** Street address of the map centre, from the phone's address lookup (needs internet); hidden when there is none. */
    private final Runnable addrLookup = () -> {
        if (map == null || !showAddr() || !android.location.Geocoder.isPresent()) return;
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        GeoPoint at = new GeoPoint(c.getLatitude(), c.getLongitude());
        if (addrAsked != null && at.distanceToAsDouble(addrAsked) < 25) return;
        addrAsked = at;
        io.execute(() -> {
            String line = null;
            try {
                @SuppressWarnings("deprecation")
                java.util.List<android.location.Address> r = new android.location.Geocoder(this, Locale.getDefault())
                        .getFromLocation(at.getLatitude(), at.getLongitude(), 1);
                if (r != null && !r.isEmpty()) line = shortAddress(r.get(0));
            } catch (Exception ignored) { }                       // offline or no lookup service
            String l = line;
            ui.post(() -> {
                if (addrAsked != at) return;                         // a newer lookup is on its way
                addrLine = l;
                addrAt = at;
                updateCoordsText();
            });
        });
    };

    private void updateCoordsText() {
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        coordBox.setText((addrLine != null && showAddr() ? addrLine + "\n" : "") + String.format(Locale.US, "%.5f, %.5f%n%s",
                c.getLatitude(), c.getLongitude(), Mgrs.of(c.getLatitude(), c.getLongitude())));
    }

    /** "123 Main St, Springfield": number, street and town; whatever the lookup has, kept short for the box. */
    private static String shortAddress(android.location.Address a) {
        StringBuilder b = new StringBuilder();
        if (a.getThoroughfare() != null) {
            if (a.getSubThoroughfare() != null) b.append(a.getSubThoroughfare()).append(' ');
            b.append(a.getThoroughfare());
        }
        if (a.getLocality() != null) b.append(b.length() > 0 ? ", " : "").append(a.getLocality());
        if (b.length() == 0 && a.getAddressLine(0) != null) b.append(a.getAddressLine(0).replaceAll(",\\s*(USA|United States)$", ""));
        String out = b.toString().trim();
        if (out.isEmpty()) return null;
        return out.length() > 34 ? out.substring(0, 33) + "…" : out;
    }

    private boolean mapDark() { return getPreferences(MODE_PRIVATE).getBoolean("map_dark", true); }

    private int mapStyle() { return getPreferences(MODE_PRIVATE).getInt("map_style3", 0); }

    private void setMapStyle(int style) {
        getPreferences(MODE_PRIVATE).edit().putInt("map_style3", style).apply();
        map.setTileSource(MapStyles.source(style));
        map.getOverlayManager().getTilesOverlay().setColorFilter(MapStyles.filter(style, mapDark()));
        if (shadeBtn != null) shadeBtn.setVisibility(MapStyles.canDarken(style) ? View.VISIBLE : View.GONE);   // Street / Topo only
        map.invalidate();
    }

    private boolean mapLinks() { return getPreferences(MODE_PRIVATE).getBoolean("map_links", true); }

    /** Lines between placed nodes for each mesh link, matched by the neighbour's radio MAC (node dev40+). */
    /** Where each node goes on the map: its GPS / set position; the node this phone is on without one sits at the
     *  phone ("near you"); other nodes without one sit on a small ring around it, faded ("position unknown"). */
    private final java.util.Set<String> approxNodes = new java.util.HashSet<>();

    private Map<String, GeoPoint> nodePositions() {
        Map<String, GeoPoint> out = new java.util.LinkedHashMap<>();
        approxNodes.clear();
        String wifi = Nodes.wifiNode(this);
        Location here = hub.here();
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (Map.Entry<String, JSONObject> e : new HashMap<>(status).entrySet()) {
            if (e.getValue() == null) continue;
            GeoPoint p = nodePoint(e.getValue());
            if (p != null) out.put(e.getKey(), p);
            else if (e.getKey().equals(wifi) && here != null) { out.put(e.getKey(), new GeoPoint(here.getLatitude(), here.getLongitude())); approxNodes.add(e.getKey()); }
            else unknown.add(e.getKey());
        }
        GeoPoint anchor = wifi != null ? out.get(wifi) : null;
        if (anchor == null && here != null) anchor = new GeoPoint(here.getLatitude(), here.getLongitude());
        if (anchor != null) {
            java.util.Collections.sort(unknown);
            for (int i = 0; i < unknown.size(); i++) {
                double a = 2 * Math.PI * (i + 0.25) / Math.max(3, unknown.size());
                double dLat = 0.0013 * Math.cos(a), dLon = 0.0013 * Math.sin(a) / Math.cos(Math.toRadians(anchor.getLatitude()));
                out.put(unknown.get(i), new GeoPoint(anchor.getLatitude() + dLat, anchor.getLongitude() + dLon));   // ~145 m away
                approxNodes.add(unknown.get(i));
            }
        }
        return out;
    }

    private boolean givePos() { return getPreferences(MODE_PRIVATE).getBoolean("give_pos", true); }

    private long lastGiven;
    private Location lastGivenAt;

    /** The node this phone is on has no GPS fix: give it the phone's position (every 60 s, sooner after 25 m), so it
     *  shows in the right place for everyone. Uses the saved node password. Call off the UI thread. */
    private void givePosition(String host, JSONObject s) {
        String src = s.optString("position_source", givePos() ? "auto" : "node");
        if ("node".equals(src)) return;
        JSONObject gps = s.optJSONObject("gps");
        if (!"phone".equals(src) && gps != null && "fix".equalsIgnoreCase(gps.optString("state"))) return;   // the node knows where it is
        Location here = hub.here();
        if (here == null || (here.hasAccuracy() && here.getAccuracy() > 60)) return;
        long now = System.currentTimeMillis();
        boolean moved = lastGivenAt == null || here.distanceTo(lastGivenAt) > 25;
        if (!moved && now - lastGiven < 60000) return;
        try {
            FieldClient fc = new FieldClient("http://" + host + ":" + Nodes.FE_PORT, getSharedPreferences("node", MODE_PRIVATE));
            if (!fc.signedIn()) {
                String[] login = Secret.load(this);
                if (login == null || !fc.login(login[0], login[1]).ok()) return;
            }
            double alt = here.hasAltitude() ? here.getAltitude() : Double.NaN;
            FieldClient.Result r = fc.setPhonePosition(here.getLatitude(), here.getLongitude(), alt);
            if (r.code == 401) {
                String[] login = Secret.load(this);
                if (login != null && fc.login(login[0], login[1]).ok())
                    r = fc.setPhonePosition(here.getLatitude(), here.getLongitude(), alt);
            }
            if (r.ok()) { lastGiven = now; lastGivenAt = here; }
        } catch (Exception ignored) { }
    }

    /** A link takes the status-light colour of the node it leads to (away from the node this phone is on). */
    private int linkColour(String a, String b, int fallback) {
        String wifi = Nodes.wifiNode(this);
        String far = b.equals(wifi) ? a : b;
        JSONObject s = status.get(far), led = s == null ? null : s.optJSONObject("led");
        int c = led == null ? Ui.MUTED : Ui.ledColour(led.optString("color", null));
        return c == Ui.MUTED ? fallback : c;
    }

    private void drawLinks() {
        Map<String, String> macHost = new HashMap<>();
        Map<String, GeoPoint> where = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : new HashMap<>(status).entrySet()) {
            JSONObject s = e.getValue();
            if (s == null) continue;
            JSONObject mesh = s.optJSONObject("mesh");
            JSONArray ifs = mesh == null ? null : mesh.optJSONArray("interfaces");
            for (int i = 0; ifs != null && i < ifs.length(); i++) {
                JSONObject f = ifs.optJSONObject(i);
                if (f != null) macHost.put(f.optString("mac").toLowerCase(Locale.US), e.getKey());
            }
        }
        where.putAll(nodePositions());
        java.util.Set<String> drawn = new java.util.HashSet<>(), meshPairs = new java.util.HashSet<>();
        for (Map.Entry<String, GeoPoint> a : where.entrySet()) {
            JSONObject mesh = status.get(a.getKey()).optJSONObject("mesh");
            JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
            for (int i = 0; nb != null && i < nb.length(); i++) {
                JSONObject n = nb.optJSONObject(i);
                String b = n == null ? null : macHost.get(n.optString("mac").toLowerCase(Locale.US));
                GeoPoint pb = b == null ? null : where.get(b);
                if (pb == null || b.equals(a.getKey())) continue;
                String tr = n.optString("transport", "Wi-Fi");
                String key = (a.getKey().compareTo(b) < 0 ? a.getKey() + b : b + a.getKey()) + tr;
                if (!drawn.add(key)) continue;
                org.osmdroid.views.overlay.Polyline line = new org.osmdroid.views.overlay.Polyline(map);
                java.util.List<GeoPoint> pts = new java.util.ArrayList<>();
                pts.add(a.getValue());
                pts.add(pb);
                line.setPoints(pts);
                line.getOutlinePaint().setColor(linkColour(a.getKey(), b, "HaLow".equals(tr) ? TopologyView.HALOW : TopologyView.WIFI));
                meshPairs.add(a.getKey().compareTo(b) < 0 ? a.getKey() + "|" + b : b + "|" + a.getKey());
                line.getOutlinePaint().setStrokeWidth(u.dp("HaLow".equals(tr) ? 4 : 3));
                line.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
                if (!"HaLow".equals(tr)) line.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{u.dp(10), u.dp(6)}, 0));
                double mbps = n.optDouble("throughput_mbps", Double.NaN);
                line.setTitle(("HaLow".equals(tr) ? "HaLow" : "2.4 GHz") + (Double.isNaN(mbps) ? "" : String.format(Locale.US, " · %.0f Mb/s", mbps)));
                map.getOverlays().add(line);
            }
        }
        // LoRa-only: nodes whose radios heard each other in the last 15 min, with no mesh link between them.
        Map<String, String> radioHost = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : loraStatus.entrySet()) {
            JSONObject r = e.getValue().optJSONObject("radio");
            if (r != null && !r.optString("node_id").isEmpty()) radioHost.put(r.optString("node_id"), e.getKey());
        }
        long now = System.currentTimeMillis() / 1000;
        for (Map.Entry<String, JSONObject> e : loraStatus.entrySet()) {
            JSONObject r = e.getValue().optJSONObject("radio");
            JSONArray heard = r == null ? null : r.optJSONArray("heard");
            for (int i = 0; heard != null && i < heard.length(); i++) {
                JSONObject h = heard.optJSONObject(i);
                String b = h == null ? null : radioHost.get(h.optString("id"));
                if (b == null || b.equals(e.getKey()) || now - h.optLong("last_heard") > 900) continue;
                String pair = e.getKey().compareTo(b) < 0 ? e.getKey() + "|" + b : b + "|" + e.getKey();
                GeoPoint pa = where.get(e.getKey()), pb = where.get(b);
                if (pa == null || pb == null || meshPairs.contains(pair) || !drawn.add(pair + "LoRa")) continue;
                org.osmdroid.views.overlay.Polyline line = new org.osmdroid.views.overlay.Polyline(map);
                java.util.List<GeoPoint> pts = new java.util.ArrayList<>();
                pts.add(pa);
                pts.add(pb);
                line.setPoints(pts);
                line.getOutlinePaint().setColor(linkColour(e.getKey(), b, TopologyView.LORA));
                line.getOutlinePaint().setStrokeWidth(u.dp(3));
                line.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{u.dp(2), u.dp(6)}, 0));
                line.setTitle("LoRa only · SNR " + h.optDouble("snr"));
                map.getOverlays().add(line);
            }
        }
    }

    private void drawMap() {
        if (map == null) return;
        map.getOverlays().clear();
        map.getOverlays().add(new org.osmdroid.views.overlay.MapEventsOverlay(mapEvents));   // first, so markers get taps before it
        drawFences();
        if (items.trailsOn()) drawTrails();
        if (mapLinks()) drawLinks();                         // under the markers
        drawWalk();
        drawPoints();
        Location here = hub.here();
        if (here != null) {
            GeoPoint p = new GeoPoint(here.getLatitude(), here.getLongitude());
            mark(p, "You (" + hub.callsign() + ")", hub.sharing() ? "Sharing your position" : "Not sharing your position", pin(0xFF4D8BFF, false));
            if (!centred) { map.getController().setCenter(p); centred = true; }
        }
        for (CotHub.Contact c : hub.contacts())
            mark(new GeoPoint(c.lat, c.lon), c.callsign, c.group == null ? c.type : c.group + " · " + c.type, pin(Ui.CYAN, false));
        String wifi = Nodes.wifiNode(this);
        for (Map.Entry<String, GeoPoint> e : nodePositions().entrySet()) {
            JSONObject s = status.get(e.getKey());
            if (s == null) continue;
            JSONObject led = s.optJSONObject("led");
            boolean approx = approxNodes.contains(e.getKey());
            boolean mine = e.getKey().equals(wifi);
            String note = approx ? "Node " + e.getKey() + " · No GPS Fix" : "Node " + e.getKey();
            String help = !approx ? null : mine
                    ? "This node has no GPS fix, so it is shown where your phone is.\n\nWhile \"Give my position to the node when it has no GPS\" is on (Settings), this phone sends the node its position about every minute so other phones see it in the right place."
                    : "This node has no GPS fix, so it is drawn on a small ring near you, not where it really is.\n\nTo place it, do one of these:\n• Connect this phone to that node's Wi-Fi. The app then gives the node your position automatically.\n• Or stand next to it, connect to it and use Settings → Node Settings → Node position.\n• Or connect a GPS to the node.";
            Drawable icon = pin(Ui.ledColour(led == null ? null : led.optString("color", null)), true);
            if (approx && !e.getKey().equals(wifi)) icon.setAlpha(150);
            mark(e.getValue(), Nodes.name(s, e.getKey()) + (approx ? (mine ? " (near you)" : " (position unknown)") : ""), note, icon)
                    .setRelatedObject(help);
        }
        map.invalidate();
    }

    private Marker mark(GeoPoint p, String title, String snippet, Drawable icon) {
        Marker m = new Marker(map);
        m.setPosition(p);
        m.setTitle(title);
        m.setSnippet(snippet);
        m.setIcon(icon);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        m.setInfoWindow(new Bubble());
        map.getOverlays().add(m);
        return m;
    }

    /** Themed marker pop-up; a marker whose related object is a String gets a "!" badge that opens it as help. */
    private final class Bubble extends org.osmdroid.views.overlay.infowindow.MarkerInfoWindow {
        Bubble() { super(R.layout.fe_bubble, map); }

        @Override
        public void onOpen(Object item) {
            super.onOpen(item);
            View badge = mView.findViewById(R.id.bubble_help);
            Object help = ((Marker) item).getRelatedObject();
            badge.setVisibility(help instanceof String ? View.VISIBLE : View.GONE);
            badge.setOnClickListener(help instanceof String ? v -> new android.app.AlertDialog.Builder(HomeActivity.this)
                    .setTitle(((Marker) item).getTitle()).setMessage((String) help)
                    .setPositiveButton("OK", null).show() : null);
        }
    }

    /** Dot for people, ringed circle for nodes, outlined so it shows on any map. */
    private Drawable pin(int colour, boolean node) {
        int s = u.dp(node ? 26 : 20);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(colour);
        edge.setColor(0xFF05080A);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(u.dp(2));
        if (node) {                                          // node: filled circle with a dark ring and an outer coloured ring
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(u.dp(2));
            ring.setColor(colour);
            c.drawCircle(s / 2f, s / 2f, s / 2f - u.dp(1.5f), ring);
            c.drawCircle(s / 2f, s / 2f, s / 2f - u.dp(6), fill);
            c.drawCircle(s / 2f, s / 2f, s / 2f - u.dp(6), edge);
        } else {
            c.drawCircle(s / 2f, s / 2f, s / 2f - u.dp(2), fill);
            c.drawCircle(s / 2f, s / 2f, s / 2f - u.dp(2), edge);
        }
        return new BitmapDrawable(getResources(), b);
    }

    // ================================================================ CHAT
    private void buildChat() {
        panel.addView(Cards.heading(u, Cards.Icon.CHAT, "Team Chat", "All Chat Rooms"));
        View l = new View(this);
        l.setBackgroundColor(Ui.LINE_SOFT);
        panel.addView(l, new LinearLayout.LayoutParams(-1, u.dp(1)));

        chatList = u.column();
        chatList.setPadding(u.dp(14), u.dp(6), u.dp(14), u.dp(6));
        chatScroll = new ScrollView(this);
        chatScroll.addView(chatList);
        panel.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout send = u.row();
        send.setGravity(Gravity.CENTER_VERTICAL);
        send.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(10));
        EditText in = new EditText(this);
        in.setHint("Message the team");
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.MUTED);
        in.setBackground(u.box(0xFF07100F, Ui.LINE));
        in.setPadding(u.dp(14), u.dp(10), u.dp(14), u.dp(10));
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setImeOptions(EditorInfo.IME_ACTION_SEND);
        send.addView(in, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = u.button("Send", true, false);
        Runnable doSend = () -> {
            String txt = in.getText().toString();
            if (txt.trim().isEmpty()) return;
            if (!hub.sendChat(txt)) Toast.makeText(this, "Not sent: join a node's Wi-Fi first", Toast.LENGTH_LONG).show();
            in.setText("");
        };
        go.setOnClickListener(v -> doSend.run());
        in.setOnEditorActionListener((v, id, e) -> { doSend.run(); return true; });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.leftMargin = u.dp(8);
        send.addView(go, gp);
        panel.addView(send);
        renderChat();
    }

    private void renderChat() {
        chatList.removeAllViews();
        List<CotHub.Chat> chats = hub.chats();
        if (chats.isEmpty()) {
            TextView e = u.text("No messages yet. Messages from ATAK and other ManetNode phones show here, "
                    + "and so do node alerts.", 13, Ui.MUTED, false, false);
            e.setPadding(0, u.dp(14), 0, 0);
            chatList.addView(e);
            return;
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        for (CotHub.Chat c : chats) {
            LinearLayout bubble = u.column();
            bubble.setBackground(u.box(c.mine ? 0x1A00E5FF : 0xFF07100F, Ui.LINE));
            bubble.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8));
            LinearLayout h = u.row();
            h.addView(u.text(c.mine ? "You" : c.callsign, 12, Ui.CYAN, true, true));
            TextView time = u.text("  " + hm.format(new Date(c.time)), 12, Ui.SUB, false, true);
            h.addView(time);
            bubble.addView(h);
            bubble.addView(u.text(c.text, 16, Ui.TEXT, false, false));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.topMargin = u.dp(8);
            bp.gravity = c.mine ? Gravity.END : Gravity.START;
            chatList.addView(bubble, bp);
        }
        chatScroll.post(() -> chatScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ================================================================ PTT
    private PttButton pttBtn;
    private TextView pttAudio;
    private TalkGroupBar tgBar;

    private void buildPtt() {
        LinearLayout box = u.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(12));
        pttState = u.text("", 12, Ui.SUB, true, true);          // kept for renderPtt's "is the PTT tab open" check
        pttBtn = new PttButton(this);
        pttBtn.setPress(new PttButton.Press() {
            @Override public boolean down() {
                if (ptt.pressed()) return true;
                String why = ptt.error();
                Toast.makeText(HomeActivity.this, why == null ? "Can't talk now" : why, Toast.LENGTH_SHORT).show();
                return false;
            }
            @Override public void up() { ptt.released(); }
        });
        int big = Math.min(getResources().getDisplayMetrics().widthPixels - u.dp(40), u.dp(420));
        box.addView(pttBtn, new LinearLayout.LayoutParams(-1, big));     // big; the page scrolls
        pttAudio = u.text("", 14, Ui.SUB, true, true);
        pttAudio.setLetterSpacing(0.14f);
        pttAudio.setGravity(Gravity.CENTER);
        pttAudio.setPadding(u.dp(26), u.dp(12), u.dp(26), u.dp(12));
        pttAudio.setOnClickListener(v -> ptt.setAudioOn(!ptt.audioOn()));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = u.dp(10);
        box.addView(pttAudio, ap);
        tgBar = new TalkGroupBar(this, ptt);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = u.dp(10);
        box.addView(tgBar, tp);
        TextView tgHint = u.text("Tap a channel to talk on it · long-press to listen too", 11, Ui.MUTED, false, false);
        tgHint.setPadding(0, u.dp(4), 0, 0);
        box.addView(tgHint);
        pttFoot = u.text("", 12, Ui.SUB, false, true);
        pttFoot.setGravity(Gravity.CENTER);
        pttFoot.setPadding(0, u.dp(14), 0, 0);
        box.addView(pttFoot);
        ScrollView psv = new ScrollView(this);
        psv.addView(box);
        panel.addView(psv, new LinearLayout.LayoutParams(-1, -1));
        psv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {   // button fills the visible area
            int h = v.getHeight() - box.getPaddingTop() - box.getPaddingBottom();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pttBtn.getLayoutParams();
            if (h > 0 && lp.height != h) { lp.height = h; pttBtn.post(pttBtn::requestLayout); }
        });
        renderPtt();
    }

    private void renderPtt() {
        PttEngine.State st = ptt.state();
        boolean online = Nodes.wifiNode(this) != null;
        switch (st) {
            case TALKING: pttBtn.show(Ui.BAD, "TRANSMITTING", true); break;
            case RECEIVING: pttBtn.show(Ui.OK, "RECEIVING · CH " + ptt.lastReceivedGroup(), true); break;
            case OFF: pttBtn.show(Ui.MUTED, "SET TALK GROUP", false); break;
            default: pttBtn.show(online ? Ui.CYAN : Ui.MUTED, online ? "HOLD TO TALK" : "OFFLINE", false);
        }
        boolean on = ptt.audioOn();
        if (tgBar != null) tgBar.invalidate();
        pttAudio.setText(on ? "🔊  PHONE AUDIO ON" : "🔇  PHONE AUDIO OFF");
        pttAudio.setTextColor(on ? Ui.CYAN : Ui.SUB);
        pttAudio.setBackground(u.box(on ? 0x1A00E5FF : 0xFF07100F, on ? Ui.CYAN : Ui.LINE));
        JSONObject s = wifiSummary();
        JSONObject mesh = s == null ? null : s.optJSONObject("mesh");
        JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
        String foot = "NETWORK " + (s == null ? "OFFLINE" : "ONLINE") + "   |   NEIGHBORS " + (nb == null ? 0 : nb.length())
                + "\nTALK GROUP " + ptt.group() + " · " + ptt.address() + ":" + ptt.port();
        if (ptt.error() != null && st != PttEngine.State.TALKING) foot += "\n" + ptt.error();
        pttFoot.setText(foot);
    }

    // ================================================================ NODES (mesh topology)
    private TopologyView topo;

    private void buildNodes() {
        panel.addView(Cards.heading(u, Cards.Icon.MESH, "Mesh Topology", "tap a node"));
        topo = new TopologyView(this);
        topo.setOnNode(host -> startActivity(new Intent(this, NodeActivity.class).putExtra(NodeActivity.HOST, host)));
        topo.set(allHosts(), status, Nodes.wifiNode(this), loraStatus);
        panel.addView(topo, new LinearLayout.LayoutParams(-1, 0, 1));   // fills the panel
    }

    // ---------------------------------------------------------------- walk test
    private final Runnable walkTick = new Runnable() {
        @Override
        public void run() {
            if (!walk.recording()) return;
            String host = Nodes.wifiNode(HomeActivity.this);
            WalkTest.Sample smp = walk.record(hub.here(), host, host == null ? null : status.get(host),
                    host == null ? null : loraStatus.get(host), wifiRssi());
            if (smp != null) drawMap();
            renderWalk();
            ui.postDelayed(this, WalkTest.INTERVAL_MS);
        }
    };

    private int wifiRssi() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            @SuppressWarnings("deprecation") int r = wm == null ? Integer.MIN_VALUE : wm.getConnectionInfo().getRssi();
            return r <= -127 ? Integer.MIN_VALUE : r;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private void toggleWalk() {
        if (!walk.recording()) {
            if (!walk.samples().isEmpty()) {
                new android.app.AlertDialog.Builder(this).setTitle("Start a new capabilities test?")
                        .setMessage("The track on the map is replaced. It was already saved when you stopped it.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Start", (d, w) -> startWalk()).show();
                return;
            }
            startWalk();
            return;
        }
        walk.stop();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(walkTick);
        renderWalk();
        if (walk.samples().isEmpty()) { Toast.makeText(this, "Nothing recorded (no GPS fix)", Toast.LENGTH_LONG).show(); return; }
        WalkTest.Ranges rg = walk.ranges();
        try {
            getPreferences(MODE_PRIVATE).edit().putString("cap_test", new JSONObject().put("w24", rg.wifi24).put("halow", rg.halow)
                    .put("lora", rg.lora).put("total", rg.total).put("start", rg.start).put("secs", rg.secs).put("points", rg.points).toString()).apply();
        } catch (Exception ignored) { }
        renderCapResults();
        io.execute(() -> {
            String msg;
            try { msg = "Saved: " + walk.save(this); } catch (Exception e) { msg = "Could not save the test: " + e.getMessage(); }
            String m = msg;
            ui.post(() -> new android.app.AlertDialog.Builder(this).setTitle("Capabilities test")
                    .setMessage(m + "\n\nOpen the .kml in Google Earth or import it into ATAK; the .csv opens in any spreadsheet.")
                    .setPositiveButton("OK", null).show());
        });
    }

    private JSONObject lastCapTest() {
        try { String j = getPreferences(MODE_PRIVATE).getString("cap_test", null); return j == null ? null : new JSONObject(j); }
        catch (Exception e) { return null; }
    }

    /** "Last test" card on the Capabilities Test page: furthest range per link type. */
    private void renderCapResults() {
        if (capResults == null) return;
        capResults.removeAllViews();
        JSONObject t = lastCapTest();
        if (t == null) { capResults.addView(u.text("No test yet.", 13, Ui.SUB, false, false)); return; }
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t.optLong("start")));
        long secs = t.optLong("secs");
        capResults.addView(Cards.statusRow(u, t.optDouble("w24", -1) >= 0 ? TopologyView.WIFI : Ui.MUTED, "2.4 GHz mesh", WalkTest.dist(t.optDouble("w24", -1))));
        capResults.addView(Cards.statusRow(u, t.optDouble("halow", -1) >= 0 ? TopologyView.HALOW : Ui.MUTED, "HaLow mesh", WalkTest.dist(t.optDouble("halow", -1))));
        capResults.addView(Cards.statusRow(u, t.optDouble("lora", -1) >= 0 ? TopologyView.LORA : Ui.MUTED, "LoRa", WalkTest.dist(t.optDouble("lora", -1))));
        capResults.addView(Cards.note(u, String.format(Locale.US, "%s · walked %s in %d:%02d · %d points. Range = furthest distance from the start "
                + "where that link still worked.", when, WalkTest.dist(t.optDouble("total")), secs / 60, secs % 60, t.optInt("points"))));
    }

    private void startWalk() {
        walk.start();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(walkTick);
        ui.post(walkTick);
        ui.removeCallbacks(tick);
        ui.post(tick);                                      // node readings every 3 s while recording
        renderWalk();
        changed();
        Toast.makeText(this, "Capabilities test started. Keep this app open and walk away from the base node.", Toast.LENGTH_LONG).show();
    }

    private void renderWalk() {
        if (walkInfo == null) return;
        WalkTest.Ranges rg = walk.ranges();
        walkInfo.setText(walk.summary() + (walk.recording() && rg.points > 0 ? "\nSo far: 2.4 GHz " + WalkTest.dist(rg.wifi24)
                + " · HaLow " + WalkTest.dist(rg.halow) + " · LoRa " + WalkTest.dist(rg.lora) : ""));
        if (walkBtn != null) walkBtn.setText(walk.recording() ? "Stop & save" : "Start test");
        if (walkClear != null) walkClear.setEnabled(!walk.recording() && !walk.samples().isEmpty());
    }

    /** The walk-test track, one coloured segment per step, under the links and markers. */
    private void drawWalk() {
        List<WalkTest.Sample> pts = walk.samples();
        for (int i = 1; i < pts.size(); i++) {
            WalkTest.Sample a = pts.get(i - 1), b = pts.get(i);
            org.osmdroid.views.overlay.Polyline seg = new org.osmdroid.views.overlay.Polyline(map);
            java.util.List<GeoPoint> g = new java.util.ArrayList<>();
            g.add(new GeoPoint(a.lat, a.lon));
            g.add(new GeoPoint(b.lat, b.lon));
            seg.setPoints(g);
            seg.getOutlinePaint().setColor(b.quality.colour);
            seg.getOutlinePaint().setStrokeWidth(u.dp(5));
            seg.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
            seg.setTitle(WalkTest.describe(b));
            map.getOverlays().add(seg);
        }
    }

    // ================================================================ points, geofences, breadcrumbs
    private final org.osmdroid.events.MapEventsReceiver mapEvents = new org.osmdroid.events.MapEventsReceiver() {
        @Override
        public boolean singleTapConfirmedHelper(GeoPoint p) {
            if (drawing == null) return false;
            drawing.add(new double[]{p.getLatitude(), p.getLongitude()});
            renderDrawBar();
            drawMap();
            return true;
        }

        @Override
        public boolean longPressHelper(GeoPoint p) {
            if (drawing != null) return false;
            pointDialog(p.getLatitude(), p.getLongitude(), null);
            return true;
        }
    };

    private GeoPoint crosshair() {
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        return new GeoPoint(c.getLatitude(), c.getLongitude());
    }

    private void mapTools() {
        LinearLayout box = u.column();
        box.setPadding(u.dp(14), u.dp(14), u.dp(14), u.dp(6));
        box.addView(Cards.heading(u, Cards.Icon.MAP, "Map Tools", null));
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        GeoPoint c = crosshair();
        Runnable[] acts = {
                () -> pointDialog(c.getLatitude(), c.getLongitude(), null),
                () -> fenceDialog(c.getLatitude(), c.getLongitude(), null, null),
                () -> { drawing = new java.util.ArrayList<>(); renderDrawBar(); },
                () -> { items.setTrailsOn(!items.trailsOn()); drawMap(); },
                () -> { select(4); settingsPage("map"); }};
        Cards.Icon[] icons = {Cards.Icon.PIN, Cards.Icon.FENCE, Cards.Icon.AREA, Cards.Icon.TRAIL, Cards.Icon.GEAR};
        String[] titles = {"Drop a Point", "Geofence Circle", "Geofence Area", "Breadcrumbs", "Settings"};
        String[] subs = {"At the crosshair, or long-press map", "Centred on the crosshair", "Tap the map to place corners",
                items.trailsOn() ? "On · tap to hide trails" : "Off · tap to show trails", "Points list, trails, alerts"};
        int[] cols = {Ui.SUB, Ui.SUB, Ui.SUB, items.trailsOn() ? Ui.OK : Ui.SUB, Ui.SUB};
        for (int i = 0; i < titles.length; i++) {
            Runnable act = acts[i];
            box.addView(Cards.menuCard(u, icons[i], titles[i], subs[i], cols[i], () -> { dlg[0].dismiss(); act.run(); }), menuGap());
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new android.app.AlertDialog.Builder(this).setView(sv).create();
        dlg[0].show();
    }

    private void renderDrawBar() {
        drawBar.setVisibility(drawing == null ? View.GONE : View.VISIBLE);
        if (drawing != null) drawInfo.setText(drawing.size() < 3 ? "Tap the map to add the area's corners (" + drawing.size() + ")"
                : drawing.size() + " corners · tap Done when finished");
    }

    private void drawAction(String what) {
        if (drawing == null) return;
        if (what.equals("Undo")) { if (!drawing.isEmpty()) drawing.remove(drawing.size() - 1); renderDrawBar(); drawMap(); return; }
        if (what.equals("Done")) {
            if (drawing.size() < 3) { Toast.makeText(this, "An area needs at least 3 corners", Toast.LENGTH_SHORT).show(); return; }
            fenceDialog(Double.NaN, Double.NaN, new java.util.ArrayList<>(drawing), null);
        }
        drawing = null;
        renderDrawBar();
        drawMap();
    }

    private EditText field(LinearLayout form, String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        form.addView(e);
        return e;
    }

    /** New point at lat/lon, or edit one (existing). */
    private void pointDialog(double lat, double lon, MapItems.Item existing) {
        LinearLayout form = u.column();
        form.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        int n = items.mine().size() + 1;
        EditText name = field(form, "Name", existing != null ? existing.name : hub.callsign() + "." + n, InputType.TYPE_CLASS_TEXT);
        android.widget.RadioGroup types = new android.widget.RadioGroup(this);
        String cur = existing != null ? existing.type : MapItems.TYPES[0][1];
        for (int i = 0; i < MapItems.TYPES.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(MapItems.TYPES[i][0]);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(MapItems.TYPE_RGB[i]));
            types.addView(rb);
            if (MapItems.TYPES[i][1].equals(cur)) rb.setChecked(true);
        }
        if (types.getCheckedRadioButtonId() == -1) types.check(1000);
        form.addView(types);
        EditText notes = field(form, "Notes (optional)", existing != null ? existing.remarks : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        TextView who = u.text(items.shareToAtak() ? "Shared with app users and ATAK." : "Shared with app users. Turn on Share to ATAK under Settings → Me to send it to ATAK too.", 12, Ui.SUB, false, false);
        who.setPadding(0, u.dp(6), 0, 0);
        form.addView(who);
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        new android.app.AlertDialog.Builder(this).setTitle(existing == null ? "New point" : "Edit point").setView(sv)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String nm = name.getText().toString().trim();
                    if (nm.isEmpty()) nm = "Point";
                    String type = MapItems.TYPES[Math.max(0, types.getCheckedRadioButtonId() - 1000)][1];
                    if (existing == null) items.newPoint(lat, lon, nm, type, notes.getText().toString().trim());
                    else { existing.name = nm; existing.type = type; existing.remarks = notes.getText().toString().trim(); items.update(existing); }
                    drawMap();
                }).show();
    }

    /** New circle (centre lat/lon) or area (corners) geofence, or edit one (existing). */
    private void fenceDialog(double lat, double lon, java.util.List<double[]> corners, MapItems.Item existing) {
        boolean area = existing != null ? existing.polygon : corners != null;
        LinearLayout form = u.column();
        form.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        EditText name = field(form, "Name", existing != null ? existing.name : "Fence " + (items.mine().size() + 1), InputType.TYPE_CLASS_TEXT);
        EditText radius = null;
        if (!area) radius = field(form, "Radius in metres", existing != null ? String.valueOf(Math.round(existing.radius)) : "100", InputType.TYPE_CLASS_NUMBER);
        android.widget.CheckBox in = new android.widget.CheckBox(this), out = new android.widget.CheckBox(this);
        in.setText("Alert when someone enters");
        out.setText("Alert when someone leaves");
        in.setChecked(existing == null || existing.alertIn);
        out.setChecked(existing != null && existing.alertOut);
        form.addView(in);
        form.addView(out);
        TextView note = u.text("Alerts show on every app user's phone that has the fence, for you and every team member on the map. "
                + (items.shareToAtak() ? "Also sent to ATAK as a geofence." : "Turn on Share to ATAK under Settings → Me to send it to ATAK too."), 12, Ui.SUB, false, false);
        note.setPadding(0, u.dp(6), 0, 0);
        form.addView(note);
        EditText rf = radius;
        new android.app.AlertDialog.Builder(this).setTitle(existing == null ? (area ? "New geofence area" : "New geofence circle") : "Edit geofence").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String nm = name.getText().toString().trim();
                    if (nm.isEmpty()) nm = "Fence";
                    double r = 100;
                    if (rf != null) try { r = Math.max(5, Double.parseDouble(rf.getText().toString().trim())); } catch (Exception ignored) { }
                    if (existing == null) {
                        if (area) items.newPolygon(corners, nm, in.isChecked(), out.isChecked());
                        else items.newCircle(lat, lon, r, nm, in.isChecked(), out.isChecked());
                    } else {
                        existing.name = nm;
                        if (!area) existing.radius = r;
                        existing.alertIn = in.isChecked();
                        existing.alertOut = out.isChecked();
                        items.update(existing);
                    }
                    if ((in.isChecked() || out.isChecked()) && android.os.Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
                    drawMap();
                }).show();
    }

    /** Tap on a point or fence: what it is, who placed it, distance and bearing; edit, delete or centre on it. */
    private void itemInfo(MapItems.Item i) {
        StringBuilder m = new StringBuilder(i.typeName());
        if (!i.by.isEmpty()) m.append(" · placed by ").append(i.mine ? "you" : i.by);
        if (i.fence) {
            m.append(i.polygon ? "\n" + i.corners.size() + " corners" : "\nRadius " + WalkTest.dist(i.radius));
            m.append("\nAlerts: ").append(i.alertIn && i.alertOut ? "enter and leave" : i.alertIn ? "enter" : i.alertOut ? "leave" : "off");
        }
        Location here = hub.here();
        if (here != null) {
            float[] d = new float[2];
            Location.distanceBetween(here.getLatitude(), here.getLongitude(), i.lat, i.lon, d);
            m.append(String.format(Locale.US, "\n%s away, bearing %.0f°", WalkTest.dist(d[0]), (d[1] + 360) % 360));
            if (i.fence && i.inside(here.getLatitude(), here.getLongitude())) m.append(" · you are inside");
        }
        m.append(String.format(Locale.US, "\n%.5f, %.5f\n%s", i.lat, i.lon, Mgrs.of(i.lat, i.lon)));
        if (!i.remarks.isEmpty()) m.append("\n\n").append(i.remarks);
        new android.app.AlertDialog.Builder(this).setTitle(i.name).setMessage(m.toString())
                .setPositiveButton("Edit", (d, w) -> { if (i.fence) fenceDialog(i.lat, i.lon, null, i); else pointDialog(i.lat, i.lon, i); })
                .setNeutralButton("Delete", (d, w) -> new android.app.AlertDialog.Builder(this).setTitle("Delete " + i.name + "?")
                        .setMessage(i.mine ? "Removes it for everyone." : "Placed by " + i.by + ". Removes it for everyone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d2, w2) -> { items.delete(i); drawMap(); }).show())
                .setNegativeButton("Close", null).show();
    }

    private void drawFences() {
        for (MapItems.Item f : items.all()) {
            if (!f.fence) continue;
            org.osmdroid.views.overlay.Polygon pg = new org.osmdroid.views.overlay.Polygon(map);
            java.util.List<GeoPoint> pts = new java.util.ArrayList<>();
            if (f.polygon) for (double[] c : f.corners) pts.add(new GeoPoint(c[0], c[1]));
            else pts = org.osmdroid.views.overlay.Polygon.pointsAsCircle(new GeoPoint(f.lat, f.lon), f.radius);
            pg.setPoints(pts);
            boolean hit = fenceHit.containsKey(f.uid) && System.currentTimeMillis() - fenceHit.get(f.uid) < 60000;
            int col = hit ? Ui.BAD : f.colour();
            pg.getFillPaint().setColor((col & 0x00FFFFFF) | (hit ? 0x44000000 : 0x22000000));
            pg.getOutlinePaint().setColor(col);
            pg.getOutlinePaint().setStrokeWidth(u.dp(hit ? 4 : 2.5f));
            if (!f.alertIn && !f.alertOut) pg.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{u.dp(8), u.dp(5)}, 0));
            pg.setOnClickListener((poly, mv, at) -> { itemInfo(f); return true; });
            map.getOverlays().add(pg);
            Marker label = mark(new GeoPoint(f.lat, f.lon), f.name, null, labelIcon(f.name, col));
            label.setOnMarkerClickListener((mk, mv) -> { itemInfo(f); return true; });
        }
        if (drawing != null && !drawing.isEmpty()) {                       // the area being drawn
            org.osmdroid.views.overlay.Polyline pl = new org.osmdroid.views.overlay.Polyline(map);
            java.util.List<GeoPoint> pts = new java.util.ArrayList<>();
            for (double[] c : drawing) pts.add(new GeoPoint(c[0], c[1]));
            if (drawing.size() > 2) pts.add(pts.get(0));
            pl.setPoints(pts);
            pl.getOutlinePaint().setColor(0xFFFF7A1A);
            pl.getOutlinePaint().setStrokeWidth(u.dp(3));
            pl.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{u.dp(8), u.dp(5)}, 0));
            map.getOverlays().add(pl);
            for (GeoPoint g : pts) mark(g, "Corner", null, pin(0xFFFF7A1A, false)).setInfoWindow(null);
        }
    }

    private void drawTrails() {
        for (Map.Entry<String, java.util.List<double[]>> e : items.trails().entrySet()) {
            boolean me = e.getKey().equals("me");
            org.osmdroid.views.overlay.Polyline pl = new org.osmdroid.views.overlay.Polyline(map);
            java.util.List<GeoPoint> pts = new java.util.ArrayList<>();
            for (double[] p : e.getValue()) pts.add(new GeoPoint(p[0], p[1]));
            pl.setPoints(pts);
            pl.getOutlinePaint().setColor(me ? 0xCC4D8BFF : 0xAA00E5FF);
            pl.getOutlinePaint().setStrokeWidth(u.dp(3));
            pl.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            pl.getOutlinePaint().setPathEffect(new android.graphics.DashPathEffect(new float[]{u.dp(2), u.dp(5)}, 0));
            pl.setInfoWindow(null);
            map.getOverlays().add(pl);
        }
    }

    private void drawPoints() {
        for (MapItems.Item p : items.all()) {
            if (p.fence) continue;
            Marker m = mark(new GeoPoint(p.lat, p.lon), p.name, p.typeName(), pointIcon(p));
            m.setAnchor(Marker.ANCHOR_CENTER, (float) u.dp(11) / m.getIcon().getIntrinsicHeight());
            m.setOnMarkerClickListener((mk, mv) -> { itemInfo(p); return true; });
        }
    }

    /** Shape by type, like ATAK: diamond hostile, square friendly/neutral, circle unknown/waypoint, flag rally. Name below. */
    private Drawable pointIcon(MapItems.Item p) {
        int s = u.dp(22), col = p.colour();
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(u.dp(11));
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int w = Math.max(s, (int) tp.measureText(p.name) + u.dp(8)), h = s + u.dp(16);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(col);
        edge.setColor(0xFF05080A);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(u.dp(2));
        float cx = w / 2f, cy = s / 2f, r = s / 2f - u.dp(2);
        Path path = new Path();
        String t = p.type;
        if (t.startsWith("a-h")) { path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close(); }
        else if (t.startsWith("a-f") || t.startsWith("a-n")) path.addRect(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, Path.Direction.CW);
        else if (t.equals("b-m-p-s-m")) { path.moveTo(cx, cy - r); path.lineTo(cx + r, cy + r * 0.8f); path.lineTo(cx - r, cy + r * 0.8f); path.close(); }
        else path.addCircle(cx, cy, r, Path.Direction.CW);
        c.drawPath(path, fill);
        c.drawPath(path, edge);
        if (t.equals("b-m-p-w")) { Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(0xFF05080A); c.drawCircle(cx, cy, u.dp(3), dot); }
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(u.dp(3));
        tp.setColor(0xFF05080A);
        c.drawText(p.name, cx, s + u.dp(12), tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(0xFFFFFFFF);
        c.drawText(p.name, cx, s + u.dp(12), tp);
        return new BitmapDrawable(getResources(), b);
    }

    private Drawable labelIcon(String text, int col) {
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(u.dp(12));
        tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.CENTER);
        int w = (int) tp.measureText(text) + u.dp(8), h = u.dp(18);
        Bitmap b = Bitmap.createBitmap(Math.max(w, 1), h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(u.dp(3));
        tp.setColor(0xFF05080A);
        c.drawText(text, w / 2f, u.dp(13), tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(col);
        c.drawText(text, w / 2f, u.dp(13), tp);
        return new BitmapDrawable(getResources(), b);
    }

    private final Map<String, Long> fenceHit = new HashMap<>();   // fence uid -> last crossing (lights the fence up for a minute)

    /** Geofence crossings by me or anyone on the map: a notification, a buzz, and the fence lights up. */
    private void checkFences() {
        if (items == null) return;
        Map<String, Object[]> who = new HashMap<>();
        Location here = hub.here();
        if (here != null) who.put("me", new Object[]{"You", here.getLatitude(), here.getLongitude()});
        for (CotHub.Contact c : hub.contacts()) who.put(c.uid, new Object[]{c.callsign, c.lat, c.lon});
        java.util.List<String> hits = items.crossings(who);
        if (hits.isEmpty()) return;
        for (MapItems.Item f : items.all())
            for (String h : hits) if (f.fence && (h.endsWith(" entered " + f.name) || h.endsWith(" left " + f.name))) fenceHit.put(f.uid, System.currentTimeMillis());
        String msg = android.text.TextUtils.join("\n", hits);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
        } catch (Exception ignored) { }
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel("geofence", "Geofence alerts", android.app.NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.Notification n = new android.app.Notification.Builder(this, "geofence")
                    .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("Geofence").setContentText(msg)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(msg)).setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE)).build();
            nm.notify((int) (System.currentTimeMillis() % 100000), n);
        } catch (Exception ignored) { }
    }

    private void saveTrail() {
        if (items.myTrail().size() < 2) { Toast.makeText(this, "No trail yet: it starts once you move", Toast.LENGTH_LONG).show(); return; }
        String name = "trail-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".kml";
        String kml = items.trailKml();
        io.execute(() -> {
            String where;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.google-earth.kml+xml");
                    v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/ManetNode");
                    android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(kml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    where = "Download/ManetNode/" + name;
                } else {
                    File f = new File(getExternalFilesDir("walktests"), name);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(kml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    where = f.getPath();
                }
            } catch (Exception e) { where = null; }
            String w = where;
            ui.post(() -> Toast.makeText(this, w == null ? "Could not save the trail" : "Trail saved: " + w, Toast.LENGTH_LONG).show());
        });
    }

    private LinearLayout.LayoutParams menuGap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(7);
        return p;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = u.dp(10);
        return p;
    }

    private void web(String url, String title) {
        startActivity(new Intent(this, WebActivity.class).putExtra(WebActivity.URL, url).putExtra(WebActivity.TITLE, title));
    }

    // ================================================================ SETTINGS (menu of cards, each opens a page)
    private static final int[] FE_PAGES = {R.id.fe_page_status, R.id.fe_page_lora, R.id.fe_page_settings};
    private FieldPanel settingsPanel;

    private void buildSettings() {
        LinearLayout box = u.column();
        box.setPadding(u.dp(14), u.dp(10), u.dp(14), u.dp(6));
        View hd = Cards.heading(u, Cards.Icon.GEAR, "Settings", null);
        hd.setPadding(u.dp(2), 0, 0, u.dp(10));
        box.addView(hd);
        JSONObject s = wifiSummary();
        String host = Nodes.wifiNode(this);
        String nodeName = s == null ? null : Nodes.name(s, host);
        settingsMenuNoNode = s == null;
        box.addView(Cards.menuCard(u, Cards.Icon.LINK, "Node Status",
                s == null ? "Not on a node's Wi-Fi" : "Connected · " + nodeName + " · battery, mesh, health",
                s == null ? Ui.WARN : Ui.OK, () -> settingsPage("connection")), menuGap());
        box.addView(Cards.menuCard(u, Cards.Icon.PERSON, "Me", hub.callsign() + " · " + (hub.sharing() ? "sharing position" : "position private"),
                Ui.SUB, () -> settingsPage("me")), menuGap());
        box.addView(Cards.menuCard(u, Cards.Icon.CHAT, "Comms", hub.chats().size() + " messages · talk on CH " + ptt.group(),
                Ui.SUB, () -> settingsPage("chat")), menuGap());
        box.addView(Cards.menuCard(u, Cards.Icon.RADIO, "ATAK", "My marker " + (hub.sharing() ? "on" : "off") + " · node radios, node markers",
                Ui.SUB, () -> settingsPage("atak")), menuGap());
        box.addView(Cards.menuCard(u, Cards.Icon.MAP, "Map", MapStyles.NAMES[mapStyle()] + " · links " + (mapLinks() ? "on" : "off") + " · " + pollSeconds() + " s refresh",
                Ui.SUB, () -> settingsPage("map")), menuGap());
        if (s != null) {
            box.addView(Cards.menuCard(u, Cards.Icon.RADIO, "LoRa", "Fallback, mode, team channel, radios heard",
                    Ui.SUB, () -> settingsPage("fe1")), menuGap());
            box.addView(Cards.menuCard(u, Cards.Icon.CHIP, "Node Settings", "Light, power, other mesh, sign-in",
                    Ui.SUB, () -> settingsPage("fe2")), menuGap());
        }
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { version = "?"; }
        final String v = version;
        org.json.JSONObject lastTest = lastCapTest();
        box.addView(Cards.menuCard(u, Cards.Icon.PULSE, "Capabilities Test", walk.recording() ? "Recording now…"
                : lastTest == null ? "How far 2.4 GHz, HaLow and LoRa reach"
                : "2.4 GHz " + WalkTest.dist(lastTest.optDouble("w24", -1)) + " · HaLow " + WalkTest.dist(lastTest.optDouble("halow", -1))
                  + " · LoRa " + WalkTest.dist(lastTest.optDouble("lora", -1)),
                walk.recording() ? Ui.OK : Ui.SUB, () -> settingsPage("captest")), menuGap());
        box.addView(Cards.menuCard(u, Cards.Icon.INFO, "About", "ManetNode " + v + " · OpenMANET Field Extension",
                Ui.SUB, () -> settingsPage("about")), menuGap());
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, -1));
    }

    /** One settings page: "← TITLE" header and its section cards. */
    private void settingsPage(String key) {
        settingsMenuNoNode = false;
        panel.removeAllViews();
        if (settingsPanel != null) { settingsPanel.dispose(); settingsPanel = null; }
        String[] titles = {"connection", "NODE STATUS", "me", "ME", "chat", "COMMS", "ptt", "COMMS", "map", "MAP", "atak", "ATAK", "captest", "CAPABILITIES TEST",
                "fe0", "NODE STATUS", "fe1", "LORA", "fe2", "NODE SETTINGS", "about", "ABOUT"};
        String title = key;
        for (int i = 0; i < titles.length; i += 2) if (titles[i].equals(key)) title = titles[i + 1];
        LinearLayout head = u.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(8));
        TextView back = u.text("←", 26, Ui.CYAN, false, false);
        back.setPadding(u.dp(8), 0, u.dp(18), 0);
        back.setOnClickListener(vv -> select(4));
        head.addView(back);
        TextView ht = u.text(title, 20, Ui.TEXT, true, false);
        ht.setLetterSpacing(0.12f);
        head.addView(ht);
        panel.addView(head);
        if (key.startsWith("fe")) { nodeSection(Integer.parseInt(key.substring(2))); return; }
        LinearLayout box = u.column();
        box.setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(14));
        JSONObject s = wifiSummary();
        switch (key) {
            case "connection": {
                LinearLayout c = Cards.section(u, box, Cards.Icon.LINK, "Connection", s == null ? null : "NODE");
                c.addView(u.text(s == null ? "Join a mesh node's Wi-Fi (for example Node01). The app finds the node by itself."
                        : "On " + Nodes.name(s, "the node") + "'s Wi-Fi (" + Nodes.wifiNode(this) + ").", 13, Ui.SUB, false, false));
                if (s != null) { nodePage(box); return; }              // the node's own status and health follow
                LinearLayout st = Cards.section(u, box, Cards.Icon.PULSE, "Status", null);
                JSONObject mesh = s == null ? null : s.optJSONObject("mesh");
                int nodes = mesh == null || mesh.isNull("nodes") ? 0 : mesh.optInt("nodes");
                JSONArray nb = mesh == null ? null : mesh.optJSONArray("neighbors");
                JSONObject inet = s == null ? null : s.optJSONObject("internet");
                JSONObject gps = s == null ? null : s.optJSONObject("gps");
                st.addView(Cards.statusRow(u, s == null ? Ui.BAD : Ui.OK, "Node", s == null ? "Offline" : "Online"));
                st.addView(Cards.statusRow(u, nodes > 1 ? Ui.OK : Ui.WARN, "Mesh", s == null ? "—" : nodes + " nodes · " + (nb == null ? 0 : nb.length()) + " direct links"));
                boolean online = inet != null && (inet.optBoolean("online") || inet.optBoolean("available"));
                st.addView(Cards.statusRow(u, online ? Ui.OK : Ui.MUTED, "Internet", s == null ? "—" : online ? "Yes" : "No"));
                st.addView(Cards.statusRow(u, gps != null && "fix".equalsIgnoreCase(gps.optString("state")) ? Ui.OK : Ui.MUTED, "GPS",
                        gps == null ? "—" : gps.optString("state", "—")));
                nodeExtras(box);
                break;
            }
            case "captest": {
                LinearLayout c = Cards.section(u, box, Cards.Icon.PULSE, "Capabilities test", walk.recording() ? "REC" : null);
                c.addView(u.text("Walk away from the base node carrying the other node, with this phone on the carried node's Wi-Fi "
                        + "and the app open. Start next to the base node: ranges are measured from where you start.", 13, Ui.SUB, false, false));
                walkBtn = Cards.withIcon(this, u.button(walk.recording() ? "Stop & save" : "Start test", true, walk.recording()), Cards.Icon.MAP);
                walkBtn.setOnClickListener(vv -> toggleWalk());
                walkClear = u.button("Clear track", true, false);
                walkClear.setOnClickListener(vv -> { walk.clear(); renderWalk(); changed(); });
                LinearLayout br = u.row();
                br.addView(walkBtn, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                cl.leftMargin = u.dp(8);
                br.addView(walkClear, cl);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
                bp.topMargin = u.dp(10);
                c.addView(br, bp);
                walkInfo = u.text("", 13, Ui.TEXT, false, true);
                walkInfo.setPadding(0, u.dp(10), 0, 0);
                c.addView(walkInfo);
                c.addView(Cards.note(u, "The track is drawn on the map: green 10 Mb/s or more · yellow 2–10 · red under 2 · "
                        + "blue mesh lost, LoRa only · grey no link. Saved to Download/ManetNode as .kml (Google Earth, ATAK) and .csv."));
                LinearLayout r = Cards.section(u, box, Cards.Icon.RADIO, "Last test", null);
                capResults = r;
                renderCapResults();
                renderWalk();
                break;
            }
            case "atak": {
                LinearLayout me = Cards.section(u, box, Cards.Icon.PERSON, "My marker", hub.sharing() ? "ON" : null);
                me.addView(toggleRow("Send my position as " + hub.callsign(), hub.sharing(), on -> { hub.setMe(hub.callsign(), on); settingsPage("atak"); }));
                me.addView(Cards.note(u, "This phone's own marker on ATAK maps and in the app, about every 30 s. Leave it off when you "
                        + "run ATAK on this phone too, so you show once (as your ATAK callsign)."));
                LinearLayout tr = Cards.section(u, box, Cards.Icon.PERSON, "Team role", hub.role().toUpperCase(Locale.US));
                tr.addView(u.text("Your role on the team. These are ATAK's roles, so this is what ATAK users see."
                        + (hub.sharing() ? "" : " Used when My marker is on."), 13, Ui.SUB, false, false));
                LinearLayout rr = null;
                for (int i = 0; i < CotHub.ROLES.length; i++) {
                    if (i % 3 == 0) { rr = u.row(); LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = u.dp(8); tr.addView(rr, rp); }
                    String role = CotHub.ROLES[i];
                    boolean sel = role.equals(hub.role());
                    TextView chip = u.text(role, 13, sel ? Ui.CYAN : Ui.SUB, sel, false);
                    chip.setGravity(Gravity.CENTER);
                    chip.setMaxLines(1);
                    chip.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                    chip.setPadding(u.dp(6), u.dp(9), u.dp(6), u.dp(9));
                    android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
                    cb.setCornerRadius(u.dp(20));
                    cb.setColor(sel ? 0x2600E5FF : 0);
                    cb.setStroke(u.dp(sel ? 2 : 1), sel ? Ui.CYAN : 0x6600E5FF);
                    chip.setBackground(cb);
                    chip.setOnClickListener(v -> { hub.setTeam(hub.team(), role); settingsPage("atak"); });
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
                    if (i % 3 != 0) cp.leftMargin = u.dp(8);
                    rr.addView(chip, cp);
                }
                if (CotHub.ROLES.length % 3 != 0) for (int k = CotHub.ROLES.length % 3; k < 3; k++) {
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1); sp.leftMargin = u.dp(8); rr.addView(new View(this), sp);
                }
                LinearLayout tc = Cards.section(u, box, Cards.Icon.MAP, "Team colour", hub.team().toUpperCase(Locale.US));
                tc.addView(u.text("Your team colour on the map. These are ATAK's team colours, so the colour you pick is the colour ATAK users see.", 13, Ui.SUB, false, false));
                LinearLayout cr = null;
                for (int i = 0; i < CotHub.TEAMS.length; i++) {
                    if (i % 7 == 0) { cr = u.row(); LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = u.dp(10); tc.addView(cr, rp); }
                    String team = CotHub.TEAMS[i];
                    boolean sel = team.equals(hub.team());
                    TextView sw = u.text(sel ? "✓" : "", 18, (CotHub.TEAM_RGB[i] & 0x00FFFFFF) > 0x888888 && i != 6 && i != 8 ? 0xFF05080A : 0xFFFFFFFF, true, false);
                    sw.setGravity(Gravity.CENTER);
                    android.graphics.drawable.GradientDrawable sb = new android.graphics.drawable.GradientDrawable();
                    sb.setCornerRadius(u.dp(12));
                    sb.setColor(CotHub.TEAM_RGB[i]);
                    if (sel) sb.setStroke(u.dp(3), 0xFFFFFFFF);
                    sw.setBackground(sb);
                    sw.setContentDescription(team);
                    sw.setOnClickListener(v -> { hub.setTeam(team, hub.role()); settingsPage("atak"); });
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, u.dp(40), 1);
                    if (i % 7 != 0) sp.leftMargin = u.dp(8);
                    cr.addView(sw, sp);
                }
                if (!hub.sharing()) for (View sec : new View[]{(View) tr.getParent(), (View) tc.getParent()}) sec.setAlpha(0.5f);   // only used with My marker on
                LinearLayout lr = Cards.section(u, box, Cards.Icon.RADIO, "Node LoRa radios", null);
                TextView lrNow = u.text(s == null ? "Join a node's Wi-Fi to change this." : "Checking the node…", 13, Ui.SUB, false, false);
                lr.addView(lrNow);
                LinearLayout lrRow = u.column();
                lr.addView(lrRow);
                lr.addView(Cards.note(u, "The nodes' own Meshtastic radios as extra markers on ATAK maps. Off by default: the node "
                        + "already shows itself. Guests' Meshtastic radios always show. Set on every node you're connected to."));
                String host = Nodes.wifiNode(this);
                if (host != null) io.execute(() -> {
                    Boolean show = null;
                    try {
                        FieldClient.Result r = new FieldClient("http://" + host + ":" + Nodes.FE_PORT).get("lora");
                        if (r.ok() && r.body.has("node_radios_in_atak")) show = r.body.optBoolean("node_radios_in_atak");
                    } catch (Exception ignored) { }
                    Boolean sh = show;
                    ui.post(() -> {
                        if (sh == null) { lrNow.setText("Needs a node with a LoRa radio"); return; }
                        lrNow.setVisibility(View.GONE);
                        lrRow.addView(toggleRow("Show node radios in ATAK", sh, on -> {
                            JSONObject b = new JSONObject();
                            try { b.put("show", on); } catch (Exception ignored) { }
                            nodeWrite(host, "lora/node-radios", b, r -> Toast.makeText(this,
                                    on ? "Node radios show in ATAK" : "Node radios hidden from ATAK", Toast.LENGTH_SHORT).show());
                        }));
                    });
                });
                LinearLayout nm = Cards.section(u, box, Cards.Icon.NODES, "Node markers", null);
                nm.addView(u.text("The node markers and mesh lines in ATAK (\"Node01\", \"Node02\") come from the OpenManet FieldExtension "
                        + "plugin: switch them in ATAK under the plugin's SET → Nodes on map. This app's map always shows the nodes.", 13, Ui.SUB, false, false));
                break;
            }
            case "me": {
                LinearLayout c = Cards.section(u, box, Cards.Icon.PERSON, "Callsign", null);
                c.addView(u.text(hub.callsign(), 20, Ui.CYAN, true, true));
                Button edit = Cards.withIcon(this, u.button("Change callsign / sharing", true, false), Cards.Icon.EDIT);
                edit.setOnClickListener(vv -> Nodes.editMe(this, hub, () -> settingsPage("me")));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
                ep.topMargin = u.dp(10);
                c.addView(edit, ep);
                c.addView(Cards.note(u, "Shown in team chat and on everyone's map, in ATAK too."));
                LinearLayout p = Cards.section(u, box, Cards.Icon.MAP, "Position sharing", hub.sharing() ? "ON" : null);
                p.addView(toggleRow("Share my position with the team", hub.sharing(), on -> { hub.setMe(hub.callsign(), on); settingsPage("me"); }));
                p.addView(Cards.note(u, "Sent over the mesh like ATAK, about every 30 s."));
                LinearLayout sa = Cards.section(u, box, Cards.Icon.RADIO, "ATAK sharing", items.shareToAtak() ? "ON" : null);
                sa.addView(toggleRow("Share my points and geofences to ATAK", items.shareToAtak(), on -> { items.setShareToAtak(on); settingsPage("me"); }));
                sa.addView(Cards.note(u, "Other app users on the mesh always see your points and geofences. With this on they also go to "
                        + "ATAK users, and the nodes pass them on over LoRa when the mesh is down."));
                break;
            }
            case "chat": {
                LinearLayout c = Cards.section(u, box, Cards.Icon.CHAT, "Chat history", hub.chats().size() + " MSGS");
                c.addView(u.text("Team chat is kept on this phone (the last 200 messages), so it is still there after the app "
                        + "closes or the phone restarts.", 13, Ui.SUB, false, false));
                Button clr = Cards.withIcon(this, u.button("Clear chat", true, true), Cards.Icon.TRASH);
                clr.setOnClickListener(vv -> new android.app.AlertDialog.Builder(this)
                        .setMessage("Delete all chat messages on this phone? Other phones keep theirs.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (d2, w2) -> { hub.clearChats(); settingsPage("chat"); }).show());
                LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, -2);
                cp2.topMargin = u.dp(10);
                c.addView(clr, cp2);
                Button fleet = Cards.withIcon(this, u.button("Clear chat on every phone", true, true), Cards.Icon.TRASH);
                fleet.setOnClickListener(vv -> new android.app.AlertDialog.Builder(this).setTitle("Clear fleet chat?")
                        .setMessage("Deletes the saved team chat on this phone, every ManetNode phone and every node's LoRa message log, "
                                + "so the next operation starts clean. Runs fleet-wide on purpose.\n\nCannot be undone. A node or phone that "
                                + "is off picks it up when it rejoins. ATAK keeps its own chat history.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear everywhere", (d2, w2) -> {
                            hub.clearChats();
                            String fh = Nodes.wifiNode(this);
                            if (fh == null) {
                                Toast.makeText(this, "Cleared here only: join a node's Wi-Fi to clear the fleet", Toast.LENGTH_LONG).show();
                                settingsPage("chat");
                                return;
                            }
                            nodeCall(fh, fc -> fc.fleetClear("chat"), r -> {
                                Toast.makeText(this, "Fleet chat cleared: every node and phone on the mesh follows", Toast.LENGTH_LONG).show();
                                if (tab == 4) settingsPage("chat");
                            });
                        }).show());
                LinearLayout.LayoutParams fp2 = new LinearLayout.LayoutParams(-1, -2);
                fp2.topMargin = u.dp(8);
                c.addView(fleet, fp2);
                teamChannelSection(box, s);
            }
            // fall through: talk groups on the same page
            case "ptt": {
                LinearLayout tg = Cards.section(u, box, Cards.Icon.MIC, "Talk group channels", "CH " + ptt.group());
                TalkGroupBar bar = new TalkGroupBar(this, ptt);
                tg.addView(bar, new LinearLayout.LayoutParams(-1, -2));
                tg.addView(Cards.note(u, "Tap a channel to talk on it. Long-press to also listen to it. Green dot = someone talking there now."));
                LinearLayout a = Cards.section(u, box, Cards.Icon.RADIO, "Audio", null);
                a.addView(toggleRow("Phone audio", ptt.audioOn(), on -> { ptt.setAudioOn(on); settingsPage("chat"); }));
                LinearLayout g = Cards.section(u, box, Cards.Icon.LINK, "Talk group address", null);
                g.addView(u.text(ptt.address() + " · ports 38801–38809", 14, Ui.TEXT, false, true));
                Button edit = Cards.withIcon(this, u.button("Change address", true, false), Cards.Icon.EDIT);
                edit.setOnClickListener(vv -> Nodes.editTalkGroup(this, ptt, () -> settingsPage("chat")));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
                ep.topMargin = u.dp(10);
                g.addView(edit, ep);
                g.addView(Cards.note(u, "All phones, plugins and nodes on a channel must use the same address. "
                        + "OpenMANET's format: Opus 48 kHz, RTP payload 111."));
                break;
            }
            case "map": {
                positionSection(box, s);
                LinearLayout ms = Cards.section(u, box, Cards.Icon.MAP, "Map style", MapStyles.NAMES[mapStyle()].toUpperCase(Locale.US));
                LinearLayout srow = u.row();
                for (int i = 0; i < MapStyles.NAMES.length; i++) {
                    final int st = i;
                    Button bt = u.button(MapStyles.NAMES[i], false, false);
                    bt.setTextSize(12);
                    if (i == mapStyle()) bt.setBackground(u.box(0x3300E5FF, Ui.CYAN));
                    bt.setOnClickListener(vv -> { setMapStyle(st); settingsPage("map"); });
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
                    bp.rightMargin = u.dp(4);
                    srow.addView(bt, bp);
                }
                ms.addView(srow);
                ms.addView(Cards.note(u, "Street (OpenStreetMap) and Topo are shown dark. Satellite, Hybrid (satellite with roads and names) and Topo: "
                        + "USGS National Map, US only. Also on the map's top-right button."));
                LinearLayout ad = Cards.section(u, box, Cards.Icon.MAP, "Address", showAddr() ? "ON" : null);
                ad.addView(toggleRow("Show the street address in the coordinate box", showAddr(), on -> {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("show_addr", on).apply(); addrAsked = null; updateCoords(); settingsPage("map"); }));
                ad.addView(Cards.note(u, "Looked up for the map centre with the phone's address service, so it needs internet; with none, the line is simply left off."));
                LinearLayout c = Cards.section(u, box, Cards.Icon.MAP, "Mesh links", mapLinks() ? "ON" : null);
                c.addView(toggleRow("Lines between nodes on the map", mapLinks(), on -> {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("map_links", on).apply(); settingsPage("map"); }));
                c.addView(Cards.note(u, "coloured like the light of the node they lead to: solid = HaLow, dashed = 2.4 GHz, dotted = LoRa only. Nodes need a GPS fix or a position set under Node Settings."));
                LinearLayout bc = Cards.section(u, box, Cards.Icon.PERSON, "Breadcrumbs", items.trailsOn() ? "ON" : null);
                bc.addView(toggleRow("Trails behind you and the team", items.trailsOn(), on -> { items.setTrailsOn(on); settingsPage("map"); }));
                LinearLayout tl = u.row();
                for (int i = 0; i < MapItems.TRAIL_NAMES.length; i++) {
                    final int k = i;
                    Button bt = u.button(MapItems.TRAIL_NAMES[i], false, false);
                    bt.setTextSize(12);
                    if (i == items.trailLength()) bt.setBackground(u.box(0x3300E5FF, Ui.CYAN));
                    bt.setOnClickListener(vv -> { items.setTrailLength(k); settingsPage("map"); });
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
                    bp.rightMargin = u.dp(4);
                    tl.addView(bt, bp);
                }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
                tlp.topMargin = u.dp(8);
                bc.addView(tl, tlp);
                LinearLayout tb = u.row();
                Button st = u.button("Save my trail", true, false);
                st.setTextSize(12);
                st.setOnClickListener(vv -> saveTrail());
                Button ct = u.button("Clear trails", true, true);
                ct.setTextSize(12);
                ct.setOnClickListener(vv -> { items.clearTrails(); drawMap(); Toast.makeText(this, "Trails cleared", Toast.LENGTH_SHORT).show(); });
                LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, -2, 1);
                sp1.rightMargin = u.dp(6);
                tb.addView(st, sp1);
                tb.addView(ct, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(-1, -2);
                tbp.topMargin = u.dp(8);
                bc.addView(tb, tbp);
                bc.addView(Cards.note(u, "Dotted lines: blue is you, cyan is the team (from their position reports). Kept on this phone "
                        + "while the app runs. Save my trail writes a .kml to Download/ManetNode."));
                java.util.List<MapItems.Item> all = items.all();
                LinearLayout pi = Cards.section(u, box, Cards.Icon.MAP, "Points & geofences", all.isEmpty() ? null : String.valueOf(all.size()));
                if (all.isEmpty()) pi.addView(u.text("None yet. On the map: long-press to drop a point, or the + button for points and geofences.", 13, Ui.SUB, false, false));
                for (MapItems.Item it : all) {
                    String sub = it.typeName() + " · " + (it.mine ? "you" : it.by.isEmpty() ? "ATAK" : it.by);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.bottomMargin = u.dp(6);
                    pi.addView(row(it.name, sub, () -> { select(0); map.getController().animateTo(new GeoPoint(it.lat, it.lon)); ui.postDelayed(() -> itemInfo(it), 600); }), rp);
                }
                pi.addView(toggleRow("Geofence alerts on this phone", items.alertsOn(), on -> { items.setAlertsOn(on); settingsPage("map"); }));
                if (!items.mine().isEmpty()) {
                    Button dm = Cards.withIcon(this, u.button("Delete all my points & geofences", true, true), Cards.Icon.TRASH);
                    dm.setOnClickListener(vv -> new android.app.AlertDialog.Builder(this).setTitle("Delete all yours?")
                            .setMessage("Removes your " + items.mine().size() + " points and geofences for everyone.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (d, w) -> { items.deleteMine(); settingsPage("map"); }).show());
                    LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
                    dp.topMargin = u.dp(8);
                    pi.addView(dm, dp);
                }
                pi.addView(Cards.note(u, "Shared with every app user on the mesh" + (items.shareToAtak() ? " and with ATAK." : ". To ATAK only with Share to ATAK on (Settings → Me).")
                        + " Tap one to go to it."));
                LinearLayout r = Cards.section(u, box, Cards.Icon.PULSE, "Refresh", pollSeconds() + " S");
                LinearLayout row = u.row();
                for (int sec : POLL_S) {
                    Button bt = u.button(sec + " s", false, false);
                    if (sec == pollSeconds()) bt.setBackground(u.box(0x3300E5FF, Ui.CYAN));
                    bt.setOnClickListener(vv -> { getPreferences(MODE_PRIVATE).edit().putInt("poll_s", sec).apply(); settingsPage("map"); });
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
                    bp.rightMargin = u.dp(6);
                    row.addView(bt, bp);
                }
                r.addView(row);
                r.addView(Cards.note(u, "How often the app asks the nodes. Faster uses more battery."));
                LinearLayout o = Cards.section(u, box, Cards.Icon.MAP, "Offline map", null);
                o.addView(u.text("Every map area you view is saved on this phone and works later without internet.", 13, Ui.SUB, false, false));
                Button clear = Cards.withIcon(this, u.button("Clear saved map tiles", true, true), Cards.Icon.TRASH);
                clear.setOnClickListener(vv -> {
                    deleteRecursive(new java.io.File(getFilesDir(), "osmdroid/tiles"));
                    Toast.makeText(this, "Saved map tiles cleared", Toast.LENGTH_SHORT).show();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
                cp.topMargin = u.dp(10);
                o.addView(clear, cp);
                break;
            }
            default: {                                                // about
                String version;
                try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { version = "?"; }
                LinearLayout c = Cards.section(u, box, Cards.Icon.INFO, "ManetNode " + version, null);
                c.addView(u.text("The field app for OpenMANET nodes with the Field Extension: map, team chat, push-to-talk, "
                        + "mesh topology, and each node's status, LoRa and settings.", 13, Ui.SUB, false, false));
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /** NODE page while on a node's Wi-Fi: connection card, the node's status and health, then saved nodes and password. */
    private void nodePage(LinearLayout top) {
        nodeSection(0);
        View fe = panel.getChildAt(panel.getChildCount() - 1);
        View sv = fe.findViewById(R.id.fe_page_status);
        if (!(sv instanceof ScrollView)) return;
        LinearLayout inner = (LinearLayout) ((ScrollView) sv).getChildAt(0);
        inner.addView(top, 0);
        LinearLayout bottom = u.column();
        bottom.setPadding(u.dp(14), u.dp(4), u.dp(14), u.dp(14));
        nodeExtras(bottom);
        inner.addView(bottom);
    }

    /** Saved nodes and the saved node password. */
    private void nodeExtras(LinearLayout box) {
        LinearLayout ns = Cards.section(u, box, Cards.Icon.NODES, "Saved nodes", null);
        for (String h : Nodes.saved(this)) {
            LinearLayout r = u.row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(u.text(h, 15, Ui.TEXT, false, true), new LinearLayout.LayoutParams(0, -2, 1));
            Button rm = Cards.withIcon(this, u.button("Remove", true, true), Cards.Icon.TRASH);
            rm.setOnClickListener(vv -> Nodes.removeNode(this, h, h, () -> settingsPage("connection")));
            r.addView(rm);
            ns.addView(r);
        }
        Button add = Cards.withIcon(this, u.button("Add a node", true, false), Cards.Icon.PLUS);
        add.setOnClickListener(vv -> Nodes.addNode(this, () -> settingsPage("connection")));
        ns.addView(add, new LinearLayout.LayoutParams(-1, -2));
        ns.addView(Cards.note(u, "The node whose Wi-Fi you are on is found automatically; add others to see them from here."));
        LinearLayout pw = Cards.section(u, box, Cards.Icon.PERSON, "Node password", Secret.saved(this) ? "SAVED" : null);
        pw.addView(u.text(Secret.saved(this) ? "Saved on this phone (encrypted). Every node signs in by itself."
                : "Not saved yet. It is saved the first time you sign in to a node.", 13, Ui.SUB, false, false));
        if (Secret.saved(this)) {
            Button forget = Cards.withIcon(this, u.button("Forget saved password", true, true), Cards.Icon.KEY);
            forget.setOnClickListener(vv -> { Secret.clear(this); Toast.makeText(this, "Password forgotten", Toast.LENGTH_SHORT).show(); settingsPage("connection"); });
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.topMargin = u.dp(10);
            pw.addView(forget, fp);
        }
    }

    // ---------------------------------------------------------------- LoRa team channel (chat with Meshtastic radios)
    static final String TEAM_DEFAULT = "ManetChat";            // 11 characters at most (Meshtastic channel name limit)

    /** Team chat channel on the nodes' LoRa radios: name + passphrase (the key), change it, share or scan it as a QR. */
    private void teamChannelSection(LinearLayout box, JSONObject s) {
        LinearLayout t = Cards.section(u, box, Cards.Icon.KEY, "Team chat channel", null);
        String host = Nodes.wifiNode(this);
        TextView now = u.text(host == null ? "Join a node's Wi-Fi to see or change it." : "Checking the node…", 14, Ui.TEXT, false, true);
        t.addView(now);
        Button change = Cards.withIcon(this, u.button("Change name / key", true, false), Cards.Icon.EDIT);
        Button share = u.button("Share QR", true, false);
        Button scan = u.button("Scan QR", true, false);
        LinearLayout row = u.row();
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
        hp.rightMargin = u.dp(8);
        row.addView(share, hp);
        row.addView(scan, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = u.dp(10);
        t.addView(change, cp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = u.dp(8);
        t.addView(row, rp);
        t.addView(Cards.note(u, "The private LoRa channel the nodes' radios use for team chat when the mesh is out of reach, and "
                + "for Meshtastic users. Every node and Meshtastic radio needs the same name and key. Changes go to every node over LoRa."));
        for (Button b : new Button[]{change, share, scan}) b.setEnabled(host != null);
        if (host == null) return;
        final String[] current = {null};
        io.execute(() -> {
            String name = null, err = null;
            try {
                FieldClient.Result r = new FieldClient("http://" + host + ":" + Nodes.FE_PORT).get("lora");
                JSONObject radio = r.ok() ? r.body.optJSONObject("radio") : null;
                if (radio == null || !radio.optBoolean("connected")) err = "No LoRa radio on this node";
                else name = FieldPanel.teamName(radio);
            } catch (Exception e) { err = "Node not reachable"; }
            String n = name, e = err;
            ui.post(() -> {
                current[0] = n;
                now.setText(e != null ? e : n == null || n.isEmpty() ? "None: radios use the public channel only" : n + " · private");
                now.setTextColor(e != null ? Ui.WARN : Ui.CYAN);
            });
        });
        change.setOnClickListener(v -> editTeam(host, current[0]));
        share.setOnClickListener(v -> shareTeam(host));
        scan.setOnClickListener(v -> ScanActivity.scan(this, code -> { if (code != null) applyScannedTeam(host, code); }));
    }

    private void editTeam(String host, String current) {
        LinearLayout form = u.column();
        form.setPadding(u.dp(20), u.dp(8), u.dp(20), 0);
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Channel name (up to 11 characters)");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(11)});
        name.setText(current == null || current.isEmpty() ? TEAM_DEFAULT : current);
        android.widget.EditText key = new android.widget.EditText(this);
        key.setHint("Key / passphrase (8 or more characters)");
        key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        Button gen = u.button("Make a random key", true, false);
        gen.setOnClickListener(v -> key.setText(randomKey()));
        form.addView(name);
        form.addView(key);
        form.addView(gen);
        new android.app.AlertDialog.Builder(this).setTitle("Team chat channel")
                .setMessage("Sent to every node over LoRa. Radios only hear each other with the same name and key.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim(), k = key.getText().toString();
                    if (n.isEmpty() || n.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 11 || k.length() < 8) {
                        Toast.makeText(this, "A name (up to 11 characters) and a key of at least 8 characters are needed", Toast.LENGTH_LONG).show();
                        return;
                    }
                    setTeam(host, n, k);
                }).show();
    }

    private static String randomKey() {
        String abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    private void setTeam(String host, String name, String key) {
        JSONObject body = new JSONObject();
        try { body.put("security", "private").put("name", name).put("passphrase", key).put("fleet", true); } catch (Exception ignored) { }
        nodeWrite(host, "lora/channel", body, r -> {
            Toast.makeText(this, "Team chat channel set to " + name, Toast.LENGTH_LONG).show();
            if (tab == 4) settingsPage("chat");
        });
    }

    private interface NodeCall { FieldClient.Result run(FieldClient fc) throws Exception; }

    private void nodeWrite(String host, String path, JSONObject body, java.util.function.Consumer<FieldClient.Result> ok) {
        nodeCall(host, fc -> fc.loraWrite(path.substring(5), body), ok);
    }

    /** Signed-in call to the node (saved password, one retry after an expired session); ok result to the UI thread. */
    private void nodeCall(String host, NodeCall call, java.util.function.Consumer<FieldClient.Result> ok) {
        io.execute(() -> {
            String msg;
            try {
                FieldClient fc = new FieldClient("http://" + host + ":" + Nodes.FE_PORT, getSharedPreferences("node", MODE_PRIVATE));
                String[] login = Secret.load(this);
                if (!fc.signedIn() && (login == null || !fc.login(login[0], login[1]).ok())) {
                    ui.post(() -> Toast.makeText(this, "Sign in to the node once first (Settings → LoRa)", Toast.LENGTH_LONG).show());
                    return;
                }
                FieldClient.Result r = call.run(fc);
                if (r != null && r.code == 401 && login != null && fc.login(login[0], login[1]).ok()) r = call.run(fc);
                if (r != null && r.ok()) { FieldClient.Result rr = r; ui.post(() -> ok.accept(rr)); return; }
                msg = r == null ? "Request failed" : r.error();
            } catch (Exception e) { msg = "Node not reachable"; }
            String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    /** One QR for everyone: the node's Meshtastic channel link. ManetNode phones (Scan QR) and the Meshtastic app both take it. */
    private void shareTeam(String host) {
        nodeWrite(host, "lora/share", new JSONObject(), r -> {
            String url = r.body.optString("url", "");
            if (url.isEmpty()) Toast.makeText(this, "No team channel to share", Toast.LENGTH_SHORT).show();
            else showQr("Team chat channel", "Scan with ManetNode (Settings → Comms → Scan QR) or the Meshtastic app "
                    + "(Channels, then the QR button). It contains the key: only show it to your team.", url);
        });
    }

    private void showQr(String title, String msg, String text) {
        android.widget.ImageView img = new android.widget.ImageView(this);
        try { img.setImageBitmap(FieldPanel.qrBitmap(text, u.dp(260))); } catch (Exception e) {
            Toast.makeText(this, "Could not draw the QR code", Toast.LENGTH_SHORT).show();
            return;
        }
        img.setPadding(u.dp(8), u.dp(8), u.dp(8), u.dp(8));
        new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg).setView(img).setPositiveButton("Done", null).show();
    }

    /** A scanned Meshtastic channel link (from ManetNode or the Meshtastic app) becomes the team channel on every node (node dev46+). */
    private void applyScannedTeam(String host, String code) {
        if (!code.contains("meshtastic.org/e/")) {
            Toast.makeText(this, "Not a team chat channel code", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("Join this team chat channel?")
                .setMessage("Every node switches its LoRa radio to the private channel in this code.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Join", (d, w) -> {
                    JSONObject body = new JSONObject();
                    try { body.put("url", code.trim()).put("fleet", true); } catch (Exception ignored) { }
                    nodeWrite(host, "lora/channel", body, r -> {
                        Toast.makeText(this, "Team chat channel changed", Toast.LENGTH_LONG).show();
                        if (tab == 4) settingsPage("chat");
                    });
                }).show();
    }

    /** The node's own web UI section (0 status+health, 1 LoRa, 2 settings), same screens as the plugin. */
    private void nodeSection(int page) {
        String host = Nodes.wifiNode(this);
        if (host == null) {
            TextView none = u.text("Join a node's Wi-Fi first.", 14, Ui.SUB, false, false);
            none.setPadding(u.dp(18), u.dp(10), u.dp(18), 0);
            panel.addView(none);
            return;
        }
        View fe = getLayoutInflater().inflate(R.layout.field_panel, panel, false);
        ((View) fe.findViewById(R.id.fe_tab_status).getParent()).setVisibility(View.GONE);
        fe.setBackground(null);
        for (int i = 0; i < FE_PAGES.length; i++) fe.findViewById(FE_PAGES[i]).setVisibility(i == page ? View.VISIBLE : View.GONE);
        panel.addView(fe, new LinearLayout.LayoutParams(-1, 0, 1));
        settingsPanel = new FieldPanel(this, fe, new FieldClient("http://" + host + ":" + Nodes.FE_PORT,
                getSharedPreferences("node", MODE_PRIVATE)), () -> select(4), () -> { });
        settingsPanel.refresh();
        settingsPanel.loginScreen(fe);
    }

    private interface OnToggle { void set(boolean on); }

    /** A fleet clear the node reports that this phone hasn't applied yet: drop chat from before it. Off the UI thread. */
    private void fleetClearSeen(JSONObject s) {
        JSONObject fc = s.optJSONObject("fleet_clear");
        if (fc == null) return;
        String id = fc.optString("id", "");
        if (id.isEmpty() || id.equals(getPreferences(MODE_PRIVATE).getString("fleet_clear_id", ""))) return;
        String what = fc.optString("what", "chat");
        double at = fc.optDouble("at", 0);
        long ms = at > 1e12 ? (long) at : (long) (at * 1000);
        getPreferences(MODE_PRIVATE).edit().putString("fleet_clear_id", id).apply();
        if (ms > 0 && ("chat".equals(what) || "both".equals(what))) hub.clearChatsBefore(ms);
    }

    /** Position source (which GPS the node on this phone's Wi-Fi uses) and the GPS state of node and phone. */
    private void positionSection(LinearLayout box, JSONObject s) {
        String src = s == null ? (givePos() ? "auto" : "node") : s.optString("position_source", givePos() ? "auto" : "node");
        LinearLayout ps = Cards.section(u, box, Cards.Icon.MAP, "Position source", src.toUpperCase(Locale.US));
        String[][] opts = {{"auto", "Auto", "Node GPS, falling back to this phone when the node has no fix. Default."},
                {"node", "Node GPS", "The node only. Without a fix it shows as position unknown (or a position set by hand under Node Settings)."},
                {"phone", "Phone GPS", "This phone only, even when the node has a fix. Use when the node's GPS is misbehaving."}};
        Cards.Icon[] icons = {Cards.Icon.MESH, Cards.Icon.CHIP, Cards.Icon.PERSON};
        String host = Nodes.wifiNode(this);
        for (int i = 0; i < opts.length; i++) {
            String key = opts[i][0];
            ps.addView(sourceOption(icons[i], opts[i][1], opts[i][2], key.equals(src), () -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean("give_pos", !"node".equals(key)).apply();
                if (host == null) { settingsPage("map"); return; }
                nodeCall(host, fc -> fc.setPositionSource(key), r -> {
                    lastGiven = 0;                                   // send the phone's position at the next poll
                    JSONObject cur = status.get(host);
                    if (cur != null) try { cur.put("position_source", key); } catch (Exception ignored) { }
                    if (tab == 4) settingsPage("map");
                });
            }));
        }
        ps.addView(Cards.note(u, "Set on the node you're connected to: decides where it shows on everyone's map and in ATAK. "
                + "This phone sends its position about every minute when it is used."));
        LinearLayout g = Cards.section(u, box, Cards.Icon.PULSE, "GPS", null);
        JSONObject gps = s == null ? null : s.optJSONObject("gps"), pos = s == null ? null : s.optJSONObject("position");
        boolean fix = gps != null && "fix".equalsIgnoreCase(gps.optString("state"));
        String gstate = s == null ? "Not on a node's Wi-Fi" : gps == null || !gps.optBoolean("available", true) ? "No GPS"
                : fix ? "Fix" + (gps.has("sats") ? " · " + gps.optInt("sats") + " satellites" : "") : gps.optString("message", gps.optString("state", "—"));
        g.addView(Cards.statusRow(u, fix ? Ui.OK : s == null ? Ui.MUTED : Ui.WARN, "Node GPS", gstate));
        String from = pos == null ? null : pos.optString("source", "");
        g.addView(Cards.statusRow(u, pos == null ? Ui.MUTED : Ui.OK, "Node position",
                pos == null ? (s == null ? "—" : "Unknown") : "gps".equals(from) || from.isEmpty() ? "From its GPS"
                        : "eud".equals(from) ? "From this phone" : "set".equals(from) ? "Set by hand" : from));
        android.location.Location me = hub.here();
        g.addView(Cards.statusRow(u, me == null ? Ui.WARN : Ui.OK, "Phone GPS",
                me == null ? "Waiting for location" : me.hasAccuracy() ? String.format(Locale.US, "±%.0f m", me.getAccuracy()) : "Located"));
    }

    private View sourceOption(Cards.Icon icon, String title, String sub, boolean on, Runnable pick) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(u.dp(12), u.dp(12), u.dp(12), u.dp(12));
        if (on) r.setBackground(u.box(0x2600E5FF, 0x5500E5FF));
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageDrawable(Cards.icon(this, icon, 24, on ? Ui.CYAN : Ui.SUB));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2);
        ip.rightMargin = u.dp(14);
        r.addView(ic, ip);
        LinearLayout col = u.column();
        col.addView(u.text(title, 16, on ? Ui.CYAN : Ui.TEXT, false, true));
        col.addView(u.text(sub, 12, Ui.SUB, false, false));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tick = u.text(on ? "✓" : "", 16, 0xFF05080A, true, false);
        tick.setGravity(Gravity.CENTER);
        if (on) tick.setBackground(u.circle(Ui.CYAN));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(u.dp(26), u.dp(26));
        tp.leftMargin = u.dp(10);
        r.addView(tick, tp);
        r.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = u.dp(6);
        r.setLayoutParams(rp);
        return r;
    }

    private View toggleRow(String label, boolean on, OnToggle t) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(u.text(label, 15, Ui.TEXT, false, false), new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch sw = new android.widget.Switch(this);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{Ui.CYAN, 0xFFB8C7CF}));
        sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{0x8800E5FF, 0x55B8C7CF}));
        sw.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> t.set(v));
        r.addView(sw);
        return r;
    }

    private static void deleteRecursive(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        f.delete();
    }

    private View row(String title, String sub, Runnable click) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(u.box(0xFF07100F, Ui.LINE));
        r.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14));
        LinearLayout col = u.column();
        col.addView(u.text(title, 17, Ui.TEXT, false, true));
        col.addView(u.text(sub, 13, Ui.SUB, false, true));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            r.addView(u.text("›", 26, Ui.SUB, false, false));
            r.setOnClickListener(v -> click.run());
        }
        return r;
    }
}
