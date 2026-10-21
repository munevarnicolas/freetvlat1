package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 * Each size is scaled down once (in steps of at most 2×) and drawn pixel for pixel at a whole-pixel position: a GPU
 * shrinking a ~150 px PNG to 40 px in one bilinear step skips pixels, and the edges come out jagged.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** Scaled copies, by name and then by pixel size (width << 16 | height); only touched holding PAINT. */
    private static final Map<String, SparseArray<Bitmap>> SIZED = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, name, b, cx, cy, pointSize, color);
        }
    }

    private static void drawLocked(Canvas canvas, String name, Bitmap b, float cx, float cy, float pointSize, int color) {
        PAINT.setColor(color);
        Bitmap s = sized(name, b, pointSize);
        if (s != null) {
            canvas.drawBitmap(s, Math.round(cx - s.getWidth() / 2f), Math.round(cy - s.getHeight() / 2f), PAINT);
            return;
        }
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    /** The symbol scaled down to `pointSize` (made once per size), or null when it is drawn at full size or larger. */
    private static Bitmap sized(String name, Bitmap b, float pointSize) {
        int w = Math.max(1, Math.round(b.getWidth() * pointSize / PX_PER_POINT));
        int h = Math.max(1, Math.round(b.getHeight() * pointSize / PX_PER_POINT));
        if (w >= b.getWidth() || h >= b.getHeight()) return null;
        SparseArray<Bitmap> sizes = SIZED.get(name);
        if (sizes == null) SIZED.put(name, sizes = new SparseArray<>());
        int key = w << 16 | h;
        Bitmap s = sizes.get(key);
        if (s != null) return s;
        s = b;
        while (s.getWidth() / 2 >= w && s.getHeight() / 2 >= h) s = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
        s = Bitmap.createScaledBitmap(s, w, h, true);
        sizes.put(key, s);
        return s;
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
