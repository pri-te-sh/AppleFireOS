package com.applefireos.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Soft tvOS-like interface sounds, synthesised once at start (no audio files): a sine with a fast
 * attack and an exponential tail, optionally sliding in pitch.
 */
final class Sound {
    private static final int RATE = 44100;
    private static AudioTrack move, select, bump, open;
    private static boolean enabled = true;

    private Sound() {}

    static void init() {
        enabled = Prefs.bool("sounds", true);
        Ui.IO.execute(() -> {
            move = make(2400, 28, 0.025f, 0);
            select = make(900, 70, 0.06f, 520);
            bump = make(180, 90, 0.05f, 120);
            open = make(660, 120, 0.04f, 990);
        });
    }

    private static AudioTrack make(float freq, int ms, float gain, float slideTo) {
        int n = RATE * (ms + 20) / 1000;
        short[] pcm = new short[n];
        double phase = 0;
        float total = ms / 1000f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float k = Math.min(1, t / total);
            double f = slideTo > 0 ? freq * Math.pow(slideTo / freq, k) : freq;
            phase += 2 * Math.PI * f / RATE;
            // 4 ms exponential attack to `gain`, then an exponential fall to silence.
            double env = t < 0.004 ? 0.0001 * Math.pow(gain / 0.0001, t / 0.004)
                    : t < total ? gain * Math.pow(0.0001 / gain, (t - 0.004) / (total - 0.004)) : 0;
            pcm[i] = (short) (Math.sin(phase) * env * 32767);
        }
        try {
            AudioTrack a = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build();
            a.write(pcm, 0, n);
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    private static void play(AudioTrack a) {
        if (!enabled || a == null) return;
        try {
            a.stop();
            a.reloadStaticData();
            a.play();
        } catch (Exception ignored) {
            // Sounds are a nicety.
        }
    }

    static void move() { play(move); }
    static void select() { play(select); }
    static void bump() { play(bump); }
    static void open() { play(open); }

    static boolean isOn() {
        return enabled;
    }

    static void set(boolean on) {
        enabled = on;
        Prefs.set("sounds", on);
        if (on) select();
    }
}
