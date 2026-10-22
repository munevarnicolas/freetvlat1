package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, via TMDB
 * (an open movie database that knows what streams where in each country). At least 25 movies and 25 shows, renewed every 12 hours.
 *
 * <p>Which titles qualify: the ones TMDB lists on a known streaming service (Netflix, Prime Video, Disney+, Max, Apple
 * TV...) in your country ({@code tmdb_region}). The service does NOT have to be installed on the TV:
 * <ul>
 *   <li>{@link Pick#app} is the title's service (shown on the card, e.g. "Netflix");</li>
 *   <li>{@link Pick#pkg} is that service's package, only when it is installed: it is what "Watch" opens, so a pick
 *       with {@code pkg == null} is shown but can't be launched. If the title is on several services, an installed
 *       one is preferred.</li>
 * </ul>
 *
 * <p>Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the
 * log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;                    // "movie" or "tv"
        String title, backdrop, poster, overview = "", meta = "";
        String app;                     // the title's service in this region ("netflix"...), or null
        String pkg;                     // that service's package if it is installed on this TV (what "Watch" opens)
        double rating;
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 12 * 3600_000L;   // the lists are fetched again every 12 hours
    private static final long RETRY_AFTER_FAILURE = 10 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 6;

    /** Carousel: at least 25 movies and 25 shows (30 asked for), the Explore tab alternates both (up to EXPLORE_MAX). */
    private static final int PER_KIND = 30, EXPLORE_MAX = 40, DISCOVER_PAGES = 3;
    private static final int TRENDING_PER_KIND = 10, TRENDING_CANDIDATES = 20;
        private static final int MAX_IMAGES_ON_DISK = 48;
    
    private static final int MORNING = 0, AFTERNOON = 1, NIGHT = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};

    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }

    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR, CO). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // built and ready to draw (least recently drawn goes first)
    private final Set<String> inFlight = new HashSet<>();   // picks whose details and image are being fetched
    private final Set<String> pending = new HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", "");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
            // A damaged cache is the same as no cache: the list is fetched again.
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    // MARK: - Status (for the settings screen)

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The status is informational only.
        }
    }

    String error() { return error; }

    static String periodLabel() {
        String[] labels = Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE
                : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL;
        return labels[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MORNING : hour >= 12 && hour < 18 ? AFTERNOON : NIGHT;
    }

    // MARK: - What the screens ask for

    /**
     * The carousel of a tab: movies only ("movie"), shows only ("tv") or both alternating (null, Explore). At least 25
     * per kind, refreshed every 12 hours. A title whose details are already known must be on a known service in your
     * region (it doesn't need that service to be installed).
     */
    List<Pick> carousel(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;     // no TMDB key: no recommendations
        for (Pick p : picks) {
            boolean sameKind = kind == null || kind.equals(p.kind);
            if (sameKind && (!p.detailed || p.app != null)) out.add(p);
            if (kind == null && out.size() >= EXPLORE_MAX) break;
        }
        return out;
    }

    /**
     * Details and image for these picks (what the carousel shows and what is next to it), one at a time in the
     * background; the listeners hear when they are ready.
     */
    void ensure(List<Pick> window) {
        if (!hasKey(context) || inBackoff(System.currentTimeMillis())) return;
        final List<Pick> todo = new ArrayList<>();
        synchronized (inFlight) {
            for (Pick p : window) if (needsWork(p) && inFlight.add(key(p))) todo.add(p);
        }
        if (todo.isEmpty()) return;
        io.execute(() -> {
            try {
                completePicks(todo);
                save();
            } finally {
                synchronized (inFlight) {
                    for (Pick p : todo) inFlight.remove(key(p));
                }
                notifyListeners();
            }
        });
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        if (p == null || p.app == null) return "";
        String name = SERVICE_NAME.get(p.app);
        return name == null ? "" : name;
    }

    /** The first cards of the three tabs (Explore, Movies, Shows), without repeats: what the home screen opens with. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            List<Pick> list = carousel(kind);
            for (int i = 0; i < Math.min(4, list.size()); i++) if (seen.add(key(list.get(i)))) out.add(list.get(i));
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    /**
     * Screensaver: the picks on a known service with an image already on disk, starting with this hour's (the featured
     * one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Set<String> franchises = new HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && franchises.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && p.app != null && hasImage(p) && franchises.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    // MARK: - Images

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /** Frees memory: only Explore's picks stay (the home screen opens on that tab, so its cards are never gray). */
    void trim() {
        Set<String> keep = new HashSet<>();
        List<Pick> first = carousel(null);
        for (int i = 0; i < Math.min(4, first.size()); i++) if (first.get(i).backdrop != null) keep.add(first.get(i).backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    // MARK: - Refreshing

    /** True when a pick still needs its details, or (if it has a service) its image. */
    private boolean needsWork(Pick p) {
        return !p.detailed || (p.app != null && !hasImage(p));
    }

    private boolean inBackoff(long now) {
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        return failedAt != 0 && sinceFail >= 0 && sinceFail < RETRY_AFTER_FAILURE;
    }

    /** With a key: fetches the lists if they are 12 hours old (or the source changed); then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country or installed streaming-app set refetches
        boolean newSource = stamp != keyStamp;
        if (!newSource && inBackoff(now)) return;
        final int currentPeriod = period(now);
        final boolean stale = newSource || now - listAt > LIST_EVERY || now < listAt
                || picks.isEmpty() || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= needsWork(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(currentPeriod);
                String failure = completePicks(wanted());
                save();
                if (failure != null) throw new IOException(failure);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = message(e);
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching || inBackoff(System.currentTimeMillis())) return;
        final List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (needsWork(p) && todo.size() < 30) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            try {
                String failure = completePicks(todo);
                save();
                if (failure != null) {
                    error = failure;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + failure);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /**
     * Details and image for each pick. One failing title (gone from TMDB, an image that won't download) doesn't stop
     * the others. Returns the last error if every one of them failed, otherwise null.
     */
    private String completePicks(List<Pick> list) {
        int failures = 0;
        String last = "";
        for (Pick pick : list) {
            try {
                if (!pick.detailed) fetchDetails(pick);
                if (pick.app != null) download(pick);
            } catch (Exception e) {
                failures++;
                last = message(e);
            }
        }
        return !list.isEmpty() && failures == list.size() ? last : null;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB: the list

    /**
     * The list for this time of day: what's trending this week on a known service in your region, then (if there is
     * room) recent releases. Not limited to the streaming apps installed on this TV.
     */
    private void fetchList(int currentPeriod) throws IOException, JSONException {
        Map<Integer, String[]> services = regionServices();
        if (services.isEmpty()) throw new IOException("no known streaming service is listed on TMDB for " + REGION);

        List<Pick> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        alternate(list, seen, kindList("movie", services), kindList("tv", services), 2 * PER_KIND);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = currentPeriod;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * Up to PER_KIND titles of one kind: what's trending this week on a known service first (their details say where),
     * then the most popular on the region's known services (the filter itself guarantees the service).
     */
    private List<Pick> kindList(String kind, Map<Integer, String[]> services) throws IOException, JSONException {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Pick p : trending(kind)) if (out.size() < PER_KIND && seen.add(key(p))) out.add(p);
        for (int page = 1; page <= DISCOVER_PAGES && out.size() < PER_KIND; page++) {
            try {
                for (Pick p : popular(kind, services, page)) if (out.size() < PER_KIND && seen.add(key(p))) out.add(p);
            } catch (IOException | JSONException e) {
                // Optional extra: what we have is a good list.
                AppLog.w("recommendations, popular: " + message(e));
                break;
            }
        }
        return out;
    }

    /** TMDB's id → known service, for the streaming services listed in the region (installed on this TV or not). */
    private Map<Integer, String[]> regionServices() throws IOException, JSONException {
        Map<Integer, String[]> out = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION)
                .getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null) out.put(o.getInt("provider_id"), s);
        }
        return out;
    }

    /**
     * What's trending this week ("movie" or "tv") that is on a known service in the region: the details say where, and
     * come back ready. Stops once it has enough.
     */
    private List<Pick> trending(String kind) throws IOException, JSONException {
        JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
        List<Pick> candidates = new ArrayList<>();
        for (int i = 0; i < hot.length() && i < TRENDING_CANDIDATES; i++) add(candidates, hot.getJSONObject(i), kind);
        List<Pick> out = new ArrayList<>();
        for (Pick pick : candidates) {
            if (out.size() >= TRENDING_PER_KIND) break;
            try {
                fetchDetails(pick);
            } catch (IOException | JSONException e) {
                continue;
            }
            if (pick.app != null) out.add(pick);
        }
        return out;
    }

    /** The most popular titles on the region's known services (a page of 20). Their details are fetched later. */
    private List<Pick> popular(String kind, Map<Integer, String[]> services, int page) throws IOException, JSONException {
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        JSONArray results = get("/discover/" + kind + "?language=" + lang() + "&watch_region=" + REGION
                + "&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc&page=" + page
                + "&vote_count.gte=" + (kind.equals("movie") ? 200 : 100)
                + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")).getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) add(out, results.getJSONObject(i), kind);
        return out;
    }

    /** Movies and shows alternating, without repeats, up to `max` in total. */
    private static void alternate(List<Pick> out, Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    // MARK: - TMDB: one title

    /** Title, texts, image, genre, runtime (or seasons) and the service it's on in the region. */
    private void fetchDetails(Pick p) throws IOException, JSONException {
        JSONObject o = get("/" + p.kind + "/" + p.id + "?language=" + lang()
                + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        p.title = title(p, o);
        p.overview = o.optString("overview", "");
        p.poster = o.optString("poster_path", "");
        p.rating = o.optDouble("vote_average", 0);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;

        JSONObject providers = o.optJSONObject("watch/providers");
        JSONObject results = providers == null ? null : providers.optJSONObject("results");
        JSONObject inRegion = results == null ? null : results.optJSONObject(REGION);
        chooseService(p, inRegion == null ? null : inRegion.optJSONArray("flatrate"));

        List<String> parts = new ArrayList<>();
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            addRuntime(parts, o.optInt("runtime"));
        } else {
            addSeasons(parts, o.optInt("number_of_seasons"));
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * The title's service in the region, among the known ones. An installed one is preferred (then `pkg` is set and
     * "Watch" can open it); otherwise the first known one, with `pkg` left null. No known service: `app` stays null and
     * the title isn't shown.
     */
    private void chooseService(Pick p, JSONArray flatrate) {
        p.app = null;
        p.pkg = null;
        for (int i = 0; flatrate != null && i < flatrate.length(); i++) {
            JSONObject provider = flatrate.optJSONObject(i);
            String[] s = provider == null ? null : service(provider.optString("provider_name"));
            if (s == null) continue;
            boolean installed = installed(s[2]);
            if (p.app == null || (installed && p.pkg == null)) {
                p.app = s[1];
                p.pkg = installed ? s[2] : null;
            }
            if (p.pkg != null) break;
        }
    }

    private static void addRuntime(List<String> parts, int minutes) {
        if (minutes <= 0) return;
        boolean hours = minutes >= 60;
        if (Ui.ru) parts.add(hours ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин");
        else if (Ui.en) parts.add(hours ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
        else parts.add(hours ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
    }

    private static void addSeasons(List<String> parts, int seasons) {
        if (seasons <= 0) return;
        if (Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
        else if (Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
        else if (Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
        else parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        if (Ui.en || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || Ui.language.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (IOException | JSONException e) {
            return own;
        }
    }

    // MARK: - Language

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.ru) return "ru-RU";
        if (Ui.es) return "ES".equals(Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    /** TV genres that TMDB returns in English even in other languages. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) {
            if (g[0].equalsIgnoreCase(name)) return g[1];
        }
        return name;
    }

    // MARK: - Services

    /** The known service whose TMDB name matches (see SERVICES), or null. */
    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    /** Changes when the key, the country or the set of installed streaming apps changes: then the list is rebuilt. */
    private long sourceStamp() {
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - TMDB: requests

    private JSONObject get(String path) throws IOException, JSONException {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code == 429) throw new IOException("TMDB rate limit reached, trying again later");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Images (TMDB originals on disk, up to MAX_IMAGES_ON_DISK so the whole list fits; this hour's kept in memory)

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920)
                    + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
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
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 12 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** Deletes download leftovers and the oldest images beyond MAX_IMAGES_ON_DISK. */
    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= MAX_IMAGES_ON_DISK) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_IMAGES_ON_DISK; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject()
                        .put("id", p.id)
                        .put("kind", p.kind)
                        .put("title", p.title)
                        .put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster)
                        .put("overview", p.overview == null ? "" : p.overview)
                        .put("rating", p.rating)
                        .put("meta", p.meta == null ? "" : p.meta)
                        .put("app", p.app == null ? "" : p.app)
                        .put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            cache.edit()
                    .putString("picks", a.toString())
                    .putInt("period", period)
                    .putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp)
                    .putInt("version", CACHE_VERSION)
                    .apply();
        } catch (Exception ignored) {
            // Not saving only means fetching again next time.
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster", "");
            p.overview = o.optString("overview", "");
            p.rating = o.optDouble("rating", 0);
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
