package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads pictures (TMDB artwork over https, the apps' own card art from their content providers),
 * keeps the downloaded files on disk and a few decoded bitmaps in memory.
 */
final class Images {
    interface Done {
        void got(Bitmap b);
    }

    private static Context app;
    private static File dir;
    /** Decoded pictures, by URL and size; ~12 MB is two backdrops plus logos and posters. */
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getAllocationByteCount();
        }
    };
    private static final Map<String, List<Done>> PENDING = new HashMap<>();

    private Images() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
    }

    static Bitmap cached(String url, int maxW) {
        return url == null ? null : MEM.get(url + "@" + maxW);
    }

    /** Calls back on the main thread with the picture (no wider than maxW), or null. */
    static void load(String url, int maxW, Done done) {
        if (url == null || url.isEmpty()) {
            Ui.post(() -> done.got(null));
            return;
        }
        String key = url + "@" + maxW;
        Bitmap hit = MEM.get(key);
        if (hit != null) {
            Ui.post(() -> done.got(hit));
            return;
        }
        synchronized (PENDING) {
            List<Done> waiting = PENDING.get(key);
            if (waiting != null) {
                waiting.add(done);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(done);
            PENDING.put(key, waiting);
        }
        Ui.IO.execute(() -> {
            Bitmap b = null;
            try {
                byte[] data = bytes(url);
                if (data != null) b = decode(data, maxW);
            } catch (Throwable ignored) {
                // Leave it empty.
            }
            if (b != null) MEM.put(key, b);
            final Bitmap result = b;
            List<Done> waiting;
            synchronized (PENDING) {
                waiting = PENDING.remove(key);
            }
            Ui.post(() -> {
                for (Done d : waiting) d.got(result);
            });
        });
    }

    /** Drops decoded pictures (the home screen is in the background). */
    static void trimMemory() {
        MEM.evictAll();
    }

    /** The same as load(), on the calling thread (which must not be the main one). */
    static Bitmap loadNow(String url, int maxW) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "@" + maxW;
        Bitmap b = MEM.get(key);
        if (b != null) return b;
        try {
            byte[] data = bytes(url);
            if (data != null) b = decode(data, maxW);
        } catch (Throwable ignored) {
            return null;
        }
        if (b != null) MEM.put(key, b);
        return b;
    }

    /** Downloads ahead of time without decoding (the Top Shelf's next picture). */
    static void prefetch(String url) {
        if (url == null || !url.startsWith("http")) return;
        Ui.IO.execute(() -> {
            try {
                bytes(url);
            } catch (Throwable ignored) {
                // It'll be fetched when needed.
            }
        });
    }

    private static Bitmap decode(byte[] data, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b != null && b.getWidth() > maxW * 1.2f) {
            Bitmap s = Bitmap.createScaledBitmap(b, maxW, Math.round(b.getHeight() * maxW / (float) b.getWidth()), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    private static byte[] bytes(String url) throws Exception {
        if (url.startsWith("content:") || url.startsWith("android.resource:") || url.startsWith("file:")) {
            try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(url))) {
                return in == null ? null : readAll(in);
            }
        }
        File f = new File(dir, sha1(url));
        if (f.isFile() && f.length() > 0) {
            try (InputStream in = new FileInputStream(f)) {
                return readAll(in);
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            byte[] data = readAll(in);
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            tmp.renameTo(f);
            trim();
            return data;
        } finally {
            c.disconnect();
        }
    }

    /** Keeps the disk cache under ~60 MB by dropping the oldest files. */
    private static void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total < 60L * 1024 * 1024) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total < 40L * 1024 * 1024) break;
            total -= f.length();
            f.delete();
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
