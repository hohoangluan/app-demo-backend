package com.youreyes.app.media

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.youreyes.app.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays a chosen track in the Spotify app through Spotify's App Remote SDK.
 *
 * This exists because every other route was measured failing on-device:
 *
 *  - `ACTION_VIEW` on a `spotify:track:` uri only navigates Spotify's UI. With
 *    something already playing it leaves that track alone, so the action would
 *    report the requested song over whatever was actually coming out.
 *  - Spotify's `MediaBrowserService` answers `onConnectionFailed` for clients
 *    that are not whitelisted (Android Auto and friends).
 *  - `MediaController.transportControls.playFromSearch()` is advertised in the
 *    session's action mask but does not change the track.
 *
 * App Remote is Spotify's supported API for exactly this, and it binds to a
 * service rather than starting an activity — so it is not subject to Android's
 * Background Activity Launch restriction and works with the handset locked and
 * the screen off, which is the whole point of this product.
 *
 * It also reports back what is playing. [PlayResult.playingUri] comes from
 * Spotify's own player state, so "did the song we asked for actually start" is
 * answered by Spotify rather than inferred from whether any audio exists.
 */
object SpotifyAppRemoteManager {
    private const val TAG = "SpotifyAppRemote"

    /** Longest we wait for the Spotify app to accept a connection. */
    private const val CONNECT_TIMEOUT_MS = 8_000L

    /** Longest we wait, after play(), for Spotify to report our track un-paused. */
    private const val PLAY_TIMEOUT_MS = 15_000L

    data class PlayResult(
        val started: Boolean,
        val playingUri: String?,
        val playingTitle: String?,
        val detail: String,
    )

