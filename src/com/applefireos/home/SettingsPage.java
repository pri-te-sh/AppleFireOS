package com.applefireos.home;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Settings (tvOS-style): a large preview of the focused setting on the left and grouped cards of
 * rows on the right, all built from the design system. Toggles flip in place; values and actions
 * open a choice sheet or another screen.
 */
final class SettingsPage extends PageScaffold {
    /** Icon tiles: in a row, and the big one in the preview. */
    private static final int TILE = 48, HERO = 300;
    private static final int HEAD_H = 64, CARD_GAP = Theme.S4, LIST_W = 1010;
    /** The list's window: right-aligned to the page margin, with room either side for the lift. */
    private static final int LIST_X = Ui.STAGE_W - Theme.PAGE_X - LIST_W - Theme.S3,
            VIEW_H = Ui.STAGE_H - Theme.PAGE_CONTENT_TOP, LEFT_W = LIST_X - Theme.PAGE_X - Theme.S4;

    private enum Kind { TOGGLE, VALUE, ACTION, LINK }

    /** One setting: what it shows and does, and the Row it's drawn as. */
    private static final class Item {
        final int icon, color;
        final String label, desc;
        final Kind kind;
        Supplier<String> value;
        BooleanSupplier on;
        /** Flips a toggle, or opens whatever the row leads to. */
        Runnable run;
        int y;
        Row row;
        View divider;
        /** The value last put on the row, so unchanged values don't repaint it. */
        String shown;

        Item(int icon, int color, String label, Kind kind, String desc) {
            this.icon = icon;
            this.color = color;
            this.label = label;
            this.kind = kind;
            this.desc = desc;
        }
    }

    private final LinearLayout left, list;
    private final FrameLayout hero;
    private final TextView heroTitle, heroDesc;
    private final List<Item> items = new ArrayList<>();
    private final int listH;
    /** Installed apps that are hidden; null until first read. */
    private List<Apps.App> hiddenApps;
    private int sel = -1;

    static void open(NativeHome home) {
        SettingsPage p = new SettingsPage(home);
        p.open();
        p.select(0);
        p.loadHidden(null);
    }

