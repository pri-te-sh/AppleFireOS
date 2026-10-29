package com.applefireos.home;

import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * Every floating panel (menus, the sidebar, profiles, folders, typing) is one of these: glass,
 * optionally over a dimmed screen, growing out of where it was opened from and shrinking back
 * there when it closes, in the shared motion. Subclasses fill `panel` and handle keys.
 */
abstract class PanelScaffold implements NativeHome.Layer {
    final NativeHome home;
    final FrameLayout host;
    final Glass panel;
    private final boolean dim;
    private float from = 0.9f;

    PanelScaffold(NativeHome home, float radius, boolean thick, boolean dim) {
        this.home = home;
        this.dim = dim;
        host = new FrameLayout(home);
        host.setClipChildren(false);
        host.setOnClickListener(v -> back());
        panel = Kit.glass(home, radius, thick);
        panel.setClipChildren(false);
        panel.setClickable(true);
    }

    /** Shows the panel at lp, growing from (fx, fy) — fractions of its size — from `fromScale`. */
    void show(FrameLayout.LayoutParams lp, float fx, float fy, float fromScale) {
        from = fromScale;
        host.addView(panel, lp);
        home.overlay.addView(host, Ui.fill());
        home.layers.add(this);
        Sound.open();
        if (dim) Motion.dim(host, Theme.SCRIM, true, null);
        Motion.open(panel, fx, fy, fromScale);
        onShown();
    }

    /** Called once the panel is on screen (subclasses paint their first focus here). */
    void onShown() {}

    void close() {
        home.layers.remove(this);
        onClosing();
        Motion.close(panel, from, dim ? null : () -> home.overlay.removeView(host));
        if (dim) Motion.dim(host, Theme.SCRIM, false, () -> home.overlay.removeView(host));
    }

    /** Called as the panel starts to close. */
    void onClosing() {}

    @Override
    public void back() {
        close();
    }

    abstract void onKey(int code, KeyEvent e);

    @Override
    public boolean key(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) onKey(e.getKeyCode(), e);
        return true;
    }
}
