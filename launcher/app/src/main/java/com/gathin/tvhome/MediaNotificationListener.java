package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MediaNotificationListener extends NotificationListenerService {

    static MediaNotificationListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        NowPlayingManager.refresh(this);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        NowPlayingManager.refresh(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NowPlayingManager.refresh(this);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NowPlayingManager.refresh(this);
    }
}
