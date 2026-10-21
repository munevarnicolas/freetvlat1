package io.freemytv;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Selects the Android recommendation source; each backend keeps its own feed and image cache. */
final class Recs {
    static final class Pick {
        long id;
        String kind, title, backdrop, meta = "", app, pkg;
        boolean detailed;
        String source, link, thumbnail;
        boolean resume;
    }

    interface Listener { void onRecs(); }

    private static Recs instance;
    private final Context context;
    private final List<Listener> listeners = new ArrayList<>();
    private TmdbRecs tmdb;
    private NativeRecs apps;
    private boolean light;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private Recs(Context c) { context = c; }

    static boolean fromApps() { return Prefs.recommendations().equals("apps"); }
    static boolean enabled() { return !Prefs.recommendations().equals("off"); }
    static boolean hasKey(Context c) { return TmdbRecs.hasKey(c); }
    static boolean canRead(Context c) { return NativeRecs.canRead(c); }
    static String periodLabel() { return TmdbRecs.periodLabel(); }
    static String key(Pick p) { return p.source == null ? TmdbRecs.key(p) : NativeRecs.key(p); }
    static String serviceName(Pick p) { return p != null && p.source != null ? NativeRecs.serviceName(p) : TmdbRecs.serviceName(p); }
    static JSONObject status(Context c) {
        return !enabled() ? new JSONObject() : fromApps() ? NativeRecs.status(c) : TmdbRecs.status(c);
    }

    private NativeRecs apps() {
        if (apps == null) {
            apps = NativeRecs.get(context);
            apps.setLight(light);
            apps.addListener(() -> { if (fromApps()) notifyListeners(); });
        }
        return apps;
    }

    private TmdbRecs tmdb() {
        if (tmdb == null) {
            tmdb = TmdbRecs.get(context);
            tmdb.setLight(light);
            tmdb.addListener(() -> { if (enabled() && !fromApps()) notifyListeners(); });
        }
        return tmdb;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    private void notifyListeners() { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); }

    List<Pick> now() { return now(null); }
    List<Pick> now(String kind) {
        return !enabled() ? new ArrayList<>() : fromApps() ? apps().now(kind) : tmdb().now(kind);
    }
    boolean hasPicks() { return enabled() && fromApps() && apps != null && apps.hasPicks(); }
    List<Pick> wanted() {
        return !enabled() ? new ArrayList<>() : fromApps() ? apps().wanted() : tmdb().wanted();
    }
    List<Pick> showcase() {
        return !enabled() ? new ArrayList<>() : fromApps() ? apps().showcase() : tmdb().showcase();
    }
    Intent intent(Pick p) { return p.source == null ? null : apps().intent(p); }
    Backdrop backdrop(Pick p) {
        return p == null || !enabled() ? null : p.source == null ? tmdb().backdrop(p) : apps().backdrop(p);
    }
    File readyImage(Pick p) { return p.source == null ? tmdb().readyImage(p) : apps().readyImage(p); }
    void refreshIfStale() {
        if (!enabled()) return;
        if (fromApps()) apps().refreshIfStale(); else tmdb().refreshIfStale();
    }
    void invalidate() { if (fromApps()) apps().invalidate(); }
    void prefetchAll() {
        if (!enabled()) return;
        if (fromApps()) apps().prefetchAll(); else tmdb().prefetchAll();
    }
    void prioritize(List<Pick> list, int from, int to) { if (fromApps()) apps().prioritize(list, from, to); }
    void setLight(boolean value) {
        light = value;
        if (apps != null) apps.setLight(value);
        if (tmdb != null) tmdb.setLight(value);
    }
    void trim() {
        if (apps != null) apps.trim();
        if (tmdb != null) tmdb.trim();
    }
}
