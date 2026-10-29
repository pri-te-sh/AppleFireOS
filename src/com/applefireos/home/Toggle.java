package com.applefireos.home;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** The on/off switch used by every setting: green when on, the knob springs across. */
final class Toggle extends FrameLayout {
    static final int W = 72, H = 40;
    private static final int KNOB = 32;
    private final View knob;
    private boolean on;

    Toggle(Context c) {
        super(c);
        knob = new View(c);
        knob.setBackground(Kit.oval(Theme.LABEL));
        knob.setElevation(3);
        LayoutParams lp = new LayoutParams(KNOB, KNOB);
        lp.leftMargin = (H - KNOB) / 2;
        lp.topMargin = (H - KNOB) / 2;
        addView(knob, lp);
        set(false, false);
    }

    boolean isOn() {
        return on;
    }

    void set(boolean value, boolean animate) {
        on = value;
        setBackground(Kit.shape(on ? Theme.GOOD : Theme.FILL, H / 2f));
        float x = on ? W - H : 0;
        if (animate) knob.animate().translationX(x).setDuration(Motion.LIFT).setInterpolator(Motion.SPRING).start();
        else knob.setTranslationX(x);
    }
}