    private SettingsPage(NativeHome home) {
        super(home, "Settings");

        // Left: a big preview of whichever setting is focused.
        left = new LinearLayout(home);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setClipChildren(false);
        hero = new FrameLayout(home);
        left.addView(hero, new LinearLayout.LayoutParams(HERO, HERO));
        heroTitle = Kit.text(home, Theme.Type.TITLE, Theme.LABEL);
        heroTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Theme.S5;
        left.addView(heroTitle, tp);
        heroDesc = Kit.paragraph(home, Theme.Type.BODY, Theme.LABEL_2, "");
        heroDesc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = Theme.S3;
        left.addView(heroDesc, dp);
        // Swaps shrink towards the tile's foot, so the preview seems to settle in place.
        left.setPivotX(LEFT_W / 2f);
        left.setPivotY(HERO);
        content.addView(left, Ui.at(Theme.PAGE_X, 100, LEFT_W, -2));

        // Right: section headers over cards of rows, in a window the list scrolls through.
        list = new LinearLayout(home);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        int y = 0;
        for (Map.Entry<String, List<Item>> g : model().entrySet()) {
            TextView head = Kit.section(home, g.getKey());
            head.setGravity(Gravity.BOTTOM);
            head.setPadding(Theme.S1 + Theme.S3, 0, 0, Theme.S2);
            list.addView(head, new LinearLayout.LayoutParams(-1, HEAD_H));
            y += HEAD_H;
            FrameLayout card = Kit.card(home);
            card.setClipChildren(false);
            LinearLayout rows = new LinearLayout(home);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setClipChildren(false);
            rows.setPadding(Theme.S1, Theme.S1, Theme.S1, Theme.S1);
            int h = Theme.S1;
            for (Item it : g.getValue()) {
                if (rows.getChildCount() > 0) {
                    // Hairlines start under the titles, like tvOS, and hide around the focus.
                    it.divider = Kit.separator(home);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 1);
                    sp.leftMargin = Theme.S3 + TILE + Theme.S3;
                    sp.rightMargin = Theme.S3;
                    rows.addView(it.divider, sp);
                    h += 1;
                }
                it.y = y + h;
                rows.addView(rowFor(it), new LinearLayout.LayoutParams(-1, Theme.ROW));
                h += Theme.ROW;
                items.add(it);
            }
            h += Theme.S1;
            card.addView(rows, new FrameLayout.LayoutParams(-1, h));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, h);
            cp.bottomMargin = CARD_GAP;
            list.addView(card, cp);
            y += h + CARD_GAP;
        }
        // Extra room at the end so the last rows can scroll up off the bottom edge.
        listH = y + 110;
        FrameLayout window = new FrameLayout(home);
        window.addView(list, Ui.at(Theme.S3, 0, LIST_W, listH));
        content.addView(window, Ui.at(LIST_X - Theme.S3, 0, LIST_W + 2 * Theme.S3, VIEW_H));
    }

    // ---- model ----------------------------------------------------------------------------

    private Map<String, List<Item>> model() {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        List<Item> homeRows = new ArrayList<>();
        homeRows.add(value(R.drawable.ic_st_order, Theme.BLUE, "App Order",
                () -> Apps.smart() ? "Smart" : "Manual",
                () -> Sheet.open(home, "App Order", of(
                        new Sheet.Option("Manual" + (Apps.smart() ? "" : "  ✓"), then(() -> setOrder("manual"))),
                        new Sheet.Option("Smart" + (Apps.smart() ? "  ✓" : ""), then(() -> setOrder("smart"))))),
                "Smart puts the apps you usually open at this time of day first. Manual keeps your own arrangement."));
        homeRows.add(value(R.drawable.ic_st_hidden, Theme.GRAY, "Hidden Apps",
                () -> {
                    if (hiddenApps == null) return "";
                    int n = hiddenApps.size();
                    return n == 0 ? "None" : n + (n == 1 ? " app" : " apps");
                },
                () -> loadHidden(this::hiddenSheet),
                "Apps you’ve hidden from Home, including Amazon extras hidden by default. Choose one to bring it back."));
        homeRows.add(action(Kind.ACTION, R.drawable.ic_st_reset, Theme.RED, "Reset Home Layout",
                () -> Sheet.open(home, "Reset Home Layout?", of(
                        new Sheet.Option("Reset", then(() -> {
                            Prefs.remove("order");
                            Prefs.remove("hidden");
                            home.loadApps(home.focusedPkg());
                            home.toast("Home layout reset");
                            loadHidden(null);
                        })),
                        new Sheet.Option("Cancel", () -> {}))),
                "Puts every app back in its original place and unhides your apps. Folders are kept."));
        groups.put("Home", homeRows);

        List<Item> shelfRows = new ArrayList<>();
        shelfRows.add(value(R.drawable.ic_in_star, Theme.ORANGE, "TMDB API Key",
                () -> Tmdb.enabled() ? "On" : "Off",
                () -> TmdbKey.menu(home, this::refresh),
                "Movie and show artwork for the Top Shelf, from The Movie Database. Get a free key at themoviedb.org › Settings › API."));
        // Region only matters to the Top Shelf's TMDB titles.
        if (Tmdb.enabled()) {
            shelfRows.add(value(R.drawable.ic_st_region, Theme.TEAL, "Region",
                    () -> Tmdb.REGIONS.containsKey(Tmdb.region()) ? Tmdb.REGIONS.get(Tmdb.region()) : Tmdb.region(),
                    this::regionSheet,
                    "Which country’s catalogue the Top Shelf follows for popular titles and where to watch."));
        }
        groups.put("Top Shelf", shelfRows);

        groups.put("Look & Feel", of(
                toggle(R.drawable.ic_st_mood, Theme.INDIGO, "Evening & Night Mood",
                        () -> Prefs.bool("mood", true), () -> Prefs.set("mood", !Prefs.bool("mood", true)),
                        "Warms the colours in the evening and dims them late at night, for watching in a dark room."),
                toggle(R.drawable.ic_st_sound, Theme.ORANGE, "Sounds",
                        Sound::isOn, () -> Sound.set(!Sound.isOn()),
                        "Soft clicks as you move around and select things."),
                value(R.drawable.ic_st_weather, Theme.CYAN, "Weather",
                        () -> Weather.label().replaceFirst("^Weather: ", ""),
                        () -> {
                            Weather.menu(home);
                            Ui.later(400, this::refresh);
                        },
                        "Shows the temperature next to the time. Choose your city, units, or turn it off.")));

        groups.put("Privacy", of(
                toggle(R.drawable.ic_st_vpn, Theme.GREEN, "VPN Status",
                        VpnStatus::shown, () -> VpnStatus.setShown(home, !VpnStatus.shown()),
                        "A shield next to the time while a VPN protects this TV, with the country your traffic appears from. Control Center shows the VPN app; select it to switch servers."),
                toggle(R.drawable.ic_st_alert, Theme.ORANGE, "Alert If VPN Drops",
                        VpnStatus::alerts, () -> VpnStatus.setAlerts(home, !VpnStatus.alerts()),
                        "Tells you straight away if the VPN disconnects, even while you’re watching something in another app.")));

        groups.put("Shortcuts", of(
                action(Kind.ACTION, R.drawable.ic_check, Theme.GREEN, "Set Up AppleFireOS",
                        () -> { close(); home.openSetup(); },
                        "Which features are on, and how to switch on the Home button, Top Shelf and Insights from a computer."),
                action(Kind.ACTION, R.drawable.ic_st_insights, Theme.INDIGO, "TV Insights",
                        () -> { close(); home.openInsights(); },
                        "Screen time, viewing habits, memory use, and one-press ways to make the TV faster."),
                action(Kind.ACTION, R.drawable.ic_st_cc, Theme.GRAY, "Control Center",
                        () -> { close(); new ControlCenter(home).show(); },
                        "Power, sleep timer, Bluetooth and the screensaver. Also opens with Up from the Top Shelf."),
                action(Kind.LINK, R.drawable.ic_st_aerial, Theme.GREEN, "Aerial Screensaver",
                        () -> home.open(new Intent(home, AerialActivity.class)),
                        "Apple’s aerial flyovers, full screen. Any button returns you here."),
                action(Kind.LINK, R.drawable.ic_st_firetv, Theme.GRAPHITE, "Fire TV Settings",
                        home::openFireTvSettings,
                        "Amazon’s full settings: network, display and sound, apps, remotes and more.")));
        return groups;
    }

    @SafeVarargs
    private static <T> List<T> of(T... items) {
        List<T> out = new ArrayList<>();
        Collections.addAll(out, items);
        return out;
    }

    private static Item toggle(int icon, int color, String label, BooleanSupplier on, Runnable flip, String desc) {
        Item it = new Item(icon, color, label, Kind.TOGGLE, desc);
        it.on = on;
        it.run = flip;
        return it;
    }

    private static Item value(int icon, int color, String label, Supplier<String> value, Runnable run, String desc) {
        Item it = new Item(icon, color, label, Kind.VALUE, desc);
        it.value = value;
        it.run = run;
        return it;
    }

    private static Item action(Kind kind, int icon, int color, String label, Runnable run, String desc) {
        Item it = new Item(icon, color, label, kind, desc);
        it.run = run;
        return it;
    }

    /** Runs a choice from a sheet, then refreshes the values shown on this page. */
    private Runnable then(Runnable fn) {
        return () -> {
            fn.run();
            refresh();
        };
    }

    private void setOrder(String mode) {
        Prefs.set("orderMode", mode);
        home.loadApps(home.focusedPkg());
    }

    private void regionSheet() {
        List<Sheet.Option> choices = new ArrayList<>();
        for (Map.Entry<String, String> e : Tmdb.REGIONS.entrySet()) {
            String code = e.getKey();
            choices.add(new Sheet.Option(e.getValue() + (code.equals(Tmdb.region()) ? "  ✓" : ""), then(() -> {
                Prefs.set("region", code);
                Tmdb.setRegion(code);
                home.loadApps(home.focusedPkg());
            })));
        }
        Sheet.open(home, "Top Shelf Region", choices);
    }

    /** Reads which installed apps are hidden (off the main thread), then runs `then` if still open. */
    private void loadHidden(Runnable then) {
        Ui.IO.execute(() -> {
            Set<String> hidden = new HashSet<>(Apps.hidden());
            List<Apps.App> out = new ArrayList<>();
            for (Apps.App a : Apps.installed(home)) if (hidden.contains(a.pkg)) out.add(a);
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
            Ui.post(() -> {
                if (!home.layers.contains(this)) return;
                hiddenApps = out;
                refresh();
                if (then != null) then.run();
            });
        });
    }

    private void hiddenSheet() {
        if (hiddenApps.isEmpty()) {
            home.toast("No hidden apps");
            return;
        }
        List<Sheet.Option> choices = new ArrayList<>();
        for (Apps.App a : hiddenApps) {
            choices.add(new Sheet.Option(a.label, () -> {
                Apps.unhide(a.pkg);
                home.loadApps(home.focusedPkg());
                home.toast(a.label + " is back on Home");
                loadHidden(null);
            }));
        }
        Sheet.open(home, "Show on Home", choices);
    }

    // ---- views ----------------------------------------------------------------------------

    private Row rowFor(Item it) {
        Row r = new Row(home, Row.Style.LIST).title(it.label);
        r.leading(Kit.iconTile(home, it.color, it.icon, TILE), TILE, TILE, false);
        if (it.kind == Kind.TOGGLE) {
            r.toggle(it.on.getAsBoolean());
        } else {
            if (it.kind == Kind.VALUE) {
                it.shown = it.value.get();
                r.value(it.shown);
            }
            r.accessory(it.kind == Kind.LINK ? Row.Accessory.LINK : Row.Accessory.CHEVRON);
        }
        r.setOnClickListener(v -> {
            select(items.indexOf(it));
            activate();
        });
        it.row = r;
        return r;
    }

    /** Values and switches, from the current state (they can change behind the page). */
    private void refresh() {
        for (Item it : items) {
            if (it.value != null) {
                String v = it.value.get();
                if (!v.equals(it.shown)) {
                    it.shown = v;
                    it.row.value(v);
                }
            }
            if (it.on != null) {
                boolean on = it.on.getAsBoolean();
                if (it.row.toggle().isOn() != on) it.row.toggle().set(on, true);
            }
        }
    }

    private void select(int i) {
        i = Math.max(0, Math.min(items.size() - 1, i));
        int prev = sel;
        sel = i;
        if (prev != i) {
            if (prev >= 0) items.get(prev).row.setRowFocused(false, true);
            items.get(i).row.setRowFocused(true, true);
            for (int k = 0; k < items.size(); k++) {
                View d = items.get(k).divider;
                if (d != null) d.setVisibility(k == sel || k - 1 == sel ? View.INVISIBLE : View.VISIBLE);
            }
        }
        // Keep the focused row comfortably on screen.
        int offset = Math.max(0, Math.min(items.get(sel).y - Math.round(VIEW_H * 0.4f), listH - VIEW_H));
        Motion.slideY(list, -offset, 400);
        if (prev == i) return;
        if (prev < 0) {
            preview(items.get(i));
            return;
        }
        // A new fade cancels the last one (and its end action), so quick moves skip the previews in between.
        Motion.spring(left, 0.96f, 120);
        Motion.fade(left, 0, 120, () -> {
            preview(items.get(sel));
            Motion.fade(left, 1, 250, null);
            Motion.spring(left, 1, 350);
        });
    }

    private void preview(Item it) {
        hero.removeAllViews();
        hero.addView(Kit.iconTile(home, it.color, it.icon, HERO), Ui.fill());
        heroTitle.setText(it.label);
        heroDesc.setText(it.desc);
    }

    private void activate() {
        Item it = items.get(sel);
        Sound.select();
        it.run.run();
        if (it.kind == Kind.TOGGLE) it.row.toggle().set(it.on.getAsBoolean(), true);
    }

    @Override
    void onKey(int code, KeyEvent e) {
        // Values can change behind the page (a sheet, the weather menu), so refresh on every key.
        refresh();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = sel + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < items.size()) {
                Sound.move();
                select(next);
            } else {
                Sound.bump();
            }
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            activate();
        }
    }
}
