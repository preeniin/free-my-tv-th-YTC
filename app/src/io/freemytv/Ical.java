package io.freemytv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * A small iCalendar (.ics) reader for the agenda card: the events that fall in a time range, with the usual repeats
 * (daily, weekly, monthly, yearly; interval, until, count, by-day, by-month-day), skipped dates and edited occurrences.
 * Not a full RFC 5545: it is meant for a Google Calendar private feed.
 */
final class Ical {
    static final class Event implements Comparable<Event> {
        long start, end;
        boolean allDay;
        String title = "", where = "";

        @Override public int compareTo(Event o) {
            return allDay != o.allDay ? (allDay ? -1 : 1) : Long.compare(start, o.start);
        }
    }

    private static final class Source {
        String uid = "", title = "", where = "", status = "";
        long start = Long.MIN_VALUE, end = Long.MIN_VALUE, durationMs = -1, recurrenceId = Long.MIN_VALUE;
        boolean allDay;
        TimeZone tz;
        Map<String, String> rule;
        final Set<Long> skipped = new HashSet<>();
    }

    private final List<Source> sources = new ArrayList<>();
    private final TimeZone local;

    private Ical(TimeZone local) {
        this.local = local;
    }

    static Ical parse(String ics, TimeZone local) {
        Ical cal = new Ical(local);
        List<String> lines = unfold(ics);
        Source cur = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) { cur = new Source(); continue; }
            if (line.equals("END:VEVENT")) {
                if (cur != null && cur.start != Long.MIN_VALUE) cal.sources.add(cur);
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String head = line.substring(0, colon), value = line.substring(colon + 1);
            String[] parts = head.split(";");
            String name = parts[0].toUpperCase(java.util.Locale.ROOT);
            Map<String, String> params = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) params.put(parts[i].substring(0, eq).toUpperCase(java.util.Locale.ROOT), parts[i].substring(eq + 1).replace("\"", ""));
            }
            switch (name) {
                case "UID": cur.uid = value; break;
                case "SUMMARY": cur.title = text(value); break;
                case "LOCATION": cur.where = text(value); break;
                case "STATUS": cur.status = value.toUpperCase(java.util.Locale.ROOT); break;
                case "DTSTART": {
                    boolean[] day = new boolean[1];
                    cur.tz = zone(params, local);
                    cur.start = time(value, params, local, day);
                    cur.allDay = day[0];
                    break;
                }
                case "DTEND": cur.end = time(value, params, local, new boolean[1]); break;
                case "DURATION": cur.durationMs = duration(value); break;
                case "RECURRENCE-ID": cur.recurrenceId = time(value, params, local, new boolean[1]); break;
                case "RRULE": cur.rule = rule(value); break;
                case "EXDATE":
                    for (String v : value.split(",")) {
                        long t = time(v.trim(), params, local, new boolean[1]);
                        if (t != Long.MIN_VALUE) cur.skipped.add(t);
                    }
                    break;
                default: break;
            }
        }
        return cal;
    }

    /** The events that overlap [from, to), sorted (all-day first, then by start). */
    List<Event> between(long from, long to) {
        // An edited occurrence replaces the one it came from: remember which originals are replaced.
        Map<String, Set<Long>> replaced = new HashMap<>();
        for (Source s : sources) {
            if (s.recurrenceId != Long.MIN_VALUE) {
                Set<Long> set = replaced.get(s.uid);
                if (set == null) replaced.put(s.uid, set = new HashSet<>());
                set.add(s.recurrenceId);
            }
        }
        List<Event> out = new ArrayList<>();
        for (Source s : sources) {
            if (s.status.equals("CANCELLED")) continue;
            long length = s.end != Long.MIN_VALUE ? s.end - s.start : s.durationMs >= 0 ? s.durationMs : s.allDay ? 86_400_000L : 0;
            if (length < 0) length = 0;
            if (s.rule == null || s.recurrenceId != Long.MIN_VALUE) {
                add(out, s, s.start, length, from, to);
                continue;
            }
            Set<Long> gone = replaced.get(s.uid);
            TimeZone tz = s.allDay ? local : s.tz;
            Calendar day = new java.util.GregorianCalendar(tz);
            day.setTimeInMillis(from - length);
            startOfDay(day);
            Calendar begin = new java.util.GregorianCalendar(tz);
            begin.setTimeInMillis(s.start);
            long until = s.rule.containsKey("UNTIL") ? time(s.rule.get("UNTIL"), new HashMap<String, String>(), local, new boolean[1]) : Long.MAX_VALUE;
            int count = s.rule.containsKey("COUNT") ? parseInt(s.rule.get("COUNT"), 0) : 0;
            for (int guard = 0; guard < 400 && day.getTimeInMillis() < to; guard++, day.add(Calendar.DAY_OF_MONTH, 1)) {
                if (!matches(s.rule, begin, day)) continue;
                long occ = at(day, begin);
                if (occ < s.start || occ > until) continue;
                if (count > 0 && occurrences(s.rule, begin, day) > count) continue;
                if (s.skipped.contains(occ) || (gone != null && gone.contains(occ))) continue;
                add(out, s, occ, length, from, to);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void add(List<Event> out, Source s, long start, long length, long from, long to) {
        long end = start + length;
        boolean in = length == 0 ? start >= from && start < to : start < to && end > from;
        if (!in) return;
        Event e = new Event();
        e.start = start;
        e.end = end;
        e.allDay = s.allDay;
        e.title = s.title;
        e.where = s.where;
        out.add(e);
    }

    // MARK: - Repeats

    private static boolean matches(Map<String, String> rule, Calendar begin, Calendar day) {
        int interval = Math.max(1, parseInt(rule.get("INTERVAL"), 1));
        String freq = rule.containsKey("FREQ") ? rule.get("FREQ") : "";
        long idx = epochDay(day) - epochDay(begin);
        if (idx < 0) return false;
        int dow = day.get(Calendar.DAY_OF_WEEK), dom = day.get(Calendar.DAY_OF_MONTH);
        String byDay = rule.get("BYDAY");
        switch (freq) {
            case "DAILY":
                return idx % interval == 0 && (byDay == null || weekdayIn(byDay, dow, day, false));
            case "WEEKLY": {
                long weeks = (mondayOf(day) - mondayOf(begin)) / 7;
                boolean ok = byDay != null ? weekdayIn(byDay, dow, day, false) : dow == begin.get(Calendar.DAY_OF_WEEK);
                return weeks % interval == 0 && ok;
            }
            case "MONTHLY": {
                long months = (day.get(Calendar.YEAR) * 12L + day.get(Calendar.MONTH)) - (begin.get(Calendar.YEAR) * 12L + begin.get(Calendar.MONTH));
                if (months % interval != 0) return false;
                String byMonthDay = rule.get("BYMONTHDAY");
                if (byMonthDay != null) {
                    int last = day.getActualMaximum(Calendar.DAY_OF_MONTH);
                    for (String v : byMonthDay.split(",")) {
                        int n = parseInt(v, 0);
                        if (n == dom || (n < 0 && last + 1 + n == dom)) return true;
                    }
                    return false;
                }
                if (byDay != null) return weekdayIn(byDay, dow, day, true);
                return dom == begin.get(Calendar.DAY_OF_MONTH);
            }
            case "YEARLY": {
                long years = day.get(Calendar.YEAR) - begin.get(Calendar.YEAR);
                if (years % interval != 0) return false;
                String byMonth = rule.get("BYMONTH");
                int month = day.get(Calendar.MONTH) + 1;
                boolean monthOk = byMonth != null ? java.util.Arrays.asList(byMonth.split(",")).contains(String.valueOf(month))
                        : month == begin.get(Calendar.MONTH) + 1;
                if (byDay != null) return monthOk && weekdayIn(byDay, dow, day, true);
                return monthOk && dom == begin.get(Calendar.DAY_OF_MONTH);
            }
            default:
                return false;
        }
    }

    /** How many occurrences there are up to and including `day` (for COUNT). */
    private static int occurrences(Map<String, String> rule, Calendar begin, Calendar day) {
        Calendar c = (Calendar) begin.clone();
        startOfDay(c);
        long last = epochDay(day);
        int n = 0;
        for (int guard = 0; guard < 20000 && epochDay(c) <= last; guard++, c.add(Calendar.DAY_OF_MONTH, 1)) {
            if (matches(rule, begin, c)) n++;
        }
        return n;
    }

    /** BYDAY like "MO,WE" or "2TU" / "-1FR" (the number counts within the month; only when `ordinal` allows it). */
    private static boolean weekdayIn(String byDay, int dow, Calendar day, boolean ordinal) {
        String[] names = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
        for (String token : byDay.split(",")) {
            token = token.trim();
            if (token.length() < 2) continue;
            String code = token.substring(token.length() - 2);
            if (!code.equals(names[dow - 1])) continue;
            String num = token.substring(0, token.length() - 2);
            if (num.isEmpty() || !ordinal) return true;
            int n = parseInt(num, 0);
            int dom = day.get(Calendar.DAY_OF_MONTH), last = day.getActualMaximum(Calendar.DAY_OF_MONTH);
            if (n > 0 && (dom - 1) / 7 + 1 == n) return true;
            if (n < 0 && (last - dom) / 7 + 1 == -n) return true;
        }
        return false;
    }

    // MARK: - Dates and text

    private static long epochDay(Calendar c) {
        Calendar u = new java.util.GregorianCalendar(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        return Math.floorDiv(u.getTimeInMillis(), 86_400_000L);
    }

    private static long mondayOf(Calendar c) {
        long d = epochDay(c);
        int dow = c.get(Calendar.DAY_OF_WEEK);           // Sunday = 1
        return d - ((dow + 5) % 7);
    }

    private static void startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** `day`'s date with `begin`'s time of day, in the same zone. */
    private static long at(Calendar day, Calendar begin) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, begin.get(Calendar.HOUR_OF_DAY));
        c.set(Calendar.MINUTE, begin.get(Calendar.MINUTE));
        c.set(Calendar.SECOND, begin.get(Calendar.SECOND));
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static TimeZone zone(Map<String, String> params, TimeZone local) {
        String id = params.get("TZID");
        if (id == null) return local;
        TimeZone tz = TimeZone.getTimeZone(id);
        return tz.getID().equals("GMT") && !id.equals("GMT") && !id.equals("UTC") ? local : tz;
    }

    /** 20261020T193000Z, 20261020T193000 (with TZID or in the TV's zone) or 20261020 (all day). */
    private static long time(String v, Map<String, String> params, TimeZone local, boolean[] allDay) {
        try {
            v = v.trim();
            boolean utc = v.endsWith("Z");
            if (utc) v = v.substring(0, v.length() - 1);
            boolean dateOnly = v.length() == 8 || "DATE".equals(params.get("VALUE"));
            allDay[0] = dateOnly;
            TimeZone tz = utc ? TimeZone.getTimeZone("UTC") : dateOnly ? local : zone(params, local);
            Calendar c = new java.util.GregorianCalendar(tz);
            c.clear();
            int y = Integer.parseInt(v.substring(0, 4)), m = Integer.parseInt(v.substring(4, 6)) - 1, d = Integer.parseInt(v.substring(6, 8));
            if (dateOnly || v.length() < 15) c.set(y, m, d);
            else c.set(y, m, d, Integer.parseInt(v.substring(9, 11)), Integer.parseInt(v.substring(11, 13)), Integer.parseInt(v.substring(13, 15)));
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    /** PT1H30M, P1D, P1W. */
    private static long duration(String v) {
        long ms = 0, n = 0;
        boolean timePart = false;
        for (char ch : v.toCharArray()) {
            if (ch >= '0' && ch <= '9') n = n * 10 + (ch - '0');
            else {
                if (ch == 'T') timePart = true;
                else if (ch == 'W') ms += n * 7 * 86_400_000L;
                else if (ch == 'D') ms += n * 86_400_000L;
                else if (ch == 'H') ms += n * 3_600_000L;
                else if (ch == 'M' && timePart) ms += n * 60_000L;
                else if (ch == 'S') ms += n * 1000L;
                n = 0;
            }
        }
        return ms;
    }

    private static Map<String, String> rule(String v) {
        Map<String, String> m = new HashMap<>();
        for (String part : v.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).toUpperCase(java.util.Locale.ROOT), part.substring(eq + 1));
        }
        return m;
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(String v) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                s.append(n == 'n' || n == 'N' ? ' ' : n);
            } else {
                s.append(ch);
            }
        }
        return s.toString().trim();
    }

    /** Lines that start with a space or tab continue the one before. */
    private static List<String> unfold(String ics) {
        List<String> out = new ArrayList<>();
        for (String raw : ics.split("\r?\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }
}
