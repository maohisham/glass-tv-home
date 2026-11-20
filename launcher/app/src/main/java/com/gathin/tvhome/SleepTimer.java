package com.gathin.tvhome;

import android.os.CountDownTimer;
import android.util.Log;

import java.util.Locale;

/**
 * Singleton countdown timer that drives the launcher clock-area swap and the HomeKeyService overlay.
 * All mutating methods must be called on the main thread.
 * Preset cycle: Off(0) → 15 → 30 → 60 → 120 → Off(0)
 *
 * Besides the UI {@link Listener} (set by MainActivity while it is alive), two permanent hooks are set by
 * always-on code: an expiry handler (puts the TV to sleep even when the launcher activity is gone) and a
 * state observer (tells Home Assistant about every change).
 */
final class SleepTimer {
    private static final String TAG = "TvHome.SleepTimer";
    private static final int[] PRESETS = {0, 15, 30, 60, 120};
    static final int MAX_MINUTES = 720;
    private static final long MIN_REMAINING_MS = 60_000L;

    interface Listener {
        void onTick(long remainingMs);
        void onExpired();
    }

    private static SleepTimer instance;

    static SleepTimer get() {
        if (instance == null) instance = new SleepTimer();
        return instance;
    }

    private CountDownTimer timer;
    private long remainingMs;
    private int activeMinutes;
    private Listener listener;
    private Runnable expiryHandler;
    private Runnable stateObserver;

    private SleepTimer() {}

    void setListener(Listener l) { this.listener = l; }

    /** Always called when the countdown ends, whether or not the launcher activity is alive. */
    void setExpiryHandler(Runnable r) { this.expiryHandler = r; }

    /** Called once after every start, cancel, adjustment and expiry. */
    void setStateObserver(Runnable r) { this.stateObserver = r; }

    boolean isActive() { return timer != null; }

    int getActiveMinutes() { return activeMinutes; }

    long getRemainingMs() { return remainingMs; }

    void start(int minutes) {
        boolean was = stopInternal();
        if (minutes <= 0) {
            if (was) notifyState();
            return;
        }
        begin(minutes * 60_000L, minutes);
        Log.d(TAG, "sleep timer started: " + minutes + " min");
    }

    /** Restarts the countdown with {@code ms} left (used by add / subtract). Zero or less cancels. */
    void setRemaining(long ms) {
        if (ms <= 0) { cancel(); return; }
        stopInternal();
        begin(ms, (int) ((ms + 59_999L) / 60_000L));
        Log.d(TAG, "sleep timer set: " + ms / 1000L + " s left");
    }

    void cancel() {
        if (stopInternal()) notifyState();
    }

    private void begin(long ms, int minutesLabel) {
        activeMinutes = minutesLabel;
        remainingMs = ms;
        timer = new CountDownTimer(ms, 1000L) {
            @Override public void onTick(long left) {
                remainingMs = left;
                if (listener != null) listener.onTick(left);
            }
            @Override public void onFinish() {
                timer = null;
                remainingMs = 0;
                activeMinutes = 0;
                Log.d(TAG, "sleep timer expired");
                if (expiryHandler != null) expiryHandler.run();
                if (listener != null) listener.onExpired();
                notifyState();
            }
        }.start();
        notifyState();
    }

    /** Stops any running countdown without telling anyone; returns whether one was running. */
    private boolean stopInternal() {
        boolean was = timer != null;
        if (timer != null) { timer.cancel(); timer = null; }
        remainingMs = 0;
        activeMinutes = 0;
        return was;
    }

    private void notifyState() {
        if (stateObserver != null) stateObserver.run();
    }

    // ---- Pure helpers (JVM-tested) ----

    static boolean validMinutes(int m) { return m >= 1 && m <= MAX_MINUTES; }

    /** Remaining time after adding {@code minutes}: an idle timer starts from zero; never above 720 minutes. */
    static long addedRemainingMs(boolean active, long remainingMs, int minutes) {
        long base = active ? remainingMs : 0L;
        return Math.min(base + minutes * 60_000L, MAX_MINUTES * 60_000L);
    }

    /** Remaining time after removing {@code minutes}: never below one minute, and never longer than before. */
    static long subtractedRemainingMs(long remainingMs, int minutes) {
        long r = remainingMs - minutes * 60_000L;
        return r >= MIN_REMAINING_MS ? r : Math.min(remainingMs, MIN_REMAINING_MS);
    }

    /**
     * Returns the next preset after the current one, cycling Off→15→30→60→120→Off. A value that is not a
     * preset (for example 45 after an adjustment from Home Assistant) goes to the first preset above it,
     * or Off when it is above 120.
     */
    static int nextPresetMinutes(int current) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == current) return PRESETS[(i + 1) % PRESETS.length];
        }
        for (int i = 1; i < PRESETS.length; i++) {
            if (PRESETS[i] > current) return PRESETS[i];
        }
        return 0;
    }

    /** Formats milliseconds as M:SS (< 1 h) or H:MM:SS (≥ 1 h). */
    static String formatMs(long ms) {
        long total = ms / 1000L;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
