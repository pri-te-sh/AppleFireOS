package com.applefireos.home;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control Center (tvOS 26 style, deliberately lean): Power Off, Sleep Timer, Bluetooth, Settings
 * and the screensaver, plus what's playing. Opens with Up from the Top Shelf or the top row.
 */
final class ControlCenter implements NativeHome.Layer {
    private static final int COL_W = 300, ROW_H = 128, GAP = 22, NP_H = 112;
    private static final String[][] MAP = {
            // id, up, down, left, right
            { "avatar", null, "np", "vpn", null },
            { "vpn", null, "np", null, "avatar" },
            { "np", "avatar", "power", null, null },
            { "power", "np", "settings", null, "timer" },
            { "timer", "np", "bluetooth", "power", null },
            { "bluetooth", "timer", "aerial", "power", null },
            { "settings", "power", null, null, "aerial" },
            { "aerial", "bluetooth", null, "settings", null },
    };

    private final NativeHome home;
    private final FrameLayout root;
    private final Map<String, View> cells = new HashMap<>();
    private final List<View> order = new ArrayList<>();
    private TextView timerLabel, npTitle, npArtist;
    private ImageView npArt, npButton;
    private View vpnChip;
    private View avatar;
    private String sel = "power";
    private long timerEnd;
    private NowPlaying.Info np;
    private final Runnable tick = this::renderTimer;

