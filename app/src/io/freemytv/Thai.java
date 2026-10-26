package io.freemytv;

import java.util.Calendar;
import java.util.GregorianCalendar;

/** Thai text of the redesign: the greeting, dates in the Buddhist era, and weather in words. */
final class Thai {
    private Thai() {}

    /** The greeting choices in Settings; 0 follows the hour of the day. */
    static final String[] GREETINGS = {"ตามเวลา", "สวัสดี", "สวัสดีครับ", "สวัสดีค่ะ", "ยินดีต้อนรับ", "Hello"};
    private static final String[] MONTHS = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};
    private static final String[] DAYS = {"อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์"};

    private static Calendar at(long now) {
        Calendar c = new GregorianCalendar();   // Gregorian whatever the locale (the TV can be set to the Buddhist calendar)
        c.setTimeInMillis(now);
        return c;
    }

    /** "สวัสดีตอนเย็น" by the hour, or the one picked in Settings. */
    static String greeting(long now) {
        int mode = Prefs.greet();
        if (mode > 0 && mode < GREETINGS.length) return GREETINGS[mode];
        int h = at(now).get(Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 12 ? "สวัสดีตอนเช้า" : h >= 12 && h < 17 ? "สวัสดีตอนบ่าย" : h >= 17 && h < 20 ? "สวัสดีตอนเย็น" : "สวัสดีตอนค่ำ";
    }

    /** "วันจันทร์ที่ 26 ต.ค. 2569". */
    static String date(long now) {
        Calendar c = at(now);
        return "วัน" + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + "ที่ " + c.get(Calendar.DAY_OF_MONTH) + " "
                + MONTHS[c.get(Calendar.MONTH)] + " " + (c.get(Calendar.YEAR) + 543);
    }

    /** "จันทร์ 26 ต.ค." */
    static String shortDate(long now) {
        Calendar c = at(now);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    /** "จ. 26 ต.ค." for tight spaces. */
    static String tinyDate(long now) {
        Calendar c = at(now);
        String d = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
        return (d.startsWith("พฤ") ? "พฤ." : d.substring(0, 1) + ".") + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? "ท้องฟ้าโปร่ง" : "ท้องฟ้าโปร่ง";
        if (code == 1) return day ? "แดดออก" : "ท้องฟ้าโปร่ง";
        if (code == 2) return "มีเมฆบางส่วน";
        if (code == 3) return "เมฆมาก";
        if (code == 45) return "มีหมอก";
        if (code == 48) return "หมอกจัด";
        if (code >= 51 && code <= 57) return "ฝนปรอย";
        if (code == 61 || code == 80) return "ฝนเล็กน้อย";
        if (code == 63 || code == 81) return "ฝนปานกลาง";
        if (code == 65 || code == 66 || code == 67) return "ฝนหนัก";
        if (code == 82) return "ฝนฟ้าคะนองหนัก";
        if (code >= 95) return "พายุฟ้าคะนอง";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "หิมะ";
        return "ฝน";
    }

    /** US AQI bands to a Thai word (the dot's color comes from {@link Lantern#aqiColor}). */
    static String aqi(double v) {
        return v <= 50 ? "ดี" : v <= 100 ? "ปานกลาง" : v <= 150 ? "เริ่มมีผลต่อสุขภาพ" : v <= 200 ? "มีผลต่อสุขภาพ" : "อันตราย";
    }

    /** PM2.5 (µg/m³) in Thailand's bands, as a Thai word. */
    static String pm(double v) {
        return v <= 15 ? "ดีมาก" : v <= 25 ? "ดี" : v <= 37.5 ? "ปานกลาง" : v <= 75 ? "เริ่มมีผลต่อสุขภาพ" : "มีผลต่อสุขภาพ";
    }
}
