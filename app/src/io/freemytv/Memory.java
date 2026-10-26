package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The home screen's Memory card: free RAM, "Clear memory" and "Close apps". Both go through the TV's own ADB (as the home
 * switch does), with fixed commands only: `am kill-all` (cached background processes, which Android would drop anyway)
 * and `am force-stop` for apps that have a launcher icon, are running, and are not on the protected list.
 */
final class Memory {
    private Memory() {}

    interface Result { void done(String text); }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+");
    private static final long CONFIRM_MS = 8000;

    private static boolean busy;
    private static List<String> pending = new ArrayList<>();
    private static long pendingAt;

    /** {free, total} in bytes. */
    static long[] snapshot(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);
        return new long[] {info.availMem, info.totalMem};
    }

    static boolean busy() { return busy; }

    /** "628 MB", or "1.9 GB" from a gigabyte up. */
    static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1000 ? String.format(java.util.Locale.US, "%.1f GB", mb / 1024) : Math.round(mb) + " MB";
    }

    /** Clears the cached background processes and reports how much memory came back. */
    static void clear(final Context app, final Result done) {
        if (busy) return;
        busy = true;
        WORKER.execute(new Runnable() {
            @Override public void run() {
                String text;
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    long before = snapshot(app)[0];
                    adb.shell("am kill-all");
                    SystemClock.sleep(1200);
                    long freed = snapshot(app)[0] - before;
                    text = freed > 4L * 1024 * 1024 ? "Freed " + size(freed) : "Memory was already clear";
                } catch (IOException e) {
                    AppLog.w("clear memory: " + e.getMessage());
                    text = "Couldn't reach the TV's debugging. Check Settings.";
                }
                finish(done, text);
            }
        });
    }

    /**
     * First press: lists the apps it would close and asks to press again. Second press within a few seconds: closes them.
     */
    static void closeApps(final Context app, final Result done) {
        if (busy) return;
        final boolean confirm = !pending.isEmpty() && SystemClock.elapsedRealtime() - pendingAt < CONFIRM_MS;
        busy = true;
        WORKER.execute(new Runnable() {
            @Override public void run() {
                String text;
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    List<String> targets = confirm ? new ArrayList<>(pending) : candidates(app, adb);
                    if (targets.isEmpty()) {
                        pending = new ArrayList<>();
                        text = "No other apps are open";
                    } else if (!confirm) {
                        pending = targets;
                        pendingAt = SystemClock.elapsedRealtime();
                        text = "Press OK again to close: " + names(app, targets);
                    } else {
                        long before = snapshot(app)[0];
                        for (String pkg : targets) adb.shell("am force-stop " + pkg);
                        pending = new ArrayList<>();
                        SystemClock.sleep(1200);
                        long freed = Math.max(0, snapshot(app)[0] - before);
                        text = "Closed " + targets.size() + (targets.size() == 1 ? " app" : " apps")
                                + (freed > 4L * 1024 * 1024 ? " · freed " + size(freed) : "");
                    }
                } catch (IOException e) {
                    AppLog.w("close apps: " + e.getMessage());
                    pending = new ArrayList<>();
                    text = "Couldn't reach the TV's debugging. Check Settings.";
                }
                finish(done, text);
            }
        });
    }

    private static void finish(final Result done, final String text) {
        busy = false;
        MAIN.post(new Runnable() {
            @Override public void run() { done.done(text); }
        });
    }

    /** Running apps with a launcher icon that are not protected, not this app and not on screen. */
    private static List<String> candidates(Context app, Adb adb) throws IOException {
        PackageManager pm = app.getPackageManager();
        Set<String> running = new LinkedHashSet<>();
        for (String line : adb.shell("ps -A -o USER,NAME").split("\n")) {
            line = line.trim();
            if (!line.startsWith("u0_a")) continue;
            String name = line.substring(line.indexOf(' ') + 1).trim();
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (PACKAGE.matcher(name).matches()) running.add(name);
        }
        String top = adb.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        List<String> out = new ArrayList<>();
        for (String pkg : running) {
            if (pkg.equals(app.getPackageName()) || HomeMode.isProtected(pkg) || top.contains(pkg + "/")) continue;
            boolean hasIcon = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
            if (hasIcon) out.add(pkg);
        }
        return out;
    }

    private static String names(Context app, List<String> packages) {
        PackageManager pm = app.getPackageManager();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            if (i > 0) s.append(", ");
            if (i == 3) {
                s.append("+").append(packages.size() - 3);
                break;
            }
            String label = packages.get(i);
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packages.get(i), 0)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            s.append(label);
        }
        return s.toString();
    }
}
