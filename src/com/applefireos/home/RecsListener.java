package com.applefireos.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects the recommendation cards apps post on Fire TV ("Continue Watching", picks for your
 * profile) so the Top Shelf can show them and open the exact title. Fire OS keeps these as
 * hidden notifications; reading them needs notification access for this app.
 */
public class RecsListener extends NotificationListenerService {
    private static final Map<String, StatusBarNotification> CARDS = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable onChange;
    /** The connected listener, so the page can always get a fresh read of the cards. */
    private static RecsListener live;
    private static final Runnable NOTIFY = () -> {
        if (onChange != null) onChange.run();
    };

    /** Called on the main thread (debounced) whenever the set of cards changes. */
    static void setOnChange(Runnable callback) {
        onChange = callback;
    }

    @Override
    public void onListenerConnected() {
        live = this;
        reload();
        changed();
        try {
            NowPlaying.start(this);
        } catch (RuntimeException e) {
            // Now Playing is optional; never let it stop the cards from loading.
        }
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
    }

    /** Re-reads every card Android currently holds (they can change while we're not bound). */
    private static void reload() {
        RecsListener l = live;
        if (l == null) return;
        try {
            StatusBarNotification[] active = l.getActiveNotifications();
            CARDS.clear();
            if (active != null) {
                for (StatusBarNotification sbn : active) consider(sbn);
            }
        } catch (RuntimeException e) {
            // Not bound right now: keep what we had.
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (consider(sbn)) changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (CARDS.remove(key(sbn)) != null) changed();
    }

    private static boolean consider(StatusBarNotification sbn) {
        if (!Notification.CATEGORY_RECOMMENDATION.equals(sbn.getNotification().category)) return false;
        CARDS.put(key(sbn), sbn);
        return true;
    }

    private static void changed() {
        // Apps post their cards in bursts; tell the page once things settle.
        MAIN.removeCallbacks(NOTIFY);
        MAIN.postDelayed(NOTIFY, 1000);
    }

    private static String key(StatusBarNotification sbn) {
        return sbn.getPackageName() + "|" + sbn.getId();
    }

    /** The cards, as JSON: { key, pkg, group, title, text, rating, progress, progressMax, image }. */
    static String json() {
        reload();
        JSONArray out = new JSONArray();
        for (Map.Entry<String, StatusBarNotification> e : CARDS.entrySet()) {
            try {
                Notification n = e.getValue().getNotification();
                Bundle x = n.extras;
                String title = text(x, Notification.EXTRA_TITLE);
                if (title.isEmpty()) continue;
                JSONObject card = new JSONObject();
                card.put("key", e.getKey());
                card.put("pkg", e.getValue().getPackageName());
                card.put("group", n.getGroup() == null ? "" : n.getGroup());
                card.put("title", title);
                card.put("text", text(x, Notification.EXTRA_TEXT));
                card.put("contentId", text(x, "com.amazon.extra.CONTENT_ID"));
                card.put("rank", number(x, "com.amazon.extra.RANK"));
                card.put("rating", text(x, "com.amazon.extra.MATURITY_RATING"));
                card.put("progress", number(x, Notification.EXTRA_PROGRESS));
                card.put("progressMax", number(x, Notification.EXTRA_PROGRESS_MAX));
                card.put("image", text(x, "android.backgroundImageUri").isEmpty()
                        ? "" : "rec-img/" + URLEncoder.encode(e.getKey(), "UTF-8"));
                out.put(card);
            } catch (Exception ignored) {
                // Skip a card we can't read.
            }
        }
        return out.toString();
    }

    /** Where a card's artwork lives (usually the posting app's content provider). */
    static Uri imageUri(String key) {
        StatusBarNotification sbn = CARDS.get(key);
        if (sbn == null) return null;
        String uri = text(sbn.getNotification().extras, "android.backgroundImageUri");
        return uri.isEmpty() ? null : Uri.parse(uri);
    }

    /** Fires the card's own action, which opens that title in its app. */
    static boolean open(String key) {
        StatusBarNotification sbn = CARDS.get(key);
        PendingIntent action = sbn == null ? null : sbn.getNotification().contentIntent;
        if (action == null) return false;
        try {
            action.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    /**
     * Title and text of an app's ongoing notification (a VPN app's "Connected · India - Delhi"),
     * or null when there is none or we aren't bound.
     */
    static String[] ongoing(String pkg) {
        RecsListener l = live;
        if (l == null || pkg == null) return null;
        try {
            StatusBarNotification[] active = l.getActiveNotifications();
            if (active == null) return null;
            for (StatusBarNotification sbn : active) {
                if (!pkg.equals(sbn.getPackageName()) || !sbn.isOngoing()) continue;
                Bundle x = sbn.getNotification().extras;
                return new String[] { text(x, Notification.EXTRA_TITLE), text(x, Notification.EXTRA_TEXT) };
            }
        } catch (RuntimeException e) {
            // Not bound right now.
        }
        return null;
    }

    private static String text(Bundle x, String name) {
        Object v = x == null ? null : x.get(name);
        return v == null ? "" : String.valueOf(v);
    }

    private static long number(Bundle x, String name) {
        try {
            return Long.parseLong(text(x, name));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
