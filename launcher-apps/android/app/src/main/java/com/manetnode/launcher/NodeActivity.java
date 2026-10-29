package com.manetnode.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

/**
 * One node, shown natively like the ATAK plugin: STATUS, HEALTH, SETTINGS and LORA tabs with
 * the same readings and controls (light, power, phone internet, LoRa, sign-in) as the node's web page.
 */
public class NodeActivity extends Activity {
    static final String HOST = "host";
    private static final long REFRESH_MS = 15000;
    private static final String PREF_TAB = "node_tab";
    private static final int[] TABS = {R.id.fe_tab_status, R.id.fe_tab_lora, R.id.fe_tab_settings};
    private static final int[] PAGES = {R.id.fe_page_status, R.id.fe_page_lora, R.id.fe_page_settings};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FieldPanel panel;
    private View root;
    private boolean resumed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            panel.refresh();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String host = getIntent().getStringExtra(HOST);
        if (host == null) { finish(); return; }
        root = getLayoutInflater().inflate(R.layout.field_panel, null);
        root.setBackground(new TopoBackground(Ui.BG, Ui.CYAN, getResources().getDisplayMetrics().density));
        setContentView(root);
        for (int i = 0; i < TABS.length; i++) {
            final int tab = i;
            root.findViewById(TABS[i]).setOnClickListener(v -> selectTab(tab));
        }
        selectTab(prefs().getInt(PREF_TAB, 0));
        // Sign-in is kept per node in the app's own preferences, like the plugin does in ATAK's.
        panel = new FieldPanel(this, root, new FieldClient("http://" + host + ":" + Nodes.FE_PORT, prefs()),
                this::finish, () -> { });
        panel.loginScreen(root);
    }

    private void selectTab(int tab) {
        if (tab < 0 || tab >= TABS.length) tab = 0;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            root.findViewById(PAGES[i]).setVisibility(on ? View.VISIBLE : View.GONE);
            TextView t = root.findViewById(TABS[i]);
            t.setBackgroundColor(on ? getResources().getColor(R.color.fe_tab_active, null) : 0);
            t.setTextColor(getResources().getColor(on ? R.color.fe_cyan : R.color.fe_sub, null));
        }
        prefs().edit().putInt(PREF_TAB, tab).apply();
    }

    private SharedPreferences prefs() { return getSharedPreferences("node", MODE_PRIVATE); }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (panel != null) panel.dispose();
    }
}
