package com.applefireos.home;

import android.view.View;
import android.widget.FrameLayout;

/**
 * A frosted glass panel (menus, Control Center tiles, the sidebar…): the blurred picture behind
 * it, a dark tint for legible text, a sheen and a lit edge. (No drop shadow: on this TV soft
 * shadows under several panels cost more GPU time than the rest of the frame.)
 */
class Glass extends FrameLayout {
    final DockView bg;
    private final NativeHome home;

    Glass(NativeHome home, float radius, boolean thick) {
        super(home);
        this.home = home;
        bg = new DockView(home, radius);
        bg.setBase(thick ? 0x9E16161E : 0x6E1C1C24);
        bg.setFrostAlpha(1);
        addView(bg, Ui.fill());
        Ui.rounded(this, radius);
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
    }

    /**
     * The glass fills whatever size the content gives the panel. (FrameLayout only re-measures a
     * lone match-parent child when there are several, so size it here.)
     */
    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        bg.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    /** Lines the frost up with what's behind the panel, wherever it is on the stage. */
    void place() {
        bg.setBackdrop(home.frost());
        float x = 0, y = 0;
        View v = this;
        while (v != null && v != home.stage) {
            x += v.getX();
            y += v.getY();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        bg.place(x, y);
    }

}
