package com.applefireos.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Performance's memory gauge: a ring filled to the share in use, in its status colour, with the
 * free amount ("476 MB / free of 1.6 GB") drawn in its middle. The label is drawn here rather than
 * laid over the ring, so it's centred on the ring's own centre and always fits inside it.
 */
final class MemoryRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big, small;
    private final String bigText, smallText;
    private final RectF oval = new RectF();
    private final Rect bigBounds = new Rect(), smallBounds = new Rect();
    private final float used;
    private float shown;

    MemoryRing(Context c, float used, int color, String bigText, String smallText) {
        super(c);
        this.used = used;
        this.bigText = bigText;
        this.smallText = smallText;
        track.setStyle(Paint.Style.STROKE);
        track.setColor(Theme.FILL_2);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(color);
        big = WeekChart.paint(c, Theme.Type.PAGE_TITLE, Theme.LABEL);
        small = WeekChart.paint(c, Theme.Type.SUBHEAD, Theme.LABEL_2);
        big.getTextBounds(bigText, 0, bigText.length(), bigBounds);
        small.getTextBounds(smallText, 0, smallText.length(), smallBounds);
    }

    /** Sweeps in from empty. */
    void sweep() {
        ValueAnimator a = ValueAnimator.ofFloat(0, used);
        a.setDuration(1200);
        a.setInterpolator(Motion.EASE);
        a.addUpdateListener(v -> {
            shown = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        // Drawn on the web's 360-unit square: radius 150, a 30-unit stroke.
        float k = Math.min(getWidth(), getHeight()) / 360f, r = 150 * k, stroke = 30 * k;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        c.drawCircle(cx, cy, r, track);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        if (shown > 0) c.drawArc(oval, -90, 360 * shown, false, arc);

        // The label: both lines centred on the ring's centre by their inked bounds (not their line
        // boxes, whose font padding pushes text off-centre), shrunk if it would touch the ring.
        float gap = Theme.S1;
        float h = bigBounds.height() + gap + smallBounds.height();
        float w = Math.max(big.measureText(bigText), small.measureText(smallText));
        float room = (r - stroke / 2) * 2 * 0.8f;
        float fit = Math.min(1, room / Math.max(w, h));
        c.save();
        c.scale(fit, fit, cx, cy);
        float top = cy - h / 2;
        c.drawText(bigText, cx, top - bigBounds.top, big);
        c.drawText(smallText, cx, top + bigBounds.height() + gap - smallBounds.top, small);
        c.restore();
    }
}
