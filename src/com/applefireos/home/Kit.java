package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/**
 * The building blocks every screen is made of, all styled from Theme: text in the type scale,
 * content cards, glass, icon tiles, app art, section headers, meters. (Focusable rows and
 * buttons are Row; the switch is Toggle; segmented tabs are TabBar.)
 */
final class Kit {
    private Kit() {}

    static Typeface face(Theme.Type t) {
        return t.weight == 2 ? Ui.BOLD : t.weight == 1 ? Ui.MEDIUM : Ui.REGULAR;
    }

    /** Text in one of the type scale's styles. */
    static TextView text(Context c, Theme.Type t, int color) {
        TextView v = new TextView(c);
        style(v, t, color);
        return v;
    }

    static TextView text(Context c, Theme.Type t, int color, CharSequence s) {
        TextView v = text(c, t, color);
        v.setText(t == Theme.Type.SECTION && s != null ? s.toString().toUpperCase(Locale.getDefault()) : s);
        return v;
    }

    static void style(TextView v, Theme.Type t, int color) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.size);
        v.setTypeface(face(t));
        v.setTextColor(color);
        v.setLetterSpacing(t.tracking);
        v.setIncludeFontPadding(false);
    }

    /** A paragraph: body text with comfortable line spacing. */
    static TextView paragraph(Context c, Theme.Type t, int color, CharSequence s) {
        TextView v = text(c, t, color, s);
        v.setLineSpacing(0, Theme.LINE_SPACING);
        return v;
    }

    /** A Paint in one of the type scale's styles, for text drawn on a Canvas (charts, the memory ring). */
    static android.graphics.Paint textPaint(Context c, Theme.Type t, int color) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face(t));
        p.setTextSize(t.size);
        p.setLetterSpacing(t.tracking);
        // Set explicitly: a TextView only applies its colour to its paint while drawing.
        p.setColor(color);
        return p;
    }

    /** One line, ending in "…" when it doesn't fit. */
    static TextView line(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** Text over artwork keeps a soft shadow so it stays legible on bright pictures. */
    static TextView overArt(TextView v) {
        v.setShadowLayer(14, 0, 2, Theme.SHADOW);
        return v;
    }

    static TextView section(Context c, String s) {
        return text(c, Theme.Type.SECTION, Theme.LABEL_3, s);
    }

    // ---- surfaces ----------------------------------------------------------------------------

    static GradientDrawable shape(int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        return g;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    /** The focus ring for round things (avatars, profile faces). */
    static GradientDrawable focusRing() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(4, Theme.FOCUS);
        return g;
    }

    /** The focus outline for pill-shaped things that aren't Rows (Control Center's VPN chip). */
    static GradientDrawable focusOutline(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setStroke(3, Theme.FOCUS);
        return g;
    }

    /** Control Center's backdrop: the screen darkens towards the side the panel is on. */
    static GradientDrawable sideScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { Theme.SCRIM_FAINT, Theme.SCRIM });
    }

    /** A content card: a plain fill with a hairline edge (text sits directly on it). */
    static FrameLayout card(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(cardShape(Theme.R_LARGE));
        return f;
    }

    static GradientDrawable cardShape(float radius) {
        GradientDrawable g = shape(Theme.CARD, radius);
        g.setStroke(1, Theme.CARD_EDGE);
        return g;
    }

    /** A small well inside a card (stat tiles). */
    static GradientDrawable well() {
        return shape(Theme.FILL_2, Theme.R_MEDIUM);
    }

    /** Floating controls: frosted glass. thick: panels with lots of text. */
    static Glass glass(NativeHome home, float radius, boolean thick) {
        return new Glass(home, radius, thick);
    }

    /** Small controls over the artwork: the "‹ Home" pill, the shelf's arrows. */
    static GradientDrawable chrome(float radius) {
        return shape(Theme.CHROME, radius);
    }

    // ---- icons and art -------------------------------------------------------------------------

    /** A line icon (vector drawable), tinted. */
    static ImageView icon(Context c, int res, int color) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return i;
    }

    /** The coloured rounded square with a white glyph (Settings rows, Insights tiles). */
    static FrameLayout iconTile(Context c, int color, int res, int size) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Ui.mix(color, Theme.LABEL, 0.12f), color });
        g.setCornerRadius(size * 0.26f);
        f.setBackground(g);
        int glyph = Math.round(size * 0.58f);
        f.addView(icon(c, res, Theme.LABEL), new FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER));
        return f;
    }

    /**
     * An app's artwork at any size, always the same way: its TV banner cropped to fit, else its
     * icon on its own tint, else its initial. Never recoloured.
     */
    static View appArt(Context c, Apps.App a, float radius) {
        return appArt(c, a == null ? null : a.banner, a == null ? null : a.icon, a == null ? Theme.GRAPHITE : a.tint,
                a == null ? "" : a.label, radius);
    }

    static View appArt(Context c, Bitmap banner, Bitmap icon, int tint, String label, float radius) {
        if (banner == null && icon == null) {
            TextView t = text(c, Theme.Type.CALLOUT, Theme.LABEL_2,
                    label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.getDefault()));
            t.setGravity(Gravity.CENTER);
            t.setBackground(shape(Theme.FILL, radius));
            return t;
        }
        ImageView i = new ImageView(c);
        i.setScaleType(banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        i.setImageBitmap(banner != null ? banner : icon);
        i.setBackground(shape(banner != null ? Theme.GRAPHITE : tint, radius));
        if (banner == null) i.setPadding(6, 6, 6, 6);
        Ui.rounded(i, radius);
        return i;
    }

    /** The profile avatar: the person's initial on the avatar gradient. */
    static TextView avatar(Context c, String name, int size) {
        TextView t = text(c, size >= 100 ? Theme.Type.PAGE_TITLE : Theme.Type.CALLOUT, Theme.LABEL,
                name == null || name.isEmpty() ? "•" : name.substring(0, 1).toUpperCase(Locale.getDefault()));
        t.setTypeface(Ui.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { Theme.AVATAR_TOP, Theme.AVATAR_BOTTOM });
        g.setShape(GradientDrawable.OVAL);
        t.setBackground(g);
        return t;
    }

    // ---- data ----------------------------------------------------------------------------------

    /** A horizontal meter: a track with a filled part (0..1), rounded ends. */
    static FrameLayout meter(Context c, double fraction, int color, int height) {
        FrameLayout track = new FrameLayout(c);
        track.setBackground(shape(Theme.FILL_2, height / 2f));
        View fill = new View(c);
        fill.setBackground(shape(color, height / 2f));
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = (int) Math.round((r - l) * Math.max(0, Math.min(1, fraction)));
            if (fill.getLayoutParams().width != Math.max(height, w)) {
                fill.getLayoutParams().width = Math.max(height, w);
                fill.post(fill::requestLayout);
            }
        });
        return track;
    }

    /** A thin rule between groups. */
    static View separator(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.SEPARATOR);
        return v;
    }
}
