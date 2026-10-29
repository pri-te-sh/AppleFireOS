package com.applefireos.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The one motion language of the home screen (the tvOS 26/27 feel): a springy
 * overshoot for things that appear or take focus, a soft ease-out for things that move. Every
 * panel and focusable thing animates through here, so they all feel the same.
 */
final class Motion {
    /** tvOS spring: overshoots a little and settles. */
    static final Interpolator SPRING = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    /** Soft ease-out for moving and scrolling. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    static final Interpolator LINEAR = new LinearInterpolator();
    /** An app opening: quick out of the tile, gentle into full screen. */
    static final Interpolator LAUNCH = new PathInterpolator(0.3f, 0.7f, 0.2f, 1f);

    /** Panels: fade quickly, grow with the spring. */
    static final long FADE = 200, GROW = 420;
    /** Focus: how fast a highlighted choice lifts. */
    static final long LIFT = 300;

    private Motion() {}

    /**
     * While a panel fades and grows, draw it once into a GPU layer and let the GPU fade and scale
     * that, instead of re-drawing the glass and text every frame (the TV's GPU stutters otherwise).
     */
    private static void layerFor(View v, long ms) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        Object pending = v.getTag(TAG_LAYER);
        if (pending instanceof Runnable) v.removeCallbacks((Runnable) pending);
        Runnable off = () -> v.setLayerType(View.LAYER_TYPE_NONE, null);
        v.setTag(TAG_LAYER, off);
        v.postDelayed(off, ms + 50);
    }

    /** View tag slot for the pending layer clean-up (any id from our own resources works). */
    private static final int TAG_LAYER = R.drawable.ic_check;

    /**
     * A panel materialises out of the thing that opened it: it grows from `fromScale` to full
     * size around (fx, fy), given as fractions of its own size (1, 0 is its top-right corner),
     * fading in. Waits for the panel's first layout so the point is in the right place.
     */
    static void open(View v, float fx, float fy, float fromScale) {
        v.setAlpha(0);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        Runnable go = () -> {
            v.setPivotX(v.getWidth() * fx);
            v.setPivotY(v.getHeight() * fy);
            layerFor(v, GROW);
            v.animate().alpha(1).setDuration(FADE).setInterpolator(LINEAR).start();
            // A separate animator, so the fade and the spring keep their own curves.
            spring(v, 1, GROW);
        };
        if (v.getWidth() > 0) {
            go.run();
            return;
        }
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                view.removeOnLayoutChangeListener(this);
                go.run();
            }
        });
    }

    /** The reverse of open(): shrinks back towards where it came from, then runs `done`. */
    static void close(View v, float toScale, Runnable done) {
        v.animate().cancel();
        layerFor(v, 260);
        ObjectAnimator.ofFloat(v, View.SCALE_X, v.getScaleX(), toScale).setDuration(240).start();
        ObjectAnimator.ofFloat(v, View.SCALE_Y, v.getScaleY(), toScale).setDuration(240).start();
        v.animate().alpha(0).setDuration(FADE).setInterpolator(EASE).withEndAction(done).start();
    }

    /** Springs a view's scale to `to` (used for focus lifts and panels growing). */
    static void spring(View v, float to, long ms) {
        ObjectAnimator x = ObjectAnimator.ofFloat(v, View.SCALE_X, v.getScaleX(), to);
        ObjectAnimator y = ObjectAnimator.ofFloat(v, View.SCALE_Y, v.getScaleY(), to);
        x.setInterpolator(SPRING);
        y.setInterpolator(SPRING);
        x.setDuration(ms);
        y.setDuration(ms);
        x.start();
        y.start();
    }

    /** A focusable thing lifting (on) or settling back (off). */
    static void lift(View v, boolean on, float scale) {
        spring(v, on ? scale : 1, LIFT);
    }

    /** Moves a view along the soft curve (lists scrolling, the grid stepping between rows). */
    static void slideY(View v, float to, long ms) {
        v.animate().translationY(to).setDuration(ms).setInterpolator(EASE).start();
    }

    /** A scrim or whole layer fading. */
    static void fade(View v, float to, long ms, Runnable done) {
        v.animate().cancel();
        layerFor(v, ms);
        v.animate().alpha(to).setDuration(ms).setInterpolator(LINEAR).withEndAction(done).start();
    }

    /**
     * Dims (or clears) the screen behind a panel by fading only the backdrop's colour, so the
     * panel inside keeps animating on its own instead of being redrawn into a full-screen layer.
     */
    static void dim(View backdrop, int color, boolean in, Runnable done) {
        android.graphics.drawable.ColorDrawable d;
        if (backdrop.getBackground() instanceof android.graphics.drawable.ColorDrawable) {
            d = (android.graphics.drawable.ColorDrawable) backdrop.getBackground();
        } else {
            d = new android.graphics.drawable.ColorDrawable(color);
            d.setAlpha(0);
            backdrop.setBackground(d);
        }
        int full = color >>> 24;
        ValueAnimator a = ValueAnimator.ofInt(d.getAlpha(), in ? full : 0);
        a.setDuration(in ? FADE : FADE + 60);
        a.addUpdateListener(an -> d.setAlpha((int) an.getAnimatedValue()));
        if (done != null) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    done.run();
                }
            });
        }
        a.start();
    }

    /** Toasts and banners: drop in from `dy` with a little scale, spring into place. */
    static void popIn(View v, float dy) {
        layerFor(v, 400);
        v.setAlpha(0);
        v.setTranslationY(dy);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1).setDuration(250).setInterpolator(LINEAR).start();
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, dy, 0).setDuration(400).start();
        spring(v, 1, 400);
    }

    /**
     * Interaction feedback on glass: it lights up from within, then settles
     * ("energize"). Only the opacity of a soft white wash animates.
     */
    static void glow(View v) {
        Drawable fg = v.getForeground();
        if (!(fg instanceof Glow)) {
            fg = new Glow();
            v.setForeground(fg);
        }
        Glow g = (Glow) fg;
        ValueAnimator a = ValueAnimator.ofFloat(0, 1, 0);
        a.setDuration(550);
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> {
            g.level = (float) an.getAnimatedValue();
            g.invalidateSelf();
        });
        a.start();
    }

    /** The soft white wash used by glow(). */
    static final class Glow extends Drawable {
        float level;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas c) {
            if (level <= 0) return;
            float w = getBounds().width(), h = getBounds().height();
            p.setShader(new RadialGradient(w / 2, h * 0.3f, Math.max(w, h) * 0.7f,
                    0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            p.setAlpha(Math.round(255 * level));
            c.drawRect(getBounds(), p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
