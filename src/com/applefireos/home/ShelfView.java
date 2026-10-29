package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Top Shelf: full-screen artwork for the focused app (its own "Continue Watching" and picks,
 * topped up with what's popular on its service from TMDB), crossfading every few seconds with a
 * slow Ken Burns drift, under the title, details and a pager.
 */
final class ShelfView extends FrameLayout {
    interface Host {
        /** A new picture is fully on screen (null: back to the aurora). */
        void onArtwork(Bitmap slide);
        boolean resting();
    }

    static final class Item {
        String title = "", overview = "", image, source = "", rating = "", recKey, pkg;
        boolean resume, card;
        float progress;
        Tmdb.Item tmdb;
        long id;
        Details ready;
        boolean loading;
        final List<Runnable> waiting = new ArrayList<>();
    }

    static final class Details {
        String image;
        Bitmap logo;
        String meta;
        Tmdb.Item tmdb;
    }

    private static final long SLIDE_MS = 9000;
    private static final int SW = 1280, SH = 720;

    private final Host host;
    final AuroraView aurora;
    private final SlidesView art;
    private final LinearLayout info;
    private final ImageView logo;
    private final TextView title, meta, overview, pager;
    private final Row play;
    private final View progress, progressFill;
    private final LinearLayout actions, dots;
    private final View[] chevrons = new View[2];
    /** Info opacity requested by others (the sidebar dims it); deep rows hide it regardless. */
    private float infoAlpha = 1;
    private boolean controls = true;

    private Apps.App app;
    private String pkg;
    private List<Item> list;
    private int index, token;
    private boolean running = true, focused, deep, artOn;
    private final Map<String, Object[]> lists = new HashMap<>();
    private Bitmap currentSlide;
    private final Runnable advance = () -> {
        if (list == null) return;
        index = (index + 1) % list.size();
        present(index, token);
    };

    ShelfView(Context c, Host host) {
        super(c);
        this.host = host;
        aurora = new AuroraView(c);
        addView(aurora, Ui.fill());

        art = new SlidesView(c);
        addView(art, Ui.fill());

        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        addView(info, Ui.at(90, 136, 860, 600));

        logo = new ImageView(c);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(620);
        logo.setMaxHeight(210);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(GONE);
        info.addView(logo, new LinearLayout.LayoutParams(-2, -2));

        title = Kit.overArt(Kit.text(c, Theme.Type.DISPLAY, Theme.LABEL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.04f);

        info.addView(title, new LinearLayout.LayoutParams(-1, -2));

        meta = Kit.overArt(Kit.line(Kit.text(c, Theme.Type.SECTION, Theme.LABEL_2)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = 22;
        info.addView(meta, mp);

        overview = Kit.overArt(Kit.paragraph(c, Theme.Type.BODY, Theme.LABEL_2, ""));
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(760, -2);
        op.topMargin = 12;
        info.addView(overview, op);

        FrameLayout bar = new FrameLayout(c);
        bar.setBackground(Kit.shape(Theme.FILL, 4));
        progressFill = new View(c);
        progressFill.setBackground(Kit.shape(Theme.LABEL, 4));
        bar.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        progress = bar;
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(340, 8);
        pp.topMargin = 22;
        info.addView(bar, pp);

        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setAlpha(0);
        // Shown only while the shelf has focus, so it's always the focused button.
        play = new Row(c, Row.Style.BUTTON).icon(R.drawable.ic_play);
        play.setRowFocused(true, false);
        actions.addView(play, new LinearLayout.LayoutParams(-2, -2));
        dots = new LinearLayout(c);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.leftMargin = 28;
        actions.addView(dots, dp);
        pager = Kit.overArt(Kit.text(c, Theme.Type.SUBHEAD, Theme.LABEL_2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.leftMargin = 28;
        actions.addView(pager, gp);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = 30;
        info.addView(actions, ap);
        actions.setTranslationY(10);

        // Glass arrows at the screen's edges while you browse the shelf.
        for (int i = 0; i < 2; i++) {
            FrameLayout chev = new FrameLayout(c);
            chev.setBackground(Kit.chrome(36));
            ImageView ic = new ImageView(c);
            ic.setImageResource(i == 0 ? R.drawable.ic_chev_left : R.drawable.ic_chev_right);
            chev.addView(ic, new FrameLayout.LayoutParams(34, 34, Gravity.CENTER));
            chev.setAlpha(0);
            chev.setScaleX(0.8f);
            chev.setScaleY(0.8f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(72, 72, Gravity.TOP | (i == 0 ? Gravity.START : Gravity.END));
            lp.topMargin = 380;
            lp.leftMargin = 28;
            lp.rightMargin = 28;
            addView(chev, lp);
            chevrons[i] = chev;
        }
    }

    // ---- items ---------------------------------------------------------------------------

    private static Item fromCard(JSONObject c, Apps.App owner) {
        Item it = new Item();
        it.resume = "continueWatching".equals(c.optString("group"));
        String name = c.optString("title"), sub = c.optString("text");
        if (it.resume) {
            // "Show, Episode title"
            int i = sub.indexOf(", ");
            name = i > 0 ? sub.substring(0, i) : sub;
            sub = i > 0 ? sub.substring(i + 2) : "";
        }
        it.title = name;
        it.overview = sub;
        String key = c.optString("key");
        it.image = c.optString("image").isEmpty() ? null : String.valueOf(RecsListener.imageUri(key));
        if ("null".equals(it.image)) it.image = null;
        long max = c.optLong("progressMax");
        it.progress = max > 0 ? c.optLong("progress") / (float) max : 0;
        it.source = owner.label;
        it.rating = c.optString("rating");
        it.card = true;
        it.recKey = key;
        it.pkg = owner.pkg;
        return it;
    }

    private static Item fromTmdb(Tmdb.Item t, Apps.App owner) {
        Item it = new Item();
        it.title = t.title;
        it.overview = t.overview;
        it.image = Tmdb.backdrop(t);
        it.tmdb = t;
        it.id = t.id;
        it.source = "Trending".equals(t.source) ? "Trending" : owner.label;
        it.pkg = owner.pkg;
        return it;
    }

    /** The apps' own cards (Continue Watching first), topped up from the TMDB pool. Blocking. */
    static List<Item> itemsFor(Apps.App owner) {
        List<Item> resume = new ArrayList<>(), picks = new ArrayList<>();
        try {
            JSONArray cards = new JSONArray(RecsListener.json());
            for (int i = 0; i < cards.length(); i++) {
                JSONObject c = cards.getJSONObject(i);
                if (!owner.pkg.equals(c.optString("pkg"))) continue;
                Item it = fromCard(c, owner);
                (it.resume ? resume : picks).add(it);
            }
        } catch (Exception ignored) {
            // No cards.
        }
        Collections.shuffle(picks);
        List<Item> out = new ArrayList<>(resume);
        out.addAll(picks);
        if (out.size() < 6) {
            List<Tmdb.Item> pool = Tmdb.pool(owner.pkg);
            for (int i = 0; i < Math.min(40, pool.size()); i++) out.add(fromTmdb(pool.get(i), owner));
        }
        return out;
    }

    /** Sharp artwork, title logo and details, borrowing TMDB's when an app's card matches a title. */
    private void details(Item item, Runnable then) {
        if (item.ready != null) {
            then.run();
            return;
        }
        item.waiting.add(then);
        if (item.loading) return;
        item.loading = true;
        Ui.IO.execute(() -> {
            Details d = new Details();
            Tmdb.Item t = item.tmdb != null ? item.tmdb : item.card ? Tmdb.match(item.title) : null;
            d.image = item.image;
            d.tmdb = t;
            List<String> parts = new ArrayList<>();
            parts.add(item.source.toUpperCase());
            if (t != null) {
                if (!item.resume || d.image == null) {
                    String b = Tmdb.backdrop(t);
                    if (b != null) d.image = b;
                }
                String url = Tmdb.logo(t);
                if (url != null) d.logo = Images.loadNow(url, 620);
                parts.add(t.tv ? "Series" : "Movie");
                if (!t.year.isEmpty()) parts.add(t.year);
            }
            if (!item.rating.isEmpty()) parts.add(item.rating);
            if (t != null && t.rating > 0) parts.add("★ " + String.format(java.util.Locale.US, "%.1f", t.rating));
            d.meta = TextUtils.join("  ·  ", parts);
            Ui.post(() -> {
                item.ready = d;
                item.loading = false;
                List<Runnable> w = new ArrayList<>(item.waiting);
                item.waiting.clear();
                for (Runnable r : w) r.run();
            });
        });
    }

    // ---- showing --------------------------------------------------------------------------

    private void swapText(Runnable change) {
        info.animate().cancel();
        info.animate().alpha(0).translationY(deep ? -30 : -12).setDuration(160).setInterpolator(Motion.EASE).withEndAction(() -> {
            change.run();
            paintInfo(320);
        }).start();
    }

    /** Where the text should be: hidden and lifted in deeper rows, else at its requested opacity. */
    private void paintInfo(long ms) {
        info.animate().alpha(deep ? 0 : infoAlpha).translationY(deep ? -30 : 0).setDuration(ms)
                .setInterpolator(Motion.EASE).start();
    }

    /** The sidebar dims the text while it's open. */
    void setInfoAlpha(float a) {
        infoAlpha = a;
        paintInfo(250);
    }

    /** Control Center hides the shelf's buttons and arrows while it's open. */
    void setControlsVisible(boolean on) {
        controls = on;
        paintControls();
    }

    private void paintControls() {
        boolean show = focused && controls;
        actions.animate().alpha(show ? 1 : 0).translationY(show ? 0 : 10).setDuration(show ? 400 : 200)
                .setInterpolator(show ? Motion.SPRING : Motion.EASE).start();
        for (View c : chevrons) {
            c.animate().alpha(show ? 1 : 0).setDuration(250).setInterpolator(Motion.LINEAR).start();
            Motion.spring(c, show ? 1 : 0.8f, 350);
        }
    }

    private void plain() {
        list = null;
        setArt(false);
        swapText(() -> {
            logo.setVisibility(GONE);
            title.setVisibility(VISIBLE);
            title.setText(app.label);
            meta.setVisibility(GONE);
            overview.setText("");
            overview.setVisibility(GONE);
            progress.setVisibility(GONE);
            actions.setVisibility(GONE);
        });
        host.onArtwork(null);
    }

    private void setArt(boolean on) {
        artOn = on;
        art.setLevel(on ? (deep ? 0.3f : 1) : 0, on ? 400 : 600);
        // The aurora is fully covered once the artwork has faded in.
        if (on && !deep) Ui.later(800, () -> aurora.setRunning(!artOn || deep));
        else aurora.setRunning(true);
    }

    private void showText(Item item, Details d) {
        if (d.logo != null) {
            logo.setImageBitmap(d.logo);
            logo.setVisibility(VISIBLE);
            title.setVisibility(GONE);
        } else {
            logo.setVisibility(GONE);
            title.setVisibility(VISIBLE);
        }
        title.setText(item.title);
        meta.setText(d.meta);
        meta.setVisibility(VISIBLE);
        overview.setText(item.overview);
        overview.setVisibility(item.overview.isEmpty() ? GONE : VISIBLE);
        progress.setVisibility(item.resume ? VISIBLE : GONE);
        progressFill.getLayoutParams().width = Math.round(340 * Math.max(0.04f, item.progress));
        progressFill.requestLayout();
        play.title(item.resume ? "Resume" : item.card ? "Play" : "Open " + app.label);
        actions.setVisibility(VISIBLE);
        updatePager();
    }

    private void updatePager() {
        if (list == null) return;
        boolean few = list.size() <= 12;
        dots.setVisibility(few ? VISIBLE : GONE);
        pager.setVisibility(few ? GONE : VISIBLE);
        if (!few) {
            pager.setText((index + 1) + " of " + list.size());
            return;
        }
        if (dots.getChildCount() != list.size()) {
            dots.removeAllViews();
            for (int i = 0; i < list.size(); i++) {
                View dot = new View(getContext());
                dot.setBackground(Kit.oval(Theme.LABEL_3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(12, 12);
                if (i > 0) lp.leftMargin = 12;
                dots.addView(dot, lp);
            }
        }
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean on = i == index;
            ((android.graphics.drawable.GradientDrawable) dot.getBackground()).setColor(on ? Theme.LABEL : Theme.LABEL_3);
            Motion.lift(dot, on, 1.25f);
        }
    }

    private static String keyOf(String image) {
        return image + "|" + AuroraView.mood();
    }

    /** Paints the artwork, its legibility shades and the mood tint into a slide bitmap, once. */
    private static Bitmap paint(Bitmap im) {
        Bitmap b = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
        Canvas cx = new Canvas(b);
        float r = Math.max(SW / (float) im.getWidth(), SH / (float) im.getHeight());
        float w = im.getWidth() * r, h = im.getHeight() * r;
        float x = (SW - w) / 2, y = (SH - h) * 0.25f;
        cx.drawBitmap(im, null, new RectF(x, y, x + w, y + h), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, SW * 0.58f, 0, new int[] { 0x9E000000, 0x47000000, 0 }, // material: legibility shade baked into the artwork
                new float[] { 0, 0.55f, 1 }, Shader.TileMode.CLAMP));
        cx.drawRect(0, 0, SW, SH, p);
        p.setShader(new LinearGradient(0, 0, 0, SH * 0.24f, 0x6B000000, 0, Shader.TileMode.CLAMP)); // material: legibility shade baked into the artwork
        cx.drawRect(0, 0, SW, SH, p);
        p.setShader(new LinearGradient(0, 0, 0, SH, new int[] { 0, 0, 0x4D000000, 0xB8000000 }, // material: legibility shade baked into the artwork
                new float[] { 0, 0.45f, 0.7f, 1 }, Shader.TileMode.CLAMP));
        cx.drawRect(0, 0, SW, SH, p);
        int tint = AuroraView.moodTint();
        if (tint != 0) cx.drawColor(tint);
        return b;
    }

    private void present(int i, int t) {
        Item item = list.get(i);
        final boolean[] shown = { false };
        // On a slow network the logo and details can land after the slide is up: fill them in then.
        Runnable partial = () -> {
            if (shown[0] || t != token) return;
            Details d = new Details();
            d.image = item.image;
            d.meta = item.source.toUpperCase();
            shown[0] = true;
            showSlide(item, d, t, true);
        };
        Ui.later(1800, partial);
        details(item, () -> {
            if (t != token) return;
            if (shown[0]) {
                swapText(() -> showText(item, item.ready));
                return;
            }
            shown[0] = true;
            Ui.MAIN.removeCallbacks(partial);
            showSlide(item, item.ready, t, false);
        });
    }

    private void showSlide(Item item, Details d, int t, boolean partial) {
        if (d.image == null) {
            if (t == token) schedule();
            return;
        }
        int w = 1280;
        Images.load(d.image, w, im -> {
            if (t != token) return;
            if (im == null) {
                schedule();
                return;
            }
            if (item.id != 0) Tmdb.markSeen(item.id);
            String key = keyOf(d.image);
            Runnable reveal = () -> {
                if (t != token) return;
                art.reveal();
                currentSlide = art.frontBitmap();
                setArt(true);
                swapText(() -> showText(item, d));
                // Once the crossfade is over: the glass follows, the next picture is painted ahead.
                Ui.later(1050, () -> {
                    if (t != token) return;
                    host.onArtwork(currentSlide);
                    paintAhead(t);
                });
                schedule();
            };
            // Usually painted ahead while the previous picture was on screen (see paintAhead).
            if (key.equals(art.backKey())) {
                reveal.run();
            } else {
                Ui.IO.execute(() -> {
                    Bitmap slide = paint(im);
                    Ui.post(() -> {
                        if (t != token) return;
                        art.prepare(slide, key);
                        reveal.run();
                    });
                });
            }
        });
    }

    /** Paints the upcoming picture into the hidden slide, so the next change is just a crossfade. */
    private void paintAhead(int t) {
        if (list == null || list.size() < 2) return;
        Item upcoming = list.get((index + 1) % list.size());
        details(upcoming, () -> {
            String image = upcoming.ready.image;
            if (image == null || t != token) return;
            Images.load(image, 1280, im -> {
                if (im == null || t != token) return;
                String key = keyOf(image);
                if (key.equals(art.backKey())) return;
                Ui.IO.execute(() -> {
                    Bitmap slide = paint(im);
                    Ui.post(() -> {
                        if (t == token) art.prepare(slide, key);
                    });
                });
            });
        });
    }

    private void schedule() {
        Ui.MAIN.removeCallbacks(advance);
        if (running && !focused && list != null && list.size() > 1) Ui.later(SLIDE_MS, advance);
    }

    private final Runnable load = this::load;

    private void load() {
        int t = token;
        Object[] saved = lists.get(pkg);
        Apps.App owner = app;
        if (saved != null) {
            @SuppressWarnings("unchecked")
            List<Item> found = (List<Item>) saved[0];
            start(found, ((int) saved[1] + 1) % found.size(), t);
            return;
        }
        Ui.IO.execute(() -> {
            List<Item> found = itemsFor(owner);
            Ui.post(() -> {
                if (t != token) return;
                if (found.isEmpty()) {
                    plain();
                    return;
                }
                lists.put(owner.pkg, new Object[] { found, 0 });
                start(found, 0, t);
            });
        });
    }

    private void start(List<Item> found, int i, int t) {
        list = found;
        index = i;
        present(index, t);
    }

    // ---- API ------------------------------------------------------------------------------

    void show(Apps.App next) {
        if (next.pkg.equals(pkg)) return;
        if (pkg != null && list != null) lists.put(pkg, new Object[] { list, index });
        pkg = next.pkg;
        app = next;
        token++;
        list = null;
        Ui.MAIN.removeCallbacks(advance);
        Ui.MAIN.removeCallbacks(load);
        // Apps with artwork crossfade straight from the current picture; others go plain at once.
        if (!Tmdb.has(next.pkg) && !lists.containsKey(next.pkg)) plain();
        // Don't hit the network for every tile passed on the way along the row.
        Ui.later(180, load);
    }

    /** Fetch what the top-row apps' shelves need ahead of time, so moving along the row is instant. */
    void prefetch(List<Apps.App> apps) {
        Ui.IO.execute(() -> {
            for (Apps.App a : apps) if (!a.special() && a.folder == null) Tmdb.pool(a.pkg);
        });
    }

    void refresh() {
        lists.clear();
        Apps.App current = app;
        pkg = null;
        if (current != null) show(current);
    }

    /** Moves focus up into the shelf; false if there is nothing to browse. */
    boolean focus() {
        if (list == null || list.isEmpty()) return false;
        focused = true;
        Ui.MAIN.removeCallbacks(advance);
        paintControls();
        return true;
    }

    void blur() {
        focused = false;
        paintControls();
        schedule();
    }

    boolean browsing() {
        return focused;
    }

    void step(int d) {
        if (list == null || list.size() < 2) return;
        Sound.move();
        index = (index + d + list.size()) % list.size();
        present(index, token);
    }

    /** Opens the title the shelf shows: the app's own deep link, else the app. */
    Item current() {
        return list == null ? null : list.get(index);
    }

    void setRunning(boolean run) {
        if (run == running) return;
        running = run;
        if (run) schedule();
        else Ui.MAIN.removeCallbacks(advance);
        aurora.setRunning(run && (!artOn || deep));
        art.setPaused(!run);
    }

    /** Deeper rows dim the artwork and tuck the title away, so the icons take over. */
    void setDeep(boolean on) {
        if (on == deep) return;
        deep = on;
        art.setLevel(artOn ? (on ? 0.3f : 1) : 0, 400);
        paintInfo(400);
        aurora.setRunning(running && (!artOn || deep));
    }

    Bitmap slide() {
        return artOn ? currentSlide : null;
    }
}
