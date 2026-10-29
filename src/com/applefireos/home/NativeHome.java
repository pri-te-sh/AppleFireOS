package com.applefireos.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: the Top Shelf, the app grid on its glass dock, and the status bar, drawn with
 * plain Android views. Panels and pages open on top of it (see PanelScaffold and PageScaffold).
 * Layout is in stage pixels: the window is 1920x1080.
 */
public class NativeHome extends Activity implements ShelfView.Host {
    static final int COLS = 6, MARGIN = 90, GAP = 40;
    static final int TILE_W = TileView.W, TILE_H = TileView.H;
    static final int ROW_PITCH = TILE_H + 74, SHELF_H = 760;
    static final float FOCUS_SCALE = 1.15f, PRESS_SCALE = 1.06f;
    static final int DOCK_PAD_X = Math.round(TILE_W * (FOCUS_SCALE - 1) / 2) + 26;
    static final int DOCK_PAD_Y = Math.round(TILE_H * (FOCUS_SCALE - 1) / 2) + 16;
    static final long LONG_PRESS_MS = 550;

    /** Full-screen panels push themselves here while open and get every key. */
    interface Layer {
        /** True if handled. */
        boolean key(KeyEvent e);

        void back();

        /** The Menu key; panels without options ignore it. */
        default void menu() {}
    }

    final List<Layer> layers = new ArrayList<>();

    FrameLayout stage;
    ShelfView shelf;
    private FrameLayout grid;
    private DockView dock;
    private TextView clock, ampm, avatar;
    private View statusBar, sidebarPill;
    private TextView toast;
    FrameLayout overlay;
    /** The app being moved (Move from its menu), or null. */
    private String moving;

    private List<Apps.App> installed = new ArrayList<>();
    List<TileView> tiles = new ArrayList<>();
    int focus;
    private int currentRow = -1;
    private float gridY;
    private ValueAnimator scroll;
    private boolean resumed, homePressed, leftForApp, inShelf;
    private long pausedAt, selectDown;
    private boolean longPressed;
    private Bitmap frost;

