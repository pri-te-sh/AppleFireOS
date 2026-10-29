package com.applefireos.home;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Typing on the TV: an on-screen keyboard driven by the remote's arrows, with live results
 * beside it that you pick from (folder names, weather cities…). Right from the keyboard's edge
 * moves into the results, Left comes back. A glass panel over the dimmed screen, growing from
 * the centre; the results are ordinary list Rows.
 */
final class TextEntry extends PanelScaffold {
    static final class Result {
        final String label, sub;
        /** Whatever the caller needs back when this one is picked. */
        final Object data;

        Result(String label, String sub, Object data) {
            this.label = label;
            this.sub = sub;
            this.data = data;
        }
    }

    /** Runs on Ui.IO (blocking is fine), debounced as you type; return [] for nothing. */
    interface Search {
        List<Result> find(String text) throws Exception;
    }

    interface Pick {
        void picked(Result r);
    }

    private static final int LIST_W = 560;
    /** Room around the results for the picked row's lift. */
    private static final int BLEED = Theme.S2;
    private static final long DEBOUNCE_MS = 300;

    private final Search search;
    private final Pick pick;
    private final Keyboard kb;
    private final LinearLayout listView;
    private final TextView empty;
    private final List<Row> rows = new ArrayList<>();
    private List<Result> list = new ArrayList<>();
    private int sel;
    private boolean inList, closed;
    /** Bumped per search, so a slow answer to an older query is dropped. */
    private int token;
    private final Runnable debounced = this::run;

    static void open(NativeHome home, String title, String placeholder, Search search, Pick pick) {
        TextEntry t = new TextEntry(home, title, placeholder, search, pick);
        t.show(new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER), 0.5f, 0.5f, 0.94f);
    }

    private TextEntry(NativeHome home, String title, String placeholder, Search search, Pick pick) {
        super(home, Theme.R_PANEL, true, true);
        this.search = search;
        this.pick = pick;
        LinearLayout box = new LinearLayout(home);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.S5, Theme.S4 + Theme.S1, Theme.S5, Theme.S5);
        box.setClipChildren(false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.bottomMargin = Theme.S4;
        box.addView(Kit.text(home, Theme.Type.TITLE, Theme.LABEL, title), hp);

        LinearLayout body = new LinearLayout(home);
        body.setClipChildren(false);
        kb = new Keyboard(home, placeholder == null ? "" : placeholder, text -> {
            Ui.MAIN.removeCallbacks(debounced);
            Ui.later(DEBOUNCE_MS, debounced);
        });
        body.addView(kb, new LinearLayout.LayoutParams(Keyboard.W, Keyboard.H));

        // The results scroll inside a clipped window; the bleed leaves room for the picked row's lift.
        FrameLayout window = new FrameLayout(home);
        window.setPadding(BLEED, BLEED, BLEED, BLEED);
        window.setClipToPadding(false);
        listView = new LinearLayout(home);
        listView.setOrientation(LinearLayout.VERTICAL);
        listView.setClipChildren(false);
        window.addView(listView, new FrameLayout.LayoutParams(LIST_W, -2));
        empty = Kit.text(home, Theme.Type.BODY, Theme.LABEL_3);
        empty.setPadding(Theme.S3, Theme.S3, Theme.S3, Theme.S3);
        window.addView(empty, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(LIST_W + 2 * BLEED, Keyboard.H + 2 * BLEED);
        wp.leftMargin = Theme.S5 - BLEED;
        wp.topMargin = -BLEED;
        wp.bottomMargin = -BLEED;
        body.addView(window, wp);
        box.addView(body, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(box, new FrameLayout.LayoutParams(-2, -2));
    }

    @Override
    void onShown() {
        kb.focus(true);
        render();
    }

    @Override
    void onClosing() {
        closed = true;
        Ui.MAIN.removeCallbacks(debounced);
    }

    private void run() {
        int t = ++token;
        String q = kb.text().trim();
        if (q.length() < 2) {
            showResults(new ArrayList<>());
            return;
        }
        Ui.IO.execute(() -> {
            List<Result> found;
            try {
                found = search.find(q);
            } catch (Exception e) {
                found = null;
            }
            final List<Result> got = found == null ? new ArrayList<>() : found;
            Ui.post(() -> {
                if (t == token && !closed) showResults(got);
            });
        });
    }

    private void showResults(List<Result> found) {
        list = found;
        sel = 0;
        if (list.isEmpty() && inList) {
            inList = false;
            kb.focus(true);
        }
        render();
    }

    private void render() {
        listView.removeAllViews();
        rows.clear();
        listView.setTranslationY(0);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(kb.text().trim().length() < 2 ? "Start typing" : "No matches");
        for (int i = 0; i < list.size(); i++) {
            Result r = list.get(i);
            Row row = new Row(home, Row.Style.LIST).title(r.label);
            if (r.sub != null && !r.sub.isEmpty()) row.subtitle(r.sub);
            final int k = i;
            row.setOnClickListener(v -> { sel = k; choose(); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rp.topMargin = Theme.S1;
            listView.addView(row, rp);
            rows.add(row);
        }
        paint(false);
    }

    /** Lights the picked row (only while the results have focus) and keeps it in the window. */
    private void paint(boolean animate) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean on = inList && i == sel;
            if (row.isRowFocused() != on) row.setRowFocused(on, animate);
        }
        if (inList && sel < rows.size()) {
            Row r = rows.get(sel);
            float ty = listView.getTranslationY(), h = Keyboard.H;
            if (r.getTop() + ty < 0) ty = -r.getTop();
            else if (r.getBottom() + ty > h) ty = h - r.getBottom();
            listView.animate().translationY(ty).setDuration(250).setInterpolator(Motion.EASE).start();
        }
    }

    private void choose() {
        Result r = list.get(sel);
        // A result with nothing behind it is only a message ("Not Recognised").
        if (r.data == null) {
            Sound.bump();
            return;
        }
        Sound.select();
        close();
        pick.picked(r);
    }

    private void toKeyboard() {
        inList = false;
        kb.focus(true);
        paint(true);
    }

    @Override
    void onKey(int code, KeyEvent e) {
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean select = NativeHome.isSelect(code);
        if (!inList) {
            if (dx != 0 || dy != 0) {
                if (kb.move(dx, dy)) return;
                if (dx > 0 && !list.isEmpty()) {
                    inList = true;
                    kb.focus(false);
                    Sound.move();
                    paint(true);
                } else {
                    Sound.bump();
                }
            } else if (select) {
                if (e.getRepeatCount() == 0) kb.press();
            } else {
                kb.type(e);
            }
        } else if (dy != 0) {
            int next = sel + dy;
            if (next >= 0 && next < list.size()) {
                sel = next;
                Sound.move();
                paint(true);
            } else {
                Sound.bump();
            }
        } else if (dx < 0) {
            Sound.move();
            toKeyboard();
        } else if (dx > 0) {
            Sound.bump();
        } else if (select) {
            if (e.getRepeatCount() == 0) choose();
        } else if (kb.type(e)) {
            toKeyboard();
        }
    }
}
