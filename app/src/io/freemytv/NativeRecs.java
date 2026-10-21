package io.freemytv;

import io.freemytv.Recs.Pick;
import io.freemytv.Recs.Listener;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-published Android TV previews and Watch Next, restricted to the current shelf. */
final class NativeRecs {

    private static final String READ = "android.permission.READ_TV_LISTINGS";
    private static final long REFRESH_MS = 60_000L;
    // Bound the row and its artwork queue even with a large shelf.
    private static final int MAX_PER_APP = 24, MAX_PICKS = 120;
    private static NativeRecs instance;

    static synchronized NativeRecs get(Context c) {
        if (instance == null) instance = new NativeRecs(c.getApplicationContext());
        return instance;
    }

    private static final class Shelf {
        final Set<String> packages;
        final List<String> choice;

        Shelf(List<String> packages, List<String> choice) {
            this.packages = new HashSet<>(packages);
            this.choice = choice;
        }
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> imageFailedAt = new HashMap<>();
    private volatile Shelf shelf = new Shelf(Collections.emptyList(), null);
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile Set<String> requestedArtwork;
    private volatile boolean fetching, dirty = true, light;
    private volatile long fetchedAt;
    private volatile String error = "";
    private String shelfStamp = "", languageStamp = "";
    private final Runnable refreshPublished = this::refreshIfStale;

