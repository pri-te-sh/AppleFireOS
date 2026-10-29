package com.applefireos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.List;
import java.util.Map;

/**
 * Screen Time's week: a stacked bar per day (the top three apps, then everything else), the
 * total over the busiest day and today, and a dashed line at the daily average.
 */
final class WeekChart extends View {
    /** The busiest day's bar height; the view is this plus room for the labels. */
    static final int H = 470, HEIGHT = H + 70;
    private static final int BAR_W = 66, GAP = 28, PAD_LEFT = 10, PAD_RIGHT = 84, FOOT = 36, RADIUS = 4;

    private final ScreenTime week;
    private final List<String> top;
    private final double max;
    /** Seconds per day: the top three apps, then Other. */
    private final long[][] parts = new long[7][4];
    private final float[] radii = new float[8];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), value, day, today, avgText;
    private final Paint avgLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    WeekChart(Context c, ScreenTime week) {
        super(c);
        this.week = week;
        top = week.top();
        double m = Math.max(week.avg, 1);
        for (ScreenTime.Day d : week.days) m = Math.max(m, d.total);
        max = m;
        for (int i = 0; i < 7; i++) {
            for (Map.Entry<String, Long> e : week.days[i].byApp.entrySet()) {
                int k = top.indexOf(e.getKey());
                parts[i][k >= 0 ? k : 3] += e.getValue();
            }
        }
        value = paint(c, Theme.Type.FOOTNOTE, Theme.LABEL);
        day = paint(c, Theme.Type.FOOTNOTE, Theme.LABEL_3);
        today = paint(c, Theme.Type.FOOTNOTE, Theme.LABEL);
        avgText = paint(c, Theme.Type.CAPTION, Theme.LABEL_3);
        avgText.setTextAlign(Paint.Align.RIGHT);
        avgLine.setStyle(Paint.Style.STROKE);
        avgLine.setStrokeWidth(2);
        avgLine.setColor(Theme.LABEL_4);
        avgLine.setPathEffect(new DashPathEffect(new float[] { 6, 6 }, 0));
    }

    /**
     * A centred Paint for chart labels in one of the type scale's styles: copied from a Kit text
     * so charts draw their words exactly as the rest of the page sets them.
     */
    static Paint paint(Context c, Theme.Type t, int color) {
        Paint p = Kit.textPaint(c, t, color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    /** Where text sits vertically centred in a line box, like the web's labels. */
    static float baseline(Paint p, float top, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return top + (height - (fm.descent - fm.ascent)) / 2 - fm.ascent;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        float base = getHeight() - FOOT;
        float col = (w - PAD_LEFT - PAD_RIGHT - 6f * GAP) / 7;
        long busiest = 0;
        for (ScreenTime.Day d : week.days) busiest = Math.max(busiest, d.total);

        for (int i = 0; i < 7; i++) {
            ScreenTime.Day d = week.days[i];
            float cx = PAD_LEFT + i * (col + GAP) + col / 2;
            long[] p = parts[i];
            int last = -1;
            for (int k = 0; k < 4; k++) if (p[k] > 0) last = k;
            // Bottom up: the most watched app sits on the baseline, Other on top; 2 px apart.
            float y = base;
            for (int k = 0; k < 4; k++) {
                if (p[k] == 0) continue;
                if (y < base) y -= 2;
                float h = (float) (p[k] / max * H);
                rect.set(cx - BAR_W / 2f, y - h, cx + BAR_W / 2f, y);
                bar.setColor(k < 3 ? Theme.SERIES[k] : Theme.OTHER);
                float r = k == last ? Math.min(RADIUS, h) : 0;
                radii[0] = radii[1] = radii[2] = radii[3] = r;
                path.reset();
                path.addRoundRect(rect, radii, Path.Direction.CW);
                c.drawPath(path, bar);
                y -= h;
            }
            boolean isToday = i == 6;
            if (d.total > 0 && (d.total == busiest || isToday)) {
                c.drawText(ScreenTime.duration(d.total), cx, baseline(value, y - 38, 28), value);
            }
            Paint label = isToday ? today : day;
            c.drawText(d.label, cx, baseline(label, base + 12, 24), label);
        }

        float avgY = (float) (base - week.avg / max * H);
        // The line stops short of its label so the text is never crossed.
        c.drawLine(0, avgY, w - 76, avgY, avgLine);
        c.drawText("avg", w, baseline(avgText, avgY - 15, 22), avgText);
        c.drawText(ScreenTime.duration(week.avg), w, baseline(avgText, avgY + 7, 22), avgText);
    }
}
