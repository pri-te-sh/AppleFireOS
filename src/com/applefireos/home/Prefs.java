package com.applefireos.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * The launcher's settings, stored as JSON values under short names ("order", "hidden",
 * "folders", "usage", "region", …).
 */
final class Prefs {
    private static SharedPreferences sp;

    private Prefs() {}

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String raw(String key) {
        return sp.getString(key, null);
    }

    static JSONArray array(String key) {
        try {
            String v = raw(key);
            return v == null ? null : new JSONArray(v);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject object(String key) {
        try {
            String v = raw(key);
            return v == null ? null : new JSONObject(v);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean bool(String key, boolean fallback) {
        String v = raw(key);
        return v == null ? fallback : "true".equals(v);
    }

    static String string(String key, String fallback) {
        String v = raw(key);
        if (v == null) return fallback;
        // Stored as JSON, so strings arrive quoted.
        if (v.length() >= 2 && v.charAt(0) == '"') return v.substring(1, v.length() - 1);
        return v;
    }

    static void set(String key, Object json) {
        sp.edit().putString(key, json instanceof String ? JSONObject.quote((String) json) : String.valueOf(json)).apply();
    }

    static void remove(String key) {
        sp.edit().remove(key).apply();
    }
}
