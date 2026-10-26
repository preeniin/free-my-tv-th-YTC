package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the three home cards show instead of TMDB picks: today's calendar (a private .ics link), news headlines (an RSS
 * link) and a shortcut to one app. Fetched in the background every 15 minutes; nothing is kept on disk.
 */
final class Feed {
    interface Listener { void onFeed(); }

    private static final long EVERY_MS = 15 * 60_000L;
    private static final Pattern ITEM = Pattern.compile("<item[ >].*?</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static Feed instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Ical calendar;
    private volatile List<String> headlines = new ArrayList<>();
    private volatile String newsSource = "";
    private volatile long newsAt;
    private volatile boolean calendarFailed, newsFailed;
    private long fetchedAt = -EVERY_MS;
    private boolean busy;

    static synchronized Feed get(Context c) {
        if (instance == null) instance = new Feed();
        return instance;
    }

    /** True when the cards show these instead of TMDB picks: a calendar link or a news link is set. */
    static boolean active() {
        return !Config.ICAL_URL.isEmpty() || !Config.NEWS_URL.isEmpty();
    }

    static boolean hasCalendar() { return !Config.ICAL_URL.isEmpty(); }

    Ical calendar() { return calendar; }
    List<String> headlines() { return headlines; }
    /** The feed's name ("ไทยรัฐ"), and when its newest item came out (0 = unknown). */
    String newsSource() { return newsSource; }
    long newsAt() { return newsAt; }
    boolean calendarFailed() { return calendarFailed; }
    boolean newsFailed() { return newsFailed; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetch again now (the calendar card asks for it with OK). */
    void refreshNow() {
        fetchedAt = -EVERY_MS;
        refreshIfStale();
    }

    /** "5 min ago" / "2 h ago" for a feed item. */
    static String ago(long now, long at) {
        if (at <= 0 || at > now + 60_000) return "";
        long minutes = (now - at) / 60_000;
        if (minutes < 1) return "เมื่อครู่";
        if (minutes < 60) return minutes + " นาทีที่แล้ว";
        long hours = minutes / 60;
        return hours < 24 ? hours + " ชม.ที่แล้ว" : "";
    }

    void refreshIfStale() {
        if (!active() || busy || SystemClock.elapsedRealtime() - fetchedAt < EVERY_MS) return;
        busy = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                if (hasCalendar()) {
                    try {
                        calendar = Ical.parse(get(Config.ICAL_URL), TimeZone.getDefault());
                        calendarFailed = false;
                    } catch (IOException | RuntimeException e) {
                        calendarFailed = calendar == null;
                        AppLog.w("calendar: " + e.getMessage());
                    }
                }
                if (!Config.NEWS_URL.isEmpty()) {
                    try {
                        String xml = get(Config.NEWS_URL);
                        List<String> found = headlines(xml);
                        if (!found.isEmpty()) {
                            headlines = found;
                            newsSource = source(xml);
                            newsAt = newest(xml);
                        }
                        newsFailed = found.isEmpty() && headlines.isEmpty();
                    } catch (IOException | RuntimeException e) {
                        newsFailed = headlines.isEmpty();
                        AppLog.w("news: " + e.getMessage());
                    }
                }
                fetchedAt = SystemClock.elapsedRealtime();
                busy = false;
                main.post(new Runnable() {
                    @Override public void run() {
                        for (Listener l : new ArrayList<>(listeners)) l.onFeed();
                    }
                });
            }
        });
    }

    private static List<String> headlines(String xml) {
        List<String> out = new ArrayList<>();
        Matcher items = ITEM.matcher(xml);
        while (items.find() && out.size() < 12) {
            Matcher t = TITLE.matcher(items.group());
            if (!t.find()) continue;
            String s = t.group(1).trim();
            if (s.startsWith("<![CDATA[")) s = s.substring(9, Math.max(9, s.length() - 3));
            s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** "Now" or "in 50 min" / "in 2 h" for an event that starts within about six hours (else null). */
    static String chip(Ical.Event e, long now) {
        if (e.allDay) return null;
        if (e.start <= now) return "Now";
        long minutes = (e.start - now + 59_999) / 60_000;
        if (minutes < 60) return "in " + minutes + " min";
        long hours = Math.round(minutes / 60.0);
        return hours <= 6 ? "in " + hours + " h" : null;
    }

    /** "1 ชั่วโมง", "45 นาที", "1 ชม. 30 นาที"; all-day events: "ทั้งวัน". */
    static String duration(Ical.Event e) {
        if (e.allDay) return "ทั้งวัน";
        long minutes = Math.round((e.end - e.start) / 60_000.0);
        if (minutes <= 0) return "";
        if (minutes < 60) return minutes + " นาที";
        long h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " ชั่วโมง" : h + " ชม. " + m + " นาที";
    }

    /** Where · how long ("ห้องประชุม 2 · 1 ชั่วโมง"), whichever of the two there is. */
    static String meta(Ical.Event e) {
        String d = duration(e);
        return e.where.isEmpty() ? d : d.isEmpty() ? e.where : e.where + " · " + d;
    }

    /** The next event that has not ended (timed ones first; an all-day one only when nothing else is coming), or null. */
    Ical.Event next(long now) {
        Ical cal = calendar;
        if (cal == null) return null;
        Ical.Event allDay = null;
        for (Ical.Event e : cal.between(now, now + 14 * 86_400_000L)) {
            if (e.allDay) {
                if (allDay == null) allDay = e;
            } else if (e.end > now || e.end == e.start && e.start >= now) {
                return e;
            }
        }
        return allDay;
    }

    /** "วันนี้" / "พรุ่งนี้" / "พฤ. 29 ต.ค." for the day `at` falls on, seen from `now`. */
    static String dayLabel(long at, long now) {
        java.util.Calendar day = new java.util.GregorianCalendar();
        day.setTimeInMillis(now);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long start = day.getTimeInMillis();
        long shown = Math.max(at, start);
        int away = (int) Math.round((shown - start) / 86_400_000.0);
        return away <= 0 ? "วันนี้" : away == 1 ? "พรุ่งนี้" : Thai.tinyDate(shown);
    }

    /** The events of the nearest day that still has something coming (all-day first, then by start), up to 14 days ahead. */
    List<Ical.Event> nextDay(long now) {
        List<Ical.Event> out = new ArrayList<>();
        Ical cal = calendar;
        if (cal == null) return out;
        java.util.Calendar day = new java.util.GregorianCalendar();
        day.setTimeInMillis(now);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        long best = Long.MAX_VALUE;
        List<Ical.Event> coming = new ArrayList<>();
        for (Ical.Event e : cal.between(now - 2 * 3_600_000L, today + 14 * 86_400_000L)) {
            if (!e.allDay && e.end <= now && e.end > e.start) continue;
            coming.add(e);
            best = Math.min(best, Math.floorDiv(Math.max(e.start, today) - today, 86_400_000L));
        }
        for (Ical.Event e : coming) {
            if (Math.floorDiv(Math.max(e.start, today) - today, 86_400_000L) == best) out.add(e);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Ical.Event>() {
            @Override public int compare(Ical.Event a, Ical.Event b) {
                if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
                return Long.compare(a.start, b.start);
            }
        });
        return out;
    }

    /** The channel's title, shortened: "ข่าวไทยรัฐออนไลน์ - ข่าวข่าว" → "ไทยรัฐ" for the known ones, else the part before " - ". */
    private static String source(String xml) {
        if (Config.NEWS_URL.contains("thairath")) return "ไทยรัฐ";
        int item = xml.indexOf("<item");
        Matcher t = TITLE.matcher(item > 0 ? xml.substring(0, item) : xml);
        if (!t.find()) return "";
        String s = t.group(1).replace("<![CDATA[", "").replace("]]>", "").trim();
        int dash = s.indexOf(" - ");
        return dash > 0 ? s.substring(0, dash) : s;
    }

    private static final Pattern PUB_DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);

    private static long newest(String xml) {
        long best = 0;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US);
        Matcher m = PUB_DATE.matcher(xml);
        while (m.find()) {
            try {
                best = Math.max(best, f.parse(m.group(1).trim()).getTime());
            } catch (java.text.ParseException ignored) {
            }
        }
        return best;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < 4_000_000) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
