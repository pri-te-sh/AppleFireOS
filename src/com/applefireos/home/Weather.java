package com.applefireos.home;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Current weather beside the time, from Open-Meteo (free, no account, no key, no tracking).
 * You choose the city once from Settings; nothing about you is sent except that city's position.
 */
final class Weather {
    private static final String GEO = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String API = "https://api.open-meteo.com/v1/forecast";
    private static final long REFRESH = 30 * 60 * 1000L;

    private static LinearLayout box;
    private static ImageView icon;
    private static TextView temp;
    private static final Runnable again = Weather::refresh;

    private Weather() {}

    /** The weather box for the status bar, with a hairline after it; hidden until a city is set. */
    static View view(NativeHome home) {
        box = new LinearLayout(home);
        box.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(home);
        box.addView(icon, new LinearLayout.LayoutParams(34, 34));
        temp = Kit.overArt(Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = 8;
        box.addView(temp, tp);
        View rule = new View(home);
        rule.setBackgroundColor(Theme.LABEL_4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(2, 36);
        rp.leftMargin = 18;
        rp.rightMargin = 22;
        box.addView(rule, rp);
        box.setVisibility(View.GONE);
        refresh();
        return box;
    }

    private static JSONObject city() {
        return Prefs.object("weatherCity");
    }

    private static boolean fahrenheit() {
        return "F".equals(Prefs.string("weatherUnit", "C"));
    }

    private static int iconFor(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code <= 2) return R.drawable.ic_wx_partly;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_rain;
    }

    static void refresh() {
        if (box == null) return;
        Ui.MAIN.removeCallbacks(again);
        JSONObject c = city();
        if (c == null) {
            box.setVisibility(View.GONE);
            return;
        }
        Ui.later(REFRESH, again);
        boolean f = fahrenheit();
        Ui.IO.execute(() -> {
            try {
                JSONObject now = get(API + "?latitude=" + c.optDouble("lat") + "&longitude=" + c.optDouble("lon")
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto"
                        + (f ? "&temperature_unit=fahrenheit" : "")).getJSONObject("current");
                int res = iconFor(now.optInt("weather_code"), now.optInt("is_day", 1) == 1);
                String t = Math.round(now.optDouble("temperature_2m")) + "°";
                Ui.post(() -> {
                    icon.setImageResource(res);
                    temp.setText(t);
                    box.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                // Keep the last reading. Soon after boot the network isn't up yet: try again shortly.
                Ui.post(() -> {
                    Ui.MAIN.removeCallbacks(again);
                    Ui.later(60_000, again);
                });
            }
        });
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(Images.readAll(in), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private static List<TextEntry.Result> findCities(String text) throws Exception {
        JSONArray r = get(GEO + "?count=6&language=en&name=" + URLEncoder.encode(text, "UTF-8")).optJSONArray("results");
        List<TextEntry.Result> out = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            List<String> where = new ArrayList<>();
            for (String k : new String[] { "admin1", "country" }) if (!o.optString(k).isEmpty()) where.add(o.optString(k));
            JSONObject city = new JSONObject().put("name", o.optString("name"))
                    .put("lat", o.optDouble("latitude")).put("lon", o.optDouble("longitude"));
            out.add(new TextEntry.Result(o.optString("name"), android.text.TextUtils.join(", ", where), city));
        }
        return out;
    }

    private static void chooseCity(NativeHome home) {
        TextEntry.open(home, "Weather City", "Type a city", Weather::findCities, r -> {
            Prefs.set("weatherCity", r.data);
            refresh();
        });
    }

    /** "Lisbon" or "Off", for Settings. */
    static String label() {
        JSONObject c = city();
        return c == null ? "Off" : c.optString("name");
    }

    /** The Settings entry: set a city, or change units / remove it. */
    static void menu(NativeHome home) {
        JSONObject c = city();
        if (c == null) {
            chooseCity(home);
            return;
        }
        boolean f = fahrenheit();
        Sheet.open(home, "Weather · " + c.optString("name"), Arrays.asList(
                new Sheet.Option("Change City", () -> chooseCity(home)),
                new Sheet.Option("Show in °" + (f ? "C" : "F"), () -> {
                    Prefs.set("weatherUnit", f ? "C" : "F");
                    refresh();
                }),
                new Sheet.Option("Hide Weather", () -> {
                    Prefs.remove("weatherCity");
                    refresh();
                })));
    }
}
