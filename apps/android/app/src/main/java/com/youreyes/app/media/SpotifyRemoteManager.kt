package com.youreyes.app.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.util.Log
import com.youreyes.app.launch.launchViaFullScreenNotification
import com.youreyes.app.service.MediaControlListenerService

object SpotifyRemoteManager {
    private const val TAG = "SpotifyRemoteManager"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val SPOTIFY_PLAY_ACTION = "com.spotify.mobile.android.service.action.client.PLAY"

    /**
     * Launches Spotify app and triggers playback for the given Spotify URI or song title.
     * Uses launchViaFullScreenNotification to bypass Android 10+ Background Activity Launch restrictions.
     */
    fun playTrack(context: Context, spotifyUri: String, song: String): Boolean {
        return try {
            val packageManager = context.packageManager
            val (title, artist) = parseSongQuery(song)
            val isExactTrack = spotifyUri.isNotBlank() && spotifyUri.startsWith("spotify:track:")

            // Step 1: Create target Spotify intent
            val targetIntent = if (isExactTrack) {
                Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
                    setPackage(SPOTIFY_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(SPOTIFY_PACKAGE)
                    putExtra(SearchManager.QUERY, song)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
                    if (artist.isNotBlank()) {
                        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (playIntent.resolveActivity(packageManager) != null) {
                    playIntent
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))).apply {
                        setPackage(SPOTIFY_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }

            // Step 2: Launch via FullScreen Notification to bypass background restrictions
            launchViaFullScreenNotification(
                context = context,
                targetIntent = targetIntent,
                notificationId = 5001,
                title = "Đang mở Spotify",
                text = song,
                sendPlayKeyDelayMs = 1800L
            )
            Log.i(TAG, "Launched Spotify via full-screen notification for: '$song' (URI: $spotifyUri)")

            // Step 3: If not exact track, schedule MediaSession controls to trigger search playback
            if (!isExactTrack) {
                schedulePlaybackTrigger(context, song, title, artist)
            }
            true
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to play Spotify track", exc)
            false
        }
    }

    private fun parseSongQuery(song: String): Pair<String, String> {
        val parts = song.split("-", " by ", " - ").map { it.trim() }
        return if (parts.size >= 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair(song.trim(), "")
        }
    }

    private fun schedulePlaybackTrigger(context: Context, song: String, title: String, artist: String) {
        val handler = Handler(Looper.getMainLooper())

        val delays = listOf(1200L, 2200L, 3500L)
        for (delay in delays) {
            handler.postDelayed({
                try {
                    val spotifyPlayServiceIntent = Intent(SPOTIFY_PLAY_ACTION).apply {
                        setPackage(SPOTIFY_PACKAGE)
                    }
                    context.startService(spotifyPlayServiceIntent)
                } catch (exc: Exception) {
                    Log.w(TAG, "Failed to start Spotify play service", exc)
                }

                triggerSpotifyMediaSessionPlay(context, song, title, artist)
                sendMediaKeys(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            }, delay)
        }
    }

    private fun triggerSpotifyMediaSessionPlay(context: Context, song: String, title: String, artist: String): Boolean {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false

            val listener = ComponentName(context, MediaControlListenerService::class.java)
            val sessions = try {
                manager.getActiveSessions(listener)
            } catch (exc: SecurityException) {
                emptyList<MediaController>()
            }

            val spotifyController = sessions.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                ?: sessions.firstOrNull()

            if (spotifyController != null) {
                val extras = Bundle().apply {
                    putString(MediaStore.EXTRA_MEDIA_TITLE, title)
                    if (artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                }
                spotifyController.transportControls.playFromSearch(song, extras)
                spotifyController.transportControls.skipToNext()
                spotifyController.transportControls.play()
                Log.i(TAG, "Called transportControls.playFromSearch('$song') & skipToNext() on ${spotifyController.packageName}")
                true
            } else {
                false
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to control Spotify MediaSession", exc)
            false
        }
    }

    private fun sendMediaKeys(context: Context, keyCode: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            context.sendOrderedBroadcast(downIntent, null)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            context.sendOrderedBroadcast(upIntent, null)
        } catch (exc: Exception) {
            Log.w(TAG, "Failed sending media keys", exc)
        }
    }

    /**
     * Sends a MEDIA_PAUSE key event to pause Spotify playback.
     */
    fun pauseTrack(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val listener = ComponentName(context, MediaControlListenerService::class.java)
            val sessions = try {
                manager?.getActiveSessions(listener)
            } catch (exc: Exception) {
                null
            }

            val spotifyController = sessions?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
                ?: sessions?.firstOrNull()

            if (spotifyController != null) {
                spotifyController.transportControls.pause()
            } else {
                sendMediaKeys(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            true
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to pause Spotify track", exc)
            false
        }
    }
}
