package com.applefireos.home;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find: search any movie or show and see which of YOUR installed apps can play it. Keyboard on
 * the left, posters on the right (this week's trending before you type); Select a poster for its
 * details and "Open …" buttons for the apps that stream it. A full-screen page like Settings.
 */
final class Find extends PageScaffold {
    private static final int COLS = 5, VISIBLE_ROWS = 2;
    private static final int POSTER_W = 196, POSTER_H = 294, LABEL_H = 30;
    private static final int COL_GAP = Theme.S4, ROW_GAP = Theme.S4, LABEL_GAP = Theme.S2;
    private static final int PITCH = POSTER_H + LABEL_GAP + LABEL_H + ROW_GAP;
    /** Room around the posters for the picked one's lift. */
    private static final int BLEED = Theme.S4;
    /** The right-hand column: after the keyboard and a gap, to the page's right margin. */
    private static final int RIGHT_X = Theme.PAGE_X + Keyboard.W + Theme.S5 + Theme.S2;
    private static final int RIGHT_W = Ui.STAGE_W - Theme.PAGE_X - RIGHT_X;
    /** Everything sits above the page's bottom margin. */
    private static final int AREA_H = Ui.STAGE_H - Theme.PAGE_CONTENT_TOP - Theme.PAGE_X;
    private static final int GRID_TOP = 40 + Theme.S3;
    private static final long DEBOUNCE_MS = 380;

    private final Keyboard kb;
    private final TextView head;
    private final FrameLayout gridWindow, grid;
    private final List<Poster> cards = new ArrayList<>();
    private List<Tmdb.Item> list = new ArrayList<>();
    private int sel;
    private boolean inGrid, closed;
    private int token;
    private final Runnable debounced = this::search;

    /** The open detail view, or null. */
    private Detail detail;

    /** A poster and its title under it. */
    private static final class Poster {
        final LinearLayout view;
        final ImageView art;
        final TextView name;
        boolean on;

        Poster(LinearLayout view, ImageView art, TextView name) {
            this.view = view;
            this.art = art;
            this.name = name;
        }
    }

    private static final class Detail {
        final Tmdb.Item item;
        final FrameLayout view;
        final LinearLayout actions;
        final TextView note;
        final List<Apps.App> buttons = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        int sel;

        Detail(Tmdb.Item item, FrameLayout view, LinearLayout actions, TextView note) {
            this.item = item;
            this.view = view;
            this.actions = actions;
            this.note = note;
        }
    }

    static void open(NativeHome home) {
        for (NativeHome.Layer l : home.layers) if (l instanceof Find) return;
        new Find(home).open();
    }

    private Find(NativeHome home) {
        super(home, "Find");
        root.setClipChildren(false);
        kb = new Keyboard(home, "Movies and shows", text -> {
            Ui.MAIN.removeCallbacks(debounced);
            Ui.later(DEBOUNCE_MS, debounced);
        });
        content.addView(kb, Ui.at(Theme.PAGE_X, 0, Keyboard.W, Keyboard.H));

        head = Kit.line(Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL_2));
        content.addView(head, Ui.at(RIGHT_X, 0, RIGHT_W, 40));
        // Two rows of posters show at a time (the next row peeks in); the bleed leaves room for the lift.
        gridWindow = new FrameLayout(home);
        gridWindow.setPadding(BLEED, BLEED, BLEED, BLEED);
        gridWindow.setClipToPadding(false);
        grid = new FrameLayout(home);
        grid.setClipChildren(false);
        gridWindow.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        content.addView(gridWindow, Ui.at(RIGHT_X - BLEED, GRID_TOP - BLEED, RIGHT_W + 2 * BLEED, AREA_H - GRID_TOP + 2 * BLEED));
    }

    @Override
    void open() {
        super.open();
        kb.focus(true);
        load("");
    }

    @Override
    void close() {
        closed = true;
        Ui.MAIN.removeCallbacks(debounced);
        super.close();
    }

    // ---- results ----------------------------------------------------------------------------

    private void search() {
        load(kb.text());
    }

    private void load(String text) {
        int t = ++token;
        String q = text.trim();
        head.setText(q.length() < 2 ? "Trending this week" : "Results for “" + q + "”");
        Ui.IO.execute(() -> {
            List<Tmdb.Item> found;
            try {
                found = q.length() < 2 ? Tmdb.trending() : Tmdb.search(q);
            } catch (Exception e) {
                found = new ArrayList<>();
            }
            final List<Tmdb.Item> got = found.size() > 20 ? new ArrayList<>(found.subList(0, 20)) : found;
            Ui.post(() -> {
                if (t != token || closed) return;
                list = got;
                sel = 0;
                if (list.isEmpty()) {
                    head.setText(q.length() < 2 ? "Nothing trending right now" : "No matches for “" + q + "”");
                    if (inGrid) focusKeyboard();
                }
                buildGrid();
            });
        });
    }

    private void buildGrid() {
        grid.removeAllViews();
        cards.clear();
        for (int i = 0; i < list.size(); i++) {
            Tmdb.Item it = list.get(i);
            LinearLayout card = new LinearLayout(home);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setClipChildren(false);
            ImageView art = new ImageView(home);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Kit.shape(Theme.FILL_2, Theme.R_MEDIUM));
            Ui.rounded(art, Theme.R_MEDIUM);
            Images.load(Tmdb.poster(it), Math.round(POSTER_W * 1.1f), art::setImageBitmap);
            card.addView(art, new LinearLayout.LayoutParams(POSTER_W, POSTER_H));
            TextView name = Kit.line(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, it.title));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(POSTER_W, LABEL_H);
            np.topMargin = LABEL_GAP;
            card.addView(name, np);
            final int k = i;
            card.setOnClickListener(v -> { sel = k; inGrid = true; kb.focus(false); openDetail(); });
            grid.addView(card, Ui.at((i % COLS) * (POSTER_W + COL_GAP), (i / COLS) * PITCH, POSTER_W, PITCH - ROW_GAP));
            cards.add(new Poster(card, art, name));
        }
        grid.setTranslationY(0);
        paintGrid();
    }

    /** The picked poster lifts like a tile and its title brightens; the others settle. */
    private void paintGrid() {
        for (int i = 0; i < cards.size(); i++) {
            Poster p = cards.get(i);
            boolean on = inGrid && i == sel;
            if (p.on == on) continue;
            p.on = on;
            Motion.lift(p.art, on, Theme.LIFT_TILE);
            p.art.setForeground(on ? Kit.focusOutline(Theme.R_MEDIUM) : null);
            p.name.setTextColor(on ? Theme.LABEL : Theme.LABEL_2);
        }
        // Keep the selected row in view.
        int rows = (list.size() + COLS - 1) / COLS;
        int first = inGrid ? Math.max(0, Math.min(sel / COLS - VISIBLE_ROWS + 1, rows - VISIBLE_ROWS)) : 0;
        grid.animate().translationY(-first * PITCH).setDuration(350).setInterpolator(Motion.EASE).start();
    }

    private void focusKeyboard() {
        inGrid = false;
        kb.focus(true);
        paintGrid();
    }

    // ---- detail -----------------------------------------------------------------------------

    private void openDetail() {
        if (sel >= list.size()) return;
        Tmdb.Item item = list.get(sel);
        Sound.select();
        List<Apps.App> installed = new ArrayList<>();
        for (TileView t : home.tiles) {
            if (t.app.folder != null) installed.addAll(t.app.apps);
            else if (!t.app.special()) installed.add(t.app);
        }
        List<String> pkgs = new ArrayList<>();
        for (Apps.App a : installed) pkgs.add(a.pkg);

        // A content card over the results, leaving the keyboard showing: the title's artwork,
        // muted over the card so the text on it stays legible.
        FrameLayout view = Kit.card(home);
        Ui.rounded(view, Theme.R_LARGE);
        ImageView backdrop = new ImageView(home);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.4f);
        view.addView(backdrop, Ui.fill());
        Images.load(Tmdb.backdrop(item), 1280, backdrop::setImageBitmap);

        LinearLayout info = new LinearLayout(home);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        info.setPadding(Theme.S5 + Theme.S2, Theme.S5 + Theme.S2, Theme.S5, Theme.S5);
        int textW = 820;
        TextView title = Kit.overArt(Kit.text(home, Theme.Type.DISPLAY, Theme.LABEL, item.title));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title, new LinearLayout.LayoutParams(textW, -2));
        ImageView logo = new ImageView(home);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(560);
        logo.setMaxHeight(190);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setVisibility(View.GONE);
        info.addView(logo, new LinearLayout.LayoutParams(-2, -2));

        List<String> meta = new ArrayList<>();
        meta.add(item.tv ? "Series" : "Movie");
        if (!item.year.isEmpty()) meta.add(item.year);
        if (item.rating > 0) meta.add(String.format(Locale.US, "★ %.1f", item.rating));
        TextView metaView = Kit.overArt(Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL_2, TextUtils.join("  ·  ", meta)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = Theme.S3;
        info.addView(metaView, mp);

        TextView overview = Kit.overArt(Kit.text(home, Theme.Type.BODY, Theme.LABEL_2, item.overview));
        overview.setLineSpacing(0, 1.4f);
        overview.setMaxLines(4);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(textW, -2);
        op.topMargin = Theme.S2;
        info.addView(overview, op);

        LinearLayout actions = new LinearLayout(home);
        actions.setClipChildren(false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = Theme.S4;
        info.addView(actions, ap);
        TextView note = Kit.overArt(Kit.text(home, Theme.Type.SUBHEAD, Theme.LABEL_2, "Checking where it’s streaming…"));
        LinearLayout.LayoutParams notep = new LinearLayout.LayoutParams(textW, -2);
        notep.topMargin = Theme.S3;
        info.addView(note, notep);
        view.addView(info, new FrameLayout.LayoutParams(-1, -2));

        content.addView(view, Ui.at(RIGHT_X - Theme.S3, 0, RIGHT_W + Theme.S3, AREA_H));
        head.setVisibility(View.INVISIBLE);
        gridWindow.setVisibility(View.INVISIBLE);
        view.setAlpha(0);
        Motion.fade(view, 1, 220, null);
        Detail d = new Detail(item, view, actions, note);
        detail = d;

        Ui.IO.execute(() -> {
            List<String>[] where = Tmdb.watchOn(item, pkgs);
            String logoUrl = Tmdb.logo(item);
            Ui.post(() -> {
                if (detail != d) return;
                if (logoUrl != null) {
                    Images.load(logoUrl, 560, b -> {
                        if (b == null) return;
                        logo.setImageBitmap(b);
                        logo.setVisibility(View.VISIBLE);
                        title.setVisibility(View.GONE);
                    });
                }
                showWhere(d, where[0], where[1], installed);
            });
        });
    }

    private void showWhere(Detail d, List<String> names, List<String> apps, List<Apps.App> installed) {
        for (String pkg : apps) {
            for (Apps.App a : installed) {
                if (a.pkg.equals(pkg)) {
                    d.buttons.add(a);
                    break;
                }
            }
        }
        List<String> elsewhere = new ArrayList<>();
        for (String n : names) {
            boolean here = false;
            for (Apps.App a : d.buttons) {
                if (n.toLowerCase(Locale.ROOT).contains(a.label.toLowerCase(Locale.ROOT).split(" ")[0])) here = true;
            }
            if (!here) elsewhere.add(n);
        }
        // "Open <app>": the design system's button, led by the app's own artwork.
        for (int i = 0; i < d.buttons.size(); i++) {
            Apps.App a = d.buttons.get(i);
            Row b = new Row(home, Row.Style.BUTTON).title("Open " + a.label);
            boolean wide = a.banner != null;
            b.leading(Kit.appArt(home, a, Theme.R_SMALL), wide ? 64 : 40, wide ? 36 : 40, false);
            final int k = i;
            b.setOnClickListener(v -> { d.sel = k; openApp(); });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) bp.leftMargin = Theme.S3;
            d.actions.addView(b, bp);
            d.rows.add(b);
        }
        if (!d.buttons.isEmpty()) {
            if (elsewhere.isEmpty()) d.note.setVisibility(View.GONE);
            else d.note.setText("Also on " + join(elsewhere));
        } else if (!names.isEmpty()) {
            d.note.setText("Not on your apps — streaming on " + join(names));
        } else {
            String region = Tmdb.REGIONS.get(Tmdb.region());
            d.note.setText("Not streaming in " + (region != null ? region : "your region") + " right now");
        }
        paintDetail(false);
    }

    private static String join(List<String> names) {
        return TextUtils.join(", ", names.subList(0, Math.min(4, names.size())));
    }

    private void paintDetail(boolean animate) {
        for (int i = 0; i < detail.rows.size(); i++) {
            Row b = detail.rows.get(i);
            boolean on = i == detail.sel;
            if (b.isRowFocused() != on) b.setRowFocused(on, animate);
        }
    }

    private void closeDetail() {
        View v = detail.view;
        detail = null;
        Motion.fade(v, 0, 180, () -> content.removeView(v));
        head.setVisibility(View.VISIBLE);
        gridWindow.setVisibility(View.VISIBLE);
        paintGrid();
    }

    private void openApp() {
        if (detail == null || detail.sel >= detail.buttons.size()) return;
        Apps.App a = detail.buttons.get(detail.sel);
        Sound.select();
        Apps.record(a.pkg);
        home.launch(a.pkg);
    }

    // ---- page -------------------------------------------------------------------------------

    @Override
    boolean backWithin() {
        if (detail == null) return false;
        closeDetail();
        return true;
    }

    @Override
    void onKey(int code, KeyEvent e) {
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        boolean select = NativeHome.isSelect(code);
        if (detail != null) {
            if (dx != 0 && !detail.buttons.isEmpty()) {
                int next = detail.sel + dx;
                if (next >= 0 && next < detail.buttons.size()) {
                    detail.sel = next;
                    Sound.move();
                    paintDetail(true);
                } else {
                    Sound.bump();
                }
            } else if (select && e.getRepeatCount() == 0) {
                openApp();
            }
            return;
        }
        if (!inGrid) {
            if (dx != 0 || dy != 0) {
                if (kb.move(dx, dy)) return;
                if (dx > 0 && !list.isEmpty()) {
                    inGrid = true;
                    sel = 0;
                    kb.focus(false);
                    Sound.move();
                    paintGrid();
                } else {
                    Sound.bump();
                }
            } else if (select) {
                if (e.getRepeatCount() == 0) kb.press();
            } else {
                kb.type(e);
            }
            return;
        }
        if (dx != 0 || dy != 0) {
            int col = sel % COLS;
            if (dx < 0 && col == 0) {
                Sound.move();
                focusKeyboard();
                return;
            }
            int next = sel + (dx != 0 ? dx : dy * COLS);
            if (next >= 0 && next < list.size() && !(dx > 0 && col == COLS - 1)) {
                sel = next;
                Sound.move();
                paintGrid();
            } else {
                Sound.bump();
            }
        } else if (select) {
            if (e.getRepeatCount() == 0) openDetail();
        } else if (kb.type(e)) {
            focusKeyboard();
        }
    }
}
