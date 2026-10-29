package com.applefireos.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The apps on the home screen: what's installed, in your order, minus hidden ones, plus folders. */
final class Apps {
    static final String SETTINGS = "__settings__", FIND = "__find__", INSIGHTS = "__insights__";

    static final class App {
        String pkg, label;
        boolean system;
        /** Tile artwork: the app's TV banner, or its icon drawn on a tint. */
        Bitmap banner, icon;
        int tint = 0xFF3A3A3C;
        /** Built-in tiles (Find, Insights, Settings) draw a glyph instead. */
        String glyph;
        /** Folder tiles. */
        Folder folder;
        List<App> apps;
        int[] palette;

        boolean special() {
            return glyph != null;
        }
    }

    static final class Folder {
        String id, name;
        List<String> apps = new ArrayList<>();
    }

    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /** Amazon system clutter that stays hidden until brought back from Settings. */
    static final List<String> DEFAULT_HIDDEN = Arrays.asList(
            "com.amazon.audiohome", "com.amazon.bueller.music", "com.amazon.bueller.photos", "com.amazon.cloud9",
            "com.amazon.firetv.troubleshooting", "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver",
            "com.amazon.hedwig", "com.amazon.minitv.android.app", "com.amazon.shoptv.firetv.client",
            "com.amazon.smarthomemapviewapp", "com.amazon.ssm", "com.amazon.tahoe", "com.amazon.tv.earlyaccess",
            "com.amazon.venezia", "com.amazon.whasettings");

    /** First-run order; everything else follows alphabetically. */
    static final List<String> PREFERRED = Arrays.asList(
            "com.netflix.ninja", "com.amazon.avod", "com.amazon.firetv.youtube", "com.disney.disneyplus", "com.hbo.hbonow",
            "com.hulu.plus", "com.apple.atve.amazon.appletv");

    private static final Map<String, App> CACHE = new HashMap<>();

    private Apps() {}

