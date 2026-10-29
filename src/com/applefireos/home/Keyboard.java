package com.applefireos.home;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The on-screen letter grid driven by the remote's arrows, with the typed text above it. A USB or
 * Bluetooth keyboard types into it too. Used by TextEntry and Find, which own the focus. Keys are
 * idle wells that take focus exactly like a Row: the white platter, dark text and a control's lift.
 */
final class Keyboard extends FrameLayout {
    interface Change {
        void changed(String text);
    }

    /** Keys are as tall as a button; the grid's gaps are the design system's. */
    private static final int KEY_W = 76, KEY_H = Theme.BUTTON, GAP = Theme.S2, COLS = 6;
    static final int W = COLS * KEY_W + (COLS - 1) * GAP, H = 8 * KEY_H + 7 * GAP;
    private static final String DEL = "del", CLEAR = "clear";

    private static final class Key {
        final String ch, label;
        /** Columns it covers, and the first one. */
        final int span, x;

        Key(String ch, String label, int span, int x) {
            this.ch = ch;
            this.label = label;
            this.span = span;
            this.x = x;
        }
    }

    private static final Key[][] ROWS = new Key[7][];
    static {
        String[] letters = { "abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890" };
        for (int r = 0; r < letters.length; r++) {
            ROWS[r] = new Key[COLS];
            for (int c = 0; c < COLS; c++) {
                String ch = letters[r].substring(c, c + 1);
                ROWS[r][c] = new Key(ch, ch.toUpperCase(Locale.ROOT), 1, c);
            }
        }
        ROWS[6] = new Key[] { new Key(" ", "Space", 2, 0), new Key(DEL, "⌫", 2, 2), new Key(CLEAR, "Clear", 2, 4) };
    }

    private final Change onChange;
    private final TextView field;
    private final TextView[][] keys = new TextView[ROWS.length][];
    private TextView lit;
    private String text = "";
    private int row, col;
    private boolean focused;

    Keyboard(Context c, String placeholder, Change onChange) {
        super(c);
        this.onChange = onChange;
        setClipChildren(false);
        // The field is a brighter well than the keys, so it reads as where the text goes.
        field = Kit.text(c, Theme.Type.HEADLINE, Theme.LABEL);
        field.setSingleLine(true);
        // Long queries show their end, where you're typing.
        field.setEllipsize(TextUtils.TruncateAt.START);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(Theme.S3, 0, Theme.S3, 0);
        field.setHint(placeholder);
        field.setHintTextColor(Theme.LABEL_3);
        field.setBackground(Kit.shape(Theme.FILL, Theme.R_MEDIUM));
        addView(field, Ui.at(0, 0, W, KEY_H));
        for (int r = 0; r < ROWS.length; r++) {
            keys[r] = new TextView[ROWS[r].length];
            for (int k = 0; k < ROWS[r].length; k++) {
                Key def = ROWS[r][k];
                TextView key = Kit.text(c, def.span > 1 ? Theme.Type.CALLOUT : Theme.Type.HEADLINE, Theme.LABEL, def.label);
                key.setGravity(Gravity.CENTER);
                final int kr = r, kc = k;
                key.setOnClickListener(v -> { row = kr; col = kc; paint(); press(); });
                addView(key, Ui.at(def.x * (KEY_W + GAP), (r + 1) * (KEY_H + GAP), def.span * KEY_W + (def.span - 1) * GAP, KEY_H));
                keys[r][k] = key;
                light(key, false, false);
            }
        }
    }

    String text() {
        return text;
    }

    void set(String t) {
        text = t;
        field.setText(text);
        onChange.changed(text);
    }

    void focus(boolean on) {
        focused = on;
        paint();
    }

    private void paint() {
        TextView now = focused ? keys[row][col] : null;
        if (now == lit) return;
        if (lit != null) light(lit, false, true);
        if (now != null) light(now, true, true);
        lit = now;
    }

    /** A key's look: an idle well, or the focus platter with dark text, lifting like a control. */
    private static void light(TextView k, boolean on, boolean animate) {
        k.setBackground(on ? Kit.shape(Theme.FOCUS, Theme.R_MEDIUM) : Kit.shape(Theme.FILL_2, Theme.R_MEDIUM));
        k.setTextColor(on ? Theme.ON_FOCUS : Theme.LABEL);
        if (animate) Motion.lift(k, on, Theme.LIFT_CONTROL);
    }

    void press() {
        String ch = ROWS[row][col].ch;
        Sound.select();
        if (ch.equals(DEL)) backspace();
        else if (ch.equals(CLEAR)) set("");
        else add(ch);
    }

    private void add(String s) {
        set((text + s).replaceAll("^\\s+", ""));
    }

    private void backspace() {
        set(text.isEmpty() ? "" : text.substring(0, text.length() - 1));
    }

    /** Arrow handling; false means "moved off this edge" so the owner can take focus. */
    boolean move(int dx, int dy) {
        if (dx != 0) {
            int next = col + dx;
            if (next < 0 || next >= ROWS[row].length) return false;
            col = next;
        } else {
            int next = row + dy;
            if (next < 0 || next >= ROWS.length) return false;
            // Land on the key under the current one's centre.
            Key from = ROWS[row][col];
            float x = from.x + from.span / 2f;
            row = next;
            col = ROWS[row].length - 1;
            for (int k = 0; k < ROWS[row].length; k++) {
                if (x >= ROWS[row][k].x && x < ROWS[row][k].x + ROWS[row][k].span) {
                    col = k;
                    break;
                }
            }
        }
        Sound.move();
        paint();
        return true;
    }

    /** A real keyboard's letters, digits, space and backspace; false for anything else. */
    boolean type(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_DEL) {
            backspace();
            return true;
        }
        int ch = e.getUnicodeChar();
        if (ch == 0 || !(Character.isLetterOrDigit(ch) || " .'&-_".indexOf(ch) >= 0)) return false;
        add(new String(Character.toChars(ch)).toLowerCase(Locale.getDefault()));
        return true;
    }
}
