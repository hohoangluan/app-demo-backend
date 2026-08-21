package com.youreyes.app.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.content.ComponentName
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.launch.launchViaFullScreenNotification
import com.youreyes.app.service.MediaControlListenerService
import com.youreyes.app.model.ActionType
import com.youreyes.app.model.ReportErrorPayload
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ActionRegistry"

private val notificationIdSeq = AtomicInteger(1000)

/**
 * Launches [intent] over the lock screen via a full-screen-intent notification
 * (see [launchViaFullScreenNotification]) instead of a raw `startActivity()`,
 * which Android silently drops when called from this background FCM-receiver
 * thread.
 */
private fun launchUiIntent(
    context: Context,
    intent: Intent,
    title: String,
    text: String,
    sendPlayKeyDelayMs: Long = 0L,
) {
    launchViaFullScreenNotification(
        context, intent, notificationIdSeq.incrementAndGet(), title, text, sendPlayKeyDelayMs
    )
}

sealed class ActionExecutionResult {
    data class Success(val resultData: Map<String, Any>) : ActionExecutionResult()
    data class Error(val errorPayload: ReportErrorPayload) : ActionExecutionResult()
}

interface ActionHandler {
    fun execute(context: Context?, paramsJson: String): ActionExecutionResult
}

/**
 * Real ContactsContract lookup, normalized case-insensitive on display name so
 * "Em iu" / "em iu" / "EM IU" all resolve to the same saved contact.
 */
object ContactLookup {
    data class Match(val name: String, val phoneNumber: String)

