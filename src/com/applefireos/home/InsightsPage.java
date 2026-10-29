package com.applefireos.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Insights: an Apple-style look at how the TV is used (Screen Time, Habits) and how it's running
 * (Performance: memory, storage, speed-ups). Left/Right switch tabs and Down enters the page;
 * on Performance, Left and Right move between the memory column and the app list.
 * Built entirely from the design system: a PageScaffold with tabs, content in Kit cards, and
 * everything focusable a Row.
 */
final class InsightsPage extends PageScaffold {
    /** The page's area under the tabs, inset by the page margins (the same margin below as above). */
    private static final int W = Ui.STAGE_W - 2 * Theme.PAGE_X,
            H = Ui.STAGE_H - Theme.PAGE_CONTENT_TOP - Theme.PAGE_TOP;
    /** Gutter between cards, and the inset of a card's content. */
    private static final int GAP = Theme.S4, PAD = Theme.S4;
    /**
     * Row's own side padding: cards holding rows inset by PAD minus this, so the rows' contents
     * line up with the text of the cards around them and the focus platter has room to rise.
     */
    private static final int ROW_PAD = 24, LIST_PAD = PAD - ROW_PAD;
    /** App art beside a row's title: a banner's shape at row height. */
    private static final int ART_W = 84, ART_H = 50, TILE = 50;
    private static final int FREE = 0, PROC = 1, ANIM = 2;

    /** Something on the Performance page that Select acts on. */
    private static final class Item {
        final int kind;
        final Row row;
        JSONObject proc;
        /** A proc row's size, and the value it's showing now. */
        String size, shown;

        Item(int kind, Row row) {
            this.kind = kind;
            this.row = row;
        }
    }

    private final FrameLayout page;
    private final List<Item> items = new ArrayList<>();
    private int tab, perfFocus;
    private boolean inContent, closed, freeing, usageLoaded;
    private Map<String, Apps.App> installed = new HashMap<>();
    /** Null without usage access. */
    private ScreenTime week;
    private JSONObject memory;
    private float animation = 1;
    private String freedNote = "";

    static void open(NativeHome home) {
        InsightsPage p = new InsightsPage(home);
        p.open();
        p.load();
    }

    /** Records recent sessions into the usage log without opening the page (on returning home). */
    static void sync(Context c) {
        Ui.IO.execute(() -> ScreenTime.record(c, 2));
    }

    private InsightsPage(NativeHome home) {
        super(home, "Insights");
        tabs("Screen Time", "Habits", "Performance");
        page = new FrameLayout(home);
        page.setClipChildren(false); // focused rows lift past their cards' edges
        content.addView(page, Ui.at(Theme.PAGE_X, 0, W, H));
        paint();
    }

    @Override
    void close() {
        closed = true;
        super.close();
    }

    // ---- data ---------------------------------------------------------------------------

    private void load() {
        Ui.IO.execute(() -> {
            Map<String, Apps.App> byPkg = new HashMap<>();
            for (Apps.App a : Apps.installed(home)) byPkg.put(a.pkg, a);
            Ui.post(() -> installed = byPkg);
            loadMemory();
            List<ScreenTime.Session> log = ScreenTime.record(home, 14);
            ScreenTime st = log == null ? null : new ScreenTime(log, byPkg);
            Ui.post(() -> {
                usageLoaded = true;
                week = st;
                if (!closed && tab < 2) paint();
            });
        });
    }

    private void loadMemory() {
        Ui.IO.execute(() -> {
            JSONObject m = json(Insights.memory(home));
            Ui.post(() -> {
                memory = m;
                animation = (float) m.optDouble("animation", 1);
                if (!closed && tab == 2) paint();
            });
        });
    }

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String mb(double bytes) {
        double gb = 1024.0 * 1024 * 1024;
        return bytes >= gb ? String.format(Locale.US, "%.1f GB", bytes / gb) : Math.round(bytes / (1024 * 1024)) + " MB";
    }

