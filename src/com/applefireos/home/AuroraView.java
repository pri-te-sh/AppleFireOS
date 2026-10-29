package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * The background when there's no artwork: four slowly drifting pools of colour, drawn on a tiny
 * bitmap ~20 times a second and stretched (soft by nature, and cheap at any size). Blends towards
 * each focused app's palette. Also owns the time-of-day mood.
 */
final class AuroraView extends View {
    private static final int W = 160, H = 90;
    private static final float[] SPEED_X = { 0.11f, 0.08f, 0.13f, 0.07f }, SPEED_Y = { 0.09f, 0.12f, 0.06f, 0.10f };
    private static final float[] PHASE = { 0f, 2.1f, 4.0f, 5.3f };

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas cx = new Canvas(bmp);
    private final Paint fill = new Paint(), add = new Paint(), draw = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect(0, 0, W, H), dst = new Rect();
    private final long start = SystemClock.uptimeMillis();
    private int[] from = { 0xFF2B3A67, 0xFF3D2C5E, 0xFF1F4E5F, 0xFF2A2A40 }, to = from;
    private long blendStart;
    private boolean running = true;
    private final Runnable tick = this::invalidate;

    AuroraView(Context c) {
        super(c);
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        add.setAntiAlias(true);
    }

    // ---- mood --------------------------------------------------------------------------------

    /** day | evening | night, or always day with the mood switched off. */
    static String mood() {
        if (!Prefs.bool("mood", true)) return "day";
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 6 && h < 17) return "day";
        if (h >= 17 && h < 21) return "evening";
        return "night";
    }

    /** Warmer in the evening, dimmer late at night: painted over artwork and the aurora. */
    static int moodTint() {
        String m = mood();
        return "evening".equals(m) ? Ui.alpha(Color.rgb(255, 150, 70), 0.07f)
                : "night".equals(m) ? Ui.alpha(Color.rgb(14, 10, 26), 0.3f) : 0;
    }

    private static float intensity() {
        String m = mood();
        return "night".equals(m) ? 0.6f : "evening".equals(m) ? 0.85f : 1f;
    }

    // ---- drawing -----------------------------------------------------------------------------

    void setPalette(int[] colors) {
        if (colors == null) return;
        long now = SystemClock.uptimeMillis();
        int[] cur = new int[4];
        for (int i = 0; i < 4; i++) cur[i] = current(i, now);
        from = cur;
        to = colors;
        blendStart = now;
    }

    /** Stops drawing while artwork covers it or the home screen is away. */
    void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        if (on) invalidate();
    }

    private int current(int i, long now) {
        float m = Math.min(1, (now - blendStart) / 900f);
        return Ui.mix(from[i], to[i], m);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float t = (now - start) / 1000f, k = intensity();
        cx.drawColor(0xFF05060A, PorterDuff.Mode.SRC);
        for (int i = 0; i < 4; i++) {
            float x = W * (0.5f + 0.42f * (float) Math.sin(t * SPEED_X[i] + PHASE[i]));
            float y = H * (0.45f + 0.38f * (float) Math.cos(t * SPEED_Y[i] + PHASE[i] * 1.3f));
            float r = W * (0.48f + 0.08f * (float) Math.sin(t * 0.21f + i));
            int c = current(i, now);
            add.setShader(new RadialGradient(x, y, r,
                    new int[] { Ui.alpha(c, 0.62f * k), Ui.alpha(c, 0.22f * k), Ui.alpha(c, 0) },
                    new float[] { 0, 0.5f, 1 }, Shader.TileMode.CLAMP));
            cx.drawRect(0, 0, W, H, add);
        }
        // Darker towards the bottom, then the time-of-day tint.
        fill.setColor(0xFF000000);
        fill.setShader(new LinearGradient(0, 0, 0, H, new int[] { 0x0D000000, 0x2E000000, 0x99000000 },
                new float[] { 0, 0.55f, 1 }, Shader.TileMode.CLAMP));
        cx.drawRect(0, 0, W, H, fill);
        fill.setShader(null);
        int tint = moodTint();
        if (tint != 0) {
            fill.setColor(tint);
            cx.drawRect(0, 0, W, H, fill);
        }
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bmp, src, dst, draw);
        if (running && isShown()) postOnAnimationDelayed(tick, 50); // ~20 fps is plenty for this
    }
}
