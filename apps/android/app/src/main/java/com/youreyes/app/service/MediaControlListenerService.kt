package com.youreyes.app.service

import android.service.notification.NotificationListenerService

/**
 * Exists only to make this app eligible for `MediaSessionManager.getActiveSessions()`.
 *
 * `MEDIA_PLAY_FROM_SEARCH` leaves YouTube Music queued but paused (measured:
 * the session publishes about 4.5s after launch and sits in PAUSED), and
 * `AudioManager.dispatchMediaKeyEvent` never reaches it from a background
 * service. Reading the active sessions and calling `transportControls.play()`
 * on the real controller does work — but the platform only hands those sessions
 * to an app the user has granted notification access to, and it checks that by
 * looking for an enabled NotificationListenerService.
 *
 * So this class stays empty on purpose: it handles no notifications and reads
 * none. It is the grant handle, nothing more. The user enables it under
 * Settings > Notifications > Device & app notifications.
 */
class MediaControlListenerService : NotificationListenerService()
