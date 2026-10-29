package com.applefireos.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Whatever is playing in the background (music, a podcast…), for the Now Playing pill and the
 * Control Center tile. Reading media sessions rides on the notification access RecsListener has.
 */
final class NowPlaying {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable onChange;
    private static MediaController current;
    private static String ownPackage;

    private static final MediaController.Callback WATCH = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            changed();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            changed();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    private NowPlaying() {}

    static void setOnChange(Runnable callback) {
        onChange = callback;
    }

    /** Called once notification access is live. */
    static void start(Context c) {
        ownPackage = c.getPackageName();
        MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(c, RecsListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(NowPlaying::pick, listener, MAIN);
            pick(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            // No notification access yet.
        }
    }

    /** Prefer something playing; otherwise the most recent paused session. */
    private static void pick(List<MediaController> sessions) {
        MediaController best = null;
        if (sessions != null) {
            for (MediaController mc : sessions) {
                if (mc.getPackageName().equals(ownPackage)) continue;
                PlaybackState st = mc.getPlaybackState();
                int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (state == PlaybackState.STATE_PLAYING) {
                    best = mc;
                    break;
                }
                if (best == null && state == PlaybackState.STATE_PAUSED) best = mc;
            }
        }
        if (current != null) current.unregisterCallback(WATCH);
        current = best;
        if (current != null) current.registerCallback(WATCH, MAIN);
        changed();
    }

    private static void changed() {
        if (onChange != null) MAIN.post(onChange);
    }

    static String json(Context c) {
        MediaController mc = current;
        MediaMetadata meta = mc == null ? null : mc.getMetadata();
        if (meta == null) return "null";
        try {
            JSONObject o = new JSONObject();
            PlaybackState st = mc.getPlaybackState();
            o.put("pkg", mc.getPackageName());
            o.put("app", appLabel(c, mc.getPackageName()));
            o.put("title", text(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE));
            o.put("artist", text(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST));
            o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art != null) o.put("art", thumbnail(art));
            return o.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    /** What's playing in the background, for the native home screen (null when nothing is). */
    static final class Info {
        String pkg, app, title, artist;
        boolean playing;
        Bitmap art;
    }

    static Info info(Context c) {
        MediaController mc = current;
        MediaMetadata meta = mc == null ? null : mc.getMetadata();
        if (meta == null) return null;
        Info i = new Info();
        PlaybackState st = mc.getPlaybackState();
        i.pkg = mc.getPackageName();
        i.app = appLabel(c, i.pkg);
        i.title = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        i.artist = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        i.playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        i.art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (i.art == null) i.art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        return i.title.isEmpty() && i.app.isEmpty() ? null : i;
    }

    /** "toggle", "next" or "previous". */
    static void act(String action) {
        MediaController mc = current;
        if (mc == null) return;
        MediaController.TransportControls t = mc.getTransportControls();
        PlaybackState st = mc.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if ("next".equals(action)) t.skipToNext();
        else if ("previous".equals(action)) t.skipToPrevious();
        else if (playing) t.pause();
        else t.play();
    }

    private static String text(MediaMetadata meta, String first, String fallback) {
        CharSequence v = meta.getText(first);
        if (v == null || v.length() == 0) v = meta.getText(fallback);
        return v == null ? "" : v.toString();
    }

    private static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String thumbnail(Bitmap art) {
        Bitmap small = Bitmap.createScaledBitmap(art, 160, Math.max(1, 160 * art.getHeight() / art.getWidth()), true);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
        return "data:image/jpeg;base64," + Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP);
    }
}