    /** "Last 7 days", or since when, while the launcher's own history is still under a week old. */
    private String coverage() {
        long first = week != null ? week.logStart : 0;
        if (first == 0 || first < System.currentTimeMillis() / 1000 - 7 * 86400) return "Last 7 days";
        return "Since " + new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date(first * 1000));
    }

    // ---- pages --------------------------------------------------------------------------

    private void paint() {
        tabs.select(tab, true);
        caption.setText(tab == 2 ? "Right now" : coverage());
        page.removeAllViews();
        items.clear();
        if (tab == 0) screenTime();
        else if (tab == 1) habits();
        else performance();
        perfFocus = Math.min(perfFocus, Math.max(0, items.size() - 1));
        select(false);
    }

    /** Shows where focus is: the tabs, or a row on the page (lifting it when animate). */
    private void select(boolean animate) {
        tabs.setTabsFocused(!inContent);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            boolean on = inContent && i == perfFocus;
            if (it.kind == PROC) {
                // A closable app says so while focused, beside its size.
                String v = on && it.proc.optBoolean("closable") ? "Close · " + it.size : it.size;
                if (!v.equals(it.shown)) it.row.value(it.shown = v);
            }
            if (it.row.isRowFocused() != on) it.row.setRowFocused(on, animate);
        }
    }

    /** False, with a spinner or a note in the page's place, until there's a week to show. */
    private boolean usageReady() {
        if (!usageLoaded) spinner();
        else if (week == null) notice("Usage history isn’t available yet", "It switches on when this home screen is installed with usage access.");
        else if (week.apps.isEmpty()) notice("Not enough history yet", "Watch something and check back — insights build up over the day.");
        else return true;
        return false;
    }

    private void screenTime() {
        if (!usageReady()) return;
        List<String> top = week.top();
        int weekW = Math.round((W - GAP) * 1.75f / 2.75f);

        LinearLayout chart = card(page, Ui.at(0, 0, weekW, H), PAD, PAD);
        chart.addView(Kit.section(home, "Daily Average"));
        LinearLayout hero = new LinearLayout(home); // baseline-aligned
        TextView avg = Kit.text(home, Theme.Type.DISPLAY, Theme.LABEL, ScreenTime.duration(week.avg));
        avg.setFontFeatureSettings("tnum");
        hero.addView(avg);
        if (week.prevAvg != null) {
            long delta = Math.round((week.avg - week.prevAvg) / week.prevAvg * 100);
            hero.addView(Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL_2,
                    (delta > 0 ? "▲ " : "▼ ") + Math.abs(delta) + "% from last week"), beside(-2, -2, Theme.S3));
        }
        chart.addView(hero, below(-1, -2, Theme.S1));
        chart.addView(new WeekChart(home, week), below(-1, WeekChart.HEIGHT, Theme.S2));
        boolean other = week.apps.size() > 3;
        if (top.size() + (other ? 1 : 0) >= 2) {
            LinearLayout legend = new LinearLayout(home);
            legend.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < top.size(); i++) legendKey(legend, Theme.SERIES[i], week.name(top.get(i)));
            if (other) legendKey(legend, Theme.OTHER, "Other");
            chart.addView(legend, below(-1, -2, Theme.S3));
        }

        // The side card holds rows, so it's inset like a list and its other content indented to match.
        LinearLayout side = card(page, Ui.at(weekW + GAP, 0, W - weekW - GAP, H), LIST_PAD, PAD);
        LinearLayout tiles = new LinearLayout(home);
        tiles.setPadding(ROW_PAD, 0, ROW_PAD, 0);
        String[][] stats = {
                { "Today", ScreenTime.duration(week.today) },
                { "This Week", ScreenTime.duration(week.weekTotal) },
                { "Sessions", String.valueOf(week.sessions) } };
        for (int i = 0; i < stats.length; i++) {
            LinearLayout t = new LinearLayout(home);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(Theme.S3, Theme.S3, Theme.S3, Theme.S3);
            t.setBackground(Kit.well());
            t.setClipToPadding(false);
            t.addView(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, stats[i][0]));
            TextView b = Kit.line(Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL, stats[i][1]));
            b.setFontFeatureSettings("tnum");
            // Long totals may run into the padding, as on the web, rather than be cut short.
            LinearLayout.LayoutParams bp = below(-1, -2, Theme.S1);
            bp.rightMargin = -Theme.S3;
            t.addView(b, bp);
            tiles.addView(t, grow(i == 0 ? 0 : Theme.S2));
        }
        side.addView(tiles);
        side.addView(indented(Kit.section(home, "Most Used")), below(-1, -2, Theme.S4));
        long most = week.apps.get(0).sec;
        for (int i = 0; i < Math.min(6, week.apps.size()); i++) {
            ScreenTime.AppTime a = week.apps.get(i);
            int k = top.indexOf(a.pkg);
            Row row = appRow(a.pkg, a.label, ScreenTime.duration(a.sec),
                    a.sec / (double) most, k >= 0 ? Theme.SERIES[k] : Theme.OTHER);
            side.addView(row, below(-1, -2, i == 0 ? Theme.S1 : 0));
        }
    }

    private void legendKey(LinearLayout legend, int color, String name) {
        View swatch = new View(home);
        swatch.setBackground(Kit.oval(color));
        legend.addView(swatch, beside(16, 16, legend.getChildCount() == 0 ? 0 : Theme.S4));
        legend.addView(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, name), beside(-2, -2, Theme.S1 + 2));
    }

    private void habits() {
        if (!usageReady()) return;
        LinearLayout col = new LinearLayout(home);
        col.setOrientation(LinearLayout.VERTICAL);
        String prime = ScreenTime.hourLabel(week.peak) + "–" + ScreenTime.hourLabel((week.peak + 2) % 24);

        LinearLayout hours = card(col, new LinearLayout.LayoutParams(-1, -2), PAD, PAD);
        hours.addView(Kit.section(home, "When You Watch"));
        hours.addView(Kit.text(home, Theme.Type.TITLE, Theme.LABEL, "Most active " + prime), below(-1, -2, Theme.S1));
        hours.addView(new HoursChart(home, week.perDayHours, week.peak), below(-1, HoursChart.HEIGHT, Theme.S3));
        hours.addView(Kit.text(home, Theme.Type.CAPTION, Theme.LABEL_3, "Average minutes per hour, last 7 days"),
                below(-1, -2, Theme.S1));

        LinearLayout row = new LinearLayout(home);
        Long weekendDiff = week.weekdayAvg > 0 && week.weekendAvg > 0
                ? Math.round((week.weekendAvg - week.weekdayAvg) / week.weekdayAvg * 100) : null;
        ScreenTime.AppTime fav = week.apps.get(0);
        long all = 0;
        for (ScreenTime.AppTime a : week.apps) all += a.sec;
        ScreenTime.Session binge = week.longest;
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(binge.start * 1000);
        tile(row, Theme.INDIGO, R.drawable.ic_in_clock, "Prime Time", prime, "when you watch the most");
        if (weekendDiff == null) {
            tile(row, Theme.ORANGE, R.drawable.ic_in_calendar, "Weekends", "—", "needs a weekday and a weekend");
        } else {
            tile(row, Theme.ORANGE, R.drawable.ic_in_calendar, "Weekends", (weekendDiff >= 0 ? "+" : "−") + Math.abs(weekendDiff) + "%",
                    (weekendDiff >= 0 ? "more" : "less") + " than on weekdays");
        }
        tile(row, Theme.PINK, R.drawable.ic_in_flame, "Longest Binge", ScreenTime.duration(binge.sec),
                week.name(binge.pkg) + " · " + ScreenTime.DAY[day.get(Calendar.DAY_OF_WEEK) - 1]);
        tile(row, Theme.TEAL, R.drawable.ic_in_moon, "Late Nights", String.valueOf(week.late),
                "day" + (week.late == 1 ? "" : "s") + " watching past midnight");
        tile(row, Theme.GREEN, R.drawable.ic_in_star, "Favourite", fav.label, Math.round(fav.sec * 100.0 / all) + "% of your watching");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1);
        rp.topMargin = GAP;
        col.addView(row, rp);
        page.addView(col, Ui.fill());
    }

    private void tile(LinearLayout row, int color, int icon, String title, String value, String note) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) p.leftMargin = GAP;
        LinearLayout t = card(row, p, PAD, PAD);
        t.addView(Kit.iconTile(home, color, icon, 62), new LinearLayout.LayoutParams(62, 62));
        t.addView(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, title), below(-1, -2, Theme.S2));
        t.addView(Kit.line(Kit.text(home, Theme.Type.TITLE, Theme.LABEL, value)), below(-1, -2, Theme.S1));
        t.addView(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, note), below(-1, -2, Theme.S1));
    }

    private void performance() {
        if (memory == null) {
            spinner();
            return;
        }
        long total = Math.max(1, memory.optLong("total")), avail = memory.optLong("avail");
        float used = (total - avail) / (float) total;
        int status = health(used);
        String[] words = status == Theme.GOOD ? new String[] { "Healthy", "Plenty of memory for streaming." }
                : status == Theme.WARNING ? new String[] { "Getting Full", "Freeing memory can make apps open faster." }
                : new String[] { "Low on Memory", "Apps may reload or stutter. Free up memory." };
        int memW = 560;

        LinearLayout mem = card(page, Ui.at(0, 0, memW, H), PAD, PAD);
        mem.setGravity(Gravity.CENTER_HORIZONTAL);
        mem.addView(Kit.section(home, "Memory"), new LinearLayout.LayoutParams(-1, -2));
        MemoryRing ring = new MemoryRing(home, used, status, mb(avail), "free of " + mb(total));
        mem.addView(ring, below(340, 340, Theme.S3));
        ring.sweep();

        LinearLayout state = new LinearLayout(home);
        state.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Kit.text(home, Theme.Type.CALLOUT, Theme.LABEL, status == Theme.GOOD ? "✓" : "!");
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Kit.oval(status));
        state.addView(mark, new LinearLayout.LayoutParams(34, 34));
        state.addView(Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL, words[0]), beside(-2, -2, Theme.S2));
        mem.addView(state, below(-2, -2, Theme.S3));
        TextView note = Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, words[1]);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        mem.addView(note, below(-1, -2, Theme.S1));

        Row button = new Row(home, Row.Style.BUTTON).title(freeing ? "Freeing…" : "Free Up Memory");
        button.icon(R.drawable.ic_in_bolt);
        if (freeing) button.setAlpha(0.7f);
        mem.addView(button, below(-2, Theme.BUTTON, Theme.S4));
        item(FREE, button);
        TextView freed = Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2, freedNote);
        freed.setGravity(Gravity.CENTER_HORIZONTAL);
        mem.addView(freed, below(-1, -2, Theme.S3));

        LinearLayout col = new LinearLayout(home);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout procs = card(col, new LinearLayout.LayoutParams(-1, 0, 1), LIST_PAD, Theme.S3);
        procs.addView(indented(Kit.section(home, "Using Memory" + (memory.optBoolean("canListProcs") ? "" : " — not available"))),
                below(-1, -2, Theme.S1));
        JSONArray list = memory.optJSONArray("procs");
        int n = list == null ? 0 : Math.min(7, list.length());
        long maxKb = n > 0 ? Math.max(1, list.optJSONObject(0).optLong("kb")) : 1;
        for (int i = 0; i < n; i++) procRow(procs, list.optJSONObject(i), maxKb, i == 0);
        if (n == 0) {
            procs.addView(indented(Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2,
                    "Per-app memory appears once the app is installed with permission.")), below(-1, -2, Theme.S2));
        }

        LinearLayout row = new LinearLayout(home);
        row.setClipChildren(false);
        JSONObject disk = memory.optJSONObject("storage");
        long diskTotal = disk == null ? 1 : Math.max(1, disk.optLong("total")), diskFree = disk == null ? 1 : disk.optLong("free");
        double diskUsed = 1 - diskFree / (double) diskTotal;
        // The two cards share one height (the taller one's), so their edges line up; rows sit centred.
        LinearLayout storage = card(row, tall(0), LIST_PAD, Theme.S2);
        storage.setGravity(Gravity.CENTER_VERTICAL);
        Row sr = new Row(home, Row.Style.LIST).title("Storage").subtitle(mb(diskFree) + " free of " + mb(diskTotal));
        sr.leading(Kit.iconTile(home, Theme.GRAY, R.drawable.ic_in_disk, TILE), TILE, TILE, false);
        sr.textColumn().addView(Kit.meter(home, diskUsed, health((float) diskUsed), 8), 1, below(-1, 8, Theme.S1));
        storage.addView(sr, new LinearLayout.LayoutParams(-1, -2));

        String speed = animation == 0 ? "Off" : animation <= 0.5f ? "Fast" : "Normal";
        LinearLayout anim = card(row, tall(GAP), LIST_PAD, Theme.S2);
        anim.setGravity(Gravity.CENTER_VERTICAL);
        Row ar = new Row(home, Row.Style.LIST).title("Animations")
                .subtitle(speed.equals("Fast") ? "Menus open twice as fast" : speed.equals("Off") ? "Instant, no motion" : "Standard speed")
                .value(speed).accessory(Row.Accessory.CHEVRON);
        ar.leading(Kit.iconTile(home, Theme.BLUE, R.drawable.ic_in_speed, TILE), TILE, TILE, false);
        anim.addView(ar, new LinearLayout.LayoutParams(-1, -2));
        item(ANIM, ar);
        col.addView(row, below(-1, -2, GAP));
        page.addView(col, Ui.at(memW + GAP, 0, W - memW - GAP, H));
    }

    /** The status colour for a share in use: memory, and storage the same way. */
    private static int health(float used) {
        return used < 0.7f ? Theme.GOOD : used < 0.85f ? Theme.WARNING : Theme.CRITICAL;
    }

    private void procRow(LinearLayout procs, JSONObject p, long maxKb, boolean first) {
        String pkg = p.optString("pkg");
        String name = ScreenTime.nameOf(installed, pkg, p.optString("label"));
        String size = Math.round(p.optLong("kb") / 1024.0) + " MB";
        Row row = appRow(pkg, name, size, p.optLong("kb") / (double) maxKb, Theme.ACCENT);
        procs.addView(row, below(-1, -2, first ? Theme.S1 : 0));
        Item it = item(PROC, row);
        it.proc = p;
        it.size = it.shown = size;
    }

    /**
     * An app as a list row: its art (never recoloured), its name with a meter beneath showing its
     * share, and the amount on the right.
     */
    private Row appRow(String pkg, String name, String value, double share, int color) {
        Row row = new Row(home, Row.Style.LIST).title(name).value(value);
        Apps.App a = installed.get(pkg);
        View art = a != null ? Kit.appArt(home, a, Theme.R_SMALL)
                : Kit.appArt(home, null, null, Theme.GRAPHITE, name, Theme.R_SMALL);
        row.leading(art, ART_W, ART_H, false);
        row.textColumn().addView(Kit.meter(home, share, color, 6), below(-1, 6, Theme.S1));
        return row;
    }

    private Item item(int kind, Row row) {
        Item it = new Item(kind, row);
        items.add(it);
        row.setOnClickListener(x -> {
            inContent = true;
            perfFocus = items.indexOf(it);
            select(true);
            activate();
        });
        return it;
    }

    // ---- actions ------------------------------------------------------------------------

    private void activate() {
        if (perfFocus >= items.size()) return;
        Item it = items.get(perfFocus);
        Sound.select();
        if (it.kind == FREE) freeUp(it);
        else if (it.kind == ANIM) animationMenu();
        else closeMenu(it.proc);
    }

    private void freeUp(Item button) {
        if (freeing) return;
        freeing = true;
        button.row.title("Freeing…");
        button.row.setAlpha(0.7f);
        Ui.IO.execute(() -> {
            JSONObject r = json(Insights.freeUp(home));
            Ui.post(() -> {
                freeing = false;
                long freed = r.optLong("freed");
                JSONArray apps = r.optJSONArray("closed");
                int n = apps == null ? 0 : apps.length();
                freedNote = freed > 0 ? "Freed " + mb(freed) + " · closed " + n + " app" + (n == 1 ? "" : "s")
                        : "Already running lean — nothing to free";
                home.toast(freed > 0 ? "Freed " + mb(freed) + " of memory" : "Nothing to free right now");
                loadMemory();
            });
        });
    }

    private void animationMenu() {
        float cur = animation;
        Sheet.open(home, "Animations", Arrays.asList(
                new Sheet.Option("Normal" + (cur > 0.5f ? "  ✓" : ""), () -> setAnimation(1)),
                new Sheet.Option("Fast" + (cur > 0 && cur <= 0.5f ? "  ✓" : ""), () -> setAnimation(0.5f)),
                new Sheet.Option("Off" + (cur == 0 ? "  ✓" : ""), () -> setAnimation(0))));
    }

    private void setAnimation(float scale) {
        Ui.IO.execute(() -> {
            boolean ok = Insights.setAnimation(home, scale);
            Ui.post(() -> {
                if (!ok) {
                    home.toast("Needs permission — granted at install");
                    return;
                }
                animation = scale;
                if (!closed && tab == 2) paint();
            });
        });
    }

    private void closeMenu(JSONObject p) {
        String pkg = p.optString("pkg"), name = ScreenTime.nameOf(installed, pkg, p.optString("label"));
        if (!p.optBoolean("closable")) {
            home.toast(name + " is part of the system and stays running");
            return;
        }
        Sheet.open(home, name, Collections.singletonList(
                new Sheet.Option("Close (frees about " + Math.round(p.optLong("kb") / 1024.0) + " MB)", () -> {
                    Ui.IO.execute(() -> Insights.close(home, pkg));
                    Ui.later(1200, this::loadMemory);
                    home.toast("Closed " + name);
                })));
    }

    // ---- building blocks ----------------------------------------------------------------

    /**
     * A Kit card added to `parent` at `lp`, returning the column inside it that holds its content.
     * padX/padY: PAD for text cards, LIST_PAD across for cards of rows. Nothing else is drawn
     * behind or around the card; it doesn't clip, so a focused row inside can lift past it.
     */
    private LinearLayout card(ViewGroup parent, ViewGroup.LayoutParams lp, int padX, int padY) {
        FrameLayout card = Kit.card(home);
        card.setClipChildren(false);
        LinearLayout col = new LinearLayout(home);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padX, padY, padX, padY);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(card, lp);
        return col;
    }

    /** Lines text in a card of rows up with the rows' own contents. */
    private static TextView indented(TextView t) {
        t.setPadding(ROW_PAD, 0, ROW_PAD, 0);
        return t;
    }

    private void spinner() {
        page.addView(new Spinner(home), new FrameLayout.LayoutParams(56, 56, Gravity.CENTER));
    }

    private void notice(String title, String body) {
        LinearLayout n = new LinearLayout(home);
        n.setOrientation(LinearLayout.VERTICAL);
        n.setGravity(Gravity.CENTER);
        n.addView(Kit.text(home, Theme.Type.TITLE, Theme.LABEL, title));
        TextView p = Kit.text(home, Theme.Type.BODY, Theme.LABEL_2, body);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setMaxWidth(760);
        n.addView(p, below(-2, -2, Theme.S3));
        page.addView(n, Ui.fill());
    }

    private static LinearLayout.LayoutParams below(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }

    private static LinearLayout.LayoutParams beside(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        return p;
    }

    /** Takes the row's remaining width. */
    private static LinearLayout.LayoutParams grow(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.leftMargin = left;
        return p;
    }

    /** Like grow, and as tall as the row's tallest child. */
    private static LinearLayout.LayoutParams tall(int left) {
        LinearLayout.LayoutParams p = grow(left);
        p.height = -1;
        return p;
    }

    /** The loading ring: a faint circle with an arc turning once every 0.9 s. */
    private static final class Spinner extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        Spinner(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5);
        }

        @Override
        protected void onDraw(Canvas c) {
            oval.set(2.5f, 2.5f, getWidth() - 2.5f, getHeight() - 2.5f);
            paint.setColor(Theme.FILL);
            c.drawOval(oval, paint);
            paint.setColor(Theme.LABEL);
            c.drawArc(oval, -135 + SystemClock.uptimeMillis() % 900 * 0.4f, 90, false, paint);
            postInvalidateOnAnimation();
        }
    }

    // ---- keys ---------------------------------------------------------------------------

    @Override
    void onKey(int code, KeyEvent e) {
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (!inContent) {
            if (dx != 0) {
                int next = tab + dx;
                if (next < 0 || next >= tabs.count()) {
                    Sound.bump();
                    return;
                }
                tab = next;
                perfFocus = 0;
                Sound.move();
                paint();
            } else if (dy > 0) {
                if (items.isEmpty()) {
                    Sound.bump();
                    return;
                }
                inContent = true;
                Sound.move();
                select(true);
            }
            return;
        }
        if (dx != 0 || dy != 0) {
            // Performance: the left column is the button and Animations, the right one the app list.
            Item el = perfFocus < items.size() ? items.get(perfFocus) : null;
            boolean inList = el != null && el.kind == PROC;
            List<Item> left = new ArrayList<>(), right = new ArrayList<>();
            for (Item it : items) (it.kind == PROC ? right : left).add(it);
            Item next = null;
            if (dx > 0 && !inList && !right.isEmpty()) next = right.get(0);
            else if (dx < 0 && inList) next = left.get(0);
            else if (dy != 0) {
                List<Item> col = inList ? right : left;
                int i = col.indexOf(el) + dy;
                if (i < 0) {
                    inContent = false;
                    Sound.move();
                    select(true);
                    return;
                }
                if (i < col.size()) next = col.get(i);
            }
            if (next == null) {
                Sound.bump();
                return;
            }
            perfFocus = items.indexOf(next);
            Sound.move();
            select(true);
        } else if (NativeHome.isSelect(code) && e.getRepeatCount() == 0) {
            activate();
        }
    }

    @Override
    boolean backWithin() {
        if (!inContent) return false;
        inContent = false;
        Sound.move();
        select(true);
        return true;
    }
}