    /** Every launchable app with its artwork (slow the first time: run on Ui.IO). */
    static synchronized List<App> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> seen = new LinkedHashSet<>();
        List<App> out = new ArrayList<>();
        for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                ActivityInfo ai = ri.activityInfo;
                String pkg = ai.packageName;
                if (pkg.equals(c.getPackageName()) || pkg.equals(AMAZON_LAUNCHER) || !seen.add(pkg)) continue;
                try {
                    long version = pm.getPackageInfo(pkg, 0).lastUpdateTime;
                    String cacheKey = pkg + "@" + version;
                    App a = CACHE.get(cacheKey);
                    if (a == null) {
                        a = new App();
                        a.pkg = pkg;
                        a.label = ri.loadLabel(pm).toString();
                        a.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        Drawable banner = ai.loadBanner(pm);
                        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                        if (banner != null) {
                            a.banner = render(banner, 320, 192);
                        } else {
                            a.icon = render(ai.loadIcon(pm), 144, 144);
                            a.tint = tintOf(a.icon);
                        }
                        a.palette = paletteOf(a.banner != null ? a.banner : a.icon);
                        CACHE.put(cacheKey, a);
                    }
                    out.add(a);
                } catch (Exception ignored) {
                    // Skip an app whose details can't be read.
                }
            }
        }
        return out;
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    // ---- order ----------------------------------------------------------------------------

    static List<String> list(String key, List<String> fallback) {
        JSONArray a = Prefs.array(key);
        if (a == null) return new ArrayList<>(fallback);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static List<String> hidden() {
        return list("hidden", DEFAULT_HIDDEN);
    }

    static void hide(String pkg) {
        Set<String> h = new LinkedHashSet<>(hidden());
        h.add(pkg);
        Prefs.set("hidden", new JSONArray(h));
    }

    static void unhide(String pkg) {
        List<String> h = hidden();
        h.remove(pkg);
        Prefs.set("hidden", new JSONArray(h));
    }

    private static int rank(List<String> order, String pkg) {
        int i = order.indexOf(pkg);
        if (i >= 0) return i;
        i = PREFERRED.indexOf(pkg);
        return i >= 0 ? 10000 + i : 20000;
    }

    /** The tiles in order: apps and folders, then Find, Insights and Settings. */
    static List<App> arrange(List<App> all) {
        Set<String> hidden = new HashSet<>(hidden());
        List<String> order = list("order", Collections.<String>emptyList());
        List<Folder> folders = folders();
        Set<String> inFolder = new HashSet<>();
        for (Folder f : folders) inFolder.addAll(f.apps);
        Map<String, App> byPkg = new HashMap<>();
        for (App a : all) byPkg.put(a.pkg, a);

        List<App> visible = new ArrayList<>();
        for (App a : all) if (!hidden.contains(a.pkg) && !inFolder.contains(a.pkg)) visible.add(a);
        for (Folder f : folders) {
            App t = new App();
            t.pkg = "folder:" + f.id;
            t.label = f.name;
            t.folder = f;
            t.system = true;
            t.apps = new ArrayList<>();
            for (String p : f.apps) {
                App a = byPkg.get(p);
                if (a != null && !hidden.contains(p)) t.apps.add(a);
            }
            if (t.apps.isEmpty()) continue;
            t.palette = t.apps.get(0).palette;
            visible.add(t);
        }
        Collections.sort(visible, (a, b) -> {
            int d = rank(order, a.pkg) - rank(order, b.pkg);
            return d != 0 ? d : a.label.compareToIgnoreCase(b.label);
        });
        if (smart()) {
            List<App> manual = new ArrayList<>(visible);
            Map<String, Double> score = new HashMap<>();
            for (App a : visible) {
                double s = 0;
                if (a.folder != null) for (App x : a.apps) s = Math.max(s, score(x.pkg));
                else s = score(a.pkg);
                score.put(a.pkg, s);
            }
            Collections.sort(visible, (a, b) -> {
                int d = Double.compare(score.get(b.pkg), score.get(a.pkg));
                return d != 0 ? d : manual.indexOf(a) - manual.indexOf(b);
            });
        }
        visible.add(special(FIND, "Find", "find", 0xFF2F5D9C));
        visible.add(special(INSIGHTS, "Insights", "insights", 0xFF5E5CE6));
        visible.add(special(SETTINGS, "Settings", "settings", 0xFF55555A));
        return visible;
    }

    private static App special(String pkg, String label, String glyph, int tint) {
        App a = new App();
        a.pkg = pkg;
        a.label = label;
        a.glyph = glyph;
        a.tint = tint;
        a.system = true;
        a.palette = new int[] { 0xFF3E4152, 0xFF2F3345, 0xFF24283A, 0xFF4A4A5A };
        return a;
    }

    static void saveOrder(List<App> tiles) {
        JSONArray a = new JSONArray();
        for (App t : tiles) if (!t.special()) a.put(t.pkg);
        Prefs.set("order", a);
    }

    // ---- folders --------------------------------------------------------------------------

    static List<Folder> folders() {
        List<Folder> out = new ArrayList<>();
        JSONArray a = Prefs.array("folders");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Folder f = new Folder();
            f.id = o.optString("id");
            f.name = o.optString("name");
            JSONArray p = o.optJSONArray("apps");
            for (int k = 0; p != null && k < p.length(); k++) f.apps.add(p.optString(k));
            out.add(f);
        }
        return out;
    }

    static void saveFolders(List<Folder> list) {
        JSONArray a = new JSONArray();
        for (Folder f : list) {
            if (f.apps.isEmpty()) continue;
            try {
                a.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.apps)));
            } catch (Exception ignored) {
                // Skip it.
            }
        }
        Prefs.set("folders", a);
    }

    static Folder addToFolder(String pkg, String name) {
        List<Folder> list = folders();
        for (Folder f : list) f.apps.remove(pkg);
        Folder target = null;
        for (Folder f : list) if (f.name.equalsIgnoreCase(name)) target = f;
        if (target == null) {
            target = new Folder();
            target.id = Long.toString(System.currentTimeMillis(), 36);
            target.name = name;
            list.add(target);
        }
        target.apps.add(pkg);
        saveFolders(list);
        return target;
    }

    static void removeFromFolder(String pkg) {
        List<Folder> list = folders();
        for (Folder f : list) f.apps.remove(pkg);
        saveFolders(list);
    }

    static void renameFolder(String id, String name) {
        List<Folder> list = folders();
        for (Folder f : list) if (f.id.equals(id)) f.name = name;
        saveFolders(list);
    }

    static void dissolveFolder(String id) {
        List<Folder> list = folders(), keep = new ArrayList<>();
        for (Folder f : list) if (!f.id.equals(id)) keep.add(f);
        saveFolders(keep);
    }

    // ---- smart order: which apps you open at this hour ------------------------------------

    static boolean smart() {
        return "smart".equals(Prefs.string("orderMode", "manual"));
    }

    static void record(String pkg) {
        JSONObject all = Prefs.object("usage");
        if (all == null) all = new JSONObject();
        try {
            // Old habits fade: every launch nudges everything else down a little.
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                JSONArray h = all.getJSONObject(it.next()).getJSONArray("h");
                for (int i = 0; i < h.length(); i++) h.put(i, h.getDouble(i) * 0.97);
            }
            JSONObject u = all.optJSONObject(pkg);
            if (u == null) {
                JSONArray h = new JSONArray();
                for (int i = 0; i < 24; i++) h.put(0);
                u = new JSONObject().put("h", h).put("last", 0);
                all.put(pkg, u);
            }
            JSONArray h = u.getJSONArray("h");
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            h.put(hour, h.getDouble(hour) + 1);
            u.put("last", System.currentTimeMillis());
            Prefs.set("usage", all);
        } catch (Exception ignored) {
            // Not recorded.
        }
    }

    static double score(String pkg) {
        JSONObject all = Prefs.object("usage");
        JSONObject u = all == null ? null : all.optJSONObject(pkg);
        if (u == null) return 0;
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        JSONArray h = u.optJSONArray("h");
        double s = 0;
        for (int i = 0; h != null && i < h.length(); i++) {
            int d = Math.min(Math.abs(i - now), 24 - Math.abs(i - now));
            s += h.optDouble(i) * (d == 0 ? 1 : d == 1 ? 0.5 : d == 2 ? 0.2 : 0.03);
        }
        return s + 1.5 * Math.exp(-(System.currentTimeMillis() - u.optLong("last")) / (3 * 24 * 3600 * 1000.0));
    }

    /** When each app was last opened from here, for the sidebar's Recent list. */
    static long lastUsed(String pkg) {
        JSONObject all = Prefs.object("usage");
        JSONObject u = all == null ? null : all.optJSONObject(pkg);
        return u == null ? 0 : u.optLong("last");
    }

    /** Region for the Top Shelf: chosen in Settings, else India if Indian apps are installed. */
    static String region(List<App> all) {
        String chosen = Prefs.string("region", null);
        if (chosen != null && !"null".equals(chosen)) return chosen;
        for (App a : all) if ("in.startv.hotstar".equals(a.pkg) || "com.zee5.amazon".equals(a.pkg)) return "IN";
        String c = Locale.getDefault().getCountry();
        return c.isEmpty() ? "US" : c;
    }

    // ---- colour ---------------------------------------------------------------------------

    /** Average colour of an icon, darkened enough for white text, for icon-only tiles. */
    static int tintOf(Bitmap icon) {
        Bitmap s = Bitmap.createScaledBitmap(icon, 12, 12, true);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
            int p = s.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
        }
        if (n == 0) return 0xFF3A3A3C;
        float[] hsv = Ui.hsv(Color.rgb((int) (r / n), (int) (g / n), (int) (b / n)));
        return Ui.fromHsv(hsv[0], Math.min(1, hsv[1] * 1.15f), Math.max(0.22f, Math.min(hsv[2], 0.62f)));
    }

    /**
     * Four glowing colours for the aurora, from the app's most vivid hues, so white-backed
     * banners (Netflix, YouTube) still glow in their brand colour rather than grey.
     */
    static int[] paletteOf(Bitmap art) {
        int[] neutral = { 0xFF3E4152, 0xFF2F3345, 0xFF24283A, 0xFF4A4A5A };
        if (art == null) return neutral;
        Bitmap s = Bitmap.createScaledBitmap(art, 32, 18, true);
        double[] weight = new double[12];
        double[][] sum = new double[12][3];
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            int p = s.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            float[] hsv = Ui.hsv(p);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            int k = (int) (hsv[0] / 30) % 12;
            double w = hsv[1] * hsv[2];
            weight[k] += w;
            sum[k][0] += Color.red(p) * w; sum[k][1] += Color.green(p) * w; sum[k][2] += Color.blue(p) * w;
        }
        int first = -1, second = -1;
        for (int k = 0; k < 12; k++) {
            if (weight[k] <= 0) continue;
            if (first < 0 || weight[k] > weight[first]) { second = first; first = k; }
            else if (second < 0 || weight[k] > weight[second]) second = k;
        }
        if (first < 0) return neutral;
        float[] c1 = avg(sum[first], weight[first]);
        float[] c2 = second >= 0 && weight[second] > weight[first] * 0.15 ? avg(sum[second], weight[second])
                : new float[] { (c1[0] + 35) % 360, c1[1], c1[2] };
        return new int[] { tone(c1, 0.78f, 0), tone(c2, 0.62f, 0), tone(c1, 0.5f, -28), tone(c1, 0.38f, 0) };
    }

    private static float[] avg(double[] s, double w) {
        return Ui.hsv(Color.rgb((int) (s[0] / w), (int) (s[1] / w), (int) (s[2] / w)));
    }

    private static int tone(float[] c, float v, float dh) {
        return Ui.fromHsv(c[0] + dh, Math.max(0.45f, Math.min(0.85f, c[1])), v);
    }
}
