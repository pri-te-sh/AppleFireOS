package com.applefireos.home;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The action sheet: a centred glass menu of choices (app options, sleep timer, folders…). */
final class Sheet extends PanelScaffold {
    static final class Option {
        final String label;
        final Runnable run;

        Option(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private static final int MAX_LIST_H = 760;
    private final List<Option> options;
    private final List<Row> rows = new ArrayList<>();
    private final ScrollView scroller;
    private int sel;

    static Sheet open(NativeHome home, String title, List<Option> options) {
        Sheet s = new Sheet(home, title, options);
        s.show(new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER), 0.5f, 0.5f, 0.88f);
        return s;
    }

    private Sheet(NativeHome home, String title, List<Option> options) {
        super(home, Theme.R_PANEL + 10, true, true);
        this.options = options;
        LinearLayout box = new LinearLayout(home);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.S4, Theme.S4, Theme.S4, Theme.S4);
        box.setMinimumWidth(720);
        TextView h = Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL_2, title);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = Theme.S2;
        box.addView(h, hp);
        // Long lists (hidden apps, cities…) scroll; the focused choice stays in view.
        LinearLayout list = new LinearLayout(home);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Theme.S1, 0, Theme.S1, Theme.S1);
        scroller = new ScrollView(home);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFocusable(false);
        scroller.setClipToPadding(false);
        scroller.addView(list);
        box.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < options.size(); i++) {
            Row r = new Row(home, Row.Style.MENU).title(options.get(i).label);
            final int k = i;
            r.setOnClickListener(v -> choose(k));
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
            op.topMargin = Theme.S1;
            list.addView(r, op);
            rows.add(r);
        }
        panel.addView(box, new FrameLayout.LayoutParams(-2, -2));
        list.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
        scroller.getLayoutParams().height = Math.min(list.getMeasuredHeight(), MAX_LIST_H);
        // The first choice is lit straight away (not animated while the panel is still growing).
        rows.get(0).setRowFocused(true, false);
    }

    private void select(int next) {
        if (next == sel) return;
        Sound.move();
        rows.get(sel).setRowFocused(false, true);
        sel = next;
        Row cur = rows.get(sel);
        cur.setRowFocused(true, true);
        scroller.post(() -> {
            int top = cur.getTop() - 40, bottom = cur.getBottom() + 40, y = scroller.getScrollY(), h = scroller.getHeight();
            if (top < y) scroller.smoothScrollTo(0, top);
            else if (bottom > y + h) scroller.smoothScrollTo(0, bottom - h);
        });
    }

    private void choose(int i) {
        Sound.select();
        close();
        options.get(i).run.run();
    }

    @Override
    void onKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = sel + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            select((next + options.size()) % options.size());
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            choose(sel);
        }
    }
}