    fun findByName(context: Context, query: String): List<Match> {
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        if (normalizedQuery.isEmpty()) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val matches = mutableListOf<Match>()
        val seenNumbers = mutableSetOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) return@use
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                if (name.trim().lowercase(Locale.getDefault()) != normalizedQuery) continue
                val digitsOnly = number.filter { it.isDigit() }
                if (seenNumbers.add(digitsOnly)) {
                    matches.add(Match(name, number))
                }
            }
        }
        return matches
    }

    fun maskPhoneNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.length < 3) return "***"
        return "***" + digits.takeLast(3)
    }

    /** True when [value] looks like a raw phone number rather than a contact display name. */
    fun looksLikePhoneNumber(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && trimmed.all { it.isDigit() || it in "+-() " }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Longest we wait for the telephony stack to leave MODE_NORMAL after dialling.
 *
 * Sized for a person, not for the OS: with the screen unlocked the launcher
 * notification does not open the dialler by itself, so this budget has to cover
 * hearing the notification and tapping it. Kept under the backend's own 60s
 * `contact_call` deadline (app/actions.py) so the device reports a real result
 * before the operation is swept to `timed_out`.
 */
private const val CALL_SETTLE_TIMEOUT_MS = 45_000L
private const val CALL_POLL_INTERVAL_MS = 500L

/**
 * True once the handset is ringing out or connected.
 *
 * [AudioManager.getMode] is used rather than `TelephonyManager.getCallState()`
 * because the latter needs READ_PHONE_STATE from API 31 onward, which this app
 * neither requests nor needs.
 */
private fun callInProgress(audioManager: AudioManager): Boolean =
    audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_RINGTONE

/**
 * Places a real phone call and waits until the handset confirms it started.
 *
 * Dials through [TelecomManager.placeCall] rather than `ACTION_CALL`. An
 * `ACTION_CALL` intent is an activity start, so it hits Android's Background
 * Activity Launch restriction: from a background push it only auto-opens while
 * the screen is off, and with the phone unlocked and in use it degrades to a
 * notification the user has to tap. `placeCall` is not an activity start — it
 * hands the number to Telecom, a system app, which brings up the in-call screen
 * itself. Hands-free is the entire point of this product, so the call has to go
 * through whether or not anyone can look at the screen.
 *
 * Returns false when CALL_PHONE is missing, when Telecom refuses the number, or
 * when the line never went off-hook within [CALL_SETTLE_TIMEOUT_MS].
 */
private fun placeRealCall(context: Context, phoneNumber: String): Boolean {
    if (!hasPermission(context, Manifest.permission.CALL_PHONE)) return false
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    val placed = if (telecomManager != null) {
        try {
            @SuppressLint("MissingPermission") // hasPermission(CALL_PHONE) checked above
            telecomManager.placeCall(Uri.fromParts("tel", dialable(phoneNumber), null), Bundle())
            true
        } catch (exc: Exception) {
            Log.e(TAG, "TelecomManager.placeCall failed for $phoneNumber", exc)
            false
        }
    } else {
        false
    }

    if (!placed) {
        // Older/odd devices without a usable TelecomManager still get the
        // notification path, which works with the screen off.
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${dialable(phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchUiIntent(context, intent, "Dang goi dien", "Cuoc goi den $phoneNumber")
    }

    val deadline = System.currentTimeMillis() + CALL_SETTLE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (callInProgress(audioManager)) return true
        Thread.sleep(CALL_POLL_INTERVAL_MS)
    }
    return callInProgress(audioManager)
}

/** Longest we wait for the radio to confirm the SOS message actually went out. */
private const val SMS_RESULT_TIMEOUT_MS = 20_000L
private const val SMS_SENT_ACTION = "com.youreyes.app.SMS_SENT"

/**
 * Sends [text] to [phoneNumber] and reports whether the radio accepted it.
 *
 * `sendTextMessage` returns void and throws only on argument errors, so the
 * previous `runCatching { send(); true }` reported `sms_sent: true` for messages
 * the radio silently dropped. The sent-PendingIntent is the only way to learn
 * the real outcome, so this waits for that broadcast before answering.
 */
/**
 * Contacts are stored the way a person typed them ("+84 797 173 768").
 *
 * Telecom normalises a dial string itself, but `SmsManager` does not: a
 * destination containing spaces fails with RESULT_ERROR_GENERIC_FAILURE, which
 * is why the SOS message never left the handset.
 */
private fun dialable(phoneNumber: String): String =
    PhoneNumberUtils.stripSeparators(phoneNumber)

@SuppressLint("MissingPermission") // caller checks SEND_SMS
private fun sendSmsAndAwaitResult(context: Context, phoneNumber: String, text: String): Boolean {
    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    } ?: return false

    val latch = CountDownLatch(1)
    var resultCode = Activity.RESULT_CANCELED
    val action = "$SMS_SENT_ACTION.${UUID.randomUUID()}"

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            resultCode = getResultCode()
            latch.countDown()
        }
    }
    ContextCompat.registerReceiver(
        context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
    )

    return try {
        val sentIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val destination = dialable(phoneNumber)
        Log.i(TAG, "Sending SOS SMS to $destination (${text.length} chars)")
        smsManager.sendTextMessage(destination, null, text, sentIntent, null)
        val answered = latch.await(SMS_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "SOS SMS to $destination answered=$answered resultCode=$resultCode")
        resultCode == Activity.RESULT_OK
    } catch (exc: Exception) {
        Log.e(TAG, "SOS SMS to $phoneNumber threw", exc)
        false
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

/** Longest we let reverse geocoding run before answering with coordinates alone. */
private const val GEOCODE_TIMEOUT_MS = 5_000L

/** Google Plus Code, e.g. "2R5J+X8M" — a grid reference, not a place name. */
private val PLUS_CODE = Regex("""^[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}$""")

/**
 * Drops the leading Plus Code the geocoder prefixes onto rural addresses.
 *
 * The address is read aloud, and "hai R năm J cộng X tám M" is pure noise to
 * someone listening — the street and district after it carry all the meaning.
 */
private fun withoutPlusCode(address: String): String =
    address.split(",")
        .map { it.trim() }
        .filterNot { PLUS_CODE.matches(it) }
        .joinToString(", ")

/**
 * Turns coordinates into a street address, or null when that is not possible.
 *
 * Worth the extra second: answering "what's the weather here" from raw
 * coordinates makes the assistant round to the nearest big city — measured at
 * Di An, Binh Duong being reported as Ho Chi Minh City, about 20 km off. A blind
 * user has no way to notice that the answer is for somewhere else.
 *
 * Bounded and non-fatal: [Geocoder] calls a backend service that can hang or be
 * missing entirely, and the contract allows a null address, so a slow lookup
 * costs a second rather than the whole action.
 */
private fun reverseGeocode(context: Context, location: Location): String? {
    if (!Geocoder.isPresent()) return null

    // Vietnamese place names, since the address ends up being read aloud.
    val geocoder = Geocoder(context, Locale.forLanguageTag("vi-VN"))
    val latch = CountDownLatch(1)
    var address: String? = null

    Thread {
        address = runCatching {
            @Suppress("DEPRECATION") // async overload is API 33+; this app supports 24+
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { it.getAddressLine(0) ?: listOfNotNull(it.subAdminArea, it.adminArea).joinToString(", ") }
                ?.let(::withoutPlusCode)
                ?.takeIf { it.isNotBlank() }
        }.onFailure { Log.w(TAG, "Reverse geocoding failed", it) }.getOrNull()
        latch.countDown()
    }.start()

    latch.await(GEOCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return address
}

/** Best-effort last known location; returns null when unavailable (no hard failure). */
@SuppressLint("MissingPermission") // permission checked explicitly below; lint can't trace hasPermission()
private fun lastKnownLocation(context: Context): Location? {
    if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
        !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return runCatching {
        lm.getProviders(true)
            .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }
    }.getOrNull()
}

class MusicVolumeHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            val level = when {
                json.has("level") -> json.optInt("level", 50).coerceIn(0, 100)
                json.has("direction") && audioManager != null -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val step = (max / 10).coerceAtLeast(1)
                    val delta = if (json.optString("direction") == "up") step else -step
                    val target = (current + delta).coerceIn(0, max)
                    (target * 100) / max.coerceAtLeast(1)
                }
                else -> 50
            }

            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVol = (level * maxVol) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
            }
            ActionExecutionResult.Success(mapOf("volume_state" to "changed", "level" to level))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_VOLUME", "Invalid music_volume params: ${it.message}")
            )
        }
    }
}

class EmergencyCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(ReportErrorPayload("CALL_FAILED", "No context available"))
        }

        // Per project_context.md 6.11: the emergency contact is local config on the
        // phone, not a request param. Value may be a raw phone number or a contact
        // display name resolved case-insensitively (same as contact_call).
        val prefs = context.getSharedPreferences(FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val configured = prefs.getString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, null)?.trim().orEmpty()
        if (configured.isEmpty()) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("EMERGENCY_CONTACT_NOT_CONFIGURED", "No emergency contact configured on device")
            )
        }

        val resolvedNumber: String = if (ContactLookup.looksLikePhoneNumber(configured)) {
            configured
        } else {
            val matches = ContactLookup.findByName(context, configured)
            if (matches.isEmpty()) {
                return ActionExecutionResult.Error(
                    ReportErrorPayload(
                        "EMERGENCY_CONTACT_NOT_CONFIGURED",
                        "Configured emergency contact '$configured' not found in device contacts"
                    )
                )
            }
            matches.first().phoneNumber
        }

        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_PERMISSION_DENIED", "CALL_PHONE permission not granted")
            )
        }

        // The SMS goes out on its own thread, started before the call but never
        // waited on here. Sending it inline used to hold the emergency call back
        // by however long the radio took to answer — measured at the full 20s
        // timeout on one run, so the person in trouble waited 20s for a dial
        // tone because a text message was slow.
        val location = lastKnownLocation(context)
        val smsResult = java.util.concurrent.CompletableFuture<Boolean>()
        if (hasPermission(context, Manifest.permission.SEND_SMS)) {
            val locationText = if (location != null) {
                "vi tri: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "vi tri khong kha dung"
            }
            Thread {
                smsResult.complete(
                    sendSmsAndAwaitResult(
                        context,
                        resolvedNumber,
                        "[SOS] Can ho tro khan cap. $locationText",
                    )
                )
            }.start()
        } else {
            smsResult.complete(false)
        }

        val called = placeRealCall(context, resolvedNumber)
        // Both are in flight together, so by the time the call settles the SMS
        // has usually resolved too; cap the extra wait so a stuck radio cannot
        // hold the report open.
        val smsSent = try {
            smsResult.get(SMS_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (exc: Exception) {
            Log.w(TAG, "SOS SMS result never arrived", exc)
            false
        }

        if (!called) {
            return ActionExecutionResult.Error(
                ReportErrorPayload(
                    "CALL_FAILED",
                    "SOS SMS sent=$smsSent, but the dialler never started a call within " +
                        "${CALL_SETTLE_TIMEOUT_MS / 1000}s",
                )
            )
        }

        return ActionExecutionResult.Success(
            mapOf(
                "emergency_state" to "completed",
                "attempt" to 1,
                "answered" to false,
                "cycle" to "stopped",
                "contact" to ContactLookup.maskPhoneNumber(resolvedNumber),
                "sms_sent" to smsSent
            )
        )
    }
}

class ContactCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        val name = runCatching { JSONObject(paramsJson).optString("name", "") }.getOrDefault("").trim()
        if (name.isEmpty()) {
            return ActionExecutionResult.Error(ReportErrorPayload("CONTACT_NOT_FOUND", "Contact name must not be empty"))
        }
        if (context == null) {
            return ActionExecutionResult.Error(ReportErrorPayload("CALL_FAILED", "No context available"))
        }
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CONTACT_PERMISSION_DENIED", "READ_CONTACTS permission not granted")
            )
        }

        val matches = ContactLookup.findByName(context, name)
        return when {
            matches.isEmpty() -> ActionExecutionResult.Error(
                ReportErrorPayload("CONTACT_NOT_FOUND", "No contact matched '$name'")
            )
            matches.size > 1 -> ActionExecutionResult.Error(
                ReportErrorPayload(
                    code = "MULTIPLE_CONTACTS_FOUND",
                    message = "Multiple contacts matched",
                    details = mapOf(
                        "candidates" to matches.map { m ->
                            mapOf("name" to m.name, "phone_number" to ContactLookup.maskPhoneNumber(m.phoneNumber))
                        }
                    )
                )
            )
            else -> {
                val match = matches.first()
                if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
                    return ActionExecutionResult.Error(
                        ReportErrorPayload("CALL_PERMISSION_DENIED", "CALL_PHONE permission not granted")
                    )
                }
                if (!placeRealCall(context, match.phoneNumber)) {
                    return ActionExecutionResult.Error(
                        ReportErrorPayload(
                            "CALL_FAILED",
                            "Resolved '${match.name}' but the dialler never started a call within " +
                                "${CALL_SETTLE_TIMEOUT_MS / 1000}s",
                        )
                    )
                }
                ActionExecutionResult.Success(
                    mapOf(
                        "call_state" to "calling",
                        "contact_name" to match.name,
                        "phone_number" to ContactLookup.maskPhoneNumber(match.phoneNumber)
                    )
                )
            }
        }
    }
}

