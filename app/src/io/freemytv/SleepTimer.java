package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;

/**
 * "Turn the TV off in 15 / 30 / 60 min" from the quick actions. It lives in the home screen's process: when the time
 * comes it asks the TV (through its own ADB, like the other quick actions) to go to sleep. Setting it again, or "off",
 * cancels the one before.
 */
final class SleepTimer {
    private SleepTimer() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable task;
    private static long fireAt;

    /** Minutes left (0 when off). */
    static synchronized int minutesLeft() {
        return fireAt == 0 ? 0 : (int) Math.max(1, (fireAt - SystemClock.elapsedRealtime() + 59_999) / 60_000);
    }

    static synchronized void set(final Context context, int minutes) {
        if (task != null) MAIN.removeCallbacks(task);
        task = null;
        fireAt = 0;
        if (minutes <= 0) return;
        final Context app = context.getApplicationContext();
        fireAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
        task = new Runnable() {
            @Override public void run() {
                synchronized (SleepTimer.class) {
                    fireAt = 0;
                    task = null;
                }
                new Thread(new Runnable() {
                    @Override public void run() {
                        try (Adb adb = Adb.connect(app, 3000, null)) {
                            adb.shell("input keyevent KEYCODE_SLEEP");
                        } catch (IOException e) {
                            AppLog.w("sleep timer: " + e.getMessage());
                        }
                    }
                }).start();
            }
        };
        MAIN.postDelayed(task, minutes * 60_000L);
    }
}
