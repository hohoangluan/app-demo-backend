package com.youreyes.app.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.youreyes.app.media.SpotifyAppRemoteManager
import com.youreyes.app.service.MediaControlListenerService
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "MusicActions"

private const val SPOTIFY_PACKAGE = "com.spotify.music"
private const val SPOTIFY_BROWSER_SERVICE =
    "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService"

/** Whole `music_play` budget; stays under the server's 45s deadline. */
private const val MUSIC_PLAY_BUDGET_MS = 38_000L
private const val MUSIC_BROWSER_TIMEOUT_MS = 5_000L
private const val PLAYBACK_STOP_TIMEOUT_MS = 8_000L
private const val PLAYBACK_POLL_INTERVAL_MS = 500L

/** Audio must keep coming this long to count as playing (a 1-2s blip then pause is not). */
private const val PLAYBACK_HOLD_MS = 4_000L
private const val PLAY_NUDGE_WINDOW_MS = 20_000L
private const val PLAY_NUDGE_INTERVAL_MS = 1_500L

/** `music_volume`: set an absolute level or step the media volume up/down by 10%. */
class MusicVolumeHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failure("INVALID_VOLUME", "Invalid music_volume params: ${it.message}")
        }
        val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val level = when {
            json.has("level") -> json.optInt("level", 50).coerceIn(0, 100)
            json.has("direction") && audio != null -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val step = (max / 10).coerceAtLeast(1)
                val delta = if (json.optString("direction") == "up") step else -step
                ((current + delta).coerceIn(0, max) * 100) / max
            }
            json.has("direction") -> return failure("VOLUME_CHANGE_FAILED", "AudioManager unavailable")
            else -> return failure("INVALID_VOLUME", "Either level or direction is required")
        }
        audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            it.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max) / 100, AudioManager.FLAG_SHOW_UI)
        }
        return success("volume_state" to "changed", "level" to level)
    }
}

/**
 * `music_play`: start the requested song in Spotify and report `playing` only
 * once the audio stack confirms the right track is sustaining playback.
 *
 * Routes, in order:
 * 1. Spotify App Remote with the exact `spotify:track:` URI the server resolved
 *    (starts the chosen track and tells us what is playing).
 * 2. Spotify's MediaBrowserService `playFromSearch` (works locked, but Spotify
 *    refuses non-whitelisted clients on many phones).
 * 3. Opening the track URI / a play-from-search intent, then pressing play on
 *    Spotify's media session (needs notification access, see
 *    [MediaControlListenerService]).
 */
class MusicPlayHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failure("PLAYBACK_FAILED", "Invalid music_play params: ${it.message}")
        }
        val song = json.optString("song").trim()
        if (song.isEmpty()) return failure("SONG_NOT_FOUND", "music_play called without a song")
        if (context == null) return failure("PLAYBACK_FAILED", "No context available")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failure("PLAYBACK_FAILED", "AudioManager unavailable")
        if (!isInstalled(context, SPOTIFY_PACKAGE)) {
            return failure("MUSIC_ACCOUNT_NOT_CONNECTED", "Spotify is not installed on this phone")
        }

        val volume = if (json.has("volume")) json.optInt("volume", 60).coerceIn(0, 100) else 60
        val trackUri = json.optString("spotify_uri").takeIf { it.startsWith("spotify:track:") }
        val query = SongQuery.parse(song)

        // Raise the stream first: a track playing into a muted stream sounds like failure.
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max) / 100, 0)

        fun playing(title: String?) = success(
            "track_id" to trackUri,
            "title" to (title ?: query.title),
            "artist" to (query.artist ?: "Unknown"),
            "playback_state" to "playing",
            "provider" to "Spotify",
            "volume" to volume,
        )

        val attempts = mutableListOf<String>()
        if (trackUri != null) {
            val result = SpotifyAppRemoteManager.playTrack(context, trackUri)
            if (result.started) return playing(result.playingTitle)
            attempts += "App Remote: ${result.detail}"
        }

        val deadline = System.currentTimeMillis() + MUSIC_PLAY_BUDGET_MS
        if (!playViaMediaBrowser(context, song, query)) {
            val intent = if (trackUri != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(trackUri))
            } else {
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, query.title)
                    query.artist?.let { putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                    putExtra("query", song)
                }
            }
            intent.setPackage(SPOTIFY_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchUiIntent(context, intent, "Đang phát nhạc", song)
        }

        // Nothing above reports whether sound started, so poll the audio stack,
        // pressing play on Spotify's own session until our song is the one playing.
        val session = SessionWatch()
        val nudge = { session.observe(pressPlayOnSpotifySession(context, song, query)) }
        while (System.currentTimeMillis() < deadline) {
            val began = awaitMusicActive(audio, true, deadline - System.currentTimeMillis(), nudge)
            if (!began) break
            if (!confirmSustainedPlayback(audio, nudge)) continue
            nudge()
            if (titleMatches(session.lastTitle, query.title)) return playing(session.lastTitle)
            Log.i(TAG, "Spotify is playing '${session.lastTitle}', not '${query.title}'; retrying")
        }

        attempts += when {
            session.seen && !session.loaded -> "Spotify opened but never loaded the track"
            session.loaded -> "Spotify kept playing '${session.lastTitle}'"
            else -> "Spotify never produced audio"
        }
        // A session that appears but never takes the track is how a Free account
        // refuses on-demand playback; that is a subscription limit, not a fault.
        val code = if (session.seen && !session.loaded) "SUBSCRIPTION_INACTIVE" else "PLAYBACK_FAILED"
        return failure(code, "Could not play '$song': ${attempts.joinToString("; ")}")
    }
}

