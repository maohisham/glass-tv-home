package com.gathin.tvhome;

import android.util.Log;

/**
 * Applies the {@code timer.*} commands from Home Assistant to {@link SleepTimer}. Runs on the main thread.
 * Invalid or missing minutes are ignored (logged without the payload).
 */
final class HaTimerCommands implements HaCommandRouter.Handler {
    private static final String TAG = "TvHome.HaTimer";

    private final Runnable reportState;

    /** @param reportState sends the current timer state to Home Assistant now ({@code timer.status}). */
    HaTimerCommands(Runnable reportState) {
        this.reportState = reportState;
    }

    @Override
    public void handle(HaMessages.Command c) {
        SleepTimer t = SleepTimer.get();
        switch (c.name) {
            case "timer.start":
                if (!ok(c)) return;
                t.start(c.minutes);
                showOverlay();
                break;
            case "timer.cancel":
                t.cancel();
                hideOverlay();
                break;
            case "timer.add":
                if (!ok(c)) return;
                t.setRemaining(SleepTimer.addedRemainingMs(t.isActive(), t.getRemainingMs(), c.minutes));
                showOverlay();
                break;
            case "timer.subtract":
                if (!ok(c)) return;
                if (!t.isActive()) return;
                t.setRemaining(SleepTimer.subtractedRemainingMs(t.getRemainingMs(), c.minutes));
                showOverlay();
                break;
            case "timer.status":
                reportState.run();
                break;
            default:
                Log.w(TAG, "unknown timer command");
        }
    }

    private static boolean ok(HaMessages.Command c) {
        if (SleepTimer.validMinutes(c.minutes)) return true;
        Log.w(TAG, c.name + " ignored: minutes must be a whole number from 1 to " + SleepTimer.MAX_MINUTES);
        return false;
    }

    private static void showOverlay() {
        HomeKeyService svc = HomeKeyService.getInstance();
        if (svc != null) svc.showTimerOverlay();
    }

    private static void hideOverlay() {
        HomeKeyService svc = HomeKeyService.getInstance();
        if (svc != null) svc.hideTimerOverlay();
    }
}
