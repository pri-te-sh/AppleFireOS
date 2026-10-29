package com.applefireos.home;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data for the Insights screen: how the TV is used (from Android's usage history), what is using
 * memory, and the speed-ups the app can offer (closing background apps, animation speed).
 * Needs permissions granted once with adb: GET_USAGE_STATS (app-op), PACKAGE_USAGE_STATS, DUMP,
 * WRITE_SECURE_SETTINGS.
 */
final class Insights {
    private static final String[] ANIMATION_KEYS = {
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale"};

    /** Never closed: voice, keyboard, connectivity and core system pieces. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "android", "system", "com.android.systemui", "com.android.phone", "com.android.bluetooth",
            "com.amazon.tv.ime", "com.amazon.vizzini", "com.amazon.alexamediaplayer.runtime.ftv",
            "com.amazon.alexa.externalmediaplayer.fireos", "com.amazon.webview.chromium",
            "com.amazon.device.messaging", "com.amazon.device.services", "com.amazon.tcomm",
            "com.amazon.connectivitycontroller", "com.amazon.tv.devicecontrol",
            "com.amazon.whisperlink.core.android", "com.amazon.dialservice"));

    private static final Pattern PROC = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid (\\d+)(?: / ([^)]*))?\\)");

    private Insights() {}

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Foreground sessions per app and screen-on spans for the last {@code days} days:
     * { access, from, to, apps: {pkg: label}, sessions: [[pkg, startSec, durationSec]], screen: [[startSec, durationSec]] }
     */
    static String usage(Context c, int days) {
        JSONObject out = new JSONObject();
        try {
            long to = System.currentTimeMillis(), from = to - days * 86_400_000L;
            out.put("access", hasUsageAccess(c));
            out.put("from", from / 1000);
            out.put("to", to / 1000);
            JSONArray sessions = new JSONArray(), screen = new JSONArray();
            Map<String, String> labels = new LinkedHashMap<>();
            if (hasUsageAccess(c)) {
                UsageEvents events = c.getSystemService(UsageStatsManager.class).queryEvents(from, to);
                UsageEvents.Event e = new UsageEvents.Event();
                String cur = null;
                long curStart = 0, screenOn = -1;
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    long t = e.getTimeStamp();
                    String pkg = e.getPackageName();
                    switch (e.getEventType()) {
                        case UsageEvents.Event.ACTIVITY_RESUMED:
                            if (pkg.equals(cur)) break;
                            if (cur != null) addSession(sessions, labels, cur, curStart, t);
                            cur = pkg;
                            curStart = t;
                            break;
                        case UsageEvents.Event.ACTIVITY_PAUSED:
                        case 23: // ACTIVITY_STOPPED
                            if (pkg.equals(cur)) {
                                addSession(sessions, labels, cur, curStart, t);
                                cur = null;
                            }
                            break;
                        case UsageEvents.Event.SCREEN_INTERACTIVE:
                            screenOn = t;
                            break;
                        case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                        case 26: // DEVICE_SHUTDOWN
                            if (cur != null) addSession(sessions, labels, cur, curStart, t);
                            cur = null;
                            if (screenOn > 0) screen.put(new JSONArray().put(screenOn / 1000).put((t - screenOn) / 1000));
                            screenOn = -1;
                            break;
                        default:
                            break;
                    }
                }
                if (cur != null) addSession(sessions, labels, cur, curStart, to);
                if (screenOn > 0) screen.put(new JSONArray().put(screenOn / 1000).put((to - screenOn) / 1000));
            }
            // Daily totals reach further back than the detailed events on Fire OS (which keeps
            // events only since boot): [[dayStartSec, pkg, foregroundSec]].
            JSONArray daily = new JSONArray();
            if (hasUsageAccess(c)) {
                for (android.app.usage.UsageStats st : c.getSystemService(UsageStatsManager.class)
                        .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)) {
                    long sec = st.getTotalTimeInForeground() / 1000;
                    if (sec < 30) continue;
                    daily.put(new JSONArray().put(st.getFirstTimeStamp() / 1000).put(st.getPackageName()).put(sec));
                    labels.put(st.getPackageName(), st.getPackageName());
                }
            }
            out.put("daily", daily);
            JSONObject names = new JSONObject();
            PackageManager pm = c.getPackageManager();
            for (String pkg : labels.keySet()) names.put(pkg, label(pm, pkg));
            out.put("apps", names);
            out.put("sessions", sessions);
            out.put("screen", screen);
        } catch (Exception ignored) {
            // Return whatever was gathered.
        }
        return out.toString();
    }

    private static void addSession(JSONArray sessions, Map<String, String> labels, String pkg, long start, long end) {
        long sec = (end - start) / 1000;
        if (sec < 3 || sec > 12 * 3600) return;
        labels.put(pkg, pkg);
        sessions.put(new JSONArray().put(pkg).put(start / 1000).put(sec));
    }

    /**
     * Memory, storage and speed settings:
     * { total, avail, threshold, low, procs: [{pkg, label, kb, closable}], storage: {total, free},
     *   animation, canSetAnimation, canListProcs }
     */
    static String memory(Context c) {
        JSONObject out = new JSONObject();
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            out.put("total", mi.totalMem);
            out.put("avail", mi.availMem);
            out.put("threshold", mi.threshold);
            out.put("low", mi.lowMemory);

            Map<String, Long> byPkg = processes(c);
            out.put("canListProcs", !byPkg.isEmpty());
            PackageManager pm = c.getPackageManager();
            JSONArray procs = new JSONArray();
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(byPkg.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> en : sorted.subList(0, Math.min(14, sorted.size()))) {
                String pkg = en.getKey();
                procs.put(new JSONObject().put("pkg", pkg).put("label", label(pm, pkg))
                        .put("kb", en.getValue()).put("closable", closable(c, pkg)));
            }
            out.put("procs", procs);

            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            out.put("storage", new JSONObject().put("total", fs.getTotalBytes()).put("free", fs.getAvailableBytes()));

            ContentResolver cr = c.getContentResolver();
            out.put("animation", Settings.Global.getFloat(cr, ANIMATION_KEYS[0], 1f));
            out.put("canSetAnimation", c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED);
        } catch (Exception ignored) {
            // Partial data is fine.
        }
        return out.toString();
    }

    private static final Pattern RECORD = Pattern.compile("ProcessRecord\\{\\S+ \\d+:([^/}]+)/(\\w+)\\}");
    private static final Pattern LAST_PSS = Pattern.compile("lastPss=([\\d.]+)(KB|MB|GB)");

    /**
     * Memory (PSS, KB) per package, from the activity manager's process list. Apps can't reach the
     * meminfo service on Fire OS, but `dumpsys activity processes` works with DUMP and
     * PACKAGE_USAGE_STATS granted. A WebView renderer counts toward the app that owns it.
     */
    private static Map<String, Long> processes(Context c) {
        Map<String, Long> byPkg = new HashMap<>();
        String me = "u0a" + (Process.myUid() % 100000 - 10000);
        try {
            java.lang.Process p = new ProcessBuilder("dumpsys", "activity", "processes").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String pkg = null;
            for (String line; (line = r.readLine()) != null; ) {
                // Only each app's own record ("*APP* UID … ProcessRecord{…}" then "lastPss=…"):
                // the same records are listed again in other sections, next to other processes' numbers.
                String t = line.trim();
                Matcher rec = RECORD.matcher(line);
                if (t.startsWith("*APP*") && rec.find()) {
                    String name = rec.group(1), uid = rec.group(2);
                    pkg = name.contains("sandboxed_process") && uid.startsWith(me + "i") ? c.getPackageName() : name.split(":")[0];
                    continue;
                }
                if (rec.find()) {
                    pkg = null;
                    continue;
                }
                Matcher m = LAST_PSS.matcher(line);
                if (pkg != null && t.startsWith("lastPss=") && m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    long kb = Math.round("GB".equals(m.group(2)) ? v * 1048576 : "MB".equals(m.group(2)) ? v * 1024 : v);
                    Long prev = byPkg.get(pkg);
                    byPkg.put(pkg, prev == null ? kb : prev + kb);
                    pkg = null;
                }
            }
            p.destroy();
        } catch (Exception ignored) {
            // No permission: no per-app list.
        }
        return byPkg;
    }

    private static boolean closable(Context c, String pkg) {
        if (KEEP.contains(pkg) || pkg.equals(c.getPackageName()) || !pkg.contains(".")) return false;
        // VPNs carry the TV's internet connection (often the only route out): never close them.
        if (pkg.toLowerCase().contains("vpn")) return false;
        if (pkg.startsWith("com.android.") || pkg.startsWith("android.")) return false;
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false; // a native or system process, not an app
        }
    }

    /**
     * Closes background apps (never the one on screen, voice, keyboard or system pieces) and
     * reports the memory it gave back: { before, after, freed, closed: [label] }.
     */
    static String freeUp(Context c) {
        JSONObject out = new JSONObject();
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long before = mi.availMem;
            JSONArray closed = new JSONArray();
            PackageManager pm = c.getPackageManager();
            for (String pkg : processes(c).keySet()) {
                if (!closable(c, pkg)) continue;
                am.killBackgroundProcesses(pkg);
                closed.put(label(pm, pkg));
            }
            Thread.sleep(1500); // let the system reclaim the memory
            am.getMemoryInfo(mi);
            out.put("before", before).put("after", mi.availMem)
                    .put("freed", Math.max(0, mi.availMem - before)).put("closed", closed);
        } catch (Exception ignored) {
            // Report what we have.
        }
        return out.toString();
    }

    static void close(Context c, String pkg) {
        if (closable(c, pkg)) c.getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
    }

    /** 1 = normal, 0.5 = fast, 0 = off. False if the permission hasn't been granted. */
    static boolean setAnimation(Context c, float scale) {
        try {
            for (String key : ANIMATION_KEYS) Settings.Global.putFloat(c.getContentResolver(), key, scale);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
