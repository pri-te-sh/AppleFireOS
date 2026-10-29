package com.applefireos.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Insights' numbers about watching: the launcher's own usage log and the week drawn from it.
 * Fire OS wipes its usage history at every restart, so the launcher keeps a 30-day log in Prefs
 * ("usageLog": [[pkg, startSec, durationSec]]). Each report from Android
 * is merged in: same app + start time = same session, and the longer duration wins, since a
 * session still running is reported cut short.
 */
final class ScreenTime {
    static final String[] DAY = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    private static final int LOG_DAYS = 30;
    /** Not counted as watching: home screens and the screensaver. */
    private static final Set<String> NOT_WATCHING = new HashSet<>(Arrays.asList(
            "com.applefireos.home", "com.amazon.tv.launcher", "com.amazon.ftv.screensaver"));
    private static final Map<String, String> FRIENDLY = new HashMap<>();

    static {
        String[] pairs = {
                "system", "Android System", "com.applefireos.home", "AppleFireOS (this app)", "com.amazon.tv.launcher", "Amazon Home",
                "com.amazon.webview.chromium", "Web Engine", "com.android.systemui", "System UI", "com.amazon.vizzini", "Alexa",
                "com.amazon.tcomm", "Amazon Services", "com.amazon.imp", "Amazon Device Services",
                "fireos.hardware.audio.service", "Audio", "com.android.providers.media.module", "Media Storage",
                "com.amazon.firebat", "Prime Video (system)", "com.amazon.venezia", "Appstore", "com.amazon.tv.ime", "Keyboard",
                "com.amazon.alexamediaplayer.runtime.ftv", "Alexa Media", "com.amazon.device.messaging", "Amazon Messaging" };
        for (int i = 0; i < pairs.length; i += 2) FRIENDLY.put(pairs[i], pairs[i + 1]);
    }

    static final class Session {
        final String pkg;
        final long start, sec;

        Session(String pkg, long start, long sec) {
            this.pkg = pkg;
            this.start = start;
            this.sec = sec;
        }
    }

    static final class Day {
        final String label;
        final boolean weekend;
        final Map<String, Long> byApp = new HashMap<>();
        long total;

        Day(String label, boolean weekend) {
            this.label = label;
            this.weekend = weekend;
        }
    }

    static final class AppTime {
        final String pkg, label;
        final long sec;

        AppTime(String pkg, String label, long sec) {
            this.pkg = pkg;
            this.label = label;
            this.sec = sec;
        }
    }

    /** The last seven days, oldest first; the last is today. */
    final Day[] days = new Day[7];
    /** Apps watched for at least a minute this week, most first. */
    final List<AppTime> apps = new ArrayList<>();
    /** Average minutes watched in each hour of the day. */
    final double[] perDayHours = new double[24];
    final double avg, weekdayAvg, weekendAvg;
    /** Last week's daily average; null until there's a full week before this one. */
    final Double prevAvg;
    final long weekTotal, today, logStart;
    final int peak, late, sessions;
    final Session longest;
    private final Map<String, Apps.App> installed;

    // ---- the log ------------------------------------------------------------------------

