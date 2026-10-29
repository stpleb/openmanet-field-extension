package com.manetnode.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A node's web page inside the app, under a slim bar with Back and Reload. */
public class WebActivity extends Activity {
    static final String URL = "url", TITLE = "title";
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui u = new Ui(this);
        LinearLayout page = u.column();

        LinearLayout bar = u.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.PANEL);
        bar.setPadding(u.dp(8), u.dp(6), u.dp(8), u.dp(6));
        Button back = u.button("‹ Nodes", false, false);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = u.text(getIntent().getStringExtra(TITLE), 14, Ui.TEXT, true, false);
        title.setSingleLine(true);
        title.setPadding(u.dp(12), 0, u.dp(12), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = u.button("Reload", true, false);
        reload.setOnClickListener(v -> web.reload());
        bar.addView(reload);
        page.addView(bar);

        web = new WebView(this);
        web.setBackgroundColor(Ui.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // both web pages are JavaScript apps
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) { title.setAlpha(0.6f); }

            @Override
            public void onPageFinished(WebView v, String url) { title.setAlpha(1f); }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                v.loadDataWithBaseURL(null, "<body style='background:#05080a;color:#d9e6ed;font-family:sans-serif;padding:24px'>"
                        + "<h2 style='color:#ff3b4d'>Node not reachable</h2><p>Check that this phone is on the node's Wi-Fi, then tap Reload.</p>"
                        + "<p style='color:#8fa7b2'>" + getIntent().getStringExtra(URL) + "</p></body>", "text/html", "utf-8", null);
            }
        });
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        web.loadUrl(getIntent().getStringExtra(URL));
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
