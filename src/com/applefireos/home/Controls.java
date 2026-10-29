package com.applefireos.home;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

/** What the Control Center tiles do on the TV: sleep, the sleep timer and the Bluetooth shortcut. */
final class Controls {
    private static final String PREFS = "controls";
    private static final String TIMER_END = "timerEnd";
    private static final String SETTINGS_PKG = "com.amazon.tv.settings.v2";

    private Controls() {}

    static String state(Context c) {
        try {
            JSONObject s = new JSONObject();
            long end = prefs(c).getLong(TIMER_END, 0);
            s.put("timerEnd", end > System.currentTimeMillis() ? end : 0);
            return s.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Puts the TV to sleep. Uses the Home-button service's lock action when it's enabled,
     * otherwise asks Fire OS's settings app, which owns its own sleep command.
     */
    static void sleep(Context c) {
        AccessibilityService service = HomeRedirectService.instance;
        if (Build.VERSION.SDK_INT >= 28 && service != null
                && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            return;
        }
        c.sendBroadcast(new Intent("com.amazon.tv.settings.action.SLEEP").setPackage(SETTINGS_PKG));
    }

    static void setSleepTimer(Context c, int minutes) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimerReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(fire);
        long end = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        prefs(c).edit().putLong(TIMER_END, end).apply();
        if (end > 0) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, fire);
    }

    static Intent screen(String name) {
        if ("bluetooth".equals(name)) {
            return new Intent().setComponent(new ComponentName(SETTINGS_PKG,
                    SETTINGS_PKG + ".tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"));
        }
        return new Intent(Settings.ACTION_SETTINGS);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Fires when the sleep timer runs out. */
    public static class SleepTimerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            prefs(c).edit().putLong(TIMER_END, 0).apply();
            sleep(c);
        }
    }
}
