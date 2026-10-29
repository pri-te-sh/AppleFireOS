package com.applefireos.home;

import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The segmented control at the top of a page (Screen Time | Habits | Performance): glass, equal
 * segments, and a platter that slides to the chosen one. While the tabs have focus the platter is
 * the white focus platter; once you're down in the page it stays as a quiet marker.
 */
final class TabBar extends FrameLayout {
    static final int H = 64, SEGMENT = 200;
    private final FrameLayout platter;
    private final TextView[] labels;
    private int selected;
    private boolean focused = true;

    TabBar(NativeHome home, String... names) {
        super(home);
        Glass g = Kit.glass(home, H / 2f, false);
        addView(g, new LayoutParams(names.length * SEGMENT + 12, H));
        platter = new FrameLayout(home);
        g.addView(platter, new LayoutParams(SEGMENT, H - 12, Gravity.CENTER_VERTICAL));
        ((LayoutParams) platter.getLayoutParams()).leftMargin = 6;
        LinearLayout row = new LinearLayout(home);
        row.setPadding(6, 0, 6, 0);
        labels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            TextView t = Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL_2, names[i]);
            t.setGravity(Gravity.CENTER);
            row.addView(t, new LinearLayout.LayoutParams(SEGMENT, H));
            labels[i] = t;
        }
        g.addView(row, new LayoutParams(-2, H));
        paint(false);
    }

    static int width(int count) {
        return count * SEGMENT + 12;
    }

    int selected() {
        return selected;
    }

    int count() {
        return labels.length;
    }

    void select(int i, boolean animate) {
        selected = i;
        paint(animate);
    }

    /** focused: the tabs themselves have focus (vs. the page under them). */
    void setTabsFocused(boolean on) {
        focused = on;
        paint(true);
    }

    private void paint(boolean animate) {
        platter.setBackground(Kit.shape(focused ? Theme.FOCUS : Theme.FILL, (H - 12) / 2f));
        float x = selected * SEGMENT;
        if (animate) platter.animate().translationX(x).setDuration(Motion.LIFT).setInterpolator(Motion.EASE).start();
        else platter.setTranslationX(x);
        for (int i = 0; i < labels.length; i++) {
            labels[i].setTextColor(i != selected ? Theme.LABEL_2 : focused ? Theme.ON_FOCUS : Theme.LABEL);
        }
    }
}
