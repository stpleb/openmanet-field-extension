package com.fieldext.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Toolbar button + pane hosting the compact Field Extension panel.
 * Node URL: manual override (long-press the node name) or the Wi-Fi DHCP server, port 8787.
 */
public class FieldExtPlugin implements IPlugin {

    private static final String PREF_NODE_URL = "fieldext_node_url";
    private static final String PREF_TAB = "fieldext_tab";
    private static final int[] TABS = {R.id.fe_tab_ptt, R.id.fe_tab_status, R.id.fe_tab_lora, R.id.fe_tab_settings};
    private static final int[] PAGES = {R.id.fe_page_ptt, R.id.fe_page_status, R.id.fe_page_lora, R.id.fe_page_settings};
    private static final int FE_PORT = 8787;          // 8080/8081/8087 belong to openmanetd

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Pane pane;
    private View root;
    private FieldPanel panel;
    private String panelUrl;
    private boolean askedForUrl;
    private StatusWidget statusWidget;
    private NodeMarkers nodeMarkers;
    private PttTab pttTab;
    private final java.util.concurrent.ExecutorService statusIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final long STATUS_MS = 20000;

    /** Keeps the corner status dot current while the plugin is loaded, pane open or not. */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (statusWidget != null && !statusWidget.attached())
                statusWidget.attach();                 // map may not have been ready at onStart
            final String url = nodeUrl();
            statusIo.execute(() -> {
                int colour = StatusWidget.OFFLINE;
                if (url != null) {
                    try {
                        FieldClient.Result r = new FieldClient(url).get("summary");
                        if (r.ok()) {
                            int led = FieldPanel.ledColour(r.body.optJSONObject("led"));
                            colour = led == FieldPanel.ORB_OFF ? FieldPanel.OK : led;
                        }
                    } catch (Exception ignored) {
                    }
                }
                final int c = colour;
                handler.post(() -> { if (statusWidget != null) statusWidget.setDot(c); });
            });
            handler.postDelayed(this, STATUS_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (pane == null || uiService == null || !uiService.isPaneVisible(pane))
                return;                                // pane closed: stop polling
            refresh();
            handler.postDelayed(this, FieldPanel.pollSeconds(hostContext()) * 1000L);
        }
    };

    public FieldExtPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        pluginContext = ctxProvider.getPluginContext();
        pluginContext.setTheme(R.style.ATAKPluginTheme);

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        new android.graphics.drawable.BitmapDrawable(pluginContext.getResources(), StatusWidget.toolbarIcon()),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null)
            uiService.addToolbarItem(toolbarItem);
        handler.post(() -> {
            statusWidget = new StatusWidget(pluginContext, this::showPane);
            statusWidget.attach();
            handler.post(statusTick);
            nodeMarkers = new NodeMarkers(hostContext(), this::nodeUrl);
            nodeMarkers.start();
            handler.postDelayed(posFeed, 15000);
        });
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(posFeed);
        statusIo.shutdownNow();
        if (statusWidget != null)
            statusWidget.detach();
        statusWidget = null;
        if (nodeMarkers != null)
            nodeMarkers.stop();
        nodeMarkers = null;
        if (panel != null)
            panel.dispose();
        if (pttTab != null)
            pttTab.dispose();
        pttTab = null;
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (pane == null) {
            root = PluginLayoutInflater.inflate(pluginContext, R.layout.field_panel, null);
            for (int i = 0; i < TABS.length; i++) {
                final int tab = i;
                root.findViewById(TABS[i]).setOnClickListener(v -> selectTab(tab));
            }
            selectTab(prefs().getInt(PREF_TAB, 0));
            pttTab = new PttTab(hostContext(), root.findViewById(R.id.fe_ptt_box));
            FieldPanel.pollSetting = true;
            final android.content.Context hc = hostContext();       // password once, for every node
            FieldPanel.savedLogin = () -> Secret.load(hc);
            FieldPanel.rememberLogin = (user, pass) -> Secret.save(hc, user, pass);
            FieldPanel.forgetLogin = () -> Secret.clear(hc);
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.4D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.55D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane)) {
            askedForUrl = false;
            uiService.showPane(pane, null);
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Show one page; the active tab is tinted like the OpenMANET plugin's. */
    private void selectTab(int tab) {
        if (tab < 0 || tab >= TABS.length)
            tab = 0;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            root.findViewById(PAGES[i]).setVisibility(on ? View.VISIBLE : View.GONE);
            android.widget.TextView t = root.findViewById(TABS[i]);
            t.setBackgroundColor(on ? pluginContext.getResources().getColor(R.color.fe_tab_active) : 0);
            t.setTextColor(pluginContext.getResources().getColor(on ? R.color.fe_cyan : R.color.fe_sub));
        }
        prefs().edit().putInt(PREF_TAB, tab).apply();
    }

    /** Rebuild the panel if the node URL changed (DHCP server moved or override edited). */
    private void refresh() {
        String url = nodeUrl();
        if (url == null) {
            if (!askedForUrl) {                       // once per pane opening, not every poll
                askedForUrl = true;
                Toast.makeText(hostContext(), "Field Extension: no node found on this Wi-Fi. Enter its address.",
                        Toast.LENGTH_LONG).show();
                editNodeUrl();
            }
            return;
        }
        if (panel == null || !url.equals(panelUrl)) {
            if (panel != null)
                panel.dispose();
            FieldPanel.here = () -> {                    // ATAK's own position, for "set node position here"
                MapView mv = MapView.getMapView();
                com.atakmap.android.maps.Marker me = mv == null ? null : mv.getSelfMarker();
                com.atakmap.coremap.maps.coords.GeoPoint g = me == null ? null : me.getPoint();
                return g == null || !g.isValid() ? null : new double[]{g.getLatitude(), g.getLongitude(), g.getAltitude()};
            };
            panel = new FieldPanel(hostContext(), root, new FieldClient(url, prefs()), this::editNodeUrl,
                    () -> { if (nodeMarkers != null) nodeMarkers.poke(); });
            panelUrl = url;
            panel.loginScreen(root);                  // like the OpenMANET plugin: sign in first, then the tabs
        }
        panel.refresh();
    }

    /**
     * Position source "phone" (or "auto" while the node has no GPS fix): give the node ATAK's own position about
     * every minute, so it shows in the right place for everyone. Uses the saved node password.
     */
    private final Runnable posFeed = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 60000);
            String url = nodeUrl();
            MapView mv = MapView.getMapView();
            com.atakmap.android.maps.Marker me = mv == null ? null : mv.getSelfMarker();
            com.atakmap.coremap.maps.coords.GeoPoint g = me == null ? null : me.getPoint();
            if (url == null || g == null || !g.isValid() || (g.getLatitude() == 0 && g.getLongitude() == 0)) return;
            double lat = g.getLatitude(), lon = g.getLongitude(), alt = g.getAltitude();
            Context hc = hostContext();
            try {
                statusIo.execute(() -> {
                    try {
                        FieldClient c = new FieldClient(url, prefs());
                        FieldClient.Result r = c.summary();
                        if (!r.ok()) return;
                        String src = r.body.optString("position_source", "");
                        org.json.JSONObject gps = r.body.optJSONObject("gps");
                        boolean fix = gps != null && "fix".equalsIgnoreCase(gps.optString("state"));
                        if (!("phone".equals(src) || ("auto".equals(src) && !fix))) return;
                        String[] login = Secret.load(hc);
                        if (!c.signedIn() && (login == null || !c.login(login[0], login[1]).ok())) return;
                        double a = Double.isNaN(alt) || alt < -1000 || alt > 20000 ? Double.NaN : alt;
                        r = c.setPhonePosition(lat, lon, a);
                        if (r.code == 401 && login != null && c.login(login[0], login[1]).ok()) c.setPhonePosition(lat, lon, a);
                    } catch (Exception ignored) {
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
        }
    };

    private String nodeUrl() {
        String override = prefs().getString(PREF_NODE_URL, "").trim();
        if (!override.isEmpty())
            return override.contains("://") ? override : "http://" + override;
        String dhcp = dhcpServer();
        return dhcp == null ? null : "http://" + dhcp + ":" + FE_PORT;
    }

    /** DHCP server of the current Wi-Fi network; in cellular-fallback mode there is no gateway to use. */
    @SuppressWarnings("deprecation")
    private String dhcpServer() {
        WifiManager wm = (WifiManager) hostContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null || wm.getDhcpInfo() == null)
            return null;
        int a = wm.getDhcpInfo().serverAddress;
        if (a == 0)
            return null;
        return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
    }

    private void editNodeUrl() {
        final EditText in = new EditText(hostContext());
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setHint("http://10.41.0.1:8787 (blank = automatic)");
        in.setText(prefs().getString(PREF_NODE_URL, ""));
        new AlertDialog.Builder(hostContext())
                .setTitle("Field Extension node address")
                .setView(in)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    prefs().edit().putString(PREF_NODE_URL, in.getText().toString().trim()).apply();
                    refresh();
                })
                .show();
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(hostContext());
    }

    /** ATAK's activity context: needed for dialogs, toasts and starting the browser. */
    private Context hostContext() {
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : pluginContext;
    }
}
