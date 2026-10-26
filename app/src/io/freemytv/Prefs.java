package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /**
     * The settings file is written by the settings screen and read by the home screen and the screensaver, which can
     * run in different processes (Android 11: the screensaver is in the main one): MODE_MULTI_PROCESS makes each read
     * pick up the other's change instead of keeping the value it loaded first.
     */
    @SuppressWarnings("deprecation")
    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - Name and greeting

    /** The name after the greeting ("Preen"). */
    static String name() {
        String v = sp().getString("name", "Preen").trim();
        return v.isEmpty() ? "Preen" : v;
    }

    static void setName(String v) { put("name", v.trim()); }

    /** The greeting: 0 follows the hour, 1 to 5 are the fixed ones in {@link Thai#GREETINGS}. */
    static int greet() {
        int v = sp().getInt("greet", 0);
        return v < 0 || v >= Thai.GREETINGS.length ? 0 : v;
    }

    static void setGreet(int v) {
        sp().edit().putInt("greet", v).apply();
        version++;
    }

    // MARK: - Cards on the home screen

    static boolean cardWeather() { return sp().getBoolean("cardWeather", true); }
    static void setCardWeather(boolean on) { put("cardWeather", on); }

    static boolean cardCalendar() { return sp().getBoolean("cardCalendar", true); }
    static void setCardCalendar(boolean on) { put("cardCalendar", on); }

    static boolean cardSystem() { return sp().getBoolean("cardSystem", true); }
    static void setCardSystem(boolean on) { put("cardSystem", on); }

    // MARK: - Recent apps (opened from this home screen, newest first)

    static List<String> recents() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("recents", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void addRecent(String pkg) {
        List<String> now = recents();
        now.remove(pkg);
        now.add(0, pkg);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, now.size()); i++) sb.append(i == 0 ? "" : ",").append(now.get(i));
        sp().edit().putString("recents", sb.toString()).apply();
    }

    // MARK: - City (from config.properties)

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
