package com.applefireos.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Artwork and details from TMDB for the Top Shelf: a large, rotating pool of what's popular on
 * each service, plus title matching so the apps' own recommendations get sharp art and logos.
 * Only titles and artwork are fetched; nothing about the viewer is sent. Calls block: run them
 * on Ui.IO.
 */
final class Tmdb {
    static final class Item {
        long id;
        boolean tv;
        String title = "", overview = "", year = "", backdrop, poster, source = "";
        double rating, popularity;
        /** null until looked up; "" when there is none. */
        String logo;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("tv", tv).put("title", title).put("overview", overview)
                    .put("year", year).put("rating", rating).put("backdrop", backdrop == null ? "" : backdrop)
                    .put("poster", poster == null ? "" : poster).put("popularity", popularity).put("source", source);
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.id = o.optLong("id");
            it.tv = o.optBoolean("tv");
            it.title = o.optString("title");
            it.overview = o.optString("overview");
            it.year = o.optString("year");
            it.rating = o.optDouble("rating", 0);
            it.backdrop = emptyToNull(o.optString("backdrop"));
            it.poster = emptyToNull(o.optString("poster"));
            it.popularity = o.optDouble("popularity", 0);
            it.source = o.optString("source");
            return it;
        }
    }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long POOL_TTL = 6 * 60 * 60 * 1000L;
    private static final int PAGES = 3;

    /** Launcher package → streaming service name as TMDB spells it. */
    static final Map<String, String> SERVICES = new HashMap<>();
    static {
        SERVICES.put("com.netflix.ninja", "Netflix");
        SERVICES.put("com.amazon.avod", "Amazon Prime Video");
        SERVICES.put("com.amazon.firetv.youtube", "YouTube");
        SERVICES.put("in.startv.hotstar", "Hotstar");
        SERVICES.put("com.zee5.amazon", "Zee5");
        SERVICES.put("com.disney.disneyplus", "Disney Plus");
        SERVICES.put("com.hulu.plus", "Hulu");
        SERVICES.put("com.apple.atve.amazon.appletv", "Apple TV Plus");
        SERVICES.put("com.hbo.hbonow", "Max");
    }

    static final Map<String, String> REGIONS = new LinkedHashMap<>();
    static {
        REGIONS.put("IN", "India");
        REGIONS.put("US", "United States");
        REGIONS.put("GB", "United Kingdom");
        REGIONS.put("CA", "Canada");
        REGIONS.put("AU", "Australia");
        REGIONS.put("AE", "UAE");
    }

    private static String key = "";
    private static File dir;
    private static volatile String region = "US";
    private static Map<String, Long> providers;
    private static final Map<String, List<Item>> POOLS = new HashMap<>();
    private static final Map<String, Item> MATCHES = new HashMap<>();
    private static final Set<String> MATCHED = new HashSet<>();
    private static List<Item> trending;

    private Tmdb() {}

    static void init(Context c) {
        dir = new File(c.getCacheDir(), "tmdb");
        dir.mkdirs();
        // A key entered in Settings (or sent by setup.sh) wins over one built into the app.
        key = Prefs.string("tmdbKey", "");
        if (!key.isEmpty()) return;
        try (InputStream in = c.getAssets().open("tmdb.key")) {
            key = new String(Images.readAll(in), "UTF-8").trim();
        } catch (Exception e) {
            key = "";
        }
    }

    /** True if TMDB accepts this key (an API key or a read access token). Blocks: call off the main thread. */
    static boolean works(String k) {
        try {
            get("/configuration", k);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Uses this key from now on ("" removes it) and forgets what the old one fetched. */
    static synchronized void setKey(String k) {
        key = k == null ? "" : k.trim();
        if (key.isEmpty()) Prefs.remove("tmdbKey");
        else Prefs.set("tmdbKey", key);
        providers = null;
        trending = null;
        synchronized (POOLS) {
            POOLS.clear();
        }
        synchronized (MATCHES) {
            MATCHES.clear();
            MATCHED.clear();
        }
    }

    static boolean enabled() {
        return !key.isEmpty();
    }

    static boolean has(String pkg) {
        return enabled() && SERVICES.containsKey(pkg);
    }

    static String region() {
        return region;
    }

    static synchronized void setRegion(String code) {
        if (code == null || code.equals(region)) return;
        region = code;
        providers = null;
        POOLS.clear();
    }

    private static JSONObject get(String path) throws Exception {
        return get(path, key);
    }

    private static JSONObject get(String path, String key) throws Exception {
        boolean bearer = key.length() > 40;
        String url = API + path;
        if (!bearer) url += (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("TMDB " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(Images.readAll(in), "UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    private static synchronized Long providerId(String name) throws Exception {
        if (providers == null) {
            Map<String, Long> ids = new HashMap<>();
            for (String kind : new String[] { "tv", "movie" }) {
                JSONArray r = get("/watch/providers/" + kind + "?watch_region=" + region).optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject p = r.getJSONObject(i);
                    String n = p.optString("provider_name").toLowerCase();
                    if (!ids.containsKey(n)) ids.put(n, p.optLong("provider_id"));
                }
            }
            providers = ids;
        }
        String want = name.toLowerCase();
        if (providers.containsKey(want)) return providers.get(want);
        for (Map.Entry<String, Long> e : providers.entrySet()) if (e.getKey().contains(want)) return e.getValue();
        return null;
    }

    private static Item toItem(JSONObject r, Boolean tv, String source) {
        boolean isTv = tv != null ? tv : "tv".equals(r.optString("media_type"));
        String date = r.optString(isTv ? "first_air_date" : "release_date", "");
        Item it = new Item();
        it.id = r.optLong("id");
        it.tv = isTv;
        it.title = r.optString(isTv ? "name" : "title");
        it.overview = r.optString("overview", "");
        it.year = date.length() >= 4 ? date.substring(0, 4) : "";
        it.rating = r.optDouble("vote_average", 0);
        it.backdrop = emptyToNull(r.optString("backdrop_path", ""));
        it.poster = emptyToNull(r.optString("poster_path", ""));
        it.popularity = r.optDouble("popularity", 0);
        it.source = source;
        return it;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static List<Item> pages(String path, Boolean tv, String source) {
        List<Item> out = new ArrayList<>();
        for (int p = 1; p <= PAGES; p++) {
            try {
                JSONArray r = get(path + (path.contains("?") ? "&" : "?") + "page=" + p).optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    if (o.optString("backdrop_path", "").isEmpty() || o.isNull("backdrop_path")) continue;
                    if ("person".equals(o.optString("media_type"))) continue;
                    out.add(toItem(o, tv, source));
                }
            } catch (Exception ignored) {
                // A missing page just means fewer titles.
            }
        }
        return out;
    }

    private static List<Item> fetchPool(String service) throws Exception {
        List<Item> list = new ArrayList<>();
        Long id = providerId(service);
        if (id != null) {
            String q = "?watch_region=" + region + "&with_watch_providers=" + id + "&sort_by=popularity.desc";
            list.addAll(pages("/discover/tv" + q, true, service));
            list.addAll(pages("/discover/movie" + q, false, service));
        }
        if (list.size() < 8) list = pages("/trending/all/week", null, "Trending");
        Set<Long> seen = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item it : list) if (seen.add(it.id)) out.add(it);
        return out;
    }

    /** A shuffled pool (up to ~120 titles) of what's popular on the app's service. */
    static List<Item> pool(String pkg) {
        if (!has(pkg)) return new ArrayList<>();
        String cacheKey = region + "-" + pkg;
        List<Item> list;
        synchronized (POOLS) {
            list = POOLS.get(cacheKey);
        }
        if (list == null) {
            list = readPool(cacheKey);
            if (list == null) {
                try {
                    list = fetchPool(SERVICES.get(pkg));
                    if (!list.isEmpty()) writePool(cacheKey, list);
                } catch (Exception e) {
                    return new ArrayList<>();
                }
            }
            synchronized (POOLS) {
                POOLS.put(cacheKey, list);
            }
        }
        return freshOrder(list);
    }

    private static List<Item> readPool(String cacheKey) {
        File f = new File(dir, "pool-" + cacheKey + ".json");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(Images.readAll(in), "UTF-8"));
            if (System.currentTimeMillis() - o.optLong("t") > POOL_TTL) return null;
            JSONArray a = o.getJSONArray("list");
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writePool(String cacheKey, List<Item> list) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : list) a.put(it.toJson());
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("list", a);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "pool-" + cacheKey + ".json"))) {
                out.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Fetched again next time.
        }
    }

    /** Shuffle, then titles shown in the last day go to the back. */
    private static List<Item> freshOrder(List<Item> list) {
        List<Item> a = new ArrayList<>(list);
        Collections.shuffle(a);
        JSONObject recent = Prefs.object("shelfSeen");
        long dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        List<Item> fresh = new ArrayList<>(), seen = new ArrayList<>();
        for (Item it : a) {
            long at = recent == null ? 0 : recent.optLong(String.valueOf(it.id), 0);
            (at > dayAgo ? seen : fresh).add(it);
        }
        fresh.addAll(seen);
        return fresh;
    }

    /** Remember a title was just shown, so the next shuffle saves it for later. */
    static synchronized void markSeen(long id) {
        JSONObject recent = Prefs.object("shelfSeen");
        if (recent == null) recent = new JSONObject();
        try {
            recent.put(String.valueOf(id), System.currentTimeMillis());
            if (recent.length() > 300) {
                List<String> keys = new ArrayList<>();
                for (java.util.Iterator<String> it = recent.keys(); it.hasNext(); ) keys.add(it.next());
                final JSONObject r = recent;
                Collections.sort(keys, (x, y) -> Long.compare(r.optLong(y), r.optLong(x)));
                JSONObject trimmed = new JSONObject();
                for (int i = 0; i < 300; i++) trimmed.put(keys.get(i), recent.optLong(keys.get(i)));
                recent = trimmed;
            }
            Prefs.set("shelfSeen", recent);
        } catch (Exception ignored) {
            // Not remembered.
        }
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Finds the TMDB entry for an app's own recommendation, by exact title. */
    static Item match(String title) {
        String want = norm(title);
        if (!enabled() || want.isEmpty()) return null;
        synchronized (MATCHES) {
            if (MATCHED.contains(want)) return MATCHES.get(want);
        }
        Item hit = null;
        try {
            JSONArray r = get("/search/multi?include_adult=false&query=" + URLEncoder.encode(title, "UTF-8")).optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                String type = o.optString("media_type");
                if (!"tv".equals(type) && !"movie".equals(type)) continue;
                if (norm(o.optString("name", o.optString("title"))).equals(want)) {
                    hit = toItem(o, null, "");
                    break;
                }
            }
        } catch (Exception e) {
            return null; // try again next time
        }
        synchronized (MATCHES) {
            MATCHED.add(want);
            MATCHES.put(want, hit);
        }
        return hit;
    }

    static String logo(Item item) {
        if (item.logo != null) return item.logo.isEmpty() ? null : item.logo;
        item.logo = "";
        try {
            JSONArray logos = get("/" + (item.tv ? "tv" : "movie") + "/" + item.id + "/images?include_image_language=en,hi,null").optJSONArray("logos");
            for (int i = 0; logos != null && i < logos.length(); i++) {
                String p = logos.getJSONObject(i).optString("file_path", "");
                if (p.endsWith(".png")) {
                    item.logo = IMG + "w500" + p;
                    break;
                }
            }
        } catch (Exception ignored) {
            // Fall back to the plain title.
        }
        return item.logo.isEmpty() ? null : item.logo;
    }

    static String backdrop(Item item) {
        return item.backdrop == null ? null : IMG + "w1280" + item.backdrop;
    }

    static String poster(Item item) {
        return item.poster == null ? null : IMG + "w342" + item.poster;
    }

    /** Movies and shows matching a search, the most popular (likeliest meant) first. */
    static List<Item> search(String text) throws Exception {
        JSONArray r = get("/search/multi?include_adult=false&query=" + URLEncoder.encode(text, "UTF-8")).optJSONArray("results");
        List<Item> out = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String type = o.optString("media_type");
            if (("tv".equals(type) || "movie".equals(type)) && !o.optString("poster_path", "").isEmpty() && !o.isNull("poster_path")) {
                out.add(toItem(o, null, ""));
            }
        }
        Collections.sort(out, (a, b) -> Double.compare(b.popularity, a.popularity));
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    /** This week's most popular titles, for Find before you type. */
    static synchronized List<Item> trending() {
        if (trending == null) {
            try {
                JSONArray r = get("/trending/all/week").optJSONArray("results");
                List<Item> out = new ArrayList<>();
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    if ("person".equals(o.optString("media_type")) || o.optString("poster_path", "").isEmpty() || o.isNull("poster_path")) continue;
                    out.add(toItem(o, null, ""));
                }
                trending = out;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        return trending;
    }

    /**
     * Where a title streams in your region, as provider names (subscription, free and ad-supported;
     * rentals left out), plus which of the given installed packages can play it.
     */
    static List<String>[] watchOn(Item item, List<String> installed) {
        List<String> names = new ArrayList<>(), apps = new ArrayList<>();
        try {
            JSONObject here = get("/" + (item.tv ? "tv" : "movie") + "/" + item.id + "/watch/providers")
                    .optJSONObject("results");
            here = here == null ? null : here.optJSONObject(region);
            if (here != null) {
                for (String k : new String[] { "flatrate", "free", "ads" }) {
                    JSONArray a = here.optJSONArray(k);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        String n = a.getJSONObject(i).optString("provider_name");
                        if (!names.contains(n)) names.add(n);
                    }
                }
            }
            for (String pkg : installed) {
                String svc = SERVICES.containsKey(pkg) ? SERVICES.get(pkg).toLowerCase() : "";
                if (svc.isEmpty()) continue;
                for (String n : names) {
                    String l = n.toLowerCase();
                    if (l.contains(svc) || svc.contains(l)) {
                        apps.add(pkg);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // Nothing found.
        }
        @SuppressWarnings("unchecked")
        List<String>[] out = new List[] { names, apps };
        return out;
    }
}
