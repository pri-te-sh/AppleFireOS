package com.applefireos.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entering a TMDB key on the TV, for the Top Shelf's movie and show artwork. Each key is checked
 * with TMDB as you type it; the result to pick appears once TMDB accepts it. (setup.sh can send
 * one instead, which saves typing 32 characters with a remote.)
 */
final class TmdbKey {
    private TmdbKey() {}

    static void enter(NativeHome home, Runnable after) {
        TextEntry.open(home, "TMDB API Key", "Free from themoviedb.org", text -> {
            String k = text.replace(" ", "");
            if (k.length() < 32) return Collections.emptyList();
            List<TextEntry.Result> out = new ArrayList<>();
            out.add(Tmdb.works(k)
                    ? new TextEntry.Result("Use This Key", "TMDB accepted it", k)
                    : new TextEntry.Result("Not Recognised", "Check the key and try again", null));
            return out;
        }, r -> {
            if (r.data == null) return;
            Tmdb.setKey((String) r.data);
            home.toast("Top Shelf artwork is on");
            home.refreshShelf();
            if (after != null) after.run();
        });
    }

    /** The Settings entry: add a key, or replace or remove the current one. */
    static void menu(NativeHome home, Runnable after) {
        if (!Tmdb.enabled()) {
            enter(home, after);
            return;
        }
        Sheet.open(home, "TMDB API Key", java.util.Arrays.asList(
                new Sheet.Option("Change Key", () -> enter(home, after)),
                new Sheet.Option("Remove Key", () -> {
                    Tmdb.setKey("");
                    home.refreshShelf();
                    if (after != null) after.run();
                })));
    }
}
