package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The remote's buttons. On TCL TVs, the stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel) and live TV for the TV button; with it off, the keys still arrive, so we open what the person picked
 * (Settings > Remote Buttons). Disney and Globoplay are opened by the system itself, so they are not listed. The
 * YouTube button (BUTTON_3) never reaches an app's window; only the accessibility service's key filter
 * (RemoteKeyService) sees it. On Vestel TVs only the Netflix button is ours: the system opens Netflix itself (a global
 * key, after the filter). On other brands, only the TV button, and only once the person picks what it opens.
 *
 * The system waits for the key filter on every key, so nothing slow happens on a key: what each button opens is
 * looked up beforehand on a thread of its own, the launch is posted, and each press is decided once, on its down.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel");

    /** The app on screen, from the service's window events ("" until the first one). */
    static volatile String foreground = "";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;
    /** What each choice opens, looked up off the key path. Missing: not looked up yet; NONE: nothing to open. */
    private static final Map<String, Intent> targets = new HashMap<>();
    private static final Intent NONE = new Intent();
    private static int preparedLoads = -1;
    /** The key taken on its down (0: none): its repeats and its up are taken too, whatever changed meanwhile. */
    private static int taken;

    static boolean supported() {
        return TCL || VESTEL;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Other brands need the key service only for a TV button that opens something. */
    static boolean serviceNeeded() {
        return supported() || !Prefs.tvApp().isEmpty();
    }

    /**
     * Opens what this key opens, on its first press. True if the key is ours and there's something to open (it is
     * then consumed). fromService: the accessibility service's filter, which sees the keys before every app.
     *
     * Each press is decided once, on its down: the app on screen and the looked-up targets can change during a press
     * (live TV comes up while the TV button is still held), so the repeats and the up follow the down.
     */
    static boolean onKey(Context c, KeyEvent e, boolean fromService) {
        int raw = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) {
            boolean ours = taken != 0 && raw == taken;
            if (ours && e.getAction() == KeyEvent.ACTION_UP) taken = 0;
            return ours;
        }
        boolean use = press(c, e, fromService);
        if (use) taken = raw;
        else if (taken == raw) taken = 0;
        return use;
    }

    /** The down of a press: true if it's ours, and then what it opens is launched (posted). */
    private static boolean press(Context c, KeyEvent e, boolean fromService) {
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int code = e.getKeyCode();
        String what;
        boolean tv;
        if (supported()) {
            if (TCL && code == KEY_YOUTUBE_TCL) code = Prefs.KEY_YOUTUBE;
            boolean ours = false;
            for (int key : Prefs.KEYS) ours |= key == code;
            if (!ours) return false;
            what = Prefs.keyApp(code);
            tv = TCL && code == Prefs.KEY_TV;
        } else {
            if (!Prefs.isTvKey(code)) return false;
            what = Prefs.tvApp();
            tv = true;
        }
        if (what.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (tv) {
            // Our own screens take the TV button themselves (the screensaver has to close, the settings may be
            // learning it), and inside live TV it does what it always did (TCL: the program guide).
            if (fromService && c.getPackageName().equals(foreground)) return false;
            if (what.equals(Prefs.LIVE_TV) && LiveTv.isApp(foreground)) return false;
        }
        Intent target = target(c, what, !fromService);
        if (target == null) return false;   // nothing to open (or not looked up yet): the key goes on
        int key = code;
        AppLog.w("remote key " + key + " -> " + what);
        MAIN.post(() -> {
            try {
                c.startActivity(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ex) {
                AppLog.w("remote key " + key + ": " + ex.getMessage());
            }
            lookUp(c, what);   // apps and inputs change: fresh for the next press
        });
        return true;
    }

    /** The service saw another window: if the choices changed in the settings, look their targets up again. */
    static void windowChanged(Context c, String pkg) {
        foreground = pkg;
        if (Prefs.keysLoads() != preparedLoads) prepare(c);
    }

    /** Looks up what every button opens now, so the first press already knows. */
    static void prepare(Context c) {
        preparedLoads = Prefs.keysLoads();
        if (supported()) for (int key : Prefs.KEYS) lookUp(c, Prefs.keyApp(key));
        else lookUp(c, Prefs.tvApp());
    }

    /**
     * What this choice opens, or null. now: our own screens, which may look it up right here (the key service never
     * does). "Nothing to open" is looked up again each time, so an app installed later works on the next press.
     */
    private static Intent target(Context c, String what, boolean now) {
        Intent t;
        synchronized (targets) {
            t = targets.get(what);
        }
        if (t == null || t == NONE) {
            if (!now) {
                lookUp(c, what);
                return null;
            }
            t = resolve(c, what);
            synchronized (targets) {
                targets.put(what, t);
            }
        }
        return t == NONE ? null : t;
    }

    private static void lookUp(Context c, String what) {
        if (what.isEmpty()) return;
        Handler h;
        synchronized (targets) {
            if (worker == null) {
                HandlerThread thread = new HandlerThread("remote-keys");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            h = worker;
        }
        h.post(() -> {
            Intent t = resolve(c, what);
            synchronized (targets) {
                targets.put(what, t);
            }
        });
    }

    private static Intent resolve(Context c, String what) {
        Intent t = what.equals(Prefs.LIVE_TV) ? LiveTv.intent(c) : launchIntent(c.getPackageManager(), what);
        return t == null ? NONE : t;
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    /**
     * Keys that can be taught as other brands' TV button: vendor codes only (above Android's own). Android's keys
     * (play/pause, channels, numbers…) belong to the apps and would be taken from every one of them.
     */
    static boolean teachable(int keyCode) {
        return keyCode > KeyEvent.getMaxKeyCode();
    }
}