    // ---- setup ----------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Prefs.init(this);
        Images.init(this);
        Tmdb.init(this);
        Sound.init();

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Theme.STAGE);
        stage.setClipChildren(false);
        shelf = new ShelfView(this, this);
        stage.addView(shelf, Ui.fill());

        grid = new FrameLayout(this);
        grid.setClipChildren(false);
        stage.addView(grid, Ui.fill());

        statusBar = statusBar();
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, 68, Gravity.TOP | Gravity.END);
        sp.topMargin = 48;
        sp.rightMargin = 72;
        stage.addView(statusBar, sp);
        statusBar.setOnClickListener(v -> new ControlCenter(this).show());
        sidebarPill = sidebarPill();
        stage.addView(sidebarPill, Ui.at(72, 48, FrameLayout.LayoutParams.WRAP_CONTENT, 60));

        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        stage.addView(overlay, Ui.fill());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BLACK);
        root.addView(stage, new FrameLayout.LayoutParams(Ui.STAGE_W, Ui.STAGE_H));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit(r - l, b - t));
        setContentView(root);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
        RecsListener.setOnChange(() -> Ui.post(() -> {
            updateAvatar();
            if (!shelf.browsing()) shelf.refresh();
        }));
        loadApps(null);
        tickClock();
        // Fire OS forgets usage at reboot; Insights keeps its own log, topped up from here.
        Ui.later(5000, () -> InsightsPage.sync(this));
        Ui.later(15_000, this::updateAvatar);
        takeKey(getIntent());
        Ui.later(1500, () -> SetupPage.firstRun(this));
    }

    /** setup.sh hands over a TMDB key this way: am start … --es tmdbKey <key>. */
    private boolean takeKey(Intent i) {
        String k = i == null ? null : i.getStringExtra("tmdbKey");
        if (k == null) return false;
        i.removeExtra("tmdbKey");
        Tmdb.setKey(k);
        refreshShelf();
        return true;
    }

    void refreshShelf() {
        if (!shelf.browsing()) shelf.refresh();
    }

    /** Scales the 1920x1080 stage to the window, should it ever differ. */
    private void fit(int w, int h) {
        if (w == 0 || h == 0) return;
        float s = Math.min(w / (float) Ui.STAGE_W, h / (float) Ui.STAGE_H);
        stage.setPivotX(0);
        stage.setPivotY(0);
        stage.setScaleX(s);
        stage.setScaleY(s);
        stage.setTranslationX((w - Ui.STAGE_W * s) / 2);
        stage.setTranslationY((h - Ui.STAGE_H * s) / 2);
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps(focusedPkg());
        }
    };

    String focusedPkg() {
        return focus < tiles.size() ? tiles.get(focus).app.pkg : null;
    }

    void loadApps(String keep) {
        Ui.IO.execute(() -> {
            List<Apps.App> list = Apps.installed(this);
            Ui.post(() -> {
                installed = list;
                arrange(keep);
            });
        });
    }

    void arrange(String keep) {
        Tmdb.setRegion(Apps.region(installed));
        render(Apps.arrange(installed), keep);
    }

    private void render(List<Apps.App> items, String keep) {
        for (TileView t : tiles) t.setWiggling(false);
        grid.removeAllViews();
        tiles.clear();
        int used = Math.min(COLS, items.size());
        dock = new DockView(this, 52);
        int dx = MARGIN - DOCK_PAD_X, dy = SHELF_H - DOCK_PAD_Y;
        grid.addView(dock, Ui.at(dx, dy, used * TILE_W + (used - 1) * GAP + 2 * DOCK_PAD_X, TILE_H + 2 * DOCK_PAD_Y));
        dock.setBackdrop(frost);
        dock.place(dx, dy + gridY);
        for (int i = 0; i < items.size(); i++) {
            TileView t = new TileView(this, items.get(i));
            t.row = i / COLS;
            t.col = i % COLS;
            final int index = i;
            t.setOnClickListener(v -> {
                setFocus(index, null);
                activate();
            });
            grid.addView(t, Ui.at(MARGIN + t.col * (TILE_W + GAP), SHELF_H + t.row * ROW_PITCH, TILE_W, TILE_H + 80));
            tiles.add(t);
        }
        currentRow = -1;
        int i = 0;
        for (int k = 0; k < tiles.size(); k++) if (tiles.get(k).app.pkg.equals(keep)) i = k;
        focus = 0;
        setFocus(i, null);
        List<Apps.App> top = new ArrayList<>();
        for (int k = 0; k < Math.min(COLS, items.size()); k++) top.add(items.get(k));
        Ui.later(3000, () -> shelf.prefetch(top));
    }

    // ---- status bar ------------------------------------------------------------------------

    private View statusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, 68);
        np.rightMargin = 22;
        bar.addView(NowPlayingPill.view(this), np);
        bar.addView(VpnStatus.view(this));
        bar.addView(Weather.view(this));
        clock = Kit.overArt(Kit.text(this, Theme.Type.HEADLINE, Theme.LABEL));
        bar.addView(clock);
        ampm = Kit.overArt(Kit.text(this, Theme.Type.FOOTNOTE, Theme.LABEL_2));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.leftMargin = 8;
        bar.addView(ampm, ap);
        avatar = Kit.avatar(this, "", 50);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(50, 50);
        vp.leftMargin = 16;
        bar.addView(avatar, vp);
        updateAvatar();
        return bar;
    }

    private void tickClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        ampm.setText(new SimpleDateFormat("a", Locale.US).format(now));
        Ui.MAIN.removeCallbacks(clockTick);
        Ui.later(60_000 - System.currentTimeMillis() % 60_000 + 50, clockTick);
    }

    private final Runnable clockTick = this::tickClock;

    /** The streaming profile's name ("Continue Watching for …"), else the device owner's. */
    String profileName() {
        try {
            JSONArray cards = new JSONArray(RecsListener.json());
            for (int i = 0; i < cards.length(); i++) {
                String t = cards.getJSONObject(i).optString("title");
                if (t.toLowerCase(Locale.US).startsWith("continue watching for ")) return t.substring(22).trim();
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        try {
            return ((android.os.UserManager) getSystemService(USER_SERVICE)).getUserName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void updateAvatar() {
        String n = profileName();
        avatar.setText(n.isEmpty() ? "•" : n.substring(0, 1).toUpperCase(Locale.getDefault()));
    }

    // ---- focus & the living tile -----------------------------------------------------------

    private final TileMotion motion = new TileMotion();

    /** Spring scale, idle "breathing" tilt and a rubbery bump, driven every frame. */
    private final class TileMotion implements Choreographer.FrameCallback {
        TileView tile;
        long t0, kick, last;
        float ix, iy, px, py, s = 1, v, target = FOCUS_SCALE;
        boolean posted;

        void start(TileView t, int[] dir) {
            tile = t;
            long now = SystemClock.uptimeMillis();
            t0 = kick = now;
            ix = dir != null ? -dir[0] * 12 : 0;
            iy = dir != null ? dir[1] * 9 : 0;
            px = py = 0;
            s = 1;
            v = 0;
            target = FOCUS_SCALE;
            post();
        }

        void post() {
            if (posted || !resumed) return;
            posted = true;
            last = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameNanos) {
            posted = false;
            if (tile == null || !resumed) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            v += (260 * (target - s) - 18 * v) * dt;
            s += v * dt;
            float time = (now - t0) / 1000f, since = (now - kick) / 1000f;
            float spring = (float) (Math.exp(-since * 5.5) * Math.cos(since * 13));
            float idle = Math.min(1, time / 1.5f);
            float rx = idle * 2.4f * (float) Math.sin(time * 1.05) + iy * spring;
            float ry = idle * 3.6f * (float) Math.sin(time * 0.7 + 0.8) + ix * spring;
            View c = tile.card;
            c.setScaleX(s);
            c.setScaleY(s);
            c.setRotationX(rx);
            c.setRotationY(ry);
            c.setTranslationX(px * spring);
            c.setTranslationY(py * spring);
            tile.tilt(rx, ry);
            post();
        }
    }

    void setFocus(int i, int[] dir) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(tiles.size() - 1, i));
        TileView prev = focus < tiles.size() ? tiles.get(focus) : null;
        if (prev != null && prev != tiles.get(i)) prev.setFocused(false);
        if (dir != null) Sound.move();
        focus = i;
        TileView t = tiles.get(i);
        t.setFocused(true, dir);
        motion.start(t, dir);
        if (t.row == 0) shelf.show(t.app);
        scrollToRow(t.row);
        shelf.aurora.setPalette(t.app.palette);
    }

    private void nudge(int[] d) {
        Sound.bump();
        motion.kick = SystemClock.uptimeMillis();
        motion.ix = d[0] * 11;
        motion.iy = -d[1] * 9;
        motion.px = d[0] * 14;
        motion.py = d[1] * 10;
    }

    private void scrollToRow(int row) {
        if (row == currentRow) return;
        currentRow = row;
        int rows = (tiles.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, SHELF_H + rows * ROW_PITCH + GAP - Ui.STAGE_H);
        int to = row == 0 ? 0 : Math.max(0, Math.min(maxScroll, Math.round(SHELF_H + row * ROW_PITCH - Ui.STAGE_H * 0.3f)));
        scrollGrid(-to);
        shelf.setDeep(to > 0);
    }

    private void scrollGrid(float to) {
        if (scroll != null) scroll.cancel();
        scroll = ValueAnimator.ofFloat(gridY, to);
        scroll.setDuration(500);
        scroll.setInterpolator(Motion.EASE);
        scroll.addUpdateListener(a -> {
            gridY = (float) a.getAnimatedValue();
            grid.setTranslationY(gridY);
            if (dock != null) dock.place(MARGIN - DOCK_PAD_X, SHELF_H - DOCK_PAD_Y + gridY);
        });
        scroll.start();
    }

    private void navigate(int[] d) {
        TileView t = tiles.get(focus);
        int rows = (tiles.size() + COLS - 1) / COLS, next = -1;
        if (d[0] != 0) {
            int col = t.col + d[0], idx = t.row * COLS + col;
            if (col >= 0 && col < COLS && idx < tiles.size()) next = idx;
        } else {
            int row = t.row + d[1];
            if (row >= 0 && row < rows) next = Math.min(row * COLS + t.col, tiles.size() - 1);
        }
        if (next < 0 && d[0] < 0 && t.col == 0) Sidebar.open(this);
        else if (next < 0 && d[1] < 0 && t.row == 0 && shelf.focus()) enterShelf();
        else if (next < 0 && d[1] < 0 && t.row == 0) new ControlCenter(this).show();
        else if (next < 0) nudge(d);
        else setFocus(next, d);
    }

    /** Up from the top row hands focus to the Top Shelf; the icons step back. */
    private void enterShelf() {
        inShelf = true;
        TileView t = tiles.get(focus);
        t.setFocused(false);
        motion.tile = null;
        Sound.move();
        scrollGrid(70);
        // The icons step back while you browse the shelf.
        grid.animate().alpha(0.55f).setDuration(350).setInterpolator(Motion.EASE).start();
    }

    private void leaveShelf() {
        inShelf = false;
        shelf.blur();
        grid.animate().alpha(1).setDuration(300).setInterpolator(Motion.EASE).start();
        currentRow = -1;
        setFocus(focus, new int[] { 0, 1 });
    }

    // ---- opening things -------------------------------------------------------------------

    void activate() {
        TileView t = tiles.get(focus);
        Apps.App a = t.app;
        if (Apps.SETTINGS.equals(a.pkg)) {
            openSettings();
            return;
        }
        if (Apps.FIND.equals(a.pkg)) {
            openFind();
            return;
        }
        if (Apps.INSIGHTS.equals(a.pkg)) {
            openInsights();
            return;
        }
        if (a.folder != null) {
            FolderPanel.open(this, a);
            return;
        }
        Sound.select();
        Apps.record(a.pkg);
        launchZoom(t);
        Ui.later(90, () -> launch(a.pkg));
    }

    void launch(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        open(i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    /** tvOS-style: the tile grows to fill the screen as its app opens. */
    private void launchZoom(TileView t) {
        ImageView z = new ImageView(this);
        z.setScaleType(t.app.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        z.setImageBitmap(t.app.banner != null ? t.app.banner : t.app.icon);
        z.setBackgroundColor(t.app.banner != null ? Theme.GRAPHITE : t.app.tint);
        float x = MARGIN + t.col * (TILE_W + GAP), y = SHELF_H + t.row * ROW_PITCH + gridY;
        overlay.addView(z, Ui.at(Math.round(x), Math.round(y), TILE_W, TILE_H));
        float k = Math.max(Ui.STAGE_W / (float) TILE_W, Ui.STAGE_H / (float) TILE_H);
        z.setScaleX(FOCUS_SCALE);
        z.setScaleY(FOCUS_SCALE);
        // The corners square off as it fills the screen, and it darkens into the app.
        float[] radius = { TileView.RADIUS };
        z.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius[0]);
            }
        });
        z.setClipToOutline(true);
        android.graphics.drawable.ColorDrawable dark = new android.graphics.drawable.ColorDrawable(Theme.BLACK);
        dark.setAlpha(0);
        z.setForeground(dark);
        ValueAnimator grow = ValueAnimator.ofFloat(0, 1);
        grow.setDuration(420);
        grow.setInterpolator(Motion.LAUNCH);
        float tx = Ui.STAGE_W / 2f - (x + TILE_W / 2f), ty = Ui.STAGE_H / 2f - (y + TILE_H / 2f);
        grow.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue(), s = FOCUS_SCALE + (k - FOCUS_SCALE) * p;
            z.setScaleX(s);
            z.setScaleY(s);
            z.setTranslationX(tx * p);
            z.setTranslationY(ty * p);
            radius[0] = TileView.RADIUS * (1 - p) / Math.max(1, s / FOCUS_SCALE) + 2 * (1 - p);
            z.invalidateOutline();
            dark.setAlpha(Math.round(255 * Math.max(0, Math.min(1, (a.getCurrentPlayTime() - 200) / 300f))));
        });
        grow.start();
        Ui.later(4000, () -> overlay.removeView(z));
    }

    private void openShelf() {
        ShelfView.Item it = shelf.current();
        if (it == null) return;
        Sound.select();
        leftForApp = true;
        if (it.recKey != null && RecsListener.open(it.recKey)) return;
        launch(it.pkg);
    }

    // ---- keys -----------------------------------------------------------------------------

    private static int[] arrow(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return new int[] { -1, 0 };
            case KeyEvent.KEYCODE_DPAD_RIGHT: return new int[] { 1, 0 };
            case KeyEvent.KEYCODE_DPAD_UP: return new int[] { 0, -1 };
            case KeyEvent.KEYCODE_DPAD_DOWN: return new int[] { 0, 1 };
            default: return null;
        }
    }

    private static boolean passThrough(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_HEADSETHOOK:
                return true;
            default:
                return false;
        }
    }

    static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Volume and media keys always reach the system, whatever panel is open.
        if (passThrough(code)) {
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (down && e.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BACK) onBack();
                else onMenu();
            }
            return true; // never leave: this is the home screen
        }
        if (!layers.isEmpty()) {
            if (layers.get(layers.size() - 1).key(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        int[] d = arrow(code);
        if (inShelf) {
            if (!down) return true;
            if (d != null && d[0] != 0) shelf.step(d[0]);
            else if (d != null && d[1] < 0) new ControlCenter(this).show();
            else if (d != null && d[1] > 0) leaveShelf();
            else if (isSelect(code) && e.getRepeatCount() == 0) openShelf();
            return true;
        }
        if (moving != null) {
            if (down && d != null) moveBy(d);
            else if (down && isSelect(code) && e.getRepeatCount() == 0) endMove();
            return true;
        }
        if (d != null) {
            if (down) navigate(d);
            return true;
        }
        if (isSelect(code)) {
            if (down && e.getRepeatCount() == 0) {
                selectDown = SystemClock.uptimeMillis();
                longPressed = false;
                motion.target = PRESS_SCALE;
                motion.post();
            } else if (down && selectDown > 0 && !longPressed && SystemClock.uptimeMillis() - selectDown > LONG_PRESS_MS) {
                longPressed = true;
                motion.target = FOCUS_SCALE;
                onMenu();
            } else if (!down && selectDown > 0) {
                selectDown = 0;
                motion.target = FOCUS_SCALE;
                if (!longPressed) activate();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    void onBack() {
        if (!layers.isEmpty()) layers.get(layers.size() - 1).back();
        else if (inShelf) leaveShelf();
        else if (moving != null) endMove();
        else setFocus(0, null);
    }

    void onMenu() {
        if (!layers.isEmpty()) {
            layers.get(layers.size() - 1).menu();
            return;
        }
        if (inShelf) return;
        if (moving != null) endMove();
        else appMenu();
    }

    /** Home pressed while already home: close whatever is open and go back to the first app. */
    void onHomeKey() {
        while (!layers.isEmpty()) layers.get(layers.size() - 1).back();
        if (inShelf) leaveShelf();
        endMove();
        setFocus(0, null);
    }

    // ---- menus, moving and folders ----------------------------------------------------------

    private static final String[] FOLDER_IDEAS = { "Movies & TV", "News", "Music", "Sports", "Tools", "Kids" };

    private void appMenu() {
        TileView t = tiles.get(focus);
        Apps.App a = t.app;
        if (Apps.SETTINGS.equals(a.pkg)) {
            openSettings();
            return;
        }
        if (a.special()) return;
        List<Sheet.Option> o = new ArrayList<>();
        if (a.folder != null) {
            o.add(new Sheet.Option("Open", () -> FolderPanel.open(this, a)));
            o.add(new Sheet.Option("Move", this::startMove));
            o.add(new Sheet.Option("Remove Folder", () -> {
                Apps.dissolveFolder(a.folder.id);
                loadApps(null);
                toast("Apps from " + a.label + " are back on Home");
            }));
            Sheet.open(this, a.label, o);
            return;
        }
        o.add(new Sheet.Option("Move", this::startMove));
        o.add(new Sheet.Option("Move to Folder\u2026", () -> chooseFolder(a)));
        o.add(new Sheet.Option("Hide from Home", () -> {
            String next = focus + 1 < tiles.size() ? tiles.get(focus + 1).app.pkg : null;
            Apps.hide(a.pkg);
            loadApps(next);
        }));
        o.add(new Sheet.Option("App Info", () -> appInfo(a.pkg)));
        if (!a.system) o.add(new Sheet.Option("Uninstall", () -> uninstall(a.pkg)));
        Sheet.open(this, a.label, o);
    }

    private void chooseFolder(Apps.App a) {
        List<Apps.Folder> folders = Apps.folders();
        List<Sheet.Option> o = new ArrayList<>();
        for (Apps.Folder f : folders) o.add(new Sheet.Option(f.name, () -> intoFolder(a, f.name)));
        int ideas = 0;
        for (String n : FOLDER_IDEAS) {
            boolean exists = false;
            for (Apps.Folder f : folders) exists |= f.name.equalsIgnoreCase(n);
            if (exists || ideas >= Math.max(2, 5 - folders.size())) continue;
            ideas++;
            o.add(new Sheet.Option("+ " + n, () -> intoFolder(a, n)));
        }
        Sheet.open(this, "Move to Folder", o);
    }

    private void intoFolder(Apps.App a, String name) {
        Apps.Folder f = Apps.addToFolder(a.pkg, name);
        loadApps("folder:" + f.id);
        toast("Moved " + a.label + " to " + name);
    }

    private void startMove() {
        if (Apps.smart()) {
            toast("Switch App Order to Manual to rearrange");
            return;
        }
        Apps.App a = tiles.get(focus).app;
        if (a.special()) return;
        moving = a.pkg;
        tiles.get(focus).setWiggling(true);
        hint("Use the arrows to move \u00B7 Press Select when done");
    }

    private void endMove() {
        if (moving == null) return;
        for (TileView t : tiles) t.setWiggling(false);
        moving = null;
        hint(null);
    }

    private void moveBy(int[] d) {
        List<Apps.App> list = new ArrayList<>();
        for (TileView t : tiles) if (!t.app.special()) list.add(t.app);
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).pkg.equals(moving)) i = k;
        int j = Math.max(0, Math.min(list.size() - 1, d[0] != 0 ? i + d[0] : i + d[1] * COLS));
        if (i < 0 || j == i) {
            nudge(d);
            return;
        }
        Sound.move();
        list.add(j, list.remove(i));
        Apps.saveOrder(list);
        arrange(moving);
        tiles.get(focus).setWiggling(true);
    }

    // ---- small helpers the panels use ------------------------------------------------------

    Bitmap frost() {
        return frost;
    }

    String clockText() {
        return clock.getText() + " " + ampm.getText();
    }

    /** The status bar's avatar, for Control Center's header. */
    View avatarView(int size) {
        return Kit.avatar(this, profileName(), size);
    }

    /** Control Center's VPN chip (null with no VPN in use). */
    View vpnChip() {
        return VpnStatus.chip(this);
    }

    void openVpnApp() {
        VpnStatus.openApp(this);
    }

    boolean isHomeResumed() {
        return resumed;
    }

    /** Control Center takes over the corner: the time, the sidebar pill and the shelf's buttons step aside. */
    void setStatusVisible(boolean on) {
        statusBar.animate().alpha(on ? 1 : 0).setDuration(250).setInterpolator(Motion.LINEAR).start();
        sidebarPill.animate().alpha(on ? 1 : 0).setDuration(250).setInterpolator(Motion.LINEAR).start();
        shelf.setControlsVisible(on);
    }

    /** The grid and the shelf's text step back while the sidebar (or its profiles) has focus. */
    void dimGrid(boolean on) {
        sidebarPill.animate().alpha(on ? 0 : 1).setDuration(250).setInterpolator(Motion.LINEAR).start();
        shelf.setInfoAlpha(on ? 0.25f : 1);
        TileView t = focus < tiles.size() ? tiles.get(focus) : null;
        if (t == null) return;
        if (on) {
            t.setFocused(false);
            motion.tile = null;
        } else {
            setFocus(focus, null);
        }
    }

    /** tvOS 27's "‹ Home" pill in the corner: the sidebar grows out of it. */
    private View sidebarPill() {
        LinearLayout p = new LinearLayout(this);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(16, 0, 26, 0);
        p.setBackground(Kit.chrome(30));
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_sb_chevron);
        chev.setAlpha(0.8f);
        p.addView(chev, new LinearLayout.LayoutParams(22, 28));
        ImageView home = new ImageView(this);
        home.setImageResource(R.drawable.ic_sb_home);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(28, 28);
        hp.leftMargin = 10;
        p.addView(home, hp);
        TextView label = Kit.text(this, Theme.Type.CALLOUT, Theme.LABEL, "Home");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = 10;
        p.addView(label, lp);
        p.setOnClickListener(v -> Sidebar.open(this));
        return p;
    }

    void openSettings() {
        SettingsPage.open(this);
    }

    void openSetup() {
        SetupPage.open(this);
    }

    void openFind() {
        Find.open(this);
    }

    void openInsights() {
        InsightsPage.open(this);
    }

    void openFireTvSettings() {
        Intent fireTv = new Intent().setComponent(new android.content.ComponentName(
                "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"));
        open(getPackageManager().resolveActivity(fireTv, 0) != null ? fireTv : new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    void appInfo(String pkg) {
        open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)));
    }

    void uninstall(String pkg) {
        open(new Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", pkg, null)));
    }

    /** A short message in a glass pill at the top centre. */
    void toast(String text) {
        pill(text, 1800);
    }

    private TextView hint;

    void hint(String text) {
        if (hint != null) {
            TextView old = hint;
            hint = null;
            Motion.close(old, 0.96f, () -> overlay.removeView(old));
        }
        if (text != null) hint = pill(text, 0);
    }

    private TextView pill(String text, long ms) {
        TextView p = Kit.text(this, Theme.Type.CALLOUT, Theme.LABEL, text);
        p.setPadding(36, 18, 36, 18);
        p.setBackground(Kit.shape(Theme.GLASS_THICK, 999));
        p.setElevation(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 52;
        overlay.addView(p, lp);
        Motion.popIn(p, 16);
        if (ms > 0) {
            if (toast != null) overlay.removeView(toast);
            toast = p;
            Ui.later(ms, () -> Motion.close(p, 0.96f, () -> overlay.removeView(p)));
        }
        return p;
    }

    // ---- ShelfView.Host -------------------------------------------------------------------

    @Override
    public void onArtwork(Bitmap slide) {
        if (slide == null) {
            frost = null;
            if (dock != null) dock.setBackdrop(null);
            return;
        }
        Ui.IO.execute(() -> {
            Bitmap f = DockView.frostOf(slide);
            Ui.post(() -> {
                frost = f;
                if (dock != null) dock.setBackdrop(f);
            });
        });
    }

    @Override
    public boolean resting() {
        return resumed && currentRow == 0 && layers.isEmpty() && !inShelf;
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        leftForApp = false;
        shelf.setRunning(true);
        motion.post();
        tickClock();
        // The profile name comes from Netflix's cards, which arrive a little after boot.
        updateAvatar();
        overlay.postDelayed(() -> {
            for (int i = overlay.getChildCount() - 1; i >= 0; i--) {
                if (overlay.getChildAt(i) instanceof ImageView) overlay.removeViewAt(i);
            }
        }, 300);
        if (homePressed) {
            homePressed = false;
            onHomeKey();
        }
        InsightsPage.sync(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        pausedAt = SystemClock.uptimeMillis();
        shelf.setRunning(false);
        super.onPause();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        // Every startActivity from here ends up in this method.
        leftForApp = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeKey(intent)) return;
        // Home pressed while already home: Fire OS shows Amazon's home first (pausing us) and the
        // redirect brings us back, so act on it once resumed. From another app, Home just returns.
        if (resumed) onHomeKey();
        else if (!leftForApp && SystemClock.uptimeMillis() - pausedAt < 2000) homePressed = true;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) Images.trimMemory();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        RecsListener.setOnChange(null);
        super.onDestroy();
    }
}
