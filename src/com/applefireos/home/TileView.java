package com.applefireos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** One app on the home screen: a rounded card with its banner (or icon on a tint) and a name. */
final class TileView extends FrameLayout {
    static final int W = 256, H = 154, RADIUS = 30;

    final Apps.App app;
    final FrameLayout card;
    final TextView label;
    private final Rim rim;
    private final View glare;
    int row, col;

    TileView(Context c, Apps.App app) {
        super(c);
        this.app = app;
        setClipChildren(false);
        setClipToPadding(false);

        card = new FrameLayout(c);
        card.setPivotX(W / 2f);
        card.setPivotY(H / 2f);
        card.setCameraDistance(9000);
        Ui.rounded(card, RADIUS);
        if (app.banner != null) {
            card.setBackgroundColor(0xFF1C1C1E);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(app.banner);
            card.addView(img, Ui.fill());
        } else if (app.folder != null) {
            card.setBackground(Ui.vertical(0, 0x59FFFFFF, 0x33FFFFFF));
            int i = 0;
            for (Apps.App a : app.apps) {
                if (i == 4) break;
                ImageView m = new ImageView(c);
                m.setScaleType(a.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                m.setImageBitmap(a.banner != null ? a.banner : a.icon);
                if (a.banner == null) m.setBackgroundColor(a.tint);
                Ui.rounded(m, 10);
                int mw = 104, mh = 58;
                card.addView(m, Ui.at(18 + (i % 2) * (mw + 12), 13 + (i / 2) * (mh + 10), mw, mh));
                i++;
            }
        } else {
            card.setBackground(Ui.vertical(0, Ui.mix(app.tint, 0xFFFFFFFF, 0.14f), app.tint));
            if (app.glyph != null) {
                card.addView(new Glyph(c, app.glyph), Ui.fill());
            } else {
                ImageView img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setImageBitmap(app.icon);
                card.addView(img, Ui.at(Math.round(W * 0.22f), Math.round(H * 0.18f), Math.round(W * 0.56f), Math.round(H * 0.64f)));
            }
        }
        // Glass lip: a soft top sheen, a glare that slides as the card tilts, and a light-catching rim.
        View sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x29FFFFFF, 0x00FFFFFF, 0x00FFFFFF }));
        card.addView(sheen, Ui.fill());
        glare = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(W * 0.5f);
        g.setColors(new int[] { 0x52FFFFFF, 0x12FFFFFF, 0x00FFFFFF });
        glare.setBackground(g);
        glare.setAlpha(0);
        card.addView(glare, Ui.at(-W / 2, -H / 2, W * 2, H * 2));
        rim = new Rim(c);
        card.addView(rim, Ui.fill());
        addView(card, new LayoutParams(W, H));

        label = Ui.oneLine(Ui.text(c, 26, Ui.MEDIUM, Ui.WHITE));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setText(app.label);
        label.setShadowLayer(12, 0, 2, 0xB3000000);
        label.setAlpha(0);
        LayoutParams lp = new LayoutParams(W + 60, -2);
        lp.leftMargin = -30;
        lp.topMargin = H + 22;
        addView(label, lp);
    }

    void setFocused(boolean on) {
        setFocused(on, null);
    }

    /** dir: the arrow that brought focus here, so the rim's highlight faces the side you came from. */
    void setFocused(boolean on, int[] dir) {
        rim.set(on, dir == null ? 135 : 135 - dir[0] * 40 + dir[1] * 30);
        card.animate().translationZ(on ? 26 : 0).setDuration(250).setInterpolator(Motion.EASE).start();
        glare.animate().alpha(on ? 1 : 0).setDuration(300).setInterpolator(Motion.LINEAR).start();
        label.animate().alpha(on ? 1 : 0).translationY(on ? 12 : 0).setDuration(on ? 300 : 220).setInterpolator(Motion.EASE).start();
        if (!on) {
            card.setRotationX(0);
            card.setRotationY(0);
            card.setTranslationX(0);
            card.setTranslationY(0);
            glare.setTranslationX(0);
            glare.setTranslationY(0);
            Motion.spring(card, 1, 240);
        }
    }

    /** The light stays put while the card tilts, so the glare slides the other way. */
    void tilt(float rx, float ry) {
        glare.setTranslationX(-W * 0.22f - ry * W * 0.07f);
        glare.setTranslationY(-H * 0.3f + rx * H * 0.09f);
    }

    /** Move mode: the icon wiggles, as on iOS and tvOS, until you're done. */
    void setWiggling(boolean on) {
        Object old = getTag();
        if (old instanceof android.animation.Animator) ((android.animation.Animator) old).cancel();
        card.setRotation(0);
        setTag(null);
        if (!on) return;
        android.animation.ObjectAnimator w = android.animation.ObjectAnimator.ofFloat(card, View.ROTATION, -1.5f, 1.5f);
        w.setDuration(260);
        w.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        w.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        w.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        w.start();
        setTag(w);
    }

    /**
     * The light-catching rim: a bright arc where the light hits and a softer one opposite, its
     * angle set by the direction you arrived from. Fainter and thinner when not focused.
     */
    static final class Rim extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float angle = 135;
        private boolean on;

        Rim(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
        }

        void set(boolean focused, float deg) {
            on = focused;
            if (focused) angle = deg;
            setAlpha(focused ? 1 : 0.5f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sw = on ? 2.5f : 1.5f;
            double a = Math.toRadians(angle - 90);
            float cx = w / 2, cy = h / 2, len = (float) (Math.abs(w * Math.cos(a)) + Math.abs(h * Math.sin(a))) / 2;
            float dx = (float) Math.cos(a) * len, dy = (float) Math.sin(a) * len;
            p.setShader(new android.graphics.LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                    new int[] { 0xF2FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x73FFFFFF },
                    new float[] { 0, 0.22f, 0.45f, 0.62f, 1 }, android.graphics.Shader.TileMode.CLAMP));
            p.setStrokeWidth(sw);
            box.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
            c.drawRoundRect(box, RADIUS - sw / 2, RADIUS - sw / 2, p);
        }
    }

    /** Line glyphs for the built-in tiles (Find, Insights, Settings). */
    static final class Glyph extends View {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(Context c, String kind) {
            super(c);
            this.kind = kind;
            p.setColor(0xFFFFFFFF);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getHeight() * 0.5f / 24f, ox = getWidth() / 2f - 12 * s, oy = getHeight() / 2f - 12 * s;
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            if ("find".equals(kind)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f);
                c.drawCircle(10.5f, 10.5f, 6, p);
                c.drawLine(15, 15, 20.5f, 20.5f, p);
            } else if ("insights".equals(kind)) {
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(4, 12, 8, 20), 1.3f, 1.3f, p);
                c.drawRoundRect(new RectF(10, 7, 14, 20), 1.3f, 1.3f, p);
                c.drawRoundRect(new RectF(16, 4, 20, 20), 1.3f, 1.3f, p);
            } else {
                drawGear(c, p);
            }
            c.restore();
        }

        static void drawGear(Canvas c, Paint p) {
            p.setStyle(Paint.Style.FILL);
            Path gear = new Path();
            int teeth = 8;
            for (int i = 0; i < teeth * 2; i++) {
                double a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / 16, a1 = a0 + Math.PI / 8;
                float r = i % 2 == 0 ? 9.6f : 7.4f;
                float x0 = 12 + r * (float) Math.cos(a0), y0 = 12 + r * (float) Math.sin(a0);
                float x1 = 12 + r * (float) Math.cos(a1), y1 = 12 + r * (float) Math.sin(a1);
                if (i == 0) gear.moveTo(x0, y0);
                else gear.lineTo(x0, y0);
                gear.lineTo(x1, y1);
            }
            gear.close();
            gear.addCircle(12, 12, 3.6f, Path.Direction.CCW);
            gear.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(gear, p);
        }
    }
}
