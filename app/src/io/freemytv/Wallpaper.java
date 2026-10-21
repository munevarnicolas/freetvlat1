package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bing's photo of the day behind the clock: the "Photo of the day" theme in Home Settings (with no picks). No key needed.
 * Checked at most every 6 h while the home screen is visible; the last photo stays on disk, so it shows right away
 * after a restart and offline. Built once, in the background, into a Backdrop with the dark scrims.
 */
final class Wallpaper {
    interface Listener { void onWallpaper(); }

    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=";
    private static final long EVERY = 6 * 60 * 60_000L, RETRY = 30 * 60_000L;
    private static Wallpaper instance;

    private final File image, info;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Backdrop backdrop;
    private volatile String caption = "";
    private volatile Listener listener;
    private long failedAt;
    private boolean busy;

    static synchronized Wallpaper get(Context c) {
        if (instance == null) instance = new Wallpaper(c.getApplicationContext());
        return instance;
    }

    private Wallpaper(Context c) {
        image = new File(c.getFilesDir(), "wallpaper.jpg");
        info = new File(c.getFilesDir(), "wallpaper.json");
    }

    /** The photo with its scrims, or null (not downloaded yet). */
    Backdrop backdrop() { return backdrop; }

    /** What is in the photo and whose it is, as Bing gives it. */
    String caption() { return caption; }

    void setListener(Listener l) { listener = l; }

    /** Loads the saved photo, then checks for a new one if the last check is old (on the main thread; works in the background). */
    void refreshIfStale() {
        if (!Prefs.theme().equals("photo") || busy) return;
        long now = System.currentTimeMillis();
        boolean stale = !info.exists() || now - info.lastModified() >= EVERY;
        if (backdrop != null && (!stale || now - failedAt < RETRY)) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (backdrop == null && image.exists()) load();
                if (stale && System.currentTimeMillis() - failedAt >= RETRY) fetch();
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

    private void fetch() {
        try {
            JSONObject day = new JSONObject(HomeData.get(API + Ui.locale().toLanguageTag())).getJSONArray("images").getJSONObject(0);
            String date = day.optString("startdate");
            JSONObject saved = saved();
            if (image.exists() && saved != null && date.equals(saved.optString("date"))) {
                //noinspection ResultOfMethodCallIgnored
                info.setLastModified(System.currentTimeMillis());   // the same photo: checked
                return;
            }
            download("https://www.bing.com" + day.getString("url"));
            JSONObject next = new JSONObject().put("date", date).put("caption", day.optString("copyright"));
            Files.write(info.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
            load();
        } catch (Exception e) {
            failedAt = System.currentTimeMillis();
            AppLog.w("wallpaper: " + e.getMessage());
        }
    }

    private JSONObject saved() {
        try {
            return new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void download(String url) throws IOException {
        File part = new File(image.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(image)) throw new IOException("couldn't save the image");
    }

    /** Decodes the saved photo (≥ 1920 wide) and builds the background with its scrims. */
    private void load() {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(image.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            Backdrop built = Backdrop.make(b, false);
            JSONObject saved = saved();
            caption = saved == null ? "" : saved.optString("caption");
            backdrop = built;
            AppLog.w("wallpaper: " + bounds.outWidth + "×" + bounds.outHeight + " " + caption);
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onWallpaper();
            });
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            image.delete();
            //noinspection ResultOfMethodCallIgnored
            info.delete();   // fetch it again next time
            AppLog.w("wallpaper image: " + e.getMessage());
        }
    }
}
