package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

class NowPlayingManager {

    interface Listener {
        void onNowPlayingChanged(NowPlayingInfo info); // null = nothing playing
    }

    static class NowPlayingInfo {
        final String title;
        final String subtitle;
        final Bitmap art;
        final boolean isPlaying;
        final MediaController controller;

        NowPlayingInfo(String title, String subtitle, Bitmap art,
                       boolean isPlaying, MediaController controller) {
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.isPlaying = isPlaying;
            this.controller = controller;
        }
    }

    private static Listener listener;
    private static NowPlayingInfo current;

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onNowPlayingChanged(current);
    }

    static NowPlayingInfo getCurrent() { return current; }

    static void refresh(Context ctx) {
        MediaNotificationListener svc = MediaNotificationListener.instance;
        if (svc == null) {
            // Listener service not connected — clear state
            dispatch(null);
            return;
        }
        try {
            MediaSessionManager msm =
                (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(ctx, MediaNotificationListener.class);
            List<MediaController> sessions = msm.getActiveSessions(cn);

            NowPlayingInfo found = null;
            for (MediaController mc : sessions) {
                PlaybackState ps = mc.getPlaybackState();
                if (ps == null) continue;
                int state = ps.getState();
                if (state != PlaybackState.STATE_PLAYING
                        && state != PlaybackState.STATE_PAUSED
                        && state != PlaybackState.STATE_BUFFERING) continue;

                MediaMetadata meta = mc.getMetadata();
                String title = meta != null ? meta.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
                if (title == null || title.isEmpty()) continue;

                String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                if (artist == null || artist.isEmpty()) artist = friendlyAppName(mc.getPackageName());

                Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);

                boolean playing = (state == PlaybackState.STATE_PLAYING);
                found = new NowPlayingInfo(title, artist, art, playing, mc);
                break; // first active session wins
            }
            dispatch(found);
        } catch (SecurityException ignored) {
            // Notification access not granted
            dispatch(null);
        } catch (Exception ignored) {
            dispatch(null);
        }
    }

    private static void dispatch(NowPlayingInfo info) {
        current = info;
        if (listener != null) listener.onNowPlayingChanged(info);
    }

    private static String friendlyAppName(String pkg) {
        if (pkg == null) return "";
        // Strip common package suffixes for a readable label
        String[] parts = pkg.split("\\.");
        return parts.length > 0 ? capitalise(parts[parts.length - 1]) : pkg;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
