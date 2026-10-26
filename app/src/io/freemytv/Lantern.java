package io.freemytv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * The "lantern" look: a deep crimson glow fading to near-black, a single amber accent, white focus rings. Colors and
 * background shaders in one place for the home screen, the screensaver and the settings.
 */
final class Lantern {
    private Lantern() {}

    static final int LANTERN = 0xFFF2A93B, ON_LANTERN = 0xFF1A1206, UP = 0xFF30D158, DOWN = 0xFFFF453A;
    static final int TEXT = 0xFFF4F4F6, DIM = 0xFFC3B4B8, FAINT = 0xFF8C7E83;
    static final int WHITE = 0xFFFFFFFF;

    /** US AQI color. */
    static int aqiColor(double a) {
        return a <= 50 ? 0xFF30D158 : a <= 100 ? 0xFFF2A93B : a <= 150 ? 0xFFFF9F0A : a <= 200 ? 0xFFFF453A : 0xFFB96BE0;
    }

    /** CSS-style elliptical radial gradient: radii `rx` × `ry` around (cx, cy). */
    private static Shader ellipse(float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
        RadialGradient g = new RadialGradient(cx, cy, rx, colors, stops, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, ry / rx, cx, cy);
        g.setLocalMatrix(m);
        return g;
    }

    /** The home screen's wallpaper: `radial-gradient(135% 120% at 12% 6%, …)`; darker at night. */
    static Shader homeSky(float w, float h, boolean night) {
        int[] day = {0xFF8F1626, 0xFF5A0F1C, 0xFF260A12, 0xFF100509, 0xFF0A0407};
        int[] dark = {0xFF5A0F1C, 0xFF360A14, 0xFF170609, 0xFF0A0407, 0xFF060304};
        float[] stops = night ? new float[] {0f, .34f, .66f, .90f, 1f} : new float[] {0f, .32f, .62f, .88f, 1f};
        return ellipse(w * .12f, h * .06f, w * 1.35f, h * 1.2f, night ? dark : day, stops);
    }

    /** The screensaver's base: dimmer, centered. */
    static Shader lockSky(float w, float h) {
        return ellipse(w * .5f, h * .06f, w * 1.3f, h * 1.2f,
                new int[] {0xFF3A0A14, 0xFF200810, 0xFF0D0508, 0xFF050304}, new float[] {0f, .34f, .66f, 1f});
    }

    /** The settings' and All Apps' base. */
    static Shader pageSky(float w, float h) {
        return ellipse(w * .5f, 0, w * 1.3f, h * 1.2f,
                new int[] {0xFF340912, 0xFF1C0710, 0xFF0C0508, 0xFF050304}, new float[] {0f, .34f, .66f, 1f});
    }

    /** A vertical veil from `top` to `bottom` alpha of near-black over the screensaver's base (as the mockup's photo veil). */
    static Shader veil(float h) {
        return new LinearGradient(0, 0, 0, h, new int[] {0x8C040204, 0xC7040204, 0xEB030103}, new float[] {0f, .6f, 1f}, Shader.TileMode.CLAMP);
    }

    private static final Paint DISC = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The small conic "lantern" disc before the greeting. */
    static void mark(Canvas c, float cx, float cy, float radius) {
        SweepGradient g = new SweepGradient(cx, cy, new int[] {LANTERN, 0xFFCC9944, 0x00CC9944, 0x00CC9944}, new float[] {0f, .32f, .7f, 1f});
        Matrix m = new Matrix();
        m.setRotate(120, cx, cy);
        g.setLocalMatrix(m);
        synchronized (DISC) {
            DISC.setShader(g);
            c.drawCircle(cx, cy, radius, DISC);
            DISC.setShader(null);
        }
    }
}
