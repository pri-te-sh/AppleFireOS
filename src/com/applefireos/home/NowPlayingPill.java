package com.applefireos.home;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Background music or audio as a small glass pill beside the time (Select it from Control Center
 * to pause). Data comes from Android's media sessions via NowPlaying.
 */
final class NowPlayingPill {
    private static LinearLayout pill;
    private static ImageView art;
    private static Eq eq;
    private static TextView title, artist;
    private static NativeHome home;

    private NowPlayingPill() {}

    static View view(NativeHome h) {
        home = h;
        pill = new LinearLayout(h);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(8, 0, 24, 0);
        pill.setBackground(Kit.chrome(34));
        art = new ImageView(h);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.rounded(art, 14);
        pill.addView(art, new LinearLayout.LayoutParams(52, 52));
        LinearLayout text = new LinearLayout(h);
        text.setOrientation(LinearLayout.VERTICAL);
        title = Kit.line(Kit.text(h, Theme.Type.CALLOUT, Theme.LABEL));
        artist = Kit.line(Kit.text(h, Theme.Type.CAPTION, Theme.LABEL_2));
        text.addView(title);
        text.addView(artist);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = 14;
        text.setMinimumWidth(0);
        pill.addView(text, tp);
        eq = new Eq(h);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(23, 26);
        ep.leftMargin = 14;
        pill.addView(eq, ep);
        pill.setOnClickListener(v -> {
            Motion.glow(pill);
            NowPlaying.act("toggle");
            Ui.later(300, NowPlayingPill::refresh);
        });
        NowPlaying.setOnChange(NowPlayingPill::refresh);
        refresh();
        return pill;
    }

    static void refresh() {
        if (pill == null) return;
        NowPlaying.Info n = NowPlaying.info(home);
        pill.setVisibility(n == null ? View.GONE : View.VISIBLE);
        if (n == null) return;
        art.setImageBitmap(n.art);
        art.setVisibility(n.art != null ? View.VISIBLE : View.GONE);
        title.setText(n.title.isEmpty() ? n.app : n.title);
        artist.setText(n.artist.isEmpty() ? n.app : n.artist);
        title.setMaxWidth(320);
        artist.setMaxWidth(320);
        eq.setPlaying(n.playing);
    }

    /** A little equaliser: three bars that bounce only while something is playing. */
    static final class Eq extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bar = new android.graphics.RectF();
        private boolean playing;
        private final long start = android.os.SystemClock.uptimeMillis();

        Eq(android.content.Context c) {
            super(c);
            p.setColor(Theme.LABEL_2);
        }

        void setPlaying(boolean on) {
            playing = on;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float t = (android.os.SystemClock.uptimeMillis() - start) / 900f, h = getHeight();
            for (int i = 0; i < 3; i++) {
                // 25%..100%, easing back and forth, each bar a third of a cycle apart.
                double phase = (t + i / 3.0) * Math.PI;
                float k = playing ? 0.25f + 0.75f * (float) (0.5 - 0.5 * Math.cos(phase)) : 0.3f;
                float x = i * 9;
                bar.set(x, h * (1 - k), x + 5, h);
                c.drawRoundRect(bar, 3, 3, p);
            }
            if (playing && isShown()) postInvalidateOnAnimation();
        }
    }
}