/**
 * Contract action `location_get`: where the handset currently is.
 *
 * The backend already routed this action but no handler existed, so every
 * request died as an unknown action. The glasses assistant needs it to answer
 * questions that only mean anything somewhere — "what's the weather", "what's
 * near me" — instead of inventing a place.
 */
class LocationGetHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CURRENT_LOCATION_UNAVAILABLE", "No context available")
            )
        }
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("LOCATION_PERMISSION_DENIED", "Location permission not granted")
            )
        }

        val location = lastKnownLocation(context)
            ?: return ActionExecutionResult.Error(
                ReportErrorPayload("CURRENT_LOCATION_UNAVAILABLE", "No last known location on this device")
            )

        val capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(location.time))

        return ActionExecutionResult.Success(
            mapOf(
                "lat" to location.latitude,
                "lng" to location.longitude,
                "address" to (reverseGeocode(context, location) ?: JSONObject.NULL),
                "captured_at" to capturedAt,
            )
        )
    }
}

/** Shared song-string parsing: "Title - Artist" (project_context.md 6.6 example) or title only. */
private fun parseSongQuery(song: String): Pair<String, String> {
    val parts = song.split(" - ", limit = 2)
    return if (parts.size == 2) parts[0].trim() to parts[1].trim() else song.trim() to "Unknown"
}

/**
 * Structured "play this specific song" voice-search contract (focus
 * `vnd.android.cursor.item/audio` + explicit artist/title), per
 * MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH docs — the generic wildcard
 * focus used previously only opened search results without auto-playing.
 */
private fun buildPlayFromSearchIntent(song: String, title: String, artist: String): Intent =
    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
        putExtra("query", song)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun openInYouTubeMusic(context: Context, song: String, title: String, artist: String) {
    val ytMusicIntent = buildPlayFromSearchIntent(song, title, artist).apply {
        setPackage("com.google.android.apps.youtube.music")
    }
    val resolved = if (ytMusicIntent.resolveActivity(context.packageManager) != null) {
        ytMusicIntent
    } else {
        buildPlayFromSearchIntent(song, title, artist)
    }
    launchUiIntent(context, resolved, "Dang phat nhac", song, sendPlayKeyDelayMs = 1800L)
}

private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
private const val YOUTUBE_MUSIC_BROWSER_SERVICE = "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"
private const val YT_MUSIC_BROWSER_TIMEOUT_MS = 8_000L

private const val SPOTIFY_PACKAGE = "com.spotify.music"

