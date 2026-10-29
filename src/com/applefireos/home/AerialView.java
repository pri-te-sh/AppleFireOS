package com.applefireos.home;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Apple's aerial flyovers, one after another, with the place name and the time. */
final class AerialView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final int MAX_FAILURES = 5;

    private final List<String[]> clips = new ArrayList<>();
    private final View curtain;
    private final TextView place, clock, status;
    private final float density;
    private Surface surface;
    private MediaPlayer player;
    private boolean started;
    private int index, failures;

    private final Runnable fadeOut = this::closeCurtain;
    private final Runnable hidePlace = this::hidePlaceName;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String pattern = DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm";
            clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
            postDelayed(this, 20_000);
        }
    };

    AerialView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF000000);
        loadClips(c);

        TextureView video = new TextureView(c);
        video.setSurfaceTextureListener(this);
        addView(video, new LayoutParams(-1, -1));

        curtain = new View(c);
        curtain.setBackgroundColor(0xFF000000);
        addView(curtain, new LayoutParams(-1, -1));

        place = label(30, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        place.setAlpha(0f);
        LayoutParams plp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        plp.leftMargin = dp(64);
        plp.bottomMargin = dp(52);
        addView(place, plp);

        clock = label(34, Typeface.create("sans-serif-light", Typeface.NORMAL));
        LayoutParams clp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.rightMargin = dp(64);
        clp.topMargin = dp(44);
        addView(clock, clp);

        status = label(20, Typeface.create("sans-serif", Typeface.NORMAL));
        status.setVisibility(GONE);
        addView(status, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    void start() {
        started = true;
        failures = 0;
        post(tick);
        if (surface != null) play();
    }

    void stop() {
        started = false;
        removeCallbacks(tick);
        removeCallbacks(fadeOut);
        removeCallbacks(hidePlace);
        release();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (started) play();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void play() {
        release();
        if (clips.isEmpty()) return;
        final String[] clip = clips.get(index % clips.size());
        curtain.setAlpha(1f);
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setSurface(surface);
            mp.setDataSource(clip[1]);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                failures = 0;
                status.setVisibility(GONE);
                p.start();
                curtain.animate().alpha(0f).setDuration(1600).start();
                showPlace(clip[0]);
                int duration = p.getDuration();
                if (duration > 4000) postDelayed(fadeOut, duration - 1600);
            });
            mp.setOnCompletionListener(p -> next());
            mp.setOnErrorListener((p, what, extra) -> {
                failed();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            failed();
        }
    }

    private void next() {
        index++;
        if (started) play();
    }

    private void failed() {
        if (++failures >= MAX_FAILURES) {
            release();
            status.setText("Can't reach Apple's aerial videos right now");
            status.setVisibility(VISIBLE);
            return;
        }
        post(this::next);
    }

    private void release() {
        removeCallbacks(fadeOut);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void closeCurtain() {
        curtain.animate().alpha(1f).setDuration(1400).start();
    }

    private void hidePlaceName() {
        place.animate().alpha(0f).setDuration(1200).start();
    }

    private void showPlace(String name) {
        removeCallbacks(hidePlace);
        place.setText(name);
        place.animate().alpha(1f).setDuration(1400).setStartDelay(800).start();
        postDelayed(hidePlace, 8000);
    }

    private void loadClips(Context c) {
        try (InputStream in = c.getAssets().open("aerials.json")) {
            byte[] data = new byte[in.available()];
            int read = 0;
            while (read < data.length) read += in.read(data, read, data.length - read);
            JSONArray a = new JSONArray(new String(data, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                clips.add(new String[]{o.optString("label"), o.optString("url")});
            }
        } catch (Exception ignored) {
            // No clips: the view stays black.
        }
        Collections.shuffle(clips);
    }

    private TextView label(int sp, Typeface face) {
        TextView v = new TextView(getContext());
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(0xF2FFFFFF);
        v.setTypeface(face);
        v.setShadowLayer(12 * density, 0, density, 0x99000000);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
