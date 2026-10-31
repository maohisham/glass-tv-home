package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import tv.projectivy.plugin.wallpaperprovider.api.Event;
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AerialWallpaperPlayer implements TextureView.SurfaceTextureListener {
    private static final String TAG = "TvHome.Aerial";
    static final String AERIAL_PACKAGE = "com.neilturner.aerialviews";
    static final String AERIAL_SERVICE_CLASS =
            "com.neilturner.aerialviews.services.projectivy.WallpaperProviderService";
    static final String BIND_ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER";

    private final Context ctx;
    private final ViewGroup container;
    private java.util.Set<String> sceneFilter = java.util.Collections.emptySet();
    private boolean usingBundledContent = false;
    private TextureView textureView;
    private android.view.View tintView;
    private MediaPlayer mediaPlayer;
    private IWallpaperProviderService providerService;
    private List<Wallpaper> wallpapers;
    private int wallpaperIndex = 0;
    private boolean surfaceReady = false;
    private boolean bound = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int consecutiveErrors = 0;


    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "AerialViews service connected");
            providerService = IWallpaperProviderService.Stub.asInterface(binder);
            fetchWallpapers();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "AerialViews service disconnected");
            providerService = null;
            retryHandler.removeCallbacksAndMessages(null);
        }
    };

    private void fetchWallpapers() {
        if (providerService == null) return;
        try {
            List<Wallpaper> result = providerService.getWallpapers();
            int count = result != null ? result.size() : 0;
            Log.d(TAG, "got " + count + " wallpapers (attempt " + (retryCount + 1) + ")");
            if (result != null) {
                for (int i = 0; i < Math.min(count, 5); i++) {
                    Wallpaper w = result.get(i);
                    Log.d(TAG, "  wallpaper[" + i + "] title=" + w.title
                            + " desc=" + w.description + " uri=" + w.uri);
                }
            }
            if (count > 0) {
                wallpapers = result;
                retryCount = 0;
                if (surfaceReady) playNext();
            } else if (retryCount < MAX_RETRIES) {
                retryCount++;
                long delayMs = retryCount * 2000L; // 2s, 4s, 6s, 8s, 10s
                Log.d(TAG, "retrying in " + delayMs + "ms");
                retryHandler.postDelayed(this::fetchWallpapers, delayMs);
            } else {
                Log.w(TAG, "gave up after " + MAX_RETRIES + " retries — falling back to Apple CDN");
                fetchAppleAerials();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "getWallpapers failed: " + e.getMessage());
            fetchAppleAerials();
        }
    }

    private void fetchAppleAerials() {
        final java.util.Set<String> activeFilter = sceneFilter; // capture at call time
        new Thread(() -> {
            // Community sources use GitHub CDN (standard CA) — prepend them so they play first.
            // Apple CDN (sylvan.apple.com) is appended as fallback; it may fail on devices
            // with incomplete trust stores.
            int[] githubIds = { R.raw.comm1, R.raw.comm2 };
            int[] appleIds  = { R.raw.tvos26 };
            List<String> githubUrls = new ArrayList<>();
            List<String> appleUrls  = new ArrayList<>();
            for (int rawId : githubIds) {
                try {
                    java.io.InputStream is = ctx.getResources().openRawResource(rawId);
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONArray assets = new JSONObject(sb.toString()).optJSONArray("assets");
                    if (assets == null) continue;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (!activeFilter.isEmpty()) {
                            String scene = a.optString("scene", "");
                            if (!activeFilter.contains(scene)) continue;
                        }
                        String url = a.optString("url-1080-H264", null);
                        if (url == null || url.isEmpty()) url = a.optString("url-1080-SDR", null);
                        if (url == null || url.isEmpty()) url = a.optString("url", null);
                        if (url != null && !url.isEmpty()) githubUrls.add(url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "fetchAppleAerials community resource failed: " + e.getMessage());
                }
            }
            for (int rawId : appleIds) {
                try {
                    java.io.InputStream is = ctx.getResources().openRawResource(rawId);
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONArray assets = new JSONObject(sb.toString()).optJSONArray("assets");
                    if (assets == null) continue;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (!activeFilter.isEmpty()) {
                            String scene = a.optString("scene", "");
                            if (!activeFilter.contains(scene)) continue;
                        }
                        String url = a.optString("url-1080-H264", null);
                        if (url == null || url.isEmpty()) url = a.optString("url-1080-SDR", null);
                        if (url == null || url.isEmpty()) url = a.optString("url", null);
                        if (url != null && !url.isEmpty()) appleUrls.add(url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "fetchAppleAerials apple resource failed: " + e.getMessage());
                }
            }
            Collections.shuffle(githubUrls);
            Collections.shuffle(appleUrls);
            List<String> videoUrls = new ArrayList<>(githubUrls);
            videoUrls.addAll(appleUrls);
            Log.i(TAG, "Bundled aerial: " + githubUrls.size() + " GitHub + " + appleUrls.size()
                    + " Apple videos (filter=" + (activeFilter.isEmpty() ? "all" : activeFilter) + ")");
            List<Wallpaper> aerialWallpapers = new ArrayList<>();
            for (String url : videoUrls) {
                Wallpaper w = new Wallpaper();
                w.uri = url;
                w.mediaType = Wallpaper.MEDIA_TYPE_VIDEO;
                aerialWallpapers.add(w);
            }
            retryHandler.post(() -> {
                usingBundledContent = true;
                wallpapers = aerialWallpapers;
                Log.i(TAG, "Bundled aerial: loaded " + wallpapers.size() + " videos — starting playback");
                if (surfaceReady) playNext();
            });
        }).start();
    }

    AerialWallpaperPlayer(Context ctx, ViewGroup container) {
        this.ctx = ctx.getApplicationContext();
        this.container = container;
    }

    void start() {
        textureView = new TextureView(ctx);
        textureView.setSurfaceTextureListener(this);
        container.addView(textureView, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tintView = new android.view.View(ctx);
        tintView.setBackgroundColor(0x4D000000); // ~30% dark tint over the video
        container.addView(tintView, 1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Intent intent = new Intent(BIND_ACTION);
        intent.setComponent(new ComponentName(AERIAL_PACKAGE, AERIAL_SERVICE_CLASS));
        try {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) Log.w(TAG, "bindService failed — AerialViews not installed or not enabled?");
        } catch (Exception e) {
            Log.w(TAG, "bindService threw: " + e.getMessage());
        }
    }

    /** Returns a snapshot of the current video frame, or null if not ready. */
    android.graphics.Bitmap getBitmap() {
        if (textureView == null || !surfaceReady) return null;
        try { return textureView.getBitmap(); } catch (Exception e) { return null; }
    }

    void setTintVisible(boolean visible, int durationMs) {
        if (tintView == null) return;
        tintView.animate().alpha(visible ? 1f : 0f).setDuration(durationMs).start();
    }

    void setSceneFilter(java.util.Set<String> scenes) {
        this.sceneFilter = scenes != null ? scenes : java.util.Collections.emptySet();
        Log.i(TAG, "setSceneFilter: " + (this.sceneFilter.isEmpty() ? "all scenes" : this.sceneFilter));
    }

    /** Applies the current scene filter immediately. If already on bundled content, rebuilds
     *  the filtered list right away without waiting for the AerialViews retry cycle. */
    void refresh() {
        wallpaperIndex = 0;
        retryCount = 0;
        retryHandler.removeCallbacksAndMessages(null);
        if (usingBundledContent) {
            // Already on bundled content — skip the 50s AerialViews retry cycle and
            // immediately reload with the new filter.
            Log.i(TAG, "refresh() — bundled mode, rebuilding filtered list immediately");
            wallpapers = null;
            fetchAppleAerials();
        } else {
            // Still trying AerialViews — unbind and rebind to get a fresh playlist.
            Log.i(TAG, "refresh() — unbinding and rebinding AerialViews service");
            wallpapers = null;
            if (bound) {
                try { ctx.unbindService(connection); } catch (Exception ignored) {}
                bound = false;
                providerService = null;
            }
            Intent intent = new Intent(BIND_ACTION);
            intent.setComponent(new ComponentName(AERIAL_PACKAGE, AERIAL_SERVICE_CLASS));
            try {
                bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (Exception e) {
                Log.w(TAG, "refresh rebind failed: " + e.getMessage());
            }
        }
    }

    void stop() {
        retryHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (bound) {
            try { ctx.unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
        if (tintView != null && tintView.getParent() != null) {
            ((ViewGroup) tintView.getParent()).removeView(tintView);
        }
        tintView = null;
        if (textureView != null && textureView.getParent() != null) {
            ((ViewGroup) textureView.getParent()).removeView(textureView);
        }
        textureView = null;
        providerService = null;
    }

    private void playNext() {
        if (wallpapers == null || wallpapers.isEmpty() || !surfaceReady) return;
        Wallpaper w = wallpapers.get(wallpaperIndex % wallpapers.size());
        wallpaperIndex++;
        if (w.uri == null || w.uri.isEmpty() || w.mediaType != Wallpaper.MEDIA_TYPE_VIDEO) {
            if (wallpaperIndex < wallpapers.size() * 2) playNext(); // skip non-video, avoid infinite loop
            return;
        }
        notifyEvent(Event.TYPE_DISPLAYED, w.uri);
        playUri(w.uri);
    }

    private void playUri(String uri) {
        releasePlayer();
        if (textureView == null || !surfaceReady) return;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(uri);
            mediaPlayer.setSurface(new Surface(textureView.getSurfaceTexture()));
            mediaPlayer.setLooping(false);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setOnPreparedListener(mp -> { consecutiveErrors = 0; mp.start(); });
            mediaPlayer.setOnCompletionListener(mp -> { consecutiveErrors = 0; playNext(); });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                consecutiveErrors++;
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra
                        + " (consec=" + consecutiveErrors + ")");
                if (wallpapers != null && consecutiveErrors < wallpapers.size()) {
                    playNext();
                } else {
                    Log.w(TAG, "All aerial videos failed — giving up");
                }
                return true;
            });
            mediaPlayer.prepareAsync();
            Log.d(TAG, "playing: " + uri);
        } catch (Exception e) {
            Log.w(TAG, "playUri failed: " + e.getMessage());
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void notifyEvent(int type, String uri) {
        if (providerService == null) return;
        try { providerService.onEvent(new Event(type, uri)); } catch (Exception ignored) {}
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surfaceReady = true;
        if (wallpapers != null && !wallpapers.isEmpty()) playNext();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
