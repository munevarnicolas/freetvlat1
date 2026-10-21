package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The verse of the day at the top of the home screen (config.properties title=verse), from assets/verses.json
 * (tools/make_verses.py: King James Version, short enough for two lines). One a day, in the list's order.
 */
final class Verses {
    private Verses() {}

    static boolean on() {
        return "verse".equalsIgnoreCase(Config.TITLE.trim());
    }

    private static List<String[]> all;

    /** Today's {text, reference}, or null without the list. */
    static synchronized String[] today(Context c) {
        if (all == null) all = load(c);
        if (all.isEmpty()) return null;
        Calendar cal = Calendar.getInstance();
        long day = (cal.getTimeInMillis() + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86_400_000L;
        return all.get((int) (day % all.size()));
    }

    private static List<String[]> load(Context c) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("verses.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            JSONArray a = new JSONArray(bytes.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!o.optString("t").isEmpty()) out.add(new String[] {o.optString("t"), o.optString("r")});
            }
        } catch (Exception e) {
            AppLog.w("verses: " + e.getMessage());
        }
        return out;
    }
}
