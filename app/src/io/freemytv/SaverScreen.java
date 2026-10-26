package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The screensaver without photos: the lantern look (a dim crimson glow, a big thin clock, the Thai date, a strip of
 * weather, air, network and memory), as in the mockup's lock page. Laid out from the 1920 × 1080 grid; the caller
 * records {@link #drawContent} into a layer once a minute (and when data changes) and drifts it a pixel at a time.
 */
final class SaverScreen {
    private static final int ICON = 0, DOT = 1, TEXT = 2;

    private static final class Seg {
        int kind, color;
        String text, icon;
        Paint paint;
        float w;
    }

    private final Context context;
    private final HomeData data;
    private final Feed feed;
    private final Status status;
    private float w, h, k = 1;
    private Shader sky, veil;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private Paint greet, name, clock, small, date, chip, chipBold, wake, brand;

    SaverScreen(Context c, HomeData data, Feed feed, Status status) {
        context = c;
        this.data = data;
        this.feed = feed;
        this.status = status;
        line.setStyle(Paint.Style.STROKE);
    }

    void size(float width, float height) {
        w = width;
        h = height;
        k = w / 1920f;
        sky = Lantern.lockSky(w, h);
        veil = Lantern.veil(h);
        greet = Ui.text(Ui.medium, 28 * k, 0x9EFFFFFF);
        name = Ui.text(Ui.semibold, 28 * k, Lantern.WHITE);
        clock = Ui.numbers(Ui.thin, 236 * k, Lantern.WHITE);
        clock.setLetterSpacing(-0.02f);
        small = Ui.text(Ui.light, 60 * k, 0x80FFFFFF);
        date = Ui.text(Ui.displayMedium, 40 * k, 0xD1FFFFFF);
        chip = Ui.text(Ui.medium, 21 * k, 0xD9FFFFFF);
        chipBold = Ui.numbers(Ui.semibold, 21 * k, Lantern.WHITE);
        wake = Ui.text(Ui.regular, 22 * k, 0x80FFFFFF);
        brand = Ui.text(Ui.semibold, 15 * k, 0x47FFFFFF);
        brand.setLetterSpacing(0.28f);
    }

    /** The glow and the veil over it (each frame: two fills). */
    void drawBackground(Canvas c) {
        fill.setColor(0xFF000000);   // a shader is painted through the paint's alpha: reset it first
        fill.setShader(sky);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(veil);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    void drawContent(Canvas c, long now) {
        // Vertical rhythm of the mockup, centered as a block.
        float greetH = 34 * k, clockH = 217 * k, dateH = 60 * k, chipH = 46 * k;
        float total = greetH + 14 * k + clockH + 6 * k + dateH + 26 * k + 3 * k + 22 * k + chipH;
        float top = Math.round((h - total) / 2f);
        float cx = w / 2f;

        // Greeting: the lantern disc, the words, the name.
        String hello = Thai.greeting(now), who = Prefs.name();
        float gw = 22 * k + 12 * k + greet.measureText(hello) + 8 * k + name.measureText(who);
        float gx = cx - gw / 2f, gy = top + greetH / 2f;
        Lantern.mark(c, gx + 11 * k, gy, 11 * k);
        gx += 34 * k;
        c.drawText(hello, Math.round(gx), Math.round(gy + Ui.halfCap(greet)), greet);
        gx += greet.measureText(hello) + 8 * k;
        c.drawText(who, Math.round(gx), Math.round(gy + Ui.halfCap(name)), name);

        // The clock (24 h) and the small "น.".
        String hhmm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(now));
        float clockTop = top + greetH + 14 * k;
        float cw = clock.measureText(hhmm), sw = small.measureText("น.");
        float clockX = cx - (cw + 14 * k + sw) / 2f;
        float baseline = Math.round(clockTop + clockH / 2f + Ui.cap(clock) / 2f);
        c.drawText(hhmm, Math.round(clockX), baseline, clock);
        c.drawText("น.", Math.round(clockX + cw + 14 * k), baseline, small);

        // The date and the gold rule.
        float dateTop = clockTop + clockH + 6 * k;
        String day = Thai.date(now);
        c.drawText(day, Math.round(cx - date.measureText(day) / 2f), Math.round(dateTop + dateH / 2f + Ui.halfCap(date)), date);
        float ruleY = dateTop + dateH + 26 * k;
        fill.setColor(0xFF000000);
        fill.setShader(new android.graphics.LinearGradient(cx - 60 * k, 0, cx + 60 * k, 0,
                new int[] {0x00F2A93B, 0xFFF2A93B, 0x00F2A93B}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(cx - 60 * k, ruleY, cx + 60 * k, ruleY + 3 * k, 1.5f * k, 1.5f * k, fill);
        fill.setShader(null);

        // The strip of chips.
        List<List<Seg>> chips = chips(now);
        float gap = 14 * k, stripW = gap * (chips.size() - 1);
        List<Float> widths = new ArrayList<>();
        for (List<Seg> s : chips) {
            float cwid = 36 * k;
            for (int i = 0; i < s.size(); i++) cwid += s.get(i).w + (i > 0 ? 10 * k : 0);
            widths.add(cwid);
            stripW += cwid;
        }
        float x = cx - stripW / 2f, chipTop = ruleY + 3 * k + 22 * k;
        for (int i = 0; i < chips.size(); i++) {
            drawChip(c, chips.get(i), x, chipTop, widths.get(i), chipH);
            x += widths.get(i) + gap;
        }

        // The footer: a lantern dot and the hint, then the name of the app.
        float brandBase = h - 56 * k - 2 * k;
        c.drawText("FREE MY TV", Math.round(cx - (brand.measureText("FREE MY TV") - 0.28f * brand.getTextSize()) / 2f), Math.round(brandBase), brand);
        String hint = "กดปุ่มใดก็ได้เพื่อปลุก";
        float hw = 22 * k + wake.measureText(hint);
        float hy = brandBase - 15 * k - 14 * k - 14 * k;
        fill.setColor(Lantern.LANTERN);
        c.drawCircle(cx - hw / 2f + 5 * k, hy, 5 * k, fill);
        c.drawText(hint, Math.round(cx - hw / 2f + 22 * k), Math.round(hy + Ui.halfCap(wake)), wake);
    }

    private List<List<Seg>> chips(long now) {
        List<List<Seg>> out = new ArrayList<>();
        HomeData.Weather wx = data.weather;
        if (Prefs.weather()) {
            List<Seg> s = new ArrayList<>();
            if (Double.isNaN(wx.temp)) {
                s.add(text("กำลังโหลดอากาศ…", chip));
            } else {
                s.add(icon(Icons.weather(wx.code, wx.isDay), 0));
                s.add(text(HomeData.degrees(wx.temp), chipBold));
                s.add(text(Thai.condition(wx.code, wx.isDay), chip));
            }
            out.add(s);
        }
        double aqi = data.aqi;
        if (!Double.isNaN(aqi)) {
            List<Seg> s = new ArrayList<>();
            s.add(dot(Lantern.aqiColor(aqi)));
            s.add(text("AQI", chip));
            s.add(text(String.valueOf(Math.round(aqi)), chipBold));
            out.add(s);
        }
        {
            List<Seg> s = new ArrayList<>();
            String link = status.link;
            s.add(icon(link.equals("Ethernet") ? "ethernet" : "wifi", 0xD9FFFFFF));
            s.add(text(link.isEmpty() ? "ออนไลน์" : link, chip));
            int ms = status.pingMs;
            Seg v = text(ms == -2 ? "ออฟไลน์" : ms < 0 ? "– ms" : ms + " ms", chipBold);
            v.color = ms == -2 ? 0xFFFF8A80 : ms > 180 ? 0xFFFF8A80 : ms > 90 ? 0xFFFFB84D : Lantern.WHITE;
            s.add(v);
            out.add(s);
        }
        {
            List<Seg> s = new ArrayList<>();
            s.add(icon("bolt", Lantern.LANTERN));
            s.add(text("RAM", chip));
            s.add(text(status.ramPercent() + "%", chipBold));
            out.add(s);
        }
        // The nearest day with something coming: its first event, and how many more there are that day.
        List<Ical.Event> day = Prefs.cardCalendar() ? feed.nextDay(now) : new ArrayList<Ical.Event>();
        if (!day.isEmpty()) {
            Ical.Event e = day.get(0);
            List<Seg> s = new ArrayList<>();
            String when = Feed.dayLabel(e.start, now) + " " + (e.allDay ? "ทั้งวัน" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(e.start)));
            Seg t = text(when, chipBold);
            t.color = Lantern.LANTERN;
            s.add(t);
            String title = e.title.isEmpty() ? "(ไม่มีชื่อ)" : e.title;
            s.add(text(TextUtils.ellipsize(title, new android.text.TextPaint(chip), 420 * k, TextUtils.TruncateAt.END).toString(), chip));
            if (day.size() > 1) s.add(text("+" + (day.size() - 1), chipBold));
            out.add(s);
        }
        return out;
    }

    private Seg text(String t, Paint p) {
        Seg s = new Seg();
        s.kind = TEXT;
        s.text = t;
        s.paint = p;
        s.color = p.getColor();
        s.w = p.measureText(t);
        return s;
    }

    private Seg icon(String name, int color) {
        Seg s = new Seg();
        s.kind = ICON;
        s.icon = name;
        s.color = color;
        s.w = 24 * k;
        return s;
    }

    private Seg dot(int color) {
        Seg s = new Seg();
        s.kind = DOT;
        s.color = color;
        s.w = 12 * k;
        return s;
    }

    private void drawChip(Canvas c, List<Seg> segs, float x, float top, float width, float height) {
        box.set(x, top, x + width, top + height);
        fill.setColor(0x0DFFFFFF);
        c.drawRoundRect(box, 16 * k, 16 * k, fill);
        line.setStrokeWidth(k);
        line.setColor(0x17FFFFFF);
        box.inset(k / 2f, k / 2f);
        c.drawRoundRect(box, 16 * k, 16 * k, line);
        float cx = x + 18 * k, cy = top + height / 2f;
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            if (i > 0) cx += 10 * k;
            if (s.kind == ICON) {
                Icons.draw(c, s.icon, cx + 12 * k, cy, 24 * k, s.color, true);
            } else if (s.kind == DOT) {
                fill.setColor(s.color);
                c.drawCircle(cx + 6 * k, cy, 6 * k, fill);
            } else {
                int old = s.paint.getColor();
                s.paint.setColor(s.color);
                c.drawText(s.text, Math.round(cx), Math.round(cy + Ui.halfCap(s.paint)), s.paint);
                s.paint.setColor(old);
            }
            cx += s.w;
        }
    }
}