/** True when Spotify is installed. Requires the `<package>` entry in AndroidManifest.xml's `<queries>` (Android 11+ package visibility) — without it this silently returns false even when Spotify is present. */
private fun isSpotifyInstalled(context: Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null

/**
 * Opens Spotify to a search for [song].
 *
 * Spotify has no equivalent of YouTube Music's `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`
 * contract (that convention is Android/Google-media-app specific; Spotify doesn't
 * implement it), so this only reaches the search results screen — same as YouTube
 * Music's own bare search intent before [playViaYouTubeMusicBrowser]/
 * [pressPlayOnActiveSession] were added. [pressPlayOnActiveSession]'s media-session
 * nudge (used as [MusicPlayHandler]'s retry loop either way) is what actually starts
 * playback; there is no Spotify-specific equivalent of the MediaBrowser bind used for
 * YouTube Music, since Spotify's browse service isn't public API the way YouTube
 * Music's is.
 */
private fun openInSpotify(context: Context, song: String) {
    val uri = Uri.parse("https://open.spotify.com/search/${Uri.encode(song)}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(SPOTIFY_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchUiIntent(context, intent, "Dang mo Spotify", song)
}

/**
 * INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH only queues the track paused (confirmed
 * on-device). YouTube Music's own MediaBrowserService is the real, public,
 * OEM-portable API Android Auto/Assistant use to make a 3rd-party music app
 * start playback directly — binding to it and calling
 * MediaController.transportControls.playFromSearch() actually starts audio,
 * unlike a raw media-key nudge (which needs an already-active session to
 * target). No screen-scraping or accessibility permission involved.
 */
private fun playViaYouTubeMusicBrowser(context: Context, song: String, title: String, artist: String): Boolean {
    val latch = CountDownLatch(1)
    var started = false
    Handler(Looper.getMainLooper()).post {
        var browser: MediaBrowser? = null
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching {
                    val token = browser?.sessionToken
                    if (token != null) {
                        val controller = MediaController(context, token)
                        val extras = Bundle().apply {
                            putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                            putString(MediaStore.EXTRA_MEDIA_TITLE, title)
                        }
                        controller.transportControls.playFromSearch(song, extras)
                        started = true
                    }
                }
                runCatching { browser?.disconnect() }
                latch.countDown()
            }

            override fun onConnectionFailed() {
                latch.countDown()
            }

            override fun onConnectionSuspended() {
                latch.countDown()
            }
        }
        browser = MediaBrowser(
            context,
            android.content.ComponentName(YOUTUBE_MUSIC_PACKAGE, YOUTUBE_MUSIC_BROWSER_SERVICE),
            callback,
            null
        )
        runCatching { browser.connect() }.onFailure { latch.countDown() }
    }
    latch.await(YT_MUSIC_BROWSER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return started
}

/**
 * Longest we wait for a music app to actually start (or stop) producing audio.
 *
 * Same reasoning as [CALL_SETTLE_TIMEOUT_MS]: the budget covers the user tapping
 * the launcher notification plus YouTube Music loading the track, and stays
 * under the backend's 45s `music_play` / 30s `music_stop` deadlines.
 */
private const val PLAYBACK_SETTLE_TIMEOUT_MS = 35_000L
private const val PLAYBACK_STOP_TIMEOUT_MS = 8_000L
private const val PLAYBACK_POLL_INTERVAL_MS = 500L

/** How long to keep nudging a queued-but-paused track before giving up. */
private const val PLAY_NUDGE_WINDOW_MS = 20_000L
private const val PLAY_NUDGE_INTERVAL_MS = 1_500L

/**
 * Presses play on whatever media session YouTube Music published, if we can see it.
 *
 * Returns false when the user has not granted notification access, which is the
 * platform's gate on `getActiveSessions()` — see [MediaControlListenerService].
 * Without it there is no supported way for a background app to start playback in
 * someone else's music app, and `music_play` will honestly report failure rather
 * than pretend.
 */
/** True when the user has switched this app's notification listener on. */
private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun pressPlayOnActiveSession(
    context: Context,
    song: String,
    title: String,
    artist: String,
    preferredPackage: String = YOUTUBE_MUSIC_PACKAGE,
): Boolean {
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        ?: return false
    val listener = ComponentName(context, MediaControlListenerService::class.java)

    val sessions = try {
        manager.getActiveSessions(listener)
    } catch (exc: SecurityException) {
        Log.w(TAG, "No notification access; cannot reach media sessions", exc)
        return false
    }

    // Prefer whichever provider this play attempt actually targeted (Spotify or
    // YouTube Music); fall back to any other active session as a last resort.
    val controller = sessions.firstOrNull { it.packageName == preferredPackage }
        ?: sessions.firstOrNull()
        ?: return false

    return try {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
            putString(MediaStore.EXTRA_MEDIA_TITLE, title)
        }
        controller.transportControls.playFromSearch(song, extras)
        controller.transportControls.play()
        true
    } catch (exc: Exception) {
        Log.e(TAG, "transportControls.play() failed on ${controller.packageName}", exc)
        false
    }
}

/**
 * Polls [AudioManager.isMusicActive] until it matches [expected] or time runs out.
 *
 * This is the only signal here that reflects what the handset is really doing.
 * Launching the intent and calling playFromSearch() both return without saying
 * whether audio ever started, which is how this action came to report "playing"
 * while YouTube Music's session sat in STOPPED.
 *
 * With [nudgePlay] set, a MEDIA_PLAY key is re-sent on a timer for the first
 * [PLAY_NUDGE_WINDOW_MS] of the wait. MEDIA_PLAY_FROM_SEARCH only queues the
 * track paused, so something has to press play — and a single fixed-delay key
 * (the previous 1.8s one in CommandLaunchActivity) fires into nothing, because
 * YouTube Music took about 4.5s to publish its session on this handset.
 * MEDIA_PLAY is not a toggle, so re-sending it cannot pause a track that is
 * already going.
 */
private fun awaitMusicActive(
    audioManager: AudioManager,
    expected: Boolean,
    timeoutMs: Long,
    nudge: (() -> Unit)? = null,
): Boolean {
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs
    var nextNudgeAt = started + PLAY_NUDGE_INTERVAL_MS

    while (System.currentTimeMillis() < deadline) {
        if (audioManager.isMusicActive == expected) return true

        val now = System.currentTimeMillis()
        if (nudge != null && now >= nextNudgeAt && now - started < PLAY_NUDGE_WINDOW_MS) {
            nudge()
            nextNudgeAt = now + PLAY_NUDGE_INTERVAL_MS
        }
        Thread.sleep(PLAYBACK_POLL_INTERVAL_MS)
    }
    return audioManager.isMusicActive == expected
}