    ControlCenter(NativeHome home) {
        this.home = home;
        root = new FrameLayout(home);
        root.setBackground(Kit.sideScrim());
        root.setAlpha(0);
        root.setOnClickListener(v -> back());

        np = NowPlaying.info(home);
        int top = 118 + (np != null ? NP_H + GAP : 0);
        int right = Ui.STAGE_W - 72, left = right - 2 * COL_W - GAP;

        // Header: time, VPN, avatar.
        LinearLayout head = new LinearLayout(home);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = Kit.overArt(Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL, home.clockText()));
        head.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        vpnChip = home.vpnChip();
        if (vpnChip != null) {
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, 58);
            vp.rightMargin = 20;
            head.addView(vpnChip, vp);
            cells.put("vpn", vpnChip);
            vpnChip.setOnClickListener(v -> { select("vpn"); activate(); });
        }
        avatar = home.avatarView(50);
        head.addView(avatar, new LinearLayout.LayoutParams(50, 50));
        cells.put("avatar", avatar);
        avatar.setOnClickListener(v -> { select("avatar"); activate(); });
        root.addView(head, Ui.at(left + 24, 44, right - left - 24, 66));

        if (np != null) {
            Glass t = tile("np", left, 118, 2 * COL_W + GAP, NP_H, 36);
            LinearLayout row = new LinearLayout(home);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16, 0, 30, 0);
            npArt = new ImageView(home);
            npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.rounded(npArt, 18);
            row.addView(npArt, new LinearLayout.LayoutParams(80, 80));
            LinearLayout text = new LinearLayout(home);
            text.setOrientation(LinearLayout.VERTICAL);
            npTitle = Kit.line(Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL));
            npArtist = Kit.line(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2));
            text.addView(npTitle);
            text.addView(npArtist);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = 20;
            tp.rightMargin = 20;
            row.addView(text, tp);
            npButton = icon(R.drawable.ic_play, 44, 0);
            row.addView(npButton, new LinearLayout.LayoutParams(44, 44));
            t.addView(row, Ui.fill());
            paintNowPlaying();
        }

        // Power: tall, icon above its label.
        Glass power = tile("power", left, top, COL_W, 2 * ROW_H + GAP, 50);
        LinearLayout pc = new LinearLayout(home);
        pc.setOrientation(LinearLayout.VERTICAL);
        pc.setGravity(Gravity.CENTER);
        pc.addView(icon(R.drawable.ic_cc_power, 110, 0), new LinearLayout.LayoutParams(110, 110));
        TextView pl = Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL, "Power Off");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.topMargin = 18;
        pc.addView(pl, plp);
        power.addView(pc, Ui.fill());

        timerLabel = pill("timer", left + COL_W + GAP, top, R.drawable.ic_cc_timer, Theme.CYAN, "Sleep Timer", null);
        pill("bluetooth", left + COL_W + GAP, top + ROW_H + GAP, R.drawable.ic_cc_bluetooth, Theme.BLUE, "Bluetooth", "Headphones");
        pill("settings", left, top + 2 * (ROW_H + GAP), R.drawable.ic_cc_settings, 0, "Settings", null);
        pill("aerial", left + COL_W + GAP, top + 2 * (ROW_H + GAP), R.drawable.ic_cc_aerial, 0, "Screensaver", null);
    }

    private ImageView icon(int res, int size, int tint) {
        ImageView i = new ImageView(home);
        i.setImageResource(res);
        if (tint != 0) i.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        return i;
    }

    private Glass tile(String id, int x, int y, int w, int h, float radius) {
        Glass g = new Glass(home, radius, id.equals("power"));
        g.setPivotX(w / 2f);
        g.setPivotY(h / 2f);
        g.setOnClickListener(v -> { select(id); activate(); });
        root.addView(g, Ui.at(x, y, w, h));
        cells.put(id, g);
        order.add(g);
        return g;
    }

    private TextView pill(String id, int x, int y, int iconRes, int tint, String label, String sub) {
        Glass g = tile(id, x, y, COL_W, ROW_H, 64);
        LinearLayout row = new LinearLayout(home);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(28, 0, 28, 0);
        row.addView(icon(iconRes, 48, tint), new LinearLayout.LayoutParams(48, 48));
        LinearLayout text = new LinearLayout(home);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView b = Kit.line(Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL, label));
        text.addView(b);
        if (sub != null) {
            TextView e = Kit.text(home, Theme.Type.CAPTION, Theme.LABEL_2, sub);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
            ep.topMargin = 4;
            text.addView(e, ep);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = 18;
        row.addView(text, tp);
        g.addView(row, Ui.fill());
        return b;
    }

    // ---- state ------------------------------------------------------------------------------

    private void paintNowPlaying() {
        if (np == null || npTitle == null) return;
        npTitle.setText(np.title.isEmpty() ? np.app : np.title);
        npArtist.setText(np.artist.isEmpty() ? np.app : np.artist);
        npArt.setImageBitmap(np.art);
        npArt.setVisibility(np.art != null ? View.VISIBLE : View.INVISIBLE);
        npButton.setImageResource(np.playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private static String countdown(long ms) {
        long total = (ms + 999) / 1000, h = total / 3600, m = total / 60 % 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private void renderTimer() {
        try {
            timerEnd = new JSONObject(Controls.state(home)).optLong("timerEnd");
        } catch (Exception e) {
            timerEnd = 0;
        }
        long left = timerEnd - System.currentTimeMillis();
        boolean on = left > 0;
        timerLabel.setText(on ? countdown(left) : "Sleep Timer");
        Glass g = (Glass) cells.get("timer");
        g.bg.setBase(on ? Theme.FOCUS : Theme.GLASS);
        timerLabel.setTextColor(on ? Theme.ON_FOCUS : Theme.LABEL);
        Ui.MAIN.removeCallbacks(tick);
        if (root.getParent() != null) Ui.later(1000, tick);
    }

    private void select(String id) {
        select(id, true);
    }

    /** lift: spring the scale too (not while the tiles are still flying in). */
    private void select(String id, boolean lift) {
        sel = id;
        for (Map.Entry<String, View> e : cells.entrySet()) {
            boolean on = e.getKey().equals(id);
            View v = e.getValue();
            float s = on ? (v instanceof Glass ? 1.06f : 1.2f) : 1;
            if (lift) Motion.spring(v, s, Motion.LIFT);
            if (v instanceof Glass) {
                ((Glass) v).bg.setFrostAlpha(1);
                if (!(e.getKey().equals("timer") && timerEnd > System.currentTimeMillis())) {
                    ((Glass) v).bg.setBase(on ? Theme.GLASS_FOCUS : (e.getKey().equals("power") ? Theme.GLASS_THICK : Theme.GLASS));
                }
            } else {
                v.setForeground(on ? (v == avatar ? Kit.focusRing() : Kit.focusOutline(999)) : null);
            }
        }
    }

    private void activate() {
        Sound.select();
        View cell = cells.get(sel);
        if (cell instanceof Glass) Motion.glow(cell);
        switch (sel) {
            case "np": NowPlaying.act("toggle"); Ui.later(300, () -> { np = NowPlaying.info(home); paintNowPlaying(); }); break;
            case "avatar": back(); Profiles.open(home, false); break;
            case "vpn": back(); home.openVpnApp(); break;
            case "power": back(); Controls.sleep(home); break;
            case "timer": sleepTimerMenu(); break;
            case "bluetooth": home.open(Controls.screen("bluetooth")); break;
            case "settings": home.openFireTvSettings(); break;
            case "aerial": back(); home.open(new android.content.Intent(home, AerialActivity.class)); break;
        }
    }

    private void sleepTimerMenu() {
        List<Sheet.Option> choices = new ArrayList<>();
        if (timerEnd > System.currentTimeMillis()) {
            choices.add(new Sheet.Option("Turn Off Timer", () -> { Controls.setSleepTimer(home, 0); renderTimer(); }));
        }
        for (int m : new int[] { 15, 30, 45, 60, 90, 120 }) {
            String label = m < 60 ? m + " minutes" : m == 60 ? "1 hour" : (m / 60) + " hours";
            choices.add(new Sheet.Option(label, () -> { Controls.setSleepTimer(home, m); renderTimer(); }));
        }
        Sheet.open(home, "Sleep Timer", choices);
    }

    // ---- open / close ----------------------------------------------------------------------

    void show() {
        Sound.open();
        home.overlay.addView(root, Ui.fill());
        home.layers.add(this);
        home.setStatusVisible(false);
        Motion.fade(root, 1, 250, null);
        renderTimer();
        select(np != null ? "np" : "power", false);
        // Like a tvOS menu, the tiles spring out of the time in the corner, one after another.
        for (int i = 0; i < order.size(); i++) {
            View v = order.get(i);
            float[] from = fromClock(v);
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            v.postDelayed(() -> v.setLayerType(View.LAYER_TYPE_NONE, null), order.size() * 30L + 500);
            v.setAlpha(0);
            v.setScaleX(0.3f);
            v.setScaleY(0.3f);
            v.setTranslationX(from[0]);
            v.setTranslationY(from[1]);
            float s = cells.get(sel) == v ? 1.06f : 1;
            v.animate().scaleX(s).scaleY(s).translationX(0).translationY(0).setStartDelay(i * 30L)
                    .setDuration(450).setInterpolator(Motion.SPRING).start();
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 0, 1);
            a.setStartDelay(i * 30L);
            a.setDuration(300);
            a.start();
        }
    }

    /** How far a tile is from the time in the corner, which it grows out of and returns to. */
    private static float[] fromClock(View v) {
        android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        float cx = lp.leftMargin + lp.width / 2f, cy = lp.topMargin + lp.height / 2f;
        return new float[] { Ui.STAGE_W - 200 - cx, 82 - cy };
    }

    @Override
    public void back() {
        home.layers.remove(this);
        Ui.MAIN.removeCallbacks(tick);
        home.setStatusVisible(true);
        for (int i = 0; i < order.size(); i++) {
            View v = order.get(i);
            float[] to = fromClock(v);
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            v.animate().scaleX(0.3f).scaleY(0.3f).translationX(to[0]).translationY(to[1]).alpha(0)
                    .setStartDelay((order.size() - 1 - i) * 20L).setDuration(260).setInterpolator(Motion.EASE).start();
        }
        Motion.fade(root, 0, 320, () -> home.overlay.removeView(root));
    }

    @Override
    public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode();
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 2
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? 3 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 4 : 0;
        if (dir > 0) {
            String next = null;
            for (String[] row : MAP) if (row[0].equals(sel)) next = row[dir];
            // Without Now Playing its row is skipped.
            if ("np".equals(next) && np == null) next = sel.equals("avatar") || sel.equals("vpn") ? "power" : "avatar";
            if ("vpn".equals(next) && vpnChip == null) next = null;
            if (next != null && cells.containsKey(next)) {
                Sound.move();
                select(next);
            } else {
                Sound.bump();
            }
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            activate();
        }
        return true;
    }

}
