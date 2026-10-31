package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "TvHome.HomeKey";
    private static HomeKeyService instance;
    private static final long DOUBLE_HOME_INTERVAL_MS = 400;

    private long lastHomePressTime = 0;
    private Runnable singleHomeRunnable = null;
    private final Handler homeHandler = new Handler(Looper.getMainLooper());

    private String targetForceStopPackage = null;
    private final Queue<String> forceStopQueue = new LinkedList<>();
    private boolean isProcessingForceStop = false;
    private boolean isWaitingForConfirmDialog = false;

    private WindowManager windowManager;
    private ViewGroup overlayContainer;
    private TextView overlayView;
    private boolean overlayShown = false;

    // Self-sustaining overlay tick: runs every 500ms inside the service, independent of
    // MainActivity's lifecycle. Auto-reattaches the window if a display-mode change removes it.
    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            SleepTimer t = SleepTimer.get();
            if (!t.isActive()) {
                hideTimerOverlay();
                return;
            }
            // Re-attach if the system removed our window (e.g. HDR mode switch).
            if (overlayShown && overlayContainer != null && !overlayContainer.isAttachedToWindow()) {
                Log.w(TAG, "overlay detached — re-attaching");
                overlayContainer = null;
                overlayView = null;
                overlayShown = false;
            }
            if (!overlayShown) attachOverlayWindow();
            if (overlayView != null) overlayView.setText(SleepTimer.formatMs(t.getRemainingMs()));
            homeHandler.postDelayed(this, 500);
        }
    };

    /** Scancodes of remote buttons that should open Settings (config.xml, settings_key_scancodes). */
    private int[] settingsScancodes = new int[0];

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Log.i(TAG, "HomeKeyService connected");
        // If the launcher already started a timer before the service connected (e.g. after reboot),
        // pick it up now so the overlay appears without needing the user to cycle the timer.
        if (SleepTimer.get().isActive()) {
            showTimerOverlay();
        }
    }

    @Override
    public void onDestroy() {
        hideTimerOverlay();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean sleepScreen() {
        if (instance != null) {
            Log.i(TAG, "Executing GLOBAL_ACTION_LOCK_SCREEN to turn off TV display");
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    public static boolean showRecents() {
        if (instance != null) {
            return instance.performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
        return false;
    }

    // ---- Sleep timer integration ----

    /** Called by SleepTimer.Listener.onExpired() to put the TV to sleep. */
    public static void triggerSleep() {
        Log.d(TAG, "triggerSleep: hiding overlay then sleeping");
        if (instance != null) instance.hideTimerOverlay();
        if (!sleepScreen()) {
            Log.w(TAG, "triggerSleep: HomeKeyService not running — cannot sleep TV");
        }
    }

    /**
     * Starts the self-sustaining overlay tick. Safe to call repeatedly — just restarts the tick.
     * The tick runs inside the service and survives the launcher going to background.
     */
    public void showTimerOverlay() {
        homeHandler.removeCallbacks(overlayTick);
        homeHandler.post(overlayTick);
    }

    /**
     * Called from MainActivity's SleepTimer.Listener for backward compatibility.
     * The overlayTick already handles updates; this just ensures the tick is running.
     */
    public void updateTimerOverlay(long remainingMs) {
        if (!homeHandler.hasCallbacks(overlayTick)) {
            homeHandler.post(overlayTick);
        }
    }

    /** Stops the tick and removes the overlay window. */
    public void hideTimerOverlay() {
        homeHandler.removeCallbacks(overlayTick);
        homeHandler.post(() -> {
            if (!overlayShown || overlayContainer == null) return;
            try {
                windowManager.removeView(overlayContainer);
                Log.d(TAG, "timer overlay hidden");
            } catch (Exception e) {
                Log.w(TAG, "hideTimerOverlay failed: " + e.getMessage());
            } finally {
                overlayContainer = null;
                overlayView = null;
                overlayShown = false;
            }
        });
    }

    /** Builds and attaches the overlay window. Called only from overlayTick on the main thread. */
    private void attachOverlayWindow() {
        if (windowManager == null) return;

        float dp = getResources().getDisplayMetrics().density;

        // Rounded dark pill: "TV shutting off in" + large countdown
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        int hPad = (int) (dp * 20), vPad = (int) (dp * 10);
        container.setPadding(hPad, vPad, hPad, vPad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);  // 60% opaque black
        bg.setCornerRadius(dp * 12);
        container.setBackground(bg);

        TextView label = new TextView(this);
        label.setText("TV shutting off in");
        label.setTextColor(0xFFFF9800);   // orange
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);

        TextView countdown = new TextView(this);
        countdown.setTextColor(0xFFFFFFFF);
        countdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        countdown.setTypeface(Typeface.DEFAULT_BOLD);
        countdown.setGravity(Gravity.CENTER);
        countdown.setText("--:--");

        container.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(countdown, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (dp * 32);
        lp.y = (int) (dp * 32);

        try {
            windowManager.addView(container, lp);
            overlayContainer = container;
            overlayView = countdown;
            overlayShown = true;
            Log.d(TAG, "timer overlay attached");
        } catch (Exception e) {
            Log.w(TAG, "attachOverlayWindow failed: " + e.getMessage());
        }
    }

    /** True when the overlay window is currently attached. */
    public boolean isOverlayShown() { return overlayShown; }

    /** Returns the running service instance or null if not connected. */
    public static HomeKeyService getInstance() { return instance; }

    public static void forceStopApp(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return;
        
        // 1. Send MEDIA_STOP to release audio/video playback locks
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP));
            }
        } catch (Exception ignored) {}

        // 2. Kill background processes
        try {
            ActivityManager actMgr = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (actMgr != null) {
                actMgr.killBackgroundProcesses(pkg);
            }
        } catch (Exception ignored) {}

        // 3. Automated force-stop via accessibility if available
        if (instance != null) {
            instance.queueForceStop(pkg);
        }
    }

    public static void forceStopAll(Context context, List<String> packages) {
        if (packages == null) return;
        // Stop media and kill background for all
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP));
            }
            ActivityManager actMgr = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (actMgr != null) {
                for (String pkg : packages) {
                    actMgr.killBackgroundProcesses(pkg);
                }
            }
        } catch (Exception ignored) {}

        if (instance != null) {
            for (String pkg : packages) {
                if (!pkg.equals(context.getPackageName()) && !pkg.contains("com.android.tv.settings")) {
                    instance.queueForceStop(pkg);
                }
            }
        }
    }

    private synchronized void queueForceStop(String pkg) {
        forceStopQueue.offer(pkg);
        if (!isProcessingForceStop) {
            processNextForceStop();
        }
    }

    private synchronized void processNextForceStop() {
        if (forceStopQueue.isEmpty()) {
            isProcessingForceStop = false;
            isWaitingForConfirmDialog = false;
            targetForceStopPackage = null;
            return;
        }
        isProcessingForceStop = true;
        isWaitingForConfirmDialog = false;
        targetForceStopPackage = forceStopQueue.poll();
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + targetForceStopPackage));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);

            // Safety timeout: if window doesn't respond or already stopped within 400ms, proceed to next
            homeHandler.removeCallbacksAndMessages(null);
            homeHandler.postDelayed(() -> {
                if (isProcessingForceStop) {
                    isProcessingForceStop = false;
                    isWaitingForConfirmDialog = false;
                    targetForceStopPackage = null;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    homeHandler.postDelayed(this::processNextForceStop, 80);
                }
            }, 400);
        } catch (Exception e) {
            processNextForceStop();
        }
    }

    private boolean clickNodeOrAncestor(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4; i++) {
            if (cur.isClickable() && cur.isEnabled()) {
                boolean res = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Clicked ancestor node: " + cur.getClassName() + " -> " + res);
                return res;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isProcessingForceStop || targetForceStopPackage == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // Step 1: Check for "OK" confirmation button (if dialog is visible)
            List<AccessibilityNodeInfo> okNodes = root.findAccessibilityNodeInfosByText("OK");
            if (okNodes.isEmpty()) {
                okNodes = root.findAccessibilityNodeInfosByViewId("com.android.tv.settings:id/guidedactions_item_title");
            }
            if (okNodes.isEmpty()) {
                okNodes = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            }

            for (AccessibilityNodeInfo ok : okNodes) {
                CharSequence text = ok.getText();
                if (text != null && text.toString().equalsIgnoreCase("OK")) {
                    Log.i(TAG, "Confirming Force Stop OK for: " + targetForceStopPackage);
                    clickNodeOrAncestor(ok);
                    isProcessingForceStop = false;
                    isWaitingForConfirmDialog = false;
                    targetForceStopPackage = null;
                    homeHandler.removeCallbacksAndMessages(null);
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    homeHandler.postDelayed(this::processNextForceStop, 80);
                    return;
                }
            }

            if (isWaitingForConfirmDialog) return;

            // Step 2: Check for "Force stop" / "Paksa henti" button
            List<AccessibilityNodeInfo> forceStopButtons = root.findAccessibilityNodeInfosByText("Force stop");
            if (forceStopButtons.isEmpty()) {
                forceStopButtons = root.findAccessibilityNodeInfosByText("Paksa henti");
            }
            if (forceStopButtons.isEmpty()) {
                forceStopButtons = root.findAccessibilityNodeInfosByViewId("com.android.tv.settings:id/force_stop");
            }
            if (forceStopButtons.isEmpty()) {
                forceStopButtons = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/force_stop_button");
            }

            for (AccessibilityNodeInfo btn : forceStopButtons) {
                CharSequence text = btn.getText();
                String t = text != null ? text.toString().toLowerCase(Locale.ROOT) : "";
                if (t.contains("force stop") || t.contains("paksa henti")) {
                    if (btn.isEnabled()) {
                        Log.i(TAG, "Clicking Force Stop button for: " + targetForceStopPackage);
                        isWaitingForConfirmDialog = true;
                        clickNodeOrAncestor(btn);
                        return;
                    } else {
                        // Already force stopped
                        Log.i(TAG, "Force Stop button already disabled for: " + targetForceStopPackage);
                        isProcessingForceStop = false;
                        isWaitingForConfirmDialog = false;
                        targetForceStopPackage = null;
                        homeHandler.removeCallbacksAndMessages(null);
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        homeHandler.postDelayed(this::processNextForceStop, 80);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error during accessibility force stop", e);
        }
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            }
            return true;
        }

        if (keyCode != KeyEvent.KEYCODE_ALL_APPS && keyCode != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastHomePressTime < DOUBLE_HOME_INTERVAL_MS) {
                // Double press detected — show system recents
                lastHomePressTime = 0;
                if (singleHomeRunnable != null) {
                    homeHandler.removeCallbacks(singleHomeRunnable);
                    singleHomeRunnable = null;
                }
                Log.i(TAG, "Double press Home -> system recents");
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                return true;
            } else {
                lastHomePressTime = now;
                singleHomeRunnable = () -> {
                    singleHomeRunnable = null;
                    Intent home = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .setPackage(getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        startActivity(home);
                    } catch (Exception ignored) {}
                };
                homeHandler.postDelayed(singleHomeRunnable, DOUBLE_HOME_INTERVAL_MS);
                return true;
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
