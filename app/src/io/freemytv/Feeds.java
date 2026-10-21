package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Soccer and News tabs: the newest videos of a few YouTube channels, from their public feeds (no key). The feed
 * often answers 404 or 500 for a while, so each channel keeps its last good list (saved, so the tabs open with videos)
 * and a failed one is tried again 2 minutes later. Checked every 20 minutes while the home screen is visible.
 * Shorts are left out. The cards play in the YouTube app (Recs.link).
 */
final class Feeds {
    /** Name on the card, channel id. Edit freely. */
    private static final String[][] SOCCER = {
        {"Premier League", "UCG5qGWdu8nIRZqJ_GgDwQ-w"},
        {"UEFA", "UCyGa1YEx9ST66rYrJTGIKOw"},
        {"CBS Sports Golazo", "UCET00YnetHT7tOpu12v8jxg"},     // Champions League highlights (in the US)
    };
    private static final String[][] NEWS = {
        {"CBC News", "UCuFFtHWoLl5fauMMD5Ww2jA"},
        {"CTV News", "UCi7Zk9baY1tvdlgxIML8MXg"},
        {"Global News", "UChLtXXpo4Ge1ReTEboVvTDg"},
        {"CP24", "UCs3o4RhBiP2wcwqkZR2QVLw"},
    };
    /** config.properties: your club ("Arsenal") comes first on the Soccer tab, and its own channel joins the list. */
    private static final String TEAM = Config.SOCCER_TEAM.trim();
    private static final String TEAM_CHANNEL = Config.SOCCER_TEAM_CHANNEL.trim().matches("UC[\\w-]{22}")
            ? Config.SOCCER_TEAM_CHANNEL.trim() : "";
    private static final long EVERY = 20 * 60_000L, RETRY = 2 * 60_000L, RECENT = 7 * 86_400_000L;
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    private static Feeds instance;

    static synchronized Feeds get(Context c) {
        if (instance == null) instance = new Feeds(c.getApplicationContext());
        return instance;
    }

    private static final class Video {
        Recs.Pick pick;
        long published;
        boolean team;           // from the club's channel, or the club in the title
        boolean highlights;
        String channel;
    }

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Channel id → its newest videos (newest first). */
    private final Map<String, List<Video>> videos = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>(), failedAt = new HashMap<>();
    private volatile boolean fetching;
    private volatile List<Recs.Pick> soccer = new ArrayList<>(), news = new ArrayList<>();

    private Feeds(Context c) {
        cache = c.getSharedPreferences("feeds", Context.MODE_PRIVATE);
        for (String[] ch : channels()) {
            String saved = cache.getString(ch[1], null);
            if (saved == null) continue;
            try {
                videos.put(ch[1], parseSaved(ch, new JSONArray(saved)));
                fetchedAt.put(ch[1], cache.getLong(ch[1] + ".at", 0));
            } catch (Exception ignored) {
            }
        }
        rebuild();
    }

    private static List<String[]> channels() {
        List<String[]> out = new ArrayList<>();
        if (!TEAM_CHANNEL.isEmpty()) out.add(new String[] {TEAM.isEmpty() ? "YouTube" : TEAM, TEAM_CHANNEL, "soccer"});
        for (String[] ch : SOCCER) out.add(new String[] {ch[0], ch[1], "soccer"});
        for (String[] ch : NEWS) out.add(new String[] {ch[0], ch[1], "news"});
        return out;
    }

    /** This tab's cards (Recs.SOCCER or Recs.NEWS); the same objects until the lists change. */
    List<Recs.Pick> now(String kind) {
        return Recs.SOCCER.equals(kind) ? soccer : news;
    }

