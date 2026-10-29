package com.applefireos.home;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * VPN status: a shield and the country's flag beside the time while a VPN carries the TV's
 * traffic, the VPN app and location in Control Center, and an alert when it drops or comes back.
 * The location is the one the VPN app names in its own notification ("India - Delhi"), which for
 * a virtual location is what websites see; failing that, ipwho.is looks up the address. Both only
 * ever see the VPN's address.
 */
final class VpnStatus {
    private static final int GREEN = Theme.GOOD, AMBER = Theme.WARNING;
    // Old or reserved codes that Locale still names (DD is "Germany" too), never a VPN location.
    private static final Set<String> NOT_COUNTRIES = new HashSet<>(java.util.Arrays.asList(
            "AN", "BU", "CS", "DD", "EU", "EZ", "FX", "NT", "QO", "SU", "TP", "UN", "XA", "XB", "YD", "YU", "ZR", "ZZ"));

    private static NativeHome home;
    private static LinearLayout box;
    private static ImageView shield;
    private static TextView flagText, codeText;
    private static boolean on, known;
    private static String pkg, label, cc, city, ip;
    private static int token;

    private VpnStatus() {}

    static View view(NativeHome h) {
        home = h;
        box = new LinearLayout(h);
        box.setGravity(Gravity.CENTER_VERTICAL);
        shield = new ImageView(h);
        box.addView(shield, new LinearLayout.LayoutParams(34, 34));
        flagText = Kit.text(h, Theme.Type.HEADLINE, Theme.LABEL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
        fp.leftMargin = 8;
        box.addView(flagText, fp);
        codeText = Kit.overArt(Kit.text(h, Theme.Type.CALLOUT, Theme.LABEL));
        codeText.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = 8;
        box.addView(codeText, cp);
        View rule = new View(h);
        rule.setBackgroundColor(Theme.LABEL_4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(2, 36);
        rp.leftMargin = 18;
        rp.rightMargin = 22;
        box.addView(rule, rp);
        box.setVisibility(View.GONE);
        Vpn.watch(h, () -> Ui.post(() -> refresh(true)));
        Vpn.setAlerts(h, alerts());
        refresh(false);
        return box;
    }

    static boolean shown() {
        return Prefs.bool("vpnStatus", true);
    }

    static void setShown(NativeHome h, boolean show) {
        Prefs.set("vpnStatus", show);
        paint();
    }

    static boolean alerts() {
        return Prefs.bool("vpnAlerts", true);
    }

    static void setAlerts(NativeHome h, boolean value) {
        Prefs.set("vpnAlerts", value);
        Vpn.setAlerts(h, value);
    }

    /** The VPN app last seen running, so "VPN Off" can offer to open it again. */
    private static JSONObject lastApp() {
        return Prefs.object("vpnApp");
    }

    private static String country(String code) {
        return new Locale("", code).getDisplayCountry(Locale.ENGLISH);
    }

    /** "DE" → the German flag (two regional-indicator letters, drawn as one emoji). */
    static String flag(String code) {
        if (code == null || !code.matches("[A-Z]{2}")) return "";
        return new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
    }

    private static List<String[]> names;

    /** Finds a country named in a VPN app's status line: "India - Delhi" → { IN, Delhi }. */
    static String[] fromNote(String text) {
        if (text == null || text.isEmpty()) return null;
        if (names == null) {
            List<String[]> list = new ArrayList<>();
            for (String code : Locale.getISOCountries()) {
                if (NOT_COUNTRIES.contains(code)) continue;
                String n = country(code);
                if (!n.isEmpty() && !n.equals(code)) list.add(new String[] { n, code });
            }
            Collections.sort(list, (a, b) -> b[0].length() - a[0].length()); // "Niger" mustn't win over "Nigeria"
            names = list;
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        for (String[] n : names) {
            int at = lower.indexOf(n[0].toLowerCase(Locale.ENGLISH));
            if (at < 0) continue;
            // Whatever else the line says, minus the usual words, is the city.
            String rest = (text.substring(0, at) + " , " + text.substring(at + n[0].length()))
                    .replaceAll("(?i)\\(?\\s*virtual location\\s*\\)?", "").replaceAll("(?i)connected( to)?", "");
            String found = "";
            for (String part : rest.split("[,·\\-–|:()]")) {
                if (!part.trim().isEmpty()) {
                    found = part.trim();
                    break;
                }
            }
            return new String[] { n[1], found };
        }
        return null;
    }

    private static String place() {
        return city != null && !city.isEmpty() ? city + ", " + country(cc) : country(cc);
    }

    // ---- reading the state ----------------------------------------------------------------

    /** changed: a VPN just came up or went away (wait a moment: switching servers drops it briefly). */
    static void refresh(boolean changed) {
        int mine = ++token;
        Ui.IO.execute(() -> {
            if (changed) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            JSONObject s;
            try {
                s = new JSONObject(Vpn.state(home));
            } catch (Exception e) {
                return;
            }
            boolean nowOn = s.optBoolean("on");
            String newCc = null, newCity = null, newIp = null;
            if (nowOn) {
                JSONObject note = s.optJSONObject("note");
                String[] named = fromNote(note == null ? null : note.optString("text"));
                try {
                    JSONObject g = lookup();
                    if (g != null && g.optBoolean("success", true)) {
                        newIp = g.optString("ip", null);
                        newCc = g.optString("country_code", null);
                        newCity = g.optString("city", null);
                    }
                } catch (Exception ignored) {
                    // Use what the app said.
                }
                if (named != null) {
                    newCc = named[0];
                    newCity = named[1];
                }
            }
            final String fc = newCc, fcity = newCity, fip = newIp;
            Ui.post(() -> {
                if (mine != token) return;
                boolean was = on, wasKnown = known;
                on = nowOn;
                known = true;
                pkg = s.optString("pkg", null);
                label = s.optString("label", "VPN");
                cc = fc;
                city = fcity;
                ip = fip;
                if (on && pkg != null) {
                    try {
                        Prefs.set("vpnApp", new JSONObject().put("pkg", pkg).put("label", label));
                    } catch (Exception ignored) {
                        // Not remembered.
                    }
                }
                paint();
                if (changed && wasKnown && was != on && alerts()) announce(on);
            });
        });
    }

    private static JSONObject lookup() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://ipwho.is/").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(Images.readAll(in), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    // ---- showing it ---------------------------------------------------------------------

    private static void paint() {
        if (box == null) return;
        boolean off = !on && lastApp() != null;
        box.setVisibility(shown() && (on || off) ? View.VISIBLE : View.GONE);
        shield.setImageResource(on ? R.drawable.ic_vpn_on : R.drawable.ic_vpn_off);
        shield.setColorFilter(on ? GREEN : AMBER, PorterDuff.Mode.SRC_IN);
        flagText.setText(on ? flag(cc) : "");
        flagText.setVisibility(on && cc != null ? View.VISIBLE : View.GONE);
        codeText.setText(on ? (cc == null ? "" : cc) : "VPN Off");
        codeText.setTextColor(on ? Theme.LABEL : Theme.WARNING);
    }

    /** Control Center's chip: the VPN app and where your traffic appears from; null with no VPN. */
    static View chip(NativeHome h) {
        JSONObject last = lastApp();
        if (!on && last == null) return null;
        LinearLayout c = new LinearLayout(h);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(14, 0, 22, 0);
        c.setBackground(Kit.chrome(999));
        ImageView i = new ImageView(h);
        i.setImageResource(on ? R.drawable.ic_vpn_on : R.drawable.ic_vpn_off);
        i.setColorFilter(on ? GREEN : AMBER, PorterDuff.Mode.SRC_IN);
        c.addView(i, new LinearLayout.LayoutParams(34, 34));
        LinearLayout text = new LinearLayout(h);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView b = Kit.line(Kit.text(h, Theme.Type.FOOTNOTE, Theme.LABEL));
        TextView em = Kit.line(Kit.text(h, Theme.Type.CAPTION, Theme.LABEL_2));
        if (on) {
            b.setText(label);
            em.setText(cc != null ? flag(cc) + " " + place() + (ip != null ? " · " + ip : "") : "Connected");
        } else {
            b.setText("VPN Off");
            em.setText("Open " + last.optString("label", "your VPN") + " to reconnect");
        }
        text.addView(b);
        text.addView(em);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = 12;
        c.addView(text, tp);
        return c;
    }

    /** Opens the VPN app, to switch servers or reconnect. */
    static void openApp(NativeHome h) {
        String p = on && pkg != null ? pkg : lastApp() != null ? lastApp().optString("pkg", null) : null;
        if (p != null) h.launch(p);
    }

    /** The banner when the VPN drops or comes back (a system toast if an app is in front). */
    private static void announce(boolean up) {
        JSONObject last = lastApp();
        String app = up ? label : last != null ? last.optString("label", "VPN") : "VPN";
        String title = up ? app + " connected" : "VPN disconnected";
        String sub = up ? (cc != null ? "Your traffic appears from " + place() + " " + flag(cc) : "Your traffic is protected")
                : app + " stopped. This TV's traffic is no longer protected.";
        if (!home.isHomeResumed()) {
            if (!up) Toast.makeText(home, "VPN disconnected. This TV's traffic is no longer protected.", Toast.LENGTH_LONG).show();
            return;
        }
        Sound.open();
        Glass g = new Glass(home, 34, true);
        LinearLayout row = new LinearLayout(home);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(28, 22, 40, 22);
        ImageView i = new ImageView(home);
        i.setImageResource(up ? R.drawable.ic_vpn_on : R.drawable.ic_vpn_off);
        i.setColorFilter(up ? GREEN : AMBER, PorterDuff.Mode.SRC_IN);
        row.addView(i, new LinearLayout.LayoutParams(52, 52));
        LinearLayout text = new LinearLayout(home);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView b = Kit.text(home, Theme.Type.HEADLINE, Theme.LABEL);
        b.setText(title);
        TextView e = Kit.text(home, Theme.Type.FOOTNOTE, Theme.LABEL_2);
        e.setText(sub);
        text.addView(b);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
        ep.topMargin = 4;
        text.addView(e, ep);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = 22;
        row.addView(text, tp);
        g.addView(row, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = 44;
        home.overlay.addView(g, lp);
        Motion.popIn(g, -24);
        Ui.later(up ? 4000 : 8000, () -> Motion.close(g, 0.96f, () -> home.overlay.removeView(g)));
    }
}