    /**
     * Reads the last {@code days} days from Android into the log and returns the whole log, or
     * null without usage access. Blocking: run on Ui.IO.
     */
    static List<Session> record(Context c, int days) {
        Prefs.init(c);
        try {
            JSONObject u = new JSONObject(Insights.usage(c, days));
            if (!u.optBoolean("access")) return null;
            return merge(u.optJSONArray("sessions"));
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized List<Session> merge(JSONArray fresh) {
        Map<String, Session> byKey = new LinkedHashMap<>();
        JSONArray log = Prefs.array("usageLog");
        for (JSONArray list : new JSONArray[] { log, fresh }) {
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONArray s = list.optJSONArray(i);
                if (s == null) continue;
                Session n = new Session(s.optString(0), s.optLong(1), s.optLong(2));
                String k = n.pkg + "@" + n.start;
                Session prev = byKey.get(k);
                if (prev == null || n.sec > prev.sec) byKey.put(k, n);
            }
        }
        long cutoff = System.currentTimeMillis() / 1000 - LOG_DAYS * 86400L;
        List<Session> out = new ArrayList<>();
        for (Session s : byKey.values()) if (s.start >= cutoff) out.add(s);
        out.sort((a, b) -> Long.compare(a.start, b.start));
        JSONArray save = new JSONArray();
        for (Session s : out) save.put(new JSONArray().put(s.pkg).put(s.start).put(s.sec));
        Prefs.set("usageLog", save);
        return out;
    }

    // ---- the week -----------------------------------------------------------------------

    ScreenTime(List<Session> log, Map<String, Apps.App> installed) {
        this.installed = installed;
        logStart = log.isEmpty() ? 0 : log.get(0).start;
        double now = System.currentTimeMillis() / 1000.0;
        List<Session> watched = new ArrayList<>();
        for (Session s : log) if (!NOT_WATCHING.contains(s.pkg) && installed.containsKey(s.pkg)) watched.add(s);
        // Average over the days actually recorded (history may be younger than a week).
        double first = watched.isEmpty() ? now : watched.get(0).start;
        int span = (int) Math.min(7, Math.max(1, Math.ceil((now - first) / 86400)));

        Calendar cal = Calendar.getInstance();
        for (int d = 6; d >= 0; d--) {
            long from = dayStart(d), to = from + 86400;
            cal.setTimeInMillis(from * 1000);
            int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
            Day day = new Day(d == 0 ? "Today" : DAY[dow], dow % 6 == 0);
            for (Session s : watched) {
                if (s.start < from || s.start >= to) continue;
                day.byApp.merge(s.pkg, s.sec, Long::sum);
                day.total += s.sec;
            }
            days[6 - d] = day;
        }

        long weekFrom = dayStart(6), prevFrom = dayStart(13);
        List<Session> week = new ArrayList<>();
        long weekSum = 0, prevSum = 0;
        boolean anyPrev = false;
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Session s : watched) {
            if (s.start >= weekFrom) {
                week.add(s);
                weekSum += s.sec;
                totals.merge(s.pkg, s.sec, Long::sum);
            } else if (s.start >= prevFrom) {
                anyPrev = true;
                prevSum += s.sec;
            }
        }
        for (Map.Entry<String, Long> e : totals.entrySet()) {
            if (e.getValue() >= 60) apps.add(new AppTime(e.getKey(), name(e.getKey()), e.getValue()));
        }
        apps.sort((a, b) -> Long.compare(b.sec, a.sec));

        double[] hours = new double[24];
        for (Session s : week) {
            // Spread each session across the hours it covers.
            for (long t = s.start; t < s.start + s.sec; t += 300) {
                hours[hourOf(cal, t)] += Math.min(300, s.start + s.sec - t) / 60.0;
            }
        }
        for (int h = 0; h < 24; h++) perDayHours[h] = hours[h] / span;
        int best = 0;
        for (int h = 0; h < 24; h++) {
            if (perDayHours[h] + perDayHours[(h + 1) % 24] > perDayHours[best] + perDayHours[(best + 1) % 24]) best = h;
        }
        peak = best;

        double wkSum = 0, weSum = 0;
        int wkDays = 0, weDays = 0;
        for (int i = 7 - span; i < 7; i++) {
            if (days[i].weekend) {
                weSum += days[i].total;
                weDays++;
            } else {
                wkSum += days[i].total;
                wkDays++;
            }
        }
        weekdayAvg = wkDays > 0 ? wkSum / wkDays : 0;
        weekendAvg = weDays > 0 ? weSum / weDays : 0;

        Session top = null;
        Set<Long> lateDays = new HashSet<>();
        for (Session s : week) {
            if (top == null || s.sec > top.sec) top = s;
            if (hourOf(cal, s.start) < 4 || hourOf(cal, s.start + s.sec) < 4) {
                cal.setTimeInMillis(s.start * 1000);
                lateDays.add(cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR));
            }
        }
        longest = top;
        late = lateDays.size();

        avg = weekSum / (double) span;
        weekTotal = weekSum;
        prevAvg = anyPrev && span == 7 ? prevSum / 7.0 : null;
        today = days[6].total;
        sessions = week.size();
    }

    /** Local midnight {@code d} days ago, in seconds. */
    private static long dayStart(int d) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, -d);
        return c.getTimeInMillis() / 1000;
    }

    private static int hourOf(Calendar cal, long sec) {
        cal.setTimeInMillis(sec * 1000);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    /** The three apps the charts colour; everything else is "Other". */
    List<String> top() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, apps.size()); i++) out.add(apps.get(i).pkg);
        return out;
    }

    String name(String pkg) {
        return nameOf(installed, pkg, null);
    }

    // ---- words --------------------------------------------------------------------------

    static String nameOf(Map<String, Apps.App> installed, String pkg, String label) {
        Apps.App a = installed == null ? null : installed.get(pkg);
        if (a != null && a.label != null) return a.label;
        String friendly = FRIENDLY.get(pkg);
        if (friendly != null) return friendly;
        return label != null && !label.isEmpty() && !label.equals(pkg) ? label : pkg;
    }

    static String duration(double sec) {
        long h = (long) Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60);
        return h > 0 ? (m > 0 ? h + "h " + m + "m" : h + "h") : m + "m";
    }

    static String hourLabel(int h) {
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }
}