/** Contract action `music_play` (project_context.md 6.6). */
class MusicPlayHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val song = json.optString("song", "Unknown")
            val requestedVolume = if (json.has("volume")) json.optInt("volume", 60).coerceIn(0, 100) else 60
            val (title, artist) = parseSongQuery(song)
            // Tracked across the context!=null block below and reused for the result's
            // track_id prefix, so isSpotifyInstalled() is only ever evaluated once.
            var targetPackage: String? = null

            if (context != null) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return ActionExecutionResult.Error(
                        ReportErrorPayload("PLAYBACK_FAILED", "AudioManager unavailable")
                    )

                // Set the volume before starting: coming up from silence afterwards
                // means a blind listener misses the first seconds of the track.
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (requestedVolume * maxVol) / 100, 0)

                // Prefer Spotify when it's installed (per product decision, 2026-08-21),
                // falling back to the existing YouTube Music integration otherwise —
                // this branch is the only thing that changed; music_stop/music_volume
                // already worked provider-agnostically via plain AudioManager calls.
                targetPackage = if (isSpotifyInstalled(context)) {
                    openInSpotify(context, song)
                    SPOTIFY_PACKAGE
                } else {
                    openInYouTubeMusic(context, song, title, artist)
                    playViaYouTubeMusicBrowser(context, song, title, artist)
                    YOUTUBE_MUSIC_PACKAGE
                }

                // The session does not exist yet when the intent returns, so press
                // play on a timer rather than once: whichever attempt lands after
                // the target app publishes its session is the one that starts audio.
                val started = awaitMusicActive(
                    audioManager,
                    expected = true,
                    timeoutMs = PLAYBACK_SETTLE_TIMEOUT_MS,
                    nudge = {
                        if (!pressPlayOnActiveSession(context, song, title, artist, targetPackage)) {
                            audioManager.dispatchMediaKeyEvent(
                                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                            )
                            audioManager.dispatchMediaKeyEvent(
                                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                            )
                        }
                    },
                )

                if (!started) {
                    val hint = if (hasNotificationAccess(context)) {
                        ""
                    } else {
                        " (grant this app notification access so it can press play)"
                    }
                    val providerLabel = if (targetPackage == SPOTIFY_PACKAGE) "Spotify" else "YouTube Music"
                    return ActionExecutionResult.Error(
                        ReportErrorPayload(
                            "PLAYBACK_FAILED",
                            "Opened $providerLabel for '$song' but no audio started within " +
                                "${PLAYBACK_SETTLE_TIMEOUT_MS / 1000}s$hint",
                        )
                    )
                }
            }

            val trackIdPrefix = if (targetPackage == SPOTIFY_PACKAGE) "spotify" else "yt-music"
            ActionExecutionResult.Success(
                mapOf(
                    "track_id" to "$trackIdPrefix-${UUID.randomUUID().toString().take(8)}",
                    "title" to title,
                    "artist" to artist,
                    "playback_state" to "playing",
                    "volume" to requestedVolume
                )
            )
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_FAILED", "Invalid music_play params: ${it.message}")
            )
        }
    }
}

/** Contract action `music_stop` (project_context.md 6.7): pauses whichever app holds audio focus. */
class MusicStopHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            if (context != null) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return ActionExecutionResult.Error(
                        ReportErrorPayload("PLAYBACK_STOP_FAILED", "AudioManager unavailable")
                    )

                if (!audioManager.isMusicActive) {
                    return ActionExecutionResult.Error(
                        ReportErrorPayload("NO_ACTIVE_PLAYBACK", "Nothing is playing on this device")
                    )
                }

                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

                if (!awaitMusicActive(audioManager, expected = false, timeoutMs = PLAYBACK_STOP_TIMEOUT_MS)) {
                    return ActionExecutionResult.Error(
                        ReportErrorPayload(
                            "PLAYBACK_STOP_FAILED",
                            "Sent MEDIA_PAUSE but audio was still playing after " +
                                "${PLAYBACK_STOP_TIMEOUT_MS / 1000}s",
                        )
                    )
                }
            }
            ActionExecutionResult.Success(mapOf("playback_state" to "stopped"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_STOP_FAILED", "Failed to stop playback: ${it.message}")
            )
        }
    }
}

/** Legacy local-demo action, kept for the "media_play" quick-demo button; not a backend contract action. */
class MediaPlayHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val song = json.optString("song", "Track")
            if (context != null) {
                val (title, artist) = parseSongQuery(song)
                openInYouTubeMusic(context, song, title, artist)
            }
            ActionExecutionResult.Success(mapOf("song" to song, "playback_status" to "playing", "app" to "youtube_music"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid media_play params: ${it.message}")
            )
        }
    }
}

