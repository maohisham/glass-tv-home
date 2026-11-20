package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The launcher's link to Home Assistant: one WebSocket that receives commands ({@code glasstv_command}
 * events) and reports state ({@code glasstv_state}). Owned by {@link HomeKeyService}. Everything here runs
 * on the main thread; the socket thread's callbacks are posted over. With the link off or not set up it
 * holds no thread, no timer and no callback.
 */
final class HaLink {
    private static final String TAG = "TvHome.HaLink";
    private static final long PING_MS = 30_000L;
    private static final long STATE_MS = 60_000L;

    enum Status { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED, NETWORK_UNREACHABLE }

    private static final HaLink INSTANCE = new HaLink();

    static HaLink get() { return INSTANCE; }

    private static volatile Status status = Status.NOT_CONFIGURED;

    static Status getStatus() { return status; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HaCommandRouter router = new HaCommandRouter();

    private Context ctx;
    private boolean started;
    private String wsUrl, token, device;
    private WebSocketClient client;
    private boolean authed, authFailed, awaitingPong, warnedRefused;
    private int attempt;
    private int nextId = 1;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable reconnect = this::connect;
    private final Runnable pingTick = this::pingTick;
    private final Runnable stateTick = this::reportState;

    private HaLink() {}

    /** Commands are dispatched by name prefix; features register their own (later: the camera). */
    HaCommandRouter router() { return router; }

    /** The validated Home Assistant address (http/https, no path) for other features, or null. */
    String baseUrl() { return ctx == null ? null : HaAddress.toHttpBase(HaSettings.url(ctx)); }

    String token() { return ctx == null ? "" : HaSettings.token(ctx); }

    /** Called by HomeKeyService when it connects. */
    void start(Context c) {
        ctx = c.getApplicationContext();
        started = true;
        router.register("timer", new HaTimerCommands(this::reportState));
        refresh();
    }

    /** Called by HomeKeyService when it goes away. */
    void stop() {
        started = false;
        teardown();
        setStatus(Status.NOT_CONFIGURED);
    }

    /** Settings changed (enable switch, address, token or device name): reconnect with the new values. */
    void onSettingsChanged() {
        main.post(this::refresh);
    }

    private void refresh() {
        teardown();
        if (!started) { setStatus(Status.NOT_CONFIGURED); return; }
        HaSettings.importTokenFile(ctx);
        wsUrl = HaAddress.toWebSocketUrl(HaSettings.url(ctx));
        token = HaSettings.token(ctx);
        device = HaSettings.device(ctx);
        if (!HaSettings.enabled(ctx) || wsUrl == null || token.isEmpty()) {
            setStatus(Status.NOT_CONFIGURED);
            return;
        }
        SleepTimer.get().setStateObserver(this::reportState);
        registerNetworkCallback();
        attempt = 0;
        authFailed = false;
        warnedRefused = false;
        connect();
    }

    /** Closes everything and removes every callback; leaves the link idle. */
    private void teardown() {
        main.removeCallbacks(reconnect);
        main.removeCallbacks(pingTick);
        main.removeCallbacks(stateTick);
        SleepTimer.get().setStateObserver(null);
        unregisterNetworkCallback();
        WebSocketClient c = client;
        client = null;
        authed = false;
        awaitingPong = false;
        if (c != null) c.close();
    }

    private void connect() {
        if (!started || wsUrl == null) return;
        main.removeCallbacks(reconnect);
        setStatus(Status.CONNECTING);
        Session s = new Session();
        s.ws = new WebSocketClient(wsUrl, s);
        client = s.ws;
        s.ws.start();
    }

    /** Receives the socket thread's callbacks and hands them to the main thread; stale sessions are dropped. */
    private final class Session implements WebSocketClient.Listener {
        WebSocketClient ws;

        @Override public void onOpen() { /* wait for auth_required */ }

        @Override public void onText(String text) {
            main.post(() -> { if (client == ws) handle(text); });
        }

        @Override public void onClosed(Exception reason) {
            main.post(() -> { if (client == ws) onDisconnected(reason); });
        }
    }

    private void handle(String text) {
        HaMessages.Incoming in = HaMessages.parse(text);
        switch (in.type) {
            case AUTH_REQUIRED:
                client.send(HaMessages.auth(token));
                break;
            case AUTH_OK:
                authed = true;
                attempt = 0;
                setStatus(Status.CONNECTED);
                client.send(HaMessages.subscribeEvents(nextId++, HaMessages.EVENT_COMMAND));
                reportState();
                awaitingPong = false;
                main.removeCallbacks(pingTick);
                main.postDelayed(pingTick, PING_MS);
                break;
            case AUTH_INVALID:
                Log.w(TAG, "Home Assistant rejected the access token");
                authFailed = true;
                setStatus(Status.AUTH_FAILED);
                WebSocketClient c = client;
                client = null;
                authed = false;
                main.removeCallbacks(pingTick);
                main.removeCallbacks(stateTick);
                if (c != null) c.close();
                break;
            case COMMAND:
                router.dispatch(in.command, device);
                break;
            case RESULT:
                if (!in.success && !warnedRefused) {
                    warnedRefused = true;
                    Log.w(TAG, "Home Assistant refused a request; the token's user may need to be an administrator");
                }
                break;
            case PONG:
                awaitingPong = false;
                break;
            default:
                break;
        }
    }

    private void onDisconnected(Exception reason) {
        authed = false;
        client = null;
        main.removeCallbacks(pingTick);
        main.removeCallbacks(stateTick);
        if (authFailed || !started) return;
        Log.i(TAG, "disconnected" + (reason == null ? "" : ": " + reason.getClass().getSimpleName()));
        setStatus(hasNetwork() ? Status.CONNECTING : Status.NETWORK_UNREACHABLE);
        main.postDelayed(reconnect, HaBackoff.delayMs(attempt++));
    }

    private void pingTick() {
        if (!authed || client == null) return;
        if (awaitingPong) {
            Log.w(TAG, "no answer to ping; reconnecting");
            client.close();
            return;
        }
        awaitingPong = true;
        client.send(HaMessages.ping(nextId++));
        main.postDelayed(pingTick, PING_MS);
    }

    /** Sends the timer state now, and again every minute while a timer runs. */
    private void reportState() {
        main.removeCallbacks(stateTick);
        if (!authed || client == null) return;
        SleepTimer t = SleepTimer.get();
        client.send(HaMessages.stateEvent(nextId++, device, t.isActive(), t.getActiveMinutes(),
                t.getRemainingMs() / 1000L));
        if (t.isActive()) main.postDelayed(stateTick, STATE_MS);
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                main.post(() -> {
                    if (started && client == null && !authFailed && wsUrl != null) {
                        attempt = 0;
                        connect();
                    }
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not watch the network: " + e.getClass().getSimpleName());
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null || ctx == null) return;
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        try {
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) { }
        networkCallback = null;
    }

    private static void setStatus(Status s) {
        status = s;
    }
}
