package com.applefireos.home;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Every full-screen page (Settings, Insights, Find) is one of these: the same near-black backdrop
 * (a faint, colourless trace of the artwork behind, so nothing tints the page), the title at the
 * top left, optional tabs in the middle and a caption on the right, the same fade in and out, and
 * the same Back behaviour. Subclasses fill `content` and handle keys.
 */
abstract class PageScaffold implements NativeHome.Layer {
    final NativeHome home;
    final FrameLayout root, content;
    final TextView title, caption;
    TabBar tabs;

    PageScaffold(NativeHome home, String name) {
        this.home = home;
        root = new FrameLayout(home);
        root.setBackgroundColor(Theme.PAGE);
        root.setClickable(true);
        if (home.frost() != null) {
            ImageView trace = new ImageView(home);
            trace.setScaleType(ImageView.ScaleType.FIT_XY);
            trace.setImageBitmap(home.frost());
            ColorMatrix grey = new ColorMatrix();
            grey.setSaturation(0);
            trace.setColorFilter(new ColorMatrixColorFilter(grey));
            trace.setAlpha(0.35f);
            root.addView(trace, Ui.fill());
            View dim = new View(home);
            dim.setBackgroundColor(Theme.PAGE_DIM);
            root.addView(dim, Ui.fill());
        }
        title = Kit.text(home, Theme.Type.PAGE_TITLE, Theme.LABEL, name);
        root.addView(title, Ui.at(Theme.PAGE_X, Theme.PAGE_TOP + 4, FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        caption = Kit.text(home, Theme.Type.SUBHEAD, Theme.LABEL_2);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = Theme.PAGE_TOP + 18;
        cp.rightMargin = Theme.PAGE_X;
        root.addView(caption, cp);
        content = new FrameLayout(home);
        content.setClipChildren(false);
        root.addView(content, Ui.at(0, Theme.PAGE_CONTENT_TOP, Ui.STAGE_W, Ui.STAGE_H - Theme.PAGE_CONTENT_TOP));
    }

    /** Segmented tabs centred at the top. */
    TabBar tabs(String... names) {
        tabs = new TabBar(home, names);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(TabBar.width(names.length), TabBar.H,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Theme.PAGE_TOP;
        root.addView(tabs, lp);
        return tabs;
    }

    void open() {
        home.overlay.addView(root, Ui.fill());
        home.layers.add(this);
        Sound.open();
        root.setAlpha(0);
        Motion.fade(root, 1, 250, null);
    }

    void close() {
        home.layers.remove(this);
        Motion.fade(root, 0, 250, () -> home.overlay.removeView(root));
    }

    /** Back: subclasses may step out of an inner level first; true if they did. */
    boolean backWithin() {
        return false;
    }

    @Override
    public void back() {
        if (!backWithin()) close();
    }

    /** Key handling for the page; ACTION_DOWN only. */
    abstract void onKey(int code, KeyEvent e);

    @Override
    public boolean key(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) onKey(e.getKeyCode(), e);
        return true;
    }
}
