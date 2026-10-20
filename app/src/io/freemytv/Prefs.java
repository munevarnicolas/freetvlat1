package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_TV = 4001, KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** Vestel's Netflix button: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_VESTEL_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    static final int[] KEYS = RemoteKeys.VESTEL ? new int[] {KEY_VESTEL_NETFLIX}
            : new int[] {KEY_TV, KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};
    /** A button's choice that isn't an app: live TV (the TV button's default on TCL). */
    static final String LIVE_TV = "live";

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de" or "ru". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_TV: return LIVE_TV;
            case KEY_NETFLIX: case KEY_VESTEL_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;
    // Other brands' TV button (TCL's is KEY_TV above): what it opens ("" = nothing) and a vendor code taught for it.
    private static String tvApp = "";
    private static int tvKey, keysLoads;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Reads the file again if it changed since the last look (called holding keyApps). */
    private static void loadKeys() {
        File f = keysFile();
        long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
        if (stamp == keysStamp) return;
        keysStamp = stamp;
        keysLoads++;
        java.util.Arrays.fill(keyApps, null);
        tvApp = "";
        tvKey = 0;
        if (stamp == 0) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("tv=")) tvApp = line.substring(3);
                else if (line.startsWith("tvkey=")) {
                    int key = number(line.substring(6));
                    tvKey = RemoteKeys.teachable(key) ? key : 0;   // vendor codes only, whatever the file says
                }
                for (int i = 0; i < KEYS.length; i++) {
                    String prefix = KEYS[i] + "=";
                    if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            loadKeys();
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** Other brands' TV button: LIVE_TV, an app, or "" (nothing, the default: the key service then stays off). */
    static String tvApp() {
        synchronized (keyApps) {
            loadKeys();
            return tvApp;
        }
    }

    /** A vendor code taught for other brands' TV button, or 0. */
    static int tvKey() {
        synchronized (keyApps) {
            loadKeys();
            return tvKey;
        }
    }

    /** Other brands' TV button: Android's own key, or the taught one. */
    static boolean isTvKey(int keyCode) {
        // A vendor code is checked before the choice is read: Android's other keys never get that far.
        return keyCode == KeyEvent.KEYCODE_TV || (RemoteKeys.teachable(keyCode) && keyCode == tvKey());
    }

    /** Goes up each time the choices are read again from the file (another process changed them). */
    static int keysLoads() {
        synchronized (keyApps) {
            loadKeys();
            return keysLoads;
        }
    }

    static void setKeyApp(int keyCode, String pkg) { saveKeys(keyCode, pkg, tvApp(), tvKey()); }
    static void setTvApp(String what) { saveKeys(0, null, what, tvKey()); }
    static void setTvKey(int keyCode) { saveKeys(0, null, tvApp(), keyCode); }

    private static void saveKeys(int keyCode, String pkg, String tv, int key) {
        StringBuilder sb = new StringBuilder();
        for (int k : KEYS) sb.append(k).append('=').append(k == keyCode ? pkg : keyApp(k)).append('\n');
        sb.append("tv=").append(tv).append('\n').append("tvkey=").append(key).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