private fun openGoogleMapsNavigation(context: Context, lat: Double, lng: Double, address: String) {
    val uri = if (address.isNotBlank()) {
        Uri.parse("google.navigation:q=${Uri.encode(address)}&mode=w")
    } else {
        Uri.parse("google.navigation:q=$lat,$lng&mode=w")
    }
    val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val resolved = if (mapIntent.resolveActivity(context.packageManager) != null) {
        mapIntent
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=${Uri.encode(address)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    launchUiIntent(context, resolved, "Dieu huong di bo", address.ifBlank { "$lat,$lng" })
}

class NavigationStartHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val destObj = json.optJSONObject("destination")
            val lat = destObj?.optDouble("lat", 10.7769) ?: 10.7769
            val lng = destObj?.optDouble("lng", 106.7009) ?: 106.7009
            val address = destObj?.optString("address", "") ?: ""

            if (context != null) {
                openGoogleMapsNavigation(context, lat, lng, address)
            }

            ActionExecutionResult.Success(
                mapOf(
                    "navigation_id" to "nav-${UUID.randomUUID().toString().take(8)}",
                    "navigation_state" to "navigating",
                    "travel_mode" to "walking",
                    "destination" to mapOf("address" to address.ifBlank { null }, "lat" to lat, "lng" to lng)
                )
            )
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("NAVIGATION_START_FAILED", "Invalid navigation_start params: ${it.message}")
            )
        }
    }
}

/**
 * Contract action `navigation_stop` (project_context.md 6.10). Google Maps is
 * launched as an external app (no embedded Navigation SDK in this prototype), so
 * there is no public API to force-stop its active guidance session — this reports
 * the logical stop state; the user still needs to exit turn-by-turn in Maps itself.
 */
class NavigationStopHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val navigationId = json.optString("navigation_id", "nav-unknown")
            ActionExecutionResult.Success(
                mapOf("navigation_id" to navigationId, "navigation_state" to "stopped")
            )
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("NAVIGATION_STOP_FAILED", "Invalid navigation_stop params: ${it.message}")
            )
        }
    }
}

/**
 * Opens the real Grab app to the requested pickup/dropoff via Grab's documented
 * App Link (works without OAuth login; booking itself still happens inside the
 * real Grab app). Uber sold its whole Southeast Asia operation to Grab in 2018,
 * so the Uber app has no live driver supply in Vietnam and always shows an empty
 * "no rides" screen here — Grab is the actual operating ride-hailing app in this
 * market. No programmatic price/ETA is available without Grab's privileged
 * partner API, so quote figures are demo-deterministic, not a real Grab quote.
 */
private fun openGrabDeepLink(
    context: Context,
    dropoffLat: Double,
    dropoffLng: Double,
    nickname: String,
    pickupLat: Double? = null,
    pickupLng: Double? = null,
) {
    val pickupParams = if (pickupLat != null && pickupLng != null) {
        "&pickupLatitude=$pickupLat&pickupLongitude=$pickupLng"
    } else {
        ""
    }
    val uri = Uri.parse(
        "grab://open?screenType=BOOKING$pickupParams" +
            "&dropOffLatitude=$dropoffLat&dropOffLongitude=$dropoffLng" +
            "&dropOffAddress=${Uri.encode(nickname)}"
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.grabtaxi.passenger")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val resolved = if (intent.resolveActivity(context.packageManager) != null) {
        intent
    } else {
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchUiIntent(context, resolved, "Dat xe Grab", nickname)
}

class RideQuoteHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val destObj = json.optJSONObject("destination")
            val lat = destObj?.optDouble("lat", 10.7721) ?: 10.7721
            val lng = destObj?.optDouble("lng", 106.6578) ?: 106.6578
            val address = destObj?.optString("address", "diem den") ?: "diem den"

            // Prefer the request's own current_location over Grab's own GPS resolution
            // (more reliable, and doesn't depend on Grab having a fresh location fix).
            val currentLocation = json.optJSONObject("current_location")
            val pickupLat = currentLocation?.takeIf { it.has("lat") }?.optDouble("lat")
            val pickupLng = currentLocation?.takeIf { it.has("lng") }?.optDouble("lng")

            if (context != null) {
                openGrabDeepLink(context, lat, lng, address, pickupLat, pickupLng)
            }

            val quoteId = "quote-${UUID.randomUUID().toString().take(8)}"
            ActionExecutionResult.Success(
                mapOf(
                    "quote_id" to quoteId,
                    "product_type" to "GrabCar",
                    "price_estimate" to mapOf("currency" to "VND", "amount" to 85000),
                    "eta_minutes" to 6,
                    "expires_at" to isoInstantPlusMinutes(5)
                )
            )
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("NO_RIDE_AVAILABLE", "Invalid ride_quote params: ${it.message}")
            )
        }
    }
}

class RideConfirmHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val quoteId = json.optString("quote_id", "quote-unknown")
            val confirm = json.optBoolean("confirm", false)

            if (confirm) {
                if (context != null) {
                    // Re-open Grab so the user finishes the real booking in-app.
                    openGrabDeepLink(context, 10.7721, 106.6578, "diem den")
                }
                ActionExecutionResult.Success(
                    mapOf(
                        "quote_id" to quoteId,
                        "ride_id" to "ride-${UUID.randomUUID().toString().take(8)}",
                        "ride_status" to "requested"
                    )
                )
            } else {
                ActionExecutionResult.Success(
                    mapOf("quote_id" to quoteId, "ride_id" to JSONObject.NULL, "ride_status" to "cancelled")
                )
            }
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("RIDE_CONFIRM_FAILED", "Invalid ride_confirm params: ${it.message}")
            )
        }
    }
}

// java.time.Instant/DateTimeFormatter.ISO_INSTANT require API 26+; minSdk here is 24
// (no core library desugaring configured), so use SimpleDateFormat like
// CommandDispatcher's report timestamp does.
private fun isoInstantPlusMinutes(minutes: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    return sdf.format(java.util.Date(System.currentTimeMillis() + minutes * 60_000L))
}

class QuotesSpeakHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val text = json.optString("text", "Hello world")
            ActionExecutionResult.Success(mapOf("spoken_text" to text, "tts_status" to "completed"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid quotes_speak params: ${it.message}")
            )
        }
    }
}

/**
 * Display-name prefix every camera_capture photo is saved under, so the Album screen
 * ([com.youreyes.app.ui.album.AlbumViewModel]) can filter MediaStore to just
 * glasses-triggered captures instead of the phone's entire camera roll. Also makes
 * every capture a MediaStore row this app's own `ContentResolver.insert()` created, so
 * Album can read them back without any READ_MEDIA_IMAGES/READ_MEDIA_VIDEO runtime
 * permission — Android's Scoped Storage always lets an app see media rows it owns.
 */
const val CAMERA_CAPTURE_NAME_PREFIX = "YourEyes_"

/** Inserts a pending MediaStore row for a new photo, or null if the insert itself failed. */
private fun createCaptureOutputUri(context: Context): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$CAMERA_CAPTURE_NAME_PREFIX${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}

class CameraCaptureHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val mode = json.optString("mode", "photo")
            if (context != null) {
                val outputUri = createCaptureOutputUri(context)
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (outputUri != null) {
                        putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                    }
                }
                // Was a raw startActivity() before — Android silently drops that when
                // called from this background FCM-receiver/service thread, same class
                // of bug launchUiIntent already exists to fix for every other action.
                launchUiIntent(context, intent, "Mo camera", "Chup anh qua kinh")
            }
            ActionExecutionResult.Success(mapOf("mode" to mode, "capture_status" to "captured"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid camera_capture params: ${it.message}")
            )
        }
    }
}

class DisplayShowHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val message = json.optString("message", "Display Notice")
            ActionExecutionResult.Success(mapOf("message" to message, "display_status" to "rendered"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid display_show params: ${it.message}")
            )
        }
    }
}

class SystemSettingsHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val key = json.optString("setting_key", "wifi")
            if (context != null) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            ActionExecutionResult.Success(mapOf("setting_key" to key, "status" to "opened"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid system_settings params: ${it.message}")
            )
        }
    }
}

class UnsupportedActionHandler(private val actionName: String) : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return ActionExecutionResult.Error(
            ReportErrorPayload("UNSUPPORTED_ACTION", "Action '$actionName' is not supported by Android client")
        )
    }
}

class ActionRegistry {
    private val handlers = mapOf<String, ActionHandler>(
        ActionType.RIDE_QUOTE.value to RideQuoteHandler(),
        ActionType.RIDE_CONFIRM.value to RideConfirmHandler(),
        ActionType.MUSIC_PLAY.value to MusicPlayHandler(),
        ActionType.MUSIC_STOP.value to MusicStopHandler(),
        ActionType.MUSIC_VOLUME.value to MusicVolumeHandler(),
        ActionType.NAVIGATION_START.value to NavigationStartHandler(),
        ActionType.NAVIGATION_STOP.value to NavigationStopHandler(),
        ActionType.EMERGENCY_CALL.value to EmergencyCallHandler(),
        ActionType.CONTACT_CALL.value to ContactCallHandler(),
        ActionType.LOCATION_GET.value to LocationGetHandler(),
        ActionType.QUOTES_SPEAK.value to QuotesSpeakHandler(),
        ActionType.MEDIA_PLAY.value to MediaPlayHandler(),
        ActionType.CAMERA_CAPTURE.value to CameraCaptureHandler(),
        ActionType.DISPLAY_SHOW.value to DisplayShowHandler(),
        ActionType.SYSTEM_SETTINGS.value to SystemSettingsHandler()
    )

    fun getHandler(action: String): ActionHandler {
        return handlers[action] ?: UnsupportedActionHandler(action)
    }
}
