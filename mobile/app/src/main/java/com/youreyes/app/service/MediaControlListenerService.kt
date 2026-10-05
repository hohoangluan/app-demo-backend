package com.youreyes.app.service

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose: an enabled NotificationListenerService is how Android decides
 * that this app may call `MediaSessionManager.getActiveSessions()`, which
 * `music_play`/`music_stop` use to press play/pause on Spotify's own session from
 * the background. It reads no notifications. The user enables it under
 * Settings > Notifications > Device & app notifications.
 *
 * Kept in this package: renaming the class would silently revoke that grant.
 */
class MediaControlListenerService : NotificationListenerService()