    private NativeRecs(Context c) {
        context = c;
        ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                dirty = true;
                main.removeCallbacks(refreshPublished);
                main.postDelayed(refreshPublished, 1000); // publishers often update a channel one row at a time
            }
        };
        try {
            c.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
            c.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            // Some TV firmwares restrict observers; the periodic refresh still works.
            AppLog.w("recommendation observer unavailable");
        }
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { invalidate(); }
        }, packages);
    }

    static boolean canRead(Context c) { return c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED; }
    boolean hasPicks() { return !picks.isEmpty(); }
    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    void invalidate() { dirty = true; refreshIfStale(); }

    static JSONObject status(Context c) {
        try (FileInputStream in = new FileInputStream(new File(c.getFilesDir(), "app-recs-status.json"))) {
            byte[] bytes = new byte[8192];
            int n = in.read(bytes);
            return n <= 0 ? new JSONObject() : new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    private void writeStatus(Map<String, Integer> counts) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "app-recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("apps", new JSONObject(counts))
                    .put("error", error).put("at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Package resolution stays on the feed executor, including the built-in shelf. */
    private List<String> shelfApps(List<String> choice) {
        if (choice == null) return HomeApps.defaultShelfPackages(context);
        List<String> packages = new ArrayList<>(choice);
        packages.removeIf(pkg -> HomeApps.launchIntent(context.getPackageManager(), pkg) == null);
        return packages;
    }

    private Set<String> shelfPackages() {
        Shelf cached = shelf;
        List<String> choice = Prefs.shelf();
        // A reset to the built-in shelf waits for its eligibility check; edits hide removed apps immediately.
        if (choice == null) return cached.choice == null ? cached.packages : Collections.emptySet();
        Set<String> packages = new HashSet<>(cached.packages);
        packages.retainAll(choice);
        return packages;
    }

    List<Pick> now() { return now(null); }

    List<Pick> now(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!canRead(context)) return out;
        Set<String> shelf = shelfPackages();
        for (Pick p : picks) {
            if (shelf.contains(p.pkg) && (kind == null || ("continue".equals(kind) ? p.resume : kind.equals(p.kind)))) {
                out.add(p);
            }
        }
        return out;
    }

    static String key(Pick p) { return p.source + ":" + p.id; }
    static String serviceName(Pick p) { return p == null || p.app == null ? "" : p.app; }

    /** Restrict published intents to their owning app, including explicit components and selectors. */
    Intent intent(Pick p) {
        try {
            Intent i = Intent.parseUri(p.link, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() != null && !p.pkg.equals(i.getComponent().getPackageName())) return null;
            i.setSelector(null);
            i.setPackage(p.pkg);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setClipData(null);
            return i;
        } catch (Exception e) { return null; }
    }

    void refreshIfStale() {
        if (!Prefs.recommendations().equals("apps") || fetching) return;
        List<String> choice = Prefs.shelf();
        String stamp = (choice == null ? "default" : choice.toString()) + canRead(context);
        long now = System.currentTimeMillis();
        if (!dirty && stamp.equals(shelfStamp) && Ui.language.equals(languageStamp)
                && now >= fetchedAt && now - fetchedAt < REFRESH_MS) return;
        shelfStamp = stamp;
        languageStamp = Ui.language;
        dirty = false;
        fetching = true;
        io.execute(() -> {
            try {
                List<String> shelf = shelfApps(choice);
                Map<String, List<Pick>> byApp = new LinkedHashMap<>();
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<String> failures = new ArrayList<>();
                for (String pkg : shelf) { byApp.put(pkg, new ArrayList<>()); counts.put(pkg, 0); }
                if (canRead(context)) {
                    read(TvContract.WatchNextPrograms.CONTENT_URI, true, byApp, failures);
                    read(TvContract.PreviewPrograms.CONTENT_URI, false, byApp, failures);
                }
                // Round robin gives each shelf app a card before taking a second from any app.
                List<Pick> list = new ArrayList<>();
                for (int row = 0; row < MAX_PER_APP && list.size() < MAX_PICKS; row++) {
                    for (String pkg : shelf) {
                        List<Pick> app = byApp.get(pkg);
                        if (list.size() < MAX_PICKS && row < app.size()) list.add(app.get(row));
                    }
                }
                for (String pkg : shelf) counts.put(pkg, byApp.get(pkg).size());
                Set<String> artwork = new HashSet<>();
                for (Pick p : list) if (p.backdrop != null) artwork.add(p.backdrop);
                synchronized (images) { imageFailedAt.keySet().retainAll(artwork); }
                this.shelf = new Shelf(shelf, choice);
                picks = list;
                error = failures.isEmpty() ? "" : String.join("; ", failures);
                fetchedAt = System.currentTimeMillis();
                writeStatus(counts);
                AppLog.w("app recommendations: " + counts + (error.isEmpty() ? "" : " (" + error + ")"));
                notifyListeners();
            } catch (RuntimeException e) {
                error = "App recommendations unavailable";
                fetchedAt = System.currentTimeMillis();
                AppLog.w("app recommendations: " + e.getClass().getSimpleName());
            } finally {
                fetching = false;
            }
            if (dirty) main.postDelayed(refreshPublished, 1000);
        });
    }

    private void read(Uri uri, boolean resume, Map<String, List<Pick>> byApp, List<String> failures) {
        String[] projection = {"_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri", "type",
                resume ? "last_engagement_time_utc_millis" : "weight", "browsable"};
        try (Cursor rows = context.getContentResolver().query(uri, projection, null, null,
                resume ? "last_engagement_time_utc_millis DESC" : "weight DESC")) {
            if (rows == null) return;
            Map<String, String> labels = new HashMap<>();
            while (rows.moveToNext()) {
                if (resume && rows.getInt(8) == 0) continue;
                String pkg = rows.getString(1);
                List<Pick> app = byApp.get(pkg);
                if (app == null || app.size() >= MAX_PER_APP) continue;
                String title = rows.getString(2), link = rows.getString(5);
                if (title == null || title.trim().isEmpty() || link == null || link.isEmpty()) continue;
                boolean duplicate = false;
                for (Pick other : app) if (link.equals(other.link)) { duplicate = true; break; }
                if (duplicate) continue;
                if (!labels.containsKey(pkg)) {
                    try { labels.put(pkg, context.getPackageManager().getApplicationLabel(
                            context.getPackageManager().getApplicationInfo(pkg, 0)).toString()); }
                    catch (PackageManager.NameNotFoundException e) { continue; }
                }
                Pick p = new Pick();
                p.id = rows.getLong(0);
                p.source = resume ? "watch-next" : "preview";
                p.pkg = pkg;
                p.app = labels.get(pkg);
                p.title = title;
                p.link = link;
                p.resume = resume;
                p.kind = kind(rows.getInt(6));
                p.backdrop = rows.getString(3);
                p.thumbnail = rows.getString(4);
                if (p.backdrop == null || p.backdrop.isEmpty()) p.backdrop = rows.getString(4);
                p.meta = p.app;
                if (intent(p) != null) app.add(p);
            }
        } catch (RuntimeException e) {
            // One provider table failing must not hide the other table's recommendations.
            failures.add(resume ? "Watch Next unavailable" : "Preview channels unavailable");
            AppLog.w("app recommendation query: " + e.getClass().getSimpleName());
        }
    }

    static String kind(int type) {
        switch (type) {
            case TvContract.PreviewPrograms.TYPE_MOVIE: return "movie";
            case TvContract.PreviewPrograms.TYPE_TV_SERIES:
            case TvContract.PreviewPrograms.TYPE_TV_SEASON:
            case TvContract.PreviewPrograms.TYPE_TV_EPISODE: return "tv";
            case TvContract.PreviewPrograms.TYPE_TRACK:
            case TvContract.PreviewPrograms.TYPE_ALBUM:
            case TvContract.PreviewPrograms.TYPE_ARTIST:
            case TvContract.PreviewPrograms.TYPE_PLAYLIST:
            case TvContract.PreviewPrograms.TYPE_STATION: return "music";
            default: return "video";
        }
    }

    List<Pick> wanted() {
        Map<String, Pick> out = new LinkedHashMap<>();
        for (String kind : new String[] {null, "movie", "tv", "continue"}) {
            List<Pick> list = now(kind);
            for (int i = 0; i < Math.min(3, list.size()); i++) out.put(key(list.get(i)), list.get(i));
        }
        return new ArrayList<>(out.values());
    }

    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        Set<String> shelf = shelfPackages();
        if (canRead(context)) for (Pick p : picks) if (shelf.contains(p.pkg) && readyImage(p) != null) out.add(p);
        return out;
    }

    void prefetchAll() {
        // Limit background work to visible cards; providers can publish hundreds of items.
        List<Pick> wanted = wanted();
        prioritize(wanted, 0, wanted.size());
        for (Pick p : wanted) backdrop(p);
    }

    void prioritize(List<Pick> list, int from, int to) {
        Set<String> keys = new HashSet<>();
        for (int i = from; i < to; i++) if (list.get(i).backdrop != null) keys.add(list.get(i).backdrop);
        requestedArtwork = keys;
    }

    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) { images.clear(); }
    }

    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty()) return null;
        synchronized (images) {
            Backdrop b = images.get(p.backdrop);
            if (b != null) return b;
            Long failed = imageFailedAt.get(p.backdrop);
            if (failed != null && System.currentTimeMillis() - failed < 300_000L) return null;
            if (!pending.add(p.backdrop)) return null;
        }
        artworkIo.execute(() -> {
            try {
                Set<String> wanted = requestedArtwork;
                if (wanted == null || wanted.contains(p.backdrop)) loadImage(p);
            }
            finally { synchronized (images) { pending.remove(p.backdrop); } }
        });
        return null;
    }

    private File imageFile(Pick p) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(p.backdrop.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new File(new File(context.getCacheDir(), "native-recs"), name.toString());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    File readyImage(Pick p) {
        if (p.backdrop == null || p.backdrop.isEmpty()) return null;
        File f = imageFile(p);
        return f.isFile() ? f : null;
    }

    private void download(String artwork, File dest) throws IOException {
        if (dest.isFile()) { dest.setLastModified(System.currentTimeMillis()); return; }
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("image cache unavailable");
        Uri uri = Uri.parse(artwork);
        HttpURLConnection connection = null;
        InputStream stream;
        if ("https".equals(uri.getScheme())) {
            connection = (HttpURLConnection) new URL(artwork).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) { connection.disconnect(); throw new IOException("image unavailable"); }
            stream = connection.getInputStream();
        } else if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
            stream = context.getContentResolver().openInputStream(uri);
        } else { throw new IOException("unsupported artwork URI"); }
        File tmp = new File(dir, dest.getName() + ".part");
        try (InputStream in = stream; FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("image unavailable");
            byte[] buffer = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 8 * 1024 * 1024) throw new IOException("image too large");
                out.write(buffer, 0, n);
            }
        } finally { if (connection != null) connection.disconnect(); }
        if (!tmp.renameTo(dest)) throw new IOException("couldn't save image");
        File[] files = dir.listFiles();
        if (files != null) {
            long bytes = 0;
            for (File file : files) bytes += file.length();
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int count = files.length;
            for (File file : files) {
                if (count <= 128 && bytes <= 64 * 1024 * 1024L) break;
                if (file.equals(dest)) continue;
                long size = file.length();
                if (file.delete()) { count--; bytes -= size; }
            }
        }
    }

    private Bitmap decode(File file) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw new IOException("invalid image");
        opts.inSampleSize = 1;
        while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2048) opts.inSampleSize *= 2;
        opts.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
        if (bitmap == null) throw new IOException("invalid image");
        return bitmap;
    }

    private Bitmap tryArtwork(String url, File file) {
        if (url == null || url.isEmpty()) return null;
        try { download(url, file); return decode(file); }
        catch (IOException | RuntimeException e) {
            file.delete();
            new File(file.getPath() + ".part").delete();
            return null;
        }
    }

    private void loadImage(Pick p) {
        File file = imageFile(p);
        try {
            Bitmap bitmap = tryArtwork(p.backdrop, file);
            if (bitmap == null && p.thumbnail != null && !p.thumbnail.equals(p.backdrop)) bitmap = tryArtwork(p.thumbnail, file);
            if (bitmap == null) throw new IOException("artwork unavailable");
            boolean forLight = light;
            Backdrop b = Backdrop.make(bitmap, forLight);
            if (forLight != light) return;
            synchronized (images) {
                images.put(p.backdrop, b);
                imageFailedAt.remove(p.backdrop);
                while (images.size() > (forLight ? 10 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            file.delete();
            new File(file.getPath() + ".part").delete();
            synchronized (images) { imageFailedAt.put(p.backdrop, System.currentTimeMillis()); }
            AppLog.w("app recommendation artwork unavailable: " + p.pkg + " " + Uri.parse(p.backdrop).getHost());
        }
    }

    void trim() {
        Set<String> keep = new HashSet<>();
        for (Pick p : wanted()) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) { images.keySet().retainAll(keep); imageFailedAt.clear(); }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }
}