/** `music_stop`: pause every visible media session and confirm the audio stopped. */
class MusicStopHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("PLAYBACK_STOP_FAILED", "No context available")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failure("PLAYBACK_STOP_FAILED", "AudioManager unavailable")
        // Music that already ended counts as stopped; the user cannot act on an error there.
        if (!audio.isMusicActive) return success("playback_state" to "stopped")

        val paused = mediaSessions(context).count { controller ->
            runCatching { controller.transportControls.pause() }.isSuccess
        }
        if (paused == 0) SpotifyAppRemoteManager.pause(context)
        return if (awaitMusicActive(audio, false, PLAYBACK_STOP_TIMEOUT_MS)) {
            success("playback_state" to "stopped")
        } else {
            failure(
                "PLAYBACK_STOP_FAILED",
                "Paused $paused session(s) but audio was still playing after ${PLAYBACK_STOP_TIMEOUT_MS / 1000}s",
            )
        }
    }
}

/** "Title - Artist" or just a title. */
internal data class SongQuery(val title: String, val artist: String?) {
    companion object {
        fun parse(song: String): SongQuery {
            val parts = song.split(" - ", limit = 2)
            return if (parts.size == 2) SongQuery(parts[0].trim(), parts[1].trim()) else SongQuery(song.trim(), null)
        }
    }
}

/** Loose match: Spotify answers "Nơi Này Có Anh" when asked for "nơi này có anh". */
internal fun titleMatches(loaded: String?, requested: String): Boolean {
    val a = loaded?.trim()?.lowercase(Locale.getDefault()).orEmpty()
    val b = requested.trim().lowercase(Locale.getDefault())
    return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
}

private class SessionWatch {
    var seen = false
    var loaded = false
    var lastTitle: String? = null

    fun observe(state: SessionState?) {
        if (state == null) return
        seen = true
        if (state.title != null) {
            loaded = true
            lastTitle = state.title
        }
    }
}

private data class SessionState(val title: String?)

private fun searchExtras(query: SongQuery) = Bundle().apply {
    putString(MediaStore.EXTRA_MEDIA_TITLE, query.title)
    query.artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
}

/** Asks Spotify's MediaBrowserService to play; true when the command was delivered. */
private fun playViaMediaBrowser(context: Context, song: String, query: SongQuery): Boolean {
    val latch = CountDownLatch(1)
    var delivered = false
    Handler(Looper.getMainLooper()).post {
        lateinit var browser: MediaBrowser
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching {
                    val controller = MediaController(context, browser.sessionToken)
                    controller.transportControls.playFromSearch(song, searchExtras(query))
                    controller.transportControls.play()
                    delivered = true
                }
                runCatching { browser.disconnect() }
                latch.countDown()
            }

            override fun onConnectionFailed() = latch.countDown()
            override fun onConnectionSuspended() = latch.countDown()
        }
        browser = MediaBrowser(context, ComponentName(SPOTIFY_PACKAGE, SPOTIFY_BROWSER_SERVICE), callback, null)
        runCatching { browser.connect() }.onFailure { latch.countDown() }
    }
    latch.await(MUSIC_BROWSER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return delivered
}

/** Media sessions this app may see; empty without notification access. */
private fun mediaSessions(context: Context): List<MediaController> {
    if (!hasNotificationAccess(context)) return emptyList()
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        ?: return emptyList()
    return runCatching {
        manager.getActiveSessions(ComponentName(context, MediaControlListenerService::class.java))
    }.getOrDefault(emptyList())
}

internal fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * Presses play on Spotify's session if our song is loaded, otherwise asks it to
 * load our song. Returns what the session shows, or null if Spotify has none.
 */
private fun pressPlayOnSpotifySession(context: Context, song: String, query: SongQuery): SessionState? {
    val controller = mediaSessions(context).firstOrNull { it.packageName == SPOTIFY_PACKAGE } ?: return null
    val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    runCatching {
        if (titleMatches(title, query.title)) {
            controller.transportControls.play()
        } else {
            controller.transportControls.prepareFromSearch(song, searchExtras(query))
            controller.transportControls.playFromSearch(song, searchExtras(query))
        }
    }.onFailure { Log.w(TAG, "Spotify session control failed", it) }
    return SessionState(title)
}

/** Polls `isMusicActive` until it equals [expected], nudging playback during the first seconds. */
private fun awaitMusicActive(
    audio: AudioManager,
    expected: Boolean,
    timeoutMs: Long,
    nudge: (() -> Unit)? = null,
): Boolean {
    val started = System.currentTimeMillis()
    var nextNudgeAt = started + PLAY_NUDGE_INTERVAL_MS
    while (System.currentTimeMillis() < started + timeoutMs) {
        if (audio.isMusicActive == expected) return true
        val now = System.currentTimeMillis()
        if (nudge != null && now >= nextNudgeAt && now - started < PLAY_NUDGE_WINDOW_MS) {
            nudge()
            nextNudgeAt = now + PLAY_NUDGE_INTERVAL_MS
        }
        Thread.sleep(PLAYBACK_POLL_INTERVAL_MS)
    }
    return audio.isMusicActive == expected
}

/** True when audio is still playing [PLAYBACK_HOLD_MS] later; nudges once if it stops. */
private fun confirmSustainedPlayback(audio: AudioManager, nudge: () -> Unit): Boolean {
    val deadline = System.currentTimeMillis() + PLAYBACK_HOLD_MS
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(PLAYBACK_POLL_INTERVAL_MS)
        if (!audio.isMusicActive) {
            nudge()
            return false
        }
    }
    return true
}
