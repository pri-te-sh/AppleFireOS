package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * The Top Shelf's artwork: two pictures crossfading, each with a slow Ken Burns zoom and drift,
 * drawn directly (no child views or view animations), so every frame is exactly what we ask for.
 */
final class SlidesView extends View {
    private static final long FADE_MS = 1000, DRIFT_MS = 10500;

    private final Bitmap[] bmp = new Bitmap[2];
    private final String[] key = new String[2];
    private final long[] driftStart = new long[2];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private int front;
    private long fadeStart;
    /** Overall opacity: 0 shows the aurora, 0.3 the dimmed art behind deeper rows, 1 full art. */
    private float level, levelFrom, levelTo;
    private long levelStart, levelMs = 1;
    private boolean paused;

    SlidesView(Context c) {
        super(c);
    }

    Bitmap frontBitmap() {
        return bmp[front];
    }

    String backKey() {
        return key[1 - front];
    }

    /** Puts a picture in the hidden slot, ready for the next crossfade. */
    void prepare(Bitmap b, String k) {
        if (fading()) return;
        bmp[1 - front] = b;
        key[1 - front] = k;
    }

    /** Crossfades to the picture in the hidden slot. */
    void reveal() {
        front = 1 - front;
        long now = SystemClock.uptimeMillis();
        fadeStart = now;
        driftStart[front] = now;
        postInvalidateOnAnimation();
    }

    /** Stops drawing while the home screen is away. */
    void setPaused(boolean on) {
        paused = on;
        if (!on) postInvalidateOnAnimation();
    }

    /** Animates the overall opacity (see `level`). */
    void setLevel(float to, long ms) {
        levelFrom = level;
        levelTo = to;
        levelStart = SystemClock.uptimeMillis();
        levelMs = Math.max(1, ms);
        postInvalidateOnAnimation();
    }

    private boolean fading() {
        return SystemClock.uptimeMillis() - fadeStart < FADE_MS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float lt = Math.min(1, (now - levelStart) / (float) levelMs);
        level = levelFrom + (levelTo - levelFrom) * (1 - (1 - lt) * (1 - lt));
        if (level <= 0.001f) {
            if (lt < 1) postInvalidateOnAnimation();
            return;
        }
        float fade = Math.min(1, (now - fadeStart) / (float) FADE_MS);
        // The outgoing picture keeps drifting while it fades out.
        if (fade < 1) draw(canvas, 1 - front, 1 - fade, now);
        draw(canvas, front, fade, now);
        // Keep drifting while anything is visible.
        if (!paused) postInvalidateOnAnimation();
    }

    private void draw(Canvas canvas, int i, float alpha, long now) {
        Bitmap b = bmp[i];
        if (b == null || alpha <= 0) return;
        float p = Math.min(1, (now - driftStart[i]) / (float) DRIFT_MS);
        float s = 1 + 0.08f * p, k = getWidth() / (float) b.getWidth();
        m.setScale(k, getHeight() / (float) b.getHeight());
        m.postScale(s, s, getWidth() * 0.65f, getHeight() * 0.35f);
        m.postTranslate(-24 * p, 0);
        paint.setAlpha(Math.round(255 * alpha * level));
        canvas.drawBitmap(b, m, paint);
    }
}
