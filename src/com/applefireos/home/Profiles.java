package com.applefireos.home;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Profiles (tvOS 27): who's watching and ways to switch. Fire OS doesn't let other apps list or
 * switch profiles, so "Switch Profile" and "Manage" open Amazon's own screens.
 */
final class Profiles extends PanelScaffold {
    private static final int FACE = 128;
    private final List<View> faces = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();
    private final Runnable[] actions;
    private int sel = 1;

    static void open(NativeHome home, boolean atLeft) {
        Profiles p = new Profiles(home);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | (atLeft ? Gravity.START : Gravity.END));
        lp.topMargin = 118;
        lp.leftMargin = atLeft ? 72 : 0;
        lp.rightMargin = atLeft ? 0 : 72;
        // Grows out of the avatar it was opened from (beside the time, or the sidebar's).
        p.show(lp, atLeft ? 0.08f : 0.92f, -0.1f, 0.4f);
    }

    private Profiles(NativeHome home) {
        super(home, Theme.R_PANEL, true, false);
        String me = home.profileName();
        me = me.isEmpty() ? "You" : me.split(" ")[0];
        LinearLayout row = new LinearLayout(home);
        row.setPadding(Theme.S5 - 8, Theme.S4, Theme.S5 - 8, Theme.S4 - 4);
        actions = new Runnable[] {
                null,
                () -> home.open(new Intent("com.amazon.tv.action.LAUNCH_PROFILE_PICKER")),
                () -> home.open(new Intent("com.amazon.tv.action.LAUNCH_PROFILE_EDIT"))
        };
        String[] labels = { me, "Switch Profile", "Manage" };
        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(home);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout face = new FrameLayout(home);
            if (i == 0) {
                face.addView(Kit.avatar(home, me, FACE), Ui.fill());
                TextView check = Kit.text(home, Theme.Type.SUBHEAD, Theme.LABEL, "✓");
                check.setGravity(Gravity.CENTER);
                check.setBackground(Kit.oval(Theme.GOOD));
                face.addView(check, new FrameLayout.LayoutParams(38, 38, Gravity.TOP | Gravity.END));
            } else {
                face.setBackground(Kit.oval(Theme.FILL));
                face.addView(Kit.icon(home, i == 1 ? R.drawable.ic_people : R.drawable.ic_plus, Theme.LABEL),
                        new FrameLayout.LayoutParams(58, 58, Gravity.CENTER));
            }
            col.addView(face, new LinearLayout.LayoutParams(FACE, FACE));
            TextView name = Kit.line(Kit.text(home, Theme.Type.SUBHEAD, Theme.LABEL_2, labels[i]));
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(150, -2);
            np.topMargin = Theme.S2;
            col.addView(name, np);
            final int k = i;
            col.setOnClickListener(v -> { sel = k; choose(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(150, -2);
            if (i > 0) cp.leftMargin = Theme.S4;
            row.addView(col, cp);
            faces.add(face);
            names.add(name);
        }
        panel.addView(row, new FrameLayout.LayoutParams(-2, -2));
        paint(false);
    }

    private void paint(boolean animate) {
        for (int i = 0; i < faces.size(); i++) {
            boolean on = i == sel;
            View f = faces.get(i);
            if (animate) Motion.lift(f, on, Theme.LIFT_TILE);
            f.setForeground(on ? Kit.focusRing() : null);
            names.get(i).setTextColor(on ? Theme.LABEL : Theme.LABEL_2);
        }
    }

    private void choose() {
        Sound.select();
        close();
        if (actions[sel] != null) actions[sel].run();
    }

    @Override
    void onKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int next = sel + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (next >= 0 && next < faces.size()) {
                sel = next;
                Sound.move();
                paint(true);
            } else {
                Sound.bump();
            }
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            choose();
        }
    }
}