    /**
     * Asks Spotify to play [trackUri] and waits until Spotify says it is.
     *
     * Blocking: the caller is a background FCM worker that must report a real
     * outcome, and there is nothing useful for it to do while it waits.
     */
    fun playTrack(context: Context, trackUri: String): PlayResult {
        val appRemote = connectBlocking(context)
            ?: return PlayResult(false, null, null, "could not connect to the Spotify app")

        return try {
            val latch = CountDownLatch(1)
            // Written on Spotify's callback thread, read on this one, so they
            // have to carry their own memory barrier.
            val lastUri = AtomicReference<String?>(null)
            val lastTitle = AtomicReference<String?>(null)
            val lastPaused = AtomicBoolean(true)

            appRemote.playerApi.play(trackUri)

            // Spotify pushes state changes as they happen, so this settles as soon
            // as the track is really rolling instead of on a fixed sleep.
            val subscription = appRemote.playerApi.subscribeToPlayerState()
            subscription.setEventCallback { state ->
                lastUri.set(state.track?.uri)
                lastTitle.set(state.track?.name)
                lastPaused.set(state.isPaused)
                if (state.track?.uri == trackUri && !state.isPaused) {
                    latch.countDown()
                }
            }

            val settled = latch.await(PLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val uri = lastUri.get()
            val detail = when {
                settled -> "playing"
                uri == null -> "Spotify never reported a track"
                uri != trackUri -> "Spotify stayed on '${lastTitle.get()}'"
                lastPaused.get() -> "Spotify loaded the track but left it paused"
                else -> "unknown state"
            }
            Log.i(
                TAG,
                "play($trackUri) -> settled=$settled uri=$uri " +
                    "title=${lastTitle.get()} paused=${lastPaused.get()}"
            )
            PlayResult(settled, uri, lastTitle.get(), detail)
        } catch (exc: Exception) {
            Log.e(TAG, "play($trackUri) threw", exc)
            PlayResult(false, null, null, "Spotify rejected the play command: ${exc.message}")
        } finally {
            disconnect(appRemote)
        }
    }

    /**
     * Triggers Spotify's one-time consent dialog, if it is still needed.
     *
     * Must be called from a foreground Activity: App Remote can only raise its
     * auth view when the app already owns the screen, and the real caller
     * ([playTrack]) runs from a background push with the handset locked, where
     * Android drops any attempt to show UI. So the grant is collected here and
     * spent there.
     *
     * Fire-and-forget — nothing waits on the result, and a connection that
     * succeeds is closed again immediately. The only goal is the dialog.
     */
    fun ensureAuthorized(activity: Activity) {
        if (!SpotifyAppRemote.isSpotifyInstalled(activity)) {
            Log.i(TAG, "Spotify is not installed; skipping App Remote authorisation")
            return
        }
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                Log.i(TAG, "Spotify already authorised; playback commands will work")
                runCatching { SpotifyAppRemote.disconnect(appRemote) }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "Spotify authorisation not granted yet: ${error.message}")
                // App Remote reports the missing grant but cannot collect it —
                // the login activity lives in the spotify-auth library, so ask
                // for it explicitly. app-remote scope is what App Remote needs.
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        val request = AuthorizationRequest.Builder(
                            BuildConfig.SPOTIFY_CLIENT_ID,
                            AuthorizationResponse.Type.CODE,   // implicit grant is retired; Spotify's web auth answers "response_type must be code"
                            BuildConfig.SPOTIFY_REDIRECT_URI,
                        ).setScopes(arrayOf("app-remote-control", "user-modify-playback-state"))
                            .build()
                        Log.i(
                            TAG,
                            "opening Spotify login: clientId=${BuildConfig.SPOTIFY_CLIENT_ID} " +
                                "redirect=${BuildConfig.SPOTIFY_REDIRECT_URI}"
                        )
                        AuthorizationClient.openLoginActivity(activity, AUTH_REQUEST_CODE, request)
                    }.onFailure { Log.e(TAG, "openLoginActivity threw", it) }
                }
            }
        })
    }

    /** Arbitrary, only has to be unique within [Activity.onActivityResult]. */
    const val AUTH_REQUEST_CODE = 7311

    /**
     * Runs the consent flow through a browser instead of the Spotify app.
     *
     * The in-app SSO path answers `AUTHENTICATION_SERVICE_UNAVAILABLE` and closes
     * itself in about 40ms when the Spotify app will not vouch for this caller,
     * which tells nobody anything. Spotify's web auth shows the real reason on a
     * readable page — a wrong redirect URI, a client id that has not registered
     * this package and signing fingerprint, or an account that has not been added
     * to the app while it is still in development mode.
     */
    fun openLoginInBrowser(activity: Activity) {
        val request = AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.CODE,   // implicit grant is retired; Spotify's web auth answers "response_type must be code"
            BuildConfig.SPOTIFY_REDIRECT_URI,
        ).setScopes(arrayOf("app-remote-control", "user-modify-playback-state"))
            .build()
        runCatching { AuthorizationClient.openLoginInBrowser(activity, request) }
            .onFailure { Log.e(TAG, "openLoginInBrowser threw", it) }
    }

    /** Pauses whatever Spotify is playing. Returns whether Spotify confirmed it. */
    fun pause(context: Context): Boolean {
        val appRemote = connectBlocking(context) ?: return false
        return try {
            val latch = CountDownLatch(1)
            appRemote.playerApi.pause()
            appRemote.playerApi.subscribeToPlayerState().setEventCallback { state ->
                if (state.isPaused) latch.countDown()
            }
            latch.await(PLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (exc: Exception) {
            Log.e(TAG, "pause() threw", exc)
            false
        } finally {
            disconnect(appRemote)
        }
    }

    /**
     * Connects on the main thread and waits for the answer.
     *
     * `SpotifyAppRemote.connect()` must be called from the main looper; this is
     * invoked from an FCM worker thread, hence the hop.
     */
    private fun connectBlocking(context: Context): SpotifyAppRemote? {
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            // The first connection needs the user to authorise this app once.
            // After that Spotify remembers and connects without any UI, which is
            // what makes later hands-free requests work on a locked handset.
            .showAuthView(true)
            .build()

        val latch = CountDownLatch(1)
        // Set on the main thread, read here: needs its own barrier.
        val connected = AtomicReference<SpotifyAppRemote?>(null)

        Handler(Looper.getMainLooper()).post {
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    connected.set(appRemote)
                    latch.countDown()
                }

                override fun onFailure(error: Throwable) {
                    Log.w(TAG, "App Remote connection failed: ${error.message}")
                    latch.countDown()
                }
            })
        }

        if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "App Remote connection timed out after ${CONNECT_TIMEOUT_MS}ms")
        }
        return connected.get()
    }

    private fun disconnect(appRemote: SpotifyAppRemote) {
        // Must also happen on the main thread, and must not take the result down
        // with it if Spotify has already gone away.
        Handler(Looper.getMainLooper()).post {
            runCatching { SpotifyAppRemote.disconnect(appRemote) }
        }
    }
}
