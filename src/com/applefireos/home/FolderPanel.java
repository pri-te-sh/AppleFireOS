package com.applefireos.home;

import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A folder opened from Home: a glass sheet with its apps. Select opens one; Menu takes it out. */
final class FolderPanel extends PanelScaffold {
    private static final int MAX_COLS = 4, GAP_X = 36, ROW_H = TileView.H + 84;
    private final Apps.App folder;
    private final List<TileView> tiles = new ArrayList<>();
    private final int cols;
    private int sel;

    static void open(NativeHome home, Apps.App folder) {
        new FolderPanel(home, folder).show(new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER), 0.5f, 0.5f, 0.7f);
    }

    private FolderPanel(NativeHome home, Apps.App folder) {
        super(home, Theme.R_PANEL + 10, true, true);
        this.folder = folder;
        cols = Math.min(MAX_COLS, Math.max(2, folder.apps.size()));
        LinearLayout box = new LinearLayout(home);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.S5, Theme.S4 + 6, Theme.S5, Theme.S5);
        box.setClipChildren(false);
        TextView h = Kit.text(home, Theme.Type.TITLE, Theme.LABEL, folder.label);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = Theme.S4;
        box.addView(h, hp);
        FrameLayout grid = new FrameLayout(home);
        grid.setClipChildren(false);
        for (int i = 0; i < folder.apps.size(); i++) {
            TileView t = new TileView(home, folder.apps.get(i));
            final int k = i;
            t.setOnClickListener(v -> { sel = k; launch(); });
            grid.addView(t, Ui.at((i % cols) * (TileView.W + GAP_X), (i / cols) * ROW_H, TileView.W, TileView.H + 70));
            tiles.add(t);
        }
        int rows = (folder.apps.size() + cols - 1) / cols;
        box.addView(grid, new LinearLayout.LayoutParams(cols * TileView.W + (cols - 1) * GAP_X, rows * ROW_H));
        panel.addView(box, new FrameLayout.LayoutParams(-2, -2));
        tiles.get(0).setFocused(true);
        tiles.get(0).card.setScaleX(Theme.LIFT_TILE);
        tiles.get(0).card.setScaleY(Theme.LIFT_TILE);
    }

    private void select(int next) {
        TileView old = tiles.get(sel);
        old.setFocused(false);
        sel = next;
        TileView t = tiles.get(sel);
        t.setFocused(true);
        Motion.lift(t.card, true, Theme.LIFT_TILE);
    }

    private void launch() {
        Apps.App a = folder.apps.get(sel);
        Sound.select();
        Apps.record(a.pkg);
        home.launch(a.pkg);
    }

    @Override
    void onKey(int code, KeyEvent e) {
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            int col = sel % cols, next = sel + (dx != 0 ? dx : dy * cols);
            boolean edge = (dx < 0 && col == 0) || (dx > 0 && col == cols - 1);
            if (!edge && next >= 0 && next < tiles.size()) {
                Sound.move();
                select(next);
            } else {
                Sound.bump();
            }
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            launch();
        }
    }

    @Override
    public void menu() {
        Apps.App a = folder.apps.get(sel);
        Sheet.open(home, a.label, Arrays.asList(
                new Sheet.Option("Remove from " + folder.label, () -> {
                    Apps.removeFromFolder(a.pkg);
                    close();
                    home.loadApps(a.pkg);
                    home.toast(a.label + " is back on Home");
                }),
                new Sheet.Option("App Info", () -> home.appInfo(a.pkg))));
    }
}
