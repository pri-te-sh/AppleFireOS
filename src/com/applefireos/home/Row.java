package com.applefireos.home;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one focusable row/button of the home screen. Every list (Settings, the sidebar, menus,
 * Insights, search results) and every button is a Row, so they all look and react the same: idle
 * on the surface, and when focused the white platter rises with dark text and a spring lift.
 * Layout: [leading] title / subtitle [value] [accessory].
 */
class Row extends LinearLayout {
    enum Style {
        /** Rows in a card or a page list: rounded platter, title + value. */
        LIST(Theme.ROW, Theme.R_MEDIUM, Theme.Type.BODY, false, Theme.LIFT_ROW),
        /** Navigation lists in glass panels (the sidebar): pill platter. */
        NAV(Theme.ROW_COMPACT, 999, Theme.Type.BODY, false, Theme.LIFT_ROW),
        /** Choices in a menu: centred, pill platter. */
        MENU(Theme.BUTTON, 999, Theme.Type.HEADLINE, false, Theme.LIFT_ROW),
        /** Buttons: a filled pill even when idle. */
        BUTTON(Theme.BUTTON, 999, Theme.Type.HEADLINE, true, Theme.LIFT_CONTROL);

        final int height;
        final float radius, lift;
        final Theme.Type type;
        final boolean filled;

        Style(int height, float radius, Theme.Type type, boolean filled, float lift) {
            this.height = height;
            this.radius = radius;
            this.type = type;
            this.filled = filled;
            this.lift = lift;
        }
    }

    enum Accessory { NONE, CHEVRON, LINK, CHECK }

    final Style style;
    final TextView title;
    private TextView subtitle, value, accessory;
    private View leading;
    private boolean tintLeading;
    private Toggle toggle;
    private boolean focused;

    Row(Context c, Style style) {
        super(c);
        this.style = style;
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(style.height);
        int pad = style == Style.MENU || style == Style.BUTTON ? 40 : 24;
        setPadding(pad, 0, pad, 0);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setGravity(style == Style.MENU ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        title = Kit.line(Kit.text(c, style.type, Theme.LABEL));
        if (style == Style.MENU) title.setGravity(Gravity.CENTER);
        text.addView(title, new LayoutParams(style == Style.MENU ? -1 : -2, -2));
        addView(text, new LayoutParams(0, -2, 1));
        paint(false);
    }

    LinearLayout textColumn() {
        return (LinearLayout) title.getParent();
    }

    Row title(CharSequence s) {
        title.setText(s);
        return this;
    }

    Row subtitle(CharSequence s) {
        if (subtitle == null) {
            subtitle = Kit.line(Kit.text(getContext(), Theme.Type.FOOTNOTE, Theme.LABEL_2));
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.topMargin = 4;
            textColumn().addView(subtitle, lp);
        }
        subtitle.setText(s);
        paint(false);
        return this;
    }

    /** The current value on the right (Settings: "Manual", "India"). */
    Row value(CharSequence s) {
        if (value == null) {
            value = Kit.line(Kit.text(getContext(), Theme.Type.CALLOUT, Theme.LABEL_2));
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.leftMargin = Theme.S3;
            addView(value, accessory == null && toggle == null ? getChildCount() : getChildCount() - 1, lp);
        }
        value.setText(s);
        paint(false);
        return this;
    }

    Row accessory(Accessory a) {
        if (a == Accessory.NONE) return this;
        accessory = Kit.text(getContext(), Theme.Type.HEADLINE, Theme.LABEL_3,
                a == Accessory.CHEVRON ? "›" : a == Accessory.LINK ? "↗" : "✓");
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.leftMargin = Theme.S2;
        addView(accessory, lp);
        paint(false);
        return this;
    }

    /** A switch on the right. */
    Row toggle(boolean on) {
        toggle = new Toggle(getContext());
        toggle.set(on, false);
        LayoutParams lp = new LayoutParams(Toggle.W, Toggle.H);
        lp.leftMargin = Theme.S3;
        addView(toggle, lp);
        return this;
    }

    Toggle toggle() {
        return toggle;
    }

    /** A line icon on the left, recoloured with the text when focused. */
    Row icon(int res) {
        ImageView i = Kit.icon(getContext(), res, Theme.LABEL);
        int s = style == Style.NAV ? 30 : 34;
        return leading(i, s, s, true);
    }

    /** Anything on the left (an icon tile, app art, an avatar); never recoloured. */
    Row leading(View v, int w, int h, boolean tint) {
        leading = v;
        tintLeading = tint;
        LayoutParams lp = new LayoutParams(w, h);
        lp.rightMargin = style == Style.NAV ? Theme.S3 : Theme.S3 + 2;
        addView(v, 0, lp);
        paint(false);
        return this;
    }

    boolean isRowFocused() {
        return focused;
    }

    /** Focus: the platter rises (springing when animate) and the text darkens to sit on it. */
    void setRowFocused(boolean on, boolean animate) {
        focused = on;
        paint(animate);
    }

    private void paint(boolean animate) {
        boolean on = focused;
        setBackground(on ? Kit.shape(Theme.FOCUS, style.radius)
                : style.filled ? Kit.shape(Theme.FILL, style.radius) : null);
        title.setTextColor(on ? Theme.ON_FOCUS : Theme.LABEL);
        if (subtitle != null) subtitle.setTextColor(on ? Theme.ON_FOCUS_2 : Theme.LABEL_2);
        if (value != null) value.setTextColor(on ? Theme.ON_FOCUS_2 : Theme.LABEL_2);
        if (accessory != null) accessory.setTextColor(on ? Theme.ON_FOCUS_2 : Theme.LABEL_3);
        if (leading instanceof ImageView && tintLeading) {
            ((ImageView) leading).setColorFilter(on ? Theme.ON_FOCUS : Theme.LABEL, PorterDuff.Mode.SRC_IN);
        }
        if (animate) {
            Motion.lift(this, on, style.lift);
        } else {
            setScaleX(on ? style.lift : 1);
            setScaleY(on ? style.lift : 1);
        }
    }
}