    /** Fetches the channels whose list is old (in the background); `changed` runs when a tab got new videos. */
    void refreshIfStale(Runnable changed) {
        if (fetching) return;
        long now = System.currentTimeMillis();
        List<String[]> todo = new ArrayList<>();
        for (String[] ch : channels()) {
            Long at = fetchedAt.get(ch[1]), failed = failedAt.get(ch[1]);
            if (failed != null && now - failed >= 0 && now - failed < RETRY) continue;
            if (at == null || now - at > EVERY || now < at) todo.add(ch);
        }
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            boolean any = false;
            List<String> failed = new ArrayList<>();
            try {
                for (String[] ch : todo) {
                    try {
                        String xml = fetchTrying("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch[1]);
                        List<Video> list = parse(ch, xml);
                        synchronized (videos) { videos.put(ch[1], list); }
                        long at = System.currentTimeMillis();
                        fetchedAt.put(ch[1], at);
                        failedAt.remove(ch[1]);
                        cache.edit().putString(ch[1], save(list)).putLong(ch[1] + ".at", at).apply();
                        any = true;
                    } catch (Exception e) {
                        failedAt.put(ch[1], System.currentTimeMillis());
                        failed.add(ch[0] + " (" + e.getMessage() + ")");
                    }
                }
                AppLog.w("feeds: " + (todo.size() - failed.size()) + " of " + todo.size() + " channels updated"
                        + (failed.isEmpty() ? "" : ", again soon: " + String.join(", ", failed)));
                if (any && rebuild()) changed.run();
            } finally {
                fetching = false;
            }
        });
    }

    /** Picks each tab's cards again; true if either changed. */
    private boolean rebuild() {
        List<Video> s = new ArrayList<>(), n = new ArrayList<>();
        synchronized (videos) {
            for (String[] ch : channels()) {
                List<Video> list = videos.get(ch[1]);
                if (list != null) ("soccer".equals(ch[2]) ? s : n).addAll(list);
            }
        }
        long since = System.currentTimeMillis() - RECENT;
        List<Recs.Pick> newSoccer = chooseSoccer(recent(s, since)), newNews = chooseNews(recent(n, since));
        boolean changed = !keys(newSoccer).equals(keys(soccer)) || !keys(newNews).equals(keys(news));
        soccer = newSoccer;
        news = newNews;
        return changed;
    }

    /** The past week's, newest first (all of them if there's nothing that recent). */
    private static List<Video> recent(List<Video> all, long since) {
        List<Video> out = new ArrayList<>();
        for (Video v : all) if (v.published >= since) out.add(v);
        if (out.isEmpty()) out.addAll(all);
        Collections.sort(out, (a, b) -> Long.compare(b.published, a.published));
        return out;
    }

    /**
     * Your club's newest video first; then highlights with your club in the title (from the league channels), the
     * newest highlights of everyone else, and the rest, newest first.
     */
    private static List<Recs.Pick> chooseSoccer(List<Video> list) {
        int n = Recs.CARDS;
        List<Recs.Pick> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Video v : list) {
            if (v.team && used.add(v.pick.video)) {
                out.add(v.pick);
                break;
            }
        }
        for (Video v : list) if (out.size() < n && v.team && v.highlights && used.add(v.pick.video)) out.add(v.pick);
        for (Video v : list) if (out.size() < n && v.highlights && used.add(v.pick.video)) out.add(v.pick);
        for (Video v : list) if (out.size() < n && used.add(v.pick.video)) out.add(v.pick);
        return out;
    }

    /** The newest from each channel first (a few different stories), then the next newest. */
    private static List<Recs.Pick> chooseNews(List<Video> list) {
        int n = Recs.CARDS;
        List<Recs.Pick> out = new ArrayList<>();
        Set<String> channels = new HashSet<>();
        for (Video v : list) if (out.size() < n && channels.add(v.channel)) out.add(v.pick);
        for (Video v : list) if (out.size() < n && !out.contains(v.pick)) out.add(v.pick);
        return out;
    }

    private static List<String> keys(List<Recs.Pick> list) {
        List<String> out = new ArrayList<>();
        for (Recs.Pick p : list) out.add(p.video);
        return out;
    }

    // MARK: - Feed

    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("<yt:videoId>([\\w-]{6,20})</yt:videoId>");
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link rel=\"alternate\" href=\"([^\"]+)\"");
    private static final Pattern PUBLISHED = Pattern.compile("<published>([^<]+)</published>");

    private static List<Video> parse(String[] ch, String xml) {
        List<Video> out = new ArrayList<>();
        Matcher e = ENTRY.matcher(xml);
        while (e.find() && out.size() < 10) {
            String entry = e.group(1);
            Matcher id = ID.matcher(entry), title = TITLE.matcher(entry), link = LINK.matcher(entry);
            Matcher published = PUBLISHED.matcher(entry);
            if (!id.find() || !title.find() || !published.find()) continue;
            if (link.find() && link.group(1).contains("/shorts/")) continue;
            long at = time(published.group(1));
            if (at == 0) continue;
            out.add(video(ch, id.group(1), unescape(title.group(1)), at));
        }
        return out;
    }

    private static Video video(String[] ch, String id, String rawTitle, long published) {
        Video v = new Video();
        v.channel = ch[0];
        v.published = published;
        String title = clean(rawTitle);
        // "HIGHLIGHTS | Arsenal v Bayern" → "Arsenal v Bayern", and "Highlights" goes to the line below.
        Matcher m = Pattern.compile("^(?:match\\s+)?highlights?\\s*[|:\\-–]\\s*", Pattern.CASE_INSENSITIVE).matcher(title);
        boolean prefixed = m.find() && m.end() < title.length();
        if (prefixed) title = title.substring(m.end());
        v.highlights = prefixed || title.toLowerCase(Locale.ROOT).contains("highlight");
        v.team = !TEAM_CHANNEL.isEmpty() && ch[1].equals(TEAM_CHANNEL)
                || !TEAM.isEmpty() && title.toLowerCase(Locale.ROOT).contains(TEAM.toLowerCase(Locale.ROOT));
        Recs.Pick p = new Recs.Pick();
        p.kind = "video";
        p.video = id;
        p.tab = "soccer".equals(ch[2]) ? Recs.SOCCER : Recs.NEWS;
        p.title = title;
        p.backdrop = "/yt-" + id + ".jpg";
        p.meta = (v.highlights ? Ui.tr("Melhores momentos", "Highlights") + " · " : "") + ch[0] + " · YouTube";
        p.app = "youtube";
        p.pkg = YOUTUBE;
        p.detailed = true;
        v.pick = p;
        return v;
    }

    /** The card's font has no emoji. */
    private static String clean(String title) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            boolean emoji = cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF)
                    || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3;
            if (!emoji) out.appendCodePoint(cp);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    private static long time(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // MARK: - Saved lists

    private static String save(List<Video> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Video v : list) a.put(new JSONObject().put("v", v.pick.video).put("t", v.pick.title).put("at", v.published)
                .put("h", v.highlights));
        return a.toString();
    }

    private static List<Video> parseSaved(String[] ch, JSONArray a) {
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.optString("v").matches("[\\w-]{6,20}")) continue;
            Video v = video(ch, o.optString("v"), (o.optBoolean("h") ? "Highlights | " : "") + o.optString("t"),
                    o.optLong("at"));
            out.add(v);
        }
        return out;
    }

    /** The feed answers 404 or 500 to most requests some hours (also from a computer), then works on a later try. */
    private static String fetchTrying(String url) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return fetch(url);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(2000); } catch (InterruptedException stop) { break; }
            }
        }
        throw last != null ? last : new IOException("interrupted");
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        // YouTube turns away the feed request without a browser's name (Android's own is "Dalvik/2.1.0").
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko)");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
