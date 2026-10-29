package com.applefireos.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What AppleFireOS can do on this TV yet, and how to switch on the rest. Fire OS only lets a
 * computer grant the Home button, Top Shelf and Insights permissions (once, over adb), so the
 * page shows the one command to run with this TV's address filled in, and ticks each feature
 * off as it starts working. Opens by itself on first launch, and from Settings any time.
 */
final class SetupPage extends PageScaffold {
    private static final int TILE = 48, LIST_W = 900, LEFT_W = 760;
    private static final String REPO = "github.com/pri-te-sh/AppleFireOS";

    /** One feature that needs something granted. */
    private static final class Need {
        final int icon, color;
        final String label;
        final Check ready;
        final Runnable fix;
        Row row;

        Need(int icon, int color, String label, Check ready, Runnable fix) {
            this.icon = icon;
            this.color = color;
            this.label = label;
            this.ready = ready;
            this.fix = fix;
        }
    }

    interface Check {
        boolean ok(Context c);
    }

    private final List<Need> needs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Row again, done;
    private int sel;

    /** On first launch, once, if anything is missing. */
    static void firstRun(NativeHome home) {
        if (Prefs.bool("setupSeen", false)) return;
        Prefs.set("setupSeen", true);
        if (missing(home) > 0) open(home);
    }

    static void open(NativeHome home) {
        SetupPage p = new SetupPage(home);
        p.open();
        p.select(0);
    }

    // ---- checks ----------------------------------------------------------------------------

    static boolean homeButton(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_accessibility_services");
        return on != null && on.contains(new ComponentName(c, HomeRedirectService.class).flattenToString());
    }

    static boolean topShelf(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(new ComponentName(c, RecsListener.class).flattenToString());
    }

    static boolean insights(Context c) {
        return c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** How many of the adb-granted features aren't working yet (the TMDB key is optional). */
    static int missing(Context c) {
        int n = 0;
        if (!homeButton(c)) n++;
        if (!topShelf(c)) n++;
        if (!Insights.hasUsageAccess(c)) n++;
        if (!insights(c)) n++;
        return n;
    }

    /** This TV's address on the local network (not a VPN tunnel's), or null. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- page ------------------------------------------------------------------------------

    private SetupPage(NativeHome home) {
        super(home, "Set Up AppleFireOS");
        needs.add(new Need(R.drawable.ic_sb_home, Theme.BLUE, "Home Button", SetupPage::homeButton, null));
        needs.add(new Need(R.drawable.ic_play, Theme.PINK, "Top Shelf from Your Apps", SetupPage::topShelf, null));
        needs.add(new Need(R.drawable.ic_in_clock, Theme.INDIGO, "Screen Time", Insights::hasUsageAccess, null));
        needs.add(new Need(R.drawable.ic_st_insights, Theme.PURPLE, "TV Insights", SetupPage::insights, null));
        needs.add(new Need(R.drawable.ic_in_star, Theme.ORANGE, "Movie & Show Artwork", c -> Tmdb.enabled(),
                () -> TmdbKey.enter(home, this::refresh)));

        // Left: why, and the one command to run.
        LinearLayout left = new LinearLayout(home);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Kit.paragraph(home, Theme.Type.BODY, Theme.LABEL_2,
                "Fire OS only lets a computer switch on the Home button, the Top Shelf and TV Insights. "
                        + "It takes a minute, and you only do it once."), new LinearLayout.LayoutParams(-1, -2));
        String ip = address();
        String[] steps = {
                "On the TV, open Settings › My Fire TV › Developer Options and turn on ADB Debugging.",
                "On a computer on the same network, get setup.sh from " + REPO + " and run:",
                "When the TV asks, allow the computer to connect.",
        };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = Theme.S4;
            left.addView(Kit.paragraph(home, Theme.Type.BODY, Theme.LABEL, (i + 1) + ".  " + steps[i]), sp);
            if (i == 1) {
                TextView cmd = Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL,
                        "./setup.sh " + (ip == null ? "<your TV's IP address>" : ip));
                cmd.setBackground(Kit.well());
                cmd.setPadding(Theme.S3, Theme.S2, Theme.S3, Theme.S2);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.topMargin = Theme.S2;
                left.addView(cmd, cp);
            }
        }
        content.addView(left, Ui.at(Theme.PAGE_X, 60, LEFT_W, -2));

        // Right: each feature, ticked off when it works; then the buttons.
        LinearLayout right = new LinearLayout(home);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        right.addView(Kit.section(home, "Features"), new LinearLayout.LayoutParams(-1, -2));
        FrameLayout card = Kit.card(home);
        card.setClipChildren(false);
        LinearLayout list = new LinearLayout(home);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(Theme.S1, Theme.S1, Theme.S1, Theme.S1);
        for (Need n : needs) {
            n.row = new Row(home, Row.Style.LIST).title(n.label);
            n.row.leading(Kit.iconTile(home, n.color, n.icon, TILE), TILE, TILE, false);
            list.addView(n.row, new LinearLayout.LayoutParams(-1, Theme.ROW));
            rows.add(n.row);
        }
        card.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Theme.S2;
        right.addView(card, lp);

        LinearLayout buttons = new LinearLayout(home);
        buttons.setGravity(Gravity.END);
        buttons.setClipChildren(false);
        again = new Row(home, Row.Style.BUTTON).title("Check Again");
        done = new Row(home, Row.Style.BUTTON).title("Done");
        buttons.addView(again, new LinearLayout.LayoutParams(-2, Theme.BUTTON));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, Theme.BUTTON);
        dp.leftMargin = Theme.S3;
        buttons.addView(done, dp);
        rows.add(again);
        rows.add(done);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Theme.S4;
        right.addView(buttons, bp);
        content.addView(right, Ui.at(Ui.STAGE_W - Theme.PAGE_X - LIST_W, 60, LIST_W, -2));

        for (int i = 0; i < rows.size(); i++) {
            int k = i;
            rows.get(i).setOnClickListener(v -> {
                select(k);
                activate();
            });
        }
        refresh();
    }

    /** Ticks and the caption, from the TV's current state (grants arrive while the page is open). */
    private void refresh() {
        int ready = 0;
        for (Need n : needs) {
            boolean ok = n.ready.ok(home);
            if (ok) ready++;
            n.row.value(ok ? "On  ✓" : n.fix != null ? "Add Key" : "Needs Setup");
        }
        caption.setText(ready + " of " + needs.size() + " ready");
    }

    private void select(int i) {
        i = Math.max(0, Math.min(rows.size() - 1, i));
        if (i != sel) rows.get(sel).setRowFocused(false, true);
        sel = i;
        rows.get(i).setRowFocused(true, true);
    }

    private void activate() {
        Sound.select();
        Row r = rows.get(sel);
        if (r == done) {
            close();
        } else if (r == again) {
            refresh();
            home.toast(missing(home) == 0 ? "Everything is set up" : "Run setup.sh, then check again");
        } else {
            Need n = needs.get(sel);
            if (n.fix != null) n.fix.run();
        }
    }

    @Override
    void onKey(int code, KeyEvent e) {
        refresh();
        int next = sel;
        if (code == KeyEvent.KEYCODE_DPAD_UP) next = sel > needs.size() ? needs.size() - 1 : sel - 1;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) next = sel < needs.size() ? sel + 1 : sel;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT && sel == needs.size() + 1) next = needs.size();
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && sel == needs.size()) next = needs.size() + 1;
        else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            activate();
            return;
        } else return;
        if (next >= 0 && next < rows.size() && next != sel) {
            Sound.move();
            select(next);
        } else {
            Sound.bump();
        }
    }
}
