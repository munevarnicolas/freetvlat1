package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs only the Netflix button is ours: the system opens Netflix itself (a global key, after the filter).
 * Kalley (MOKA/Realtek R10G) remotes add the Input, Settings, Guide/Live TV and Profile buttons, which the disabled
 * stock launcher used to answer (see dumpsys window policy's mKeyMapping).
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KALLEY = is("Kalley");

    static boolean supported() {
        return TCL || VESTEL || KALLEY;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (KALLEY && onActionKey(c, e)) return true;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /**
     * Kalley/Realtek remotes: the buttons the disabled stock launcher used to handle. Input opens the system input
     * switcher, Settings opens the TV's Settings, Guide opens Live TV, Profile opens this app's settings (the Google
     * TV profile picker lived in the disabled launcher). Consumed even when the target is missing, so the dead
     * stock handlers never see them.
     */
    private static boolean onActionKey(Context c, KeyEvent e) {
        Intent intent;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_TV_INPUT:
                intent = new Intent(ACTION_VIEW_INPUTS);
                break;
            case KeyEvent.KEYCODE_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            case KeyEvent.KEYCODE_GUIDE:
                intent = launchIntent(c.getPackageManager(), "com.realtek.tv");
                break;
            case KeyEvent.KEYCODE_PROFILE_SWITCH:
                intent = new Intent(c, SettingsActivity.class);
                break;
            case KeyEvent.KEYCODE_ALL_APPS:
                intent = new Intent(c, HomeActivity.class).putExtra(HomeActivity.EXTRA_OPEN_APPS, true);
                break;
            default:
                return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && intent != null) {
            AppLog.w("remote action key " + e.getKeyCode() + " -> " + intent);
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
