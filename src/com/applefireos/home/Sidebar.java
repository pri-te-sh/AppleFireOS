package com.applefireos.home;

import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The tvOS 27 sidebar: Left from the first column (or the "‹ Home" pill) opens a floating glass
 * panel with your profile, the main places, your recent apps and Settings.
 */
final class Sidebar extends PanelScaffold {
    private static final int W = 470;
    private final List<Row> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int sel;

    static void open(NativeHome home) {
        Sidebar s = new Sidebar(home);
        // Grows out of the "‹ Home" pill in the corner, as on tvOS 27.
        s.show(Ui.at(48, 30, W, FrameLayout.LayoutParams.WRAP_CONTENT), 0.1f, 0.04f, 0.35f);
    }

    private Sidebar(NativeHome home) {
        super(home, Theme.R_PANEL, true, false);
        LinearLayout list = new LinearLayout(home);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Theme.S2, Theme.S3 - 4, Theme.S2, Theme.S3);

        String me = home.profileName();
        me = me.isEmpty() ? "You" : me.split(" ")[0];
        Row prof = new Row(home, Row.Style.NAV).title(me).value(home.clockText());
        prof.leading(Kit.avatar(home, me, 50), 50, 50, false);
        add(list, prof, () -> Profiles.open(home, true));

        add(list, new Row(home, Row.Style.NAV).title("Search").icon(R.drawable.ic_sb_search), home::openFind);
        Row homeRow = new Row(home, Row.Style.NAV).title("Home").icon(R.drawable.ic_sb_home);
        add(list, homeRow, () -> {});
        add(list, new Row(home, Row.Style.NAV).title("Insights").icon(R.drawable.ic_sb_insights), home::openInsights);
        add(list, new Row(home, Row.Style.NAV).title("Control Center").icon(R.drawable.ic_sb_cc), () -> new ControlCenter(home).show());

        List<Apps.App> recent = new ArrayList<>();
        for (TileView t : home.tiles) if (!t.app.special() && t.app.folder == null && Apps.lastUsed(t.app.pkg) > 0) recent.add(t.app);
        Collections.sort(recent, (a, b) -> Long.compare(Apps.lastUsed(b.pkg), Apps.lastUsed(a.pkg)));
        if (!recent.isEmpty()) {
            TextView h = Kit.section(home, "Recent");
            h.setPadding(Theme.S4, Theme.S3 - 4, 0, Theme.S1);
            list.addView(h);
            for (int i = 0; i < Math.min(3, recent.size()); i++) {
                Apps.App a = recent.get(i);
                Row r = new Row(home, Row.Style.NAV).title(a.label);
                r.leading(Kit.appArt(home, a, Theme.R_SMALL - 4), 64, 38, false);
                add(list, r, () -> { Apps.record(a.pkg); home.launch(a.pkg); });
            }
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 2);
        rp.setMargins(Theme.S4, Theme.S2, Theme.S4, Theme.S1);
        list.addView(Kit.separator(home), rp);
        add(list, new Row(home, Row.Style.NAV).title("Settings").icon(R.drawable.ic_sb_settings), home::openSettings);

        panel.addView(list, new FrameLayout.LayoutParams(W, -2));
        // Start on Home, the page you're on.
        sel = rows.indexOf(homeRow);
        rows.get(sel).setRowFocused(true, false);
        home.dimGrid(true);
    }

    private void add(LinearLayout list, Row r, Runnable run) {
        int i = rows.size();
        rows.add(r);
        actions.add(run);
        r.setOnClickListener(v -> { sel = i; choose(); });
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void choose() {
        Sound.select();
        close();
        actions.get(sel).run();
    }

    @Override
    void onClosing() {
        home.dimGrid(false);
    }

    @Override
    void onKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = sel + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next < 0 || next >= rows.size()) {
                Sound.bump();
                return;
            }
            Sound.move();
            rows.get(sel).setRowFocused(false, true);
            sel = next;
            rows.get(sel).setRowFocused(true, true);
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            Sound.move();
            close();
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            Sound.bump();
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            choose();
        }
    }
}
