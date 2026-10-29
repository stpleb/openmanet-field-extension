package com.fieldext.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The plugin's PTT tab: the same push-to-talk as the ManetNode app (OpenMANET comms format, so it
 * talks with OpenMANET nodes, ManetNode phones and other plugin users), so one plugin is enough.
 */
final class PttTab implements PttEngine.Listener {
    private final Context ctx;
    private final PttEngine ptt;
    private final float d;
    private final PttButton button;
    private final TextView audio, group, foot;
    private TalkGroupBar bar;

    PttTab(Context ctx, LinearLayout box) {
        this.ctx = ctx;
        this.ptt = PttEngine.get(ctx);
        this.d = ctx.getResources().getDisplayMetrics().density;
        ptt.start();
        box.removeAllViews();
        button = new PttButton(ctx);
        button.setPress(new PttButton.Press() {
            @Override public boolean down() {
                if (ptt.pressed()) return true;
                String why = ptt.error();
                Toast.makeText(ctx, why == null ? "Can't talk now" : why, Toast.LENGTH_SHORT).show();
                return false;
            }
            @Override public void up() { ptt.released(); }
        });
        box.addView(button, new LinearLayout.LayoutParams(-1, dp(300)));
        fitToView(box, button);                                          // button fills the visible area; channels below
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER);
        audio = pill();
        audio.setOnClickListener(v -> ptt.setAudioOn(!ptt.audioOn()));
        group = pill();
        group.setOnClickListener(v -> editGroup());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.leftMargin = dp(8);
        row.addView(audio);
        row.addView(group, gp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = dp(6);
        box.addView(row, rp);
        bar = new TalkGroupBar(ctx, ptt);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(4);
        box.addView(bar, bp);
        foot = new TextView(ctx);
        foot.setTextSize(11);
        foot.setTypeface(Typeface.MONOSPACE);
        foot.setTextColor(FieldPanel.SUB);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(6), 0, 0);
        box.addView(foot);
        ptt.addListener(this);
        changed();
    }

    /** Size the button to the visible height of the scrolling tab, so it all shows and the rest scrolls below. */
    static void fitToView(LinearLayout box, android.view.View button) {
        android.view.View page = (android.view.View) box.getParent();
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = v.getHeight() - box.getPaddingTop() - box.getPaddingBottom();
            if (h <= 0) return;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
            if (lp.height != h) { lp.height = h; button.post(button::requestLayout); }
        });
    }

    void dispose() {
        ptt.removeListener(this);
        if (ptt.state() == PttEngine.State.TALKING) ptt.released();
    }

    private int dp(float v) { return Math.round(v * d); }

    private TextView pill() {
        TextView t = new TextView(ctx);
        t.setTextSize(12);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        return t;
    }

    private static GradientDrawable box(int fill, int stroke, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, Math.round(d)), stroke);
        g.setCornerRadius(2 * d);
        return g;
    }

    @Override
    public void changed() {
        PttEngine.State st = ptt.state();
        switch (st) {
            case TALKING: button.show(FieldPanel.BAD, "TRANSMITTING", true); break;
            case RECEIVING: button.show(FieldPanel.OK, "RECEIVING · CH " + ptt.lastReceivedGroup(), true); break;
            case OFF: button.show(FieldPanel.MUTED, "SET TALK GROUP", false); break;
            default: button.show(FieldPanel.CYAN, "HOLD TO TALK", false);
        }
        boolean on = ptt.audioOn();
        audio.setText(on ? "🔊 PHONE AUDIO ON" : "🔇 PHONE AUDIO OFF");
        audio.setTextColor(on ? FieldPanel.CYAN : FieldPanel.SUB);
        audio.setBackground(box(on ? 0x1A00E5FF : 0xFF07100F, on ? FieldPanel.CYAN : FieldPanel.LINE, d));
        group.setText("ADDR");
        if (bar != null) bar.invalidate();
        group.setTextColor(FieldPanel.CYAN);
        group.setBackground(box(0xFF07100F, FieldPanel.LINE, d));
        foot.setText("Talk on CH " + ptt.group() + " · " + ptt.address() + " · tap a channel to talk, long-press to listen" + (ptt.error() != null && st != PttEngine.State.TALKING ? "\n" + ptt.error() : ""));
    }

    private void editGroup() {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText addr = new EditText(ctx);
        addr.setHint("Multicast address");
        addr.setText(ptt.address());
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(addr);
        EditText grp = new EditText(ctx);
        grp.setHint("Talk group 1-5");
        grp.setText(String.valueOf(ptt.group()));
        grp.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(grp);
        new AlertDialog.Builder(ctx).setTitle("Talk group").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dlg, w) -> {
                    int g;
                    try { g = Integer.parseInt(grp.getText().toString().trim()); } catch (NumberFormatException e) { g = 1; }
                    ptt.configure(addr.getText().toString(), Math.max(1, Math.min(5, g)));
                }).show();
    }
}
