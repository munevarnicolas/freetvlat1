package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

/** Opening live TV. Small and on its own: the key process (RemoteKeys) uses it too, without loading HomeApps. */
final class LiveTv {
    private LiveTv() {}

    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx"};

    static boolean isApp(String pkg) {
        for (String app : APPS) if (app.equals(pkg)) return true;
        return false;
    }

    /**
     * Live TV: tune the antenna input directly. MediaTek TV Center otherwise restores the last source used, which can
     * be HDMI, so its explicit activity receives the tuner URI. Other TV apps keep the standard channel-list request.
     */
    static Intent intent(Context c) {
        PackageManager pm = c.getPackageManager();
        String tuner = tunerInput(c);
        Intent mediaTek = launchIntent(pm, "com.mediatek.wwtv.tvcenter");
        if (mediaTek != null && tuner != null) {
            return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tuner))
                    .setComponent(mediaTek.getComponent());
        }
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        if (mediaTek != null) return mediaTek;
        for (String pkg : APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs (TCL: AirPlay), and Android names the hardware ones "…/HW<id>".
     */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String system = null, fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (info.getId().contains("/HW")) return info.getId();
                    if (system == null) system = info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return system != null ? system : fallback;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
