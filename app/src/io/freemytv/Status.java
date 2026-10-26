package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's own vital signs for the status card and the screensaver chips: how fast the internet answers, which link it
 * is on, how busy the CPU is, and how much memory is in use. Refreshed at most every 20 s, only while a screen asks.
 */
final class Status {
    interface Listener { void onStatus(); }

    private static final long EVERY_MS = 20_000, CPU_RETRY_MS = 10 * 60_000L;
    private static Status instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private long refreshedAt = -EVERY_MS, cpuFailedAt;
    private boolean busy;

    /** Milliseconds to answer (-1 not measured yet, -2 no internet). */
    volatile int pingMs = -1;
    /** "Wi-Fi", "Ethernet" or "". */
    volatile String link = "";
    /** CPU busy percent over a second (-1 unknown: the TV's debugging isn't on, or it said no). */
    volatile int cpu = -1;

    private Status(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized Status get(Context c) {
        if (instance == null) instance = new Status(c);
        return instance;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Memory in use, percent. */
    int ramPercent() {
        long[] m = Memory.snapshot(app);
        return m[1] <= 0 ? 0 : (int) Math.round(100.0 * (m[1] - m[0]) / m[1]);
    }

    void refreshIfStale() {
        if (busy || SystemClock.elapsedRealtime() - refreshedAt < EVERY_MS) return;
        busy = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                link = linkName();
                pingMs = ping();
                long now = SystemClock.elapsedRealtime();
                if (HomeMode.adbAuthorized(app) && now - cpuFailedAt > CPU_RETRY_MS) {
                    try (Adb adb = Adb.connect(app, 1500, null)) {
                        cpu = cpuPercent(adb.shell("head -1 /proc/stat; sleep 1; head -1 /proc/stat"));
                    } catch (IOException e) {
                        cpu = -1;
                        cpuFailedAt = now;
                    }
                }
                refreshedAt = SystemClock.elapsedRealtime();
                busy = false;
                main.post(new Runnable() {
                    @Override public void run() {
                        for (Listener l : new ArrayList<>(listeners)) l.onStatus();
                    }
                });
            }
        });
    }

    private String linkName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null) return "";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static int ping() {
        try {
            request();   // the first request pays for DNS and the TLS handshake: time the second, on the same connection
            long t0 = SystemClock.elapsedRealtime();
            request();
            return (int) Math.max(1, SystemClock.elapsedRealtime() - t0);
        } catch (IOException e) {
            return -2;
        }
    }

    private static void request() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setUseCaches(false);
        c.getResponseCode();
        c.getInputStream().close();   // back to the pool (not disconnect(): that would close the socket)
    }

    /** Two "cpu  user nice system idle iowait irq softirq steal…" lines, a second apart. */
    private static int cpuPercent(String out) {
        String[] lines = out.trim().split("\n");
        if (lines.length < 2) return -1;
        long[] a = fields(lines[0]), b = fields(lines[1]);
        if (a == null || b == null) return -1;
        long total = b[0] - a[0], idle = b[1] - a[1];
        return total <= 0 ? -1 : (int) Math.max(0, Math.min(100, Math.round(100.0 * (total - idle) / total)));
    }

    /** {sum of all fields, idle + iowait}. */
    private static long[] fields(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 6 || !p[0].equals("cpu")) return null;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i < Math.min(p.length, 9); i++) {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new long[] {total, idle};
    }
}
