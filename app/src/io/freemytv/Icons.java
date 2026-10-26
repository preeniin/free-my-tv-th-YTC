package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.HashMap;
import java.util.Map;

/**
 * Vector icons (the redesign's set: 2 px strokes on a 24-unit grid), drawn as paths at their final size: crisp at any
 * size, also after the TV scales its 1080p interface up to 4K. Each icon is a few layers; a layer is stroked, filled,
 * or both, in the caller's color or the icon's own (weather icons carry their own colors, for day and night).
 */
final class Icons {
    private Icons() {}

    /** Caller's color (the accent passed to draw). */
    private static final int INK = 1;

    private static final class Layer {
        final Path path;
        final int stroke, fill, strokeDark, fillDark;   // 0 = none, INK = the caller's color

        Layer(String d, int stroke, int fill, int strokeDark, int fillDark) {
            path = parse(d);
            this.stroke = stroke;
            this.fill = fill;
            this.strokeDark = strokeDark;
            this.fillDark = fillDark;
        }
    }

    private static final class Icon {
        final float box, width;
        final Layer[] layers;

        Icon(float box, float width, Layer... layers) {
            this.box = box;
            this.width = width;
            this.layers = layers;
        }
    }

    private static final Map<String, Icon> ICONS = new HashMap<>();
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG), FILL = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
        FILL.setStyle(Paint.Style.FILL);

        String sunCore = "M16.6 12a4.6 4.6 0 1 1-9.2 0a4.6 4.6 0 1 1 9.2 0z";
        String sunRays = "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4";
        String moon = "M19.5 14.6A7.8 7.8 0 1 1 9.4 4.5a6.2 6.2 0 0 0 10.1 10.1z";
        String cloud = "M7 19h10a4 4 0 0 0 .3-8 5.5 5.5 0 0 0-10.6 1.6A3.2 3.2 0 0 0 7 19z";
        String cloudHigh = "M7 15h10a4 4 0 0 0 .3-8 5.5 5.5 0 0 0-10.6 1.6A3.2 3.2 0 0 0 7 15z";
        String cloudSmall = "M8 20h9a3.6 3.6 0 0 0 .2-7.2A5 5 0 0 0 8 13.5a3.3 3.3 0 0 0 0 6.5z";

        ICONS.put("sunny", new Icon(24, 1.8f,
                new Layer(sunCore, 0xFFE07A1F, 0xFFFFD9A8, 0xFFFFA65C, 0xFF5A3A16),
                new Layer(sunRays, 0xFFE07A1F, 0, 0xFFFFA65C, 0)));
        ICONS.put("clear_night", new Icon(24, 1.8f,
                new Layer(moon, 0xFF6E62D9, 0xFFE4E0FF, 0xFFB4A8FF, 0xFF3B3570)));
        ICONS.put("partly_cloudy_day", new Icon(24, 1.8f,
                new Layer("M12.5 8.5a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z", 0xFFE07A1F, 0xFFFFD9A8, 0xFFFFA65C, 0xFF5A3A16),
                new Layer(cloudSmall, 0xFF6F7D96, 0xFFFFFEFB, 0xFF9AA6BC, 0xFF2A3140)));
        ICONS.put("partly_cloudy_night", new Icon(24, 1.8f,
                new Layer("M14 9.6A5 5 0 1 1 7.6 3.2a4 4 0 0 0 6.4 6.4z", 0xFF6E62D9, 0xFFE4E0FF, 0xFFB4A8FF, 0xFF3B3570),
                new Layer(cloudSmall, 0xFF6F7D96, 0xFFFFFEFB, 0xFF9AA6BC, 0xFF2A3140)));
        ICONS.put("cloud", new Icon(24, 1.8f,
                new Layer(cloud, 0xFF6F7D96, 0xFFE6EAF1, 0xFF9AA6BC, 0xFF2A3140)));
        ICONS.put("foggy", new Icon(24, 1.8f,
                new Layer("M4 9h16M6 13h12M4 17h16", 0xFF7F8CA3, 0, 0xFF9AA6BC, 0)));
        ICONS.put("rainy", new Icon(24, 1.8f,
                new Layer(cloudHigh, 0xFF2F6FD6, 0xFFE3ECFB, 0xFF7FB2FF, 0xFF1D2C47),
                new Layer("M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5", 0xFF2F6FD6, 0, 0xFF7FB2FF, 0)));
        ICONS.put("snowflake", new Icon(24, 1.8f,
                new Layer("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9", 0xFF40A8C8, 0, 0xFF7FD3EA, 0)));
        ICONS.put("thunderstorm", new Icon(24, 1.8f,
                new Layer("M7 14h10a4 4 0 0 0 .3-8 5.5 5.5 0 0 0-10.6 1.6A3.2 3.2 0 0 0 7 14z", 0xFF8A5BD9, 0xFFEEE6FB, 0xFFC09BFF, 0xFF33264D),
                new Layer("M12.5 15.5l-2 3.5h3l-2 3.5", 0xFF8A5BD9, 0, 0xFFC09BFF, 0)));

        ICONS.put("live_tv", new Icon(24, 2,
                new Layer("M5.5 5h13a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 14.5v-7A2.5 2.5 0 0 1 5.5 5zM8 21h8", INK, 0, INK, 0),
                new Layer("M10.5 8.8v4.4l3.6-2.2z", INK, INK, INK, INK)));
        ICONS.put("hdmi", new Icon(24, 2,
                new Layer("M5 4h14v6l-2.5 3.5h-9L5 10zM10 13.5V18M14 13.5V18M8.5 20.5h7", INK, 0, INK, 0)));
        ICONS.put("settings", new Icon(24, 2,
                new Layer("M4 7h9M18 7h2M4 17h3M12 17h8M18 7a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0zM12 17a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z", INK, 0, INK, 0)));
        ICONS.put("calendar", new Icon(24, 2,
                new Layer("M6 5h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3zM3 10h18M8 3v4M16 3v4", INK, 0, INK, 0)));
        ICONS.put("news", new Icon(24, 2,
                new Layer("M5.5 4h13A2.5 2.5 0 0 1 21 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5v-11A2.5 2.5 0 0 1 5.5 4zM7 8.5h10M7 12h10M7 15.5h6", INK, 0, INK, 0)));
        ICONS.put("pin", new Icon(24, 2,
                new Layer("M12 21s-6.5-6.2-6.5-11a6.5 6.5 0 0 1 13 0c0 4.8-6.5 11-6.5 11zM14.3 10a2.3 2.3 0 1 1-4.6 0a2.3 2.3 0 1 1 4.6 0z", INK, 0, INK, 0)));
        ICONS.put("play_circle", new Icon(24, 2,
                new Layer("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", INK, 0, INK, 0),
                new Layer("M10 8.5v7l5.5-3.5z", INK, INK, INK, INK)));
        ICONS.put("apps", new Icon(24, 2, new Layer("M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4zM15.5 4h3A1.5 1.5 0 0 1 20 5.5v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 14 8.5v-3A1.5 1.5 0 0 1 15.5 4zM5.5 14h3A1.5 1.5 0 0 1 10 15.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14zM15.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z", INK, 0, INK, 0)));
        ICONS.put("home", new Icon(24, 2, new Layer("M3 11l9-8 9 8M5 10v10h14V10", INK, 0, INK, 0)));
        ICONS.put("cloud", new Icon(24, 2, new Layer("M17 18a4 4 0 0 0 0-8 6 6 0 0 0-11.3-1.5A3.5 3.5 0 0 0 6 18z", INK, 0, INK, 0)));
        ICONS.put("cards", new Icon(24, 2, new Layer("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM3 9h18", INK, 0, INK, 0)));
        ICONS.put("remote", new Icon(24, 2, new Layer("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM7 10h.01M11 10h.01M15 10h.01M7 14h6", INK, 0, INK, 0)));
        ICONS.put("gear", new Icon(24, 2, new Layer("M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM12 2v3M12 19v3M2 12h3M19 12h3", INK, 0, INK, 0)));
        ICONS.put("chevron", new Icon(24, 2.2f, new Layer("M9 6l6 6-6 6", INK, 0, INK, 0)));
        ICONS.put("check", new Icon(24, 2.4f, new Layer("M4 12l5 5L20 6", INK, 0, INK, 0)));
        ICONS.put("wifi", new Icon(24, 2,
                new Layer("M2 8.5a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0", INK, 0, INK, 0),
                new Layer("M13 19a1 1 0 1 1-2 0a1 1 0 1 1 2 0z", INK, INK, INK, INK)));
        ICONS.put("ethernet", new Icon(24, 2,
                new Layer("M6 3h12v7h-2v3h-8v-3H6zM12 13v4M5 21h14M12 17v4", INK, 0, INK, 0)));
        ICONS.put("bolt", new Icon(24, 2,
                new Layer("M13 2L3 14h7l-1 8 10-12h-7l1-8z", INK, 0, INK, 0)));
        ICONS.put("search", new Icon(24, 2,
                new Layer("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0zM20 20l-4-4", INK, 0, INK, 0)));
        ICONS.put("memory", new Icon(24, 2,
                new Layer("M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM10 10h4v4h-4z"
                        + "M9 3v2M12 3v2M15 3v2M9 19v2M12 19v2M15 19v2M3 9h2M3 12h2M3 15h2M19 9h2M19 12h2M19 15h2", INK, 0, INK, 0)));
        ICONS.put("drop", new Icon(16, 0,
                new Layer("M7 1.5C5 4.6 2.5 7.4 2.5 10a4.5 4.5 0 0 0 9 0C11.5 7.4 9 4.6 7 1.5z", 0, INK, 0, INK)));
    }

    /** Weather icon name for an Open-Meteo WMO code. */
    static String weather(int code, boolean day) {
        return Symbols.weather(code, day);
    }

    static boolean has(String name) {
        return ICONS.containsKey(name);
    }

    /**
     * Draws the icon `size` px square, centered at (cx, cy). `ink` colors the layers that take the caller's color;
     * `dark` picks the icon's night colors.
     */
    static void draw(Canvas c, String name, float cx, float cy, float size, int ink, boolean dark) {
        Icon icon = ICONS.get(name);
        if (icon == null) return;
        float scale = size / icon.box;
        synchronized (STROKE) {
            c.save();
            c.translate(Math.round(cx - size / 2), Math.round(cy - size / 2));
            c.scale(scale, scale);
            STROKE.setStrokeWidth(icon.width);
            for (Layer l : icon.layers) {
                int fill = dark ? l.fillDark : l.fill, stroke = dark ? l.strokeDark : l.stroke;
                if (fill != 0) {
                    FILL.setColor(fill == INK ? ink : fill);
                    c.drawPath(l.path, FILL);
                }
                if (stroke != 0 && icon.width > 0) {
                    STROKE.setColor(stroke == INK ? ink : stroke);
                    c.drawPath(l.path, STROKE);
                }
            }
            c.restore();
        }
    }

    // MARK: - SVG path data (M L H V C S Q A Z and their relative forms)

    private static Path parse(String d) {
        Path p = new Path();
        int[] at = {0};
        char cmd = 0;
        float x = 0, y = 0, sx = 0, sy = 0, cx2 = 0, cy2 = 0;
        char prev = 0;
        while (true) {
            skip(d, at);
            if (at[0] >= d.length()) break;
            char ch = d.charAt(at[0]);
            if (Character.isLetter(ch)) {
                cmd = ch;
                at[0]++;
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + num(d, at); y = oy + num(d, at);
                    p.moveTo(x, y);
                    sx = x; sy = y;
                    break;
                case 'L':
                    x = ox + num(d, at); y = oy + num(d, at);
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + num(d, at);
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = (rel ? y : 0) + num(d, at);
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + num(d, at), y1 = oy + num(d, at);
                    cx2 = ox + num(d, at); cy2 = oy + num(d, at);
                    x = ox + num(d, at); y = oy + num(d, at);
                    p.cubicTo(x1, y1, cx2, cy2, x, y);
                    break;
                }
                case 'S': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = pu == 'C' || pu == 'S' ? 2 * x - cx2 : x, y1 = pu == 'C' || pu == 'S' ? 2 * y - cy2 : y;
                    cx2 = ox + num(d, at); cy2 = oy + num(d, at);
                    x = ox + num(d, at); y = oy + num(d, at);
                    p.cubicTo(x1, y1, cx2, cy2, x, y);
                    break;
                }
                case 'Q': {
                    float x1 = ox + num(d, at), y1 = oy + num(d, at);
                    x = ox + num(d, at); y = oy + num(d, at);
                    p.quadTo(x1, y1, x, y);
                    break;
                }
                case 'A': {
                    float rx = num(d, at), ry = num(d, at), rot = num(d, at);
                    boolean large = flag(d, at), sweep = flag(d, at);
                    float ex = ox + num(d, at), ey = oy + num(d, at);
                    arc(p, x, y, ex, ey, rx, ry, rot, large, sweep);
                    x = ex; y = ey;
                    break;
                }
                case 'Z':
                    p.close();
                    x = sx; y = sy;
                    break;
                default:
                    return p;
            }
            prev = cmd;
        }
        return p;
    }

    private static void skip(String d, int[] at) {
        while (at[0] < d.length() && (d.charAt(at[0]) == ' ' || d.charAt(at[0]) == ',')) at[0]++;
    }

    private static float num(String d, int[] at) {
        skip(d, at);
        int start = at[0];
        boolean dot = false, exp = false;
        if (at[0] < d.length() && (d.charAt(at[0]) == '-' || d.charAt(at[0]) == '+')) at[0]++;
        while (at[0] < d.length()) {
            char ch = d.charAt(at[0]);
            if (ch >= '0' && ch <= '9') at[0]++;
            else if (ch == '.' && !dot && !exp) { dot = true; at[0]++; }
            else if ((ch == 'e' || ch == 'E') && !exp) { exp = true; at[0]++; if (at[0] < d.length() && (d.charAt(at[0]) == '-' || d.charAt(at[0]) == '+')) at[0]++; }
            else break;
        }
        return Float.parseFloat(d.substring(start, at[0]));
    }

    /** Arc flags are single digits that may touch the next number ("0 1 1-9.2 0" or "011"). */
    private static boolean flag(String d, int[] at) {
        skip(d, at);
        return d.charAt(at[0]++) == '1';
    }

    /** SVG's endpoint arc as cubic Béziers (the conversion in the SVG spec, appendix F.6). */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry, float rotDeg,
                            boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { p.lineTo(x2, y2); return; }
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double arx = Math.abs(rx), ary = Math.abs(ry);
        double lambda = (x1p * x1p) / (arx * arx) + (y1p * y1p) / (ary * ary);
        if (lambda > 1) { arx *= Math.sqrt(lambda); ary *= Math.sqrt(lambda); }
        double num = arx * arx * ary * ary - arx * arx * y1p * y1p - ary * ary * x1p * x1p;
        double den = arx * arx * y1p * y1p + ary * ary * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * arx * y1p / ary, cyp = -coef * ary * x1p / arx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double t1 = angle(1, 0, (x1p - cxp) / arx, (y1p - cyp) / ary);
        double dt = angle((x1p - cxp) / arx, (y1p - cyp) / ary, (-x1p - cxp) / arx, (-y1p - cyp) / ary);
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;
        int segments = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2));
        double step = dt / segments, k = 4.0 / 3.0 * Math.tan(step / 4);
        double t = t1;
        for (int i = 0; i < segments; i++) {
            double ca = Math.cos(t), sa = Math.sin(t), cb = Math.cos(t + step), sb = Math.sin(t + step);
            double e1x = ca - k * sa, e1y = sa + k * ca, e2x = cb + k * sb, e2y = sb - k * cb;
            p.cubicTo(px(e1x, e1y, arx, ary, cos, sin, cx), py(e1x, e1y, arx, ary, cos, sin, cy),
                    px(e2x, e2y, arx, ary, cos, sin, cx), py(e2x, e2y, arx, ary, cos, sin, cy),
                    px(cb, sb, arx, ary, cos, sin, cx), py(cb, sb, arx, ary, cos, sin, cy));
            t += step;
        }
    }

    private static float px(double ux, double uy, double rx, double ry, double cos, double sin, double cx) {
        return (float) (cos * rx * ux - sin * ry * uy + cx);
    }

    private static float py(double ux, double uy, double rx, double ry, double cos, double sin, double cy) {
        return (float) (sin * rx * ux + cos * ry * uy + cy);
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }
}
