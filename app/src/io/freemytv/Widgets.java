package io.freemytv;

/** The widgets there are, in the order Home Settings offers them (after "None"). */
final class Widgets {
    private Widgets() {}

    static final String NONE = "none";

    static final CryptoWidget CRYPTO = new CryptoWidget();
    static final TailscaleWidget TAILSCALE = new TailscaleWidget();
    static final Widget[] ALL = {CRYPTO, TAILSCALE};

    static Widget byId(String id) {
        for (Widget w : ALL) if (w.id().equals(id)) return w;
        return null;
    }

    private static volatile Widget chosen;
    private static volatile int chosenPrefs = -1;

    /** The one picked in Home Settings; null for none. Asked on every frame: read again only when a setting changes. */
    static Widget chosen() {
        int v = Prefs.version;
        if (v != chosenPrefs) {
            chosen = byId(Prefs.widget());
            chosenPrefs = v;
        }
        return chosen;
    }

    /** The chosen one, if it has something to show now. */
    static Widget shown() {
        Widget w = chosen();
        return w != null && w.visible() ? w : null;
    }

    /** "none" and the ids of the widgets this TV can show, in order: what Home Settings cycles through. */
    static String[] choices(android.content.Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(NONE);
        for (Widget w : ALL) if (w.available(c)) out.add(w.id());
        return out.toArray(new String[0]);
    }
}
