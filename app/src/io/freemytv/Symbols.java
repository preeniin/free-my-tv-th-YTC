package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<Integer, PorterDuffColorFilter> FILTERS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) b.setHasMipMap(true);
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
        int w = Math.max(1, Math.round(b.getWidth() * pointSize / PX_PER_POINT));
        int h = Math.max(1, Math.round(b.getHeight() * pointSize / PX_PER_POINT));
        Bitmap sized = sized(name, b, w, h);
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, sized, cx, cy, w, h, color);
        }
    }

    private static final Map<String, Bitmap> SIZED = new HashMap<>();

    /**
     * The symbol scaled to exactly w × h, once, by halving steps (a single 5× bilinear shrink drops detail, and mipmaps
     * blur it): drawn 1:1 on whole pixels, small icons stay crisp, also after the TV scales its 1080p interface to 4K.
     */
    private static Bitmap sized(String name, Bitmap src, int w, int h) {
        String key = name + "@" + w + "x" + h;
        synchronized (SIZED) {
            Bitmap cached = SIZED.get(key);
            if (cached != null) return cached;
            Bitmap b = src;
            while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
                Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
                if (b != src) b.recycle();
                b = half;
            }
            Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
            if (b != src && b != out) b.recycle();
            if (out == src) out = src.copy(Bitmap.Config.ARGB_8888, false);
            if (SIZED.size() > 200) SIZED.clear();
            SIZED.put(key, out);
            return out;
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float w, float h, int color) {
        PorterDuffColorFilter filter = FILTERS.get(color);
        if (filter == null) {
            // Animated colors (focus, night) produce a new color every frame: the cache must not grow forever.
            if (FILTERS.size() > 256) FILTERS.clear();
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            FILTERS.put(color, filter);
        }
        PAINT.setColorFilter(filter);
        float left = Math.round(cx - w / 2), top = Math.round(cy - h / 2);
        RECT.set(left, top, left + w, top + h);
        canvas.drawBitmap(b, null, RECT, PAINT);
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
