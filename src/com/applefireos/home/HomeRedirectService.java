package com.applefireos.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS always routes the Home button to Amazon's launcher. When that home screen
 * comes to the front, this service immediately brings our launcher back instead.
 */
public class HomeRedirectService extends AccessibilityService {
    /** Set while the service is enabled; Control Center uses it to put the TV to sleep. */
    static HomeRedirectService instance;
    private long lastRedirect;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!"com.amazon.tv.launcher".contentEquals(String.valueOf(event.getPackageName()))) return;
        CharSequence cls = event.getClassName();
        if (cls == null || !cls.toString().contains("HomeActivity")) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 400) return;
        lastRedirect = now;

        startActivity(new Intent(this, NativeHome.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    public void onInterrupt() {
    }
}
