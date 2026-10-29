package com.applefireos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Habits' "When You Watch": average minutes in each hour of the day, the prime two hours lit. */
final class HoursChart extends View {
    /** The busiest hour's bar height; the view is this plus room for the labels. */
    static final int H = 300, HEIGHT = H + 50;
    private static final int GAP = 10, FOOT = 38, RADIUS = 4;

    private final double[] minutes;
    private final int peak;
    private final double max;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), label;
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];

    HoursChart(Context c, double[] minutes, int peak) {
        super(c);
        this.minutes = minutes;
        this.peak = peak;
        label = WeekChart.paint(c, Theme.Type.CAPTION, Theme.LABEL_3);
        double m = 1;
        for (double v : minutes) m = Math.max(m, v);
        max = m;
    }

    @Override
    protected void onDraw(Canvas c) {
        float base = getHeight() - FOOT;
        float col = (getWidth() - 23f * GAP) / 24;
        for (int h = 0; h < 24; h++) {
            float x = h * (col + GAP);
            double m = minutes[h];
            float height = (float) Math.max(m > 0 ? 6 : 0, m / max * H);
            if (height > 0) {
                boolean hot = h == peak || h == (peak + 1) % 24;
                bar.setColor(hot ? Theme.SERIES[0] : Ui.alpha(Theme.SERIES[0], 0.55f));
                rect.set(x, base - height, x + col, base);
                float r = Math.min(RADIUS, height);
                radii[0] = radii[1] = radii[2] = radii[3] = r;
                path.reset();
                path.addRoundRect(rect, radii, Path.Direction.CW);
                c.drawPath(path, bar);
            }
            if (h % 6 == 0) c.drawText(ScreenTime.hourLabel(h), x + col / 2, WeekChart.baseline(label, base + 12, 26), label);
        }
    }
}
