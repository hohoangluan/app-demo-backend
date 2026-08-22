package com.youreyes.app.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
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

private const val CALL_CONTROL_TIMEOUT_MS = 2_000L

@SuppressLint("MissingPermission")
private fun currentPhoneCallState(context: Context): Int? {
    if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) return null
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return null
    @Suppress("DEPRECATION")
    return telephony.callState
}

private fun awaitPhoneCallState(context: Context, expected: Int): Boolean {
    val deadline = System.currentTimeMillis() + CALL_CONTROL_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (currentPhoneCallState(context) == expected) return true
        Thread.sleep(100)
    }
    return currentPhoneCallState(context) == expected
}

/** T19 action sent by the glasses server while its 15-second call window is open. */
class CallAnswerHandler : ActionHandler {
    @SuppressLint("MissingPermission")
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "No context available")
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_UNSUPPORTED", "Answering requires Android 8 or newer")
            )
        }
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ||
            !hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        ) {
            return ActionExecutionResult.Error(
                ReportErrorPayload(
                    "CALL_PERMISSION_DENIED",
                    "ANSWER_PHONE_CALLS and READ_PHONE_STATE permissions are required",
                )
            )
        }
        if (currentPhoneCallState(context) != TelephonyManager.CALL_STATE_RINGING) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("NO_INCOMING_CALL", "No ringing call is available to answer")
            )
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "TelecomManager unavailable")
            )
        return runCatching {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            if (!awaitPhoneCallState(context, TelephonyManager.CALL_STATE_OFFHOOK)) {
                ActionExecutionResult.Error(
                    ReportErrorPayload("CALL_CONTROL_FAILED", "The call remained ringing after answer")
                )
            } else {
                ActionExecutionResult.Success(mapOf("call_state" to "answered"))
            }
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "Unable to answer call: ${it.message}")
            )
        }
    }
}

/** T19 rejection; TelecomManager.endCall() returns false when nothing was rejected. */
class CallRejectHandler : ActionHandler {
    @SuppressLint("MissingPermission")
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "No context available")
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_UNSUPPORTED", "Rejecting requires Android 9 or newer")
            )
        }
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ||
            !hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        ) {
            return ActionExecutionResult.Error(
                ReportErrorPayload(
                    "CALL_PERMISSION_DENIED",
                    "ANSWER_PHONE_CALLS and READ_PHONE_STATE permissions are required",
                )
            )
        }
        if (currentPhoneCallState(context) != TelephonyManager.CALL_STATE_RINGING) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("NO_INCOMING_CALL", "No ringing call is available to reject")
            )
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "TelecomManager unavailable")
            )
        return runCatching {
            @Suppress("DEPRECATION")
            if (telecom.endCall()) {
                ActionExecutionResult.Success(mapOf("call_state" to "rejected"))
            } else {
                ActionExecutionResult.Error(
                    ReportErrorPayload("CALL_CONTROL_FAILED", "Telecom did not reject a call")
                )
            }
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("CALL_CONTROL_FAILED", "Unable to reject call: ${it.message}")
            )
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

/** Stands in for the artist when the request only named a song. */
private const val UNKNOWN_ARTIST = "Unknown"

/** Shared song-string parsing: "Title - Artist" (project_context.md 6.6 example) or title only. */
private fun parseSongQuery(song: String): Pair<String, String> {
    val parts = song.split(" - ", limit = 2)
    return if (parts.size == 2) parts[0].trim() to parts[1].trim() else song.trim() to UNKNOWN_ARTIST
}

private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
private const val YOUTUBE_MUSIC_BROWSER_SERVICE = "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"
private const val SPOTIFY_MUSIC_PACKAGE = "com.spotify.music"
private const val SPOTIFY_BROWSER_SERVICE =
    "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService"
private const val MUSIC_BROWSER_TIMEOUT_MS = 5_000L

/** A music app we can drive through its own MediaBrowserService. */
private data class MusicProvider(
    val label: String,
    val packageName: String,
    val browserService: String,
)

/**
 * Who gets asked to play. Spotify is the product's music app.
 *
 * YouTube Music is deliberately not in this list even though it is the one that
 * was measured loading and briefly playing the requested track on this handset,
 * while Spotify published a session that never carried any metadata. Falling
 * back to it would start a different app than the user expects, and the constant
 * above is kept only so the manifest `<queries>` entry and this note stay
 * discoverable if that decision is ever revisited.
 */
private val MUSIC_PROVIDERS = listOf(
    MusicProvider("Spotify", SPOTIFY_MUSIC_PACKAGE, SPOTIFY_BROWSER_SERVICE),
)

private fun isInstalled(context: Context, packageName: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

/**
 * Queues [song] inside [provider] using the platform's voice-search contract.
 *
 * Needed because [playViaMediaBrowser] is refused on this handset: Spotify and
 * YouTube Music both return null from `onGetRoot()` for callers that are not
 * whitelisted (Android Auto, Assistant), so their MediaBrowserService answers
 * `onConnectionFailed`. Measured, both of them.
 *
 * This intent does reach the app and loads the correct track — verified on-device
 * by the resulting session carrying the right title and artist — but it leaves
 * playback PAUSED. Pressing play is [pressPlayOnActiveSession]'s job.
 *
 * Sent through a full-screen-intent notification rather than `startActivity()`
 * because this runs on a background FCM thread, where Android silently drops a
 * bare activity start.
 */
/**
 * Opens an exact track by its `spotify:track:<id>` uri.
 *
 * Preferred over a search when the backend could resolve one, because a search
 * leaves the app to pick among covers, remixes and karaoke versions of the same
 * title, while the uri names one recording. The backend resolves it through the
 * Spotify Search API (`services/spotify.py`).
 *
 * This only loads the track — Spotify opens it and waits — so the caller still
 * has to press play through the session, same as the search route.
 */
private fun openTrackUri(context: Context, provider: MusicProvider, uri: String, song: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        setPackage(provider.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchUiIntent(context, intent, "Dang phat nhac", song)
}

private fun queueViaSearchIntent(
    context: Context,
    provider: MusicProvider,
    song: String,
    title: String,
    artist: String,
) {
    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        setPackage(provider.packageName)
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, title)
        if (artist.isNotBlank() && artist != UNKNOWN_ARTIST) {
            putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        }
        // SearchManager.QUERY, spelled out to avoid pulling in the whole class.
        putExtra("query", song)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    launchUiIntent(context, intent, "Dang phat nhac", song)
}

/**
 * Asks [provider] to search for [song] and start playing it.
 *
 * Binding the music app's own MediaBrowserService and calling
 * `transportControls.playFromSearch()` is the public, OEM-portable API that
 * Android Auto and Assistant use to make a third-party music app start playing.
 * Two properties are what this action needs:
 *
 *  - It is **not** an activity start, so Android's Background Activity Launch
 *    restriction does not apply and it works with the screen off and the handset
 *    locked. Someone who cannot look at the phone is the entire use case.
 *  - It takes a search string, not a catalog id, so it does not depend on the
 *    Spotify Web API — which currently answers 403 for this app's credentials
 *    and therefore cannot supply a trustworthy `spotify:track:` URI.
 *
 * `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` was the previous approach and only
 * queues the track *paused* (confirmed on-device), which is why it is gone.
 *
 * Returns whether the command was **delivered**, not whether audio started —
 * only [awaitMusicActive] can answer that.
 */
private fun playViaMediaBrowser(
    context: Context,
    provider: MusicProvider,
    song: String,
    title: String,
    artist: String,
): Boolean {
    val latch = CountDownLatch(1)
    var delivered = false
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
                        controller.transportControls.play()
                        delivered = true
                    }
                }
                runCatching { browser?.disconnect() }
                latch.countDown()
            }

            override fun onConnectionFailed() {
                Log.w(TAG, "${provider.label}: MediaBrowser connection refused")
                latch.countDown()
            }

            override fun onConnectionSuspended() {
                latch.countDown()
            }
        }
        browser = MediaBrowser(
            context,
            ComponentName(provider.packageName, provider.browserService),
            callback,
            null
        )
        runCatching { browser.connect() }.onFailure {
            Log.w(TAG, "${provider.label}: MediaBrowser.connect() threw", it)
            latch.countDown()
        }
    }
    latch.await(MUSIC_BROWSER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return delivered
}

/**
 * Longest the whole `music_play` action may spend waiting for audio, across
 * every provider it tries.
 *
 * This is a budget for the action, not for one app, because [MUSIC_PROVIDERS] is
 * tried in order and each attempt costs real seconds. Giving each provider its
 * own 35s wait would total 70s and blow through the backend's 45s `music_play`
 * deadline (app/actions.py), so the operation would be swept to `timed_out`
 * while the handset was still working — the device's honest answer would never
 * be heard. 38s leaves the backend a few seconds of headroom.
 */
private const val MUSIC_PLAY_BUDGET_MS = 38_000L
private const val PLAYBACK_STOP_TIMEOUT_MS = 8_000L
private const val PLAYBACK_POLL_INTERVAL_MS = 500L

/**
 * How long audio has to keep coming before the track counts as playing.
 *
 * Measured on this handset: YouTube Music produced 1.8s of audio and then paused
 * itself. The first `isMusicActive` sample was true, so a handler that answered
 * on that sample would have reported "playing" for a song the user heard a
 * second of — the same lie this action was fixed to stop telling, just harder to
 * catch.
 */
private const val PLAYBACK_HOLD_MS = 4_000L

/**
 * True when audio is still coming out [PLAYBACK_HOLD_MS] after it first appeared.
 *
 * Nudges once and returns false the moment it goes quiet, leaving the caller to
 * decide whether there is still budget to try again.
 */
private fun confirmSustainedPlayback(
    audioManager: AudioManager,
    holdMs: Long,
    nudge: () -> Unit,
): Boolean {
    val deadline = System.currentTimeMillis() + holdMs
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(PLAYBACK_POLL_INTERVAL_MS)
        if (!audioManager.isMusicActive) {
            nudge()
            return false
        }
    }
    return true
}

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

/**
 * What one nudge saw.
 *
 * The two flags separate the failure modes that matter: [sessionFound] false
 * means the music app never came up at all, while [sessionFound] true with
 * [metadataPresent] false means it came up and refused to take the track — the
 * difference between "could not open Spotify" and "Spotify will not play this".
 */
private data class NudgeOutcome(
    val sessionFound: Boolean,
    val metadataPresent: Boolean,
    /** Title the app says is loaded, so the caller can check it is the right song. */
    val loadedTitle: String? = null,
)

/**
 * True when [loaded] is the song that was asked for.
 *
 * Without this check the action reports the song it *requested* while the phone
 * plays whatever was already queued: asked for "Lạc Trôi" with Spotify already
 * playing "Nơi Này Có Anh", `isMusicActive` was true immediately and the action
 * answered `playing: Lạc Trôi` over the wrong song. Audio coming out is not
 * evidence that it is the right audio.
 *
 * Matching is loose on purpose — Spotify answers "Nơi Này Có Anh" for a request
 * of "nơi này có anh" — so it compares case-insensitively and accepts either
 * string containing the other.
 */
private fun titleMatches(loaded: String?, requested: String): Boolean {
    val a = loaded?.trim()?.lowercase(Locale.getDefault()).orEmpty()
    val b = requested.trim().lowercase(Locale.getDefault())
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

private fun pressPlayOnActiveSession(
    context: Context,
    packageName: String,
    song: String,
    title: String,
    artist: String,
): NudgeOutcome {
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        ?: return NudgeOutcome(false, false)
    val listener = ComponentName(context, MediaControlListenerService::class.java)

    val sessions = try {
        manager.getActiveSessions(listener)
    } catch (exc: SecurityException) {
        Log.w(TAG, "No notification access; cannot reach media sessions", exc)
        return NudgeOutcome(false, false)
    }

    Log.i(
        TAG,
        "nudge $packageName: visible sessions=" +
            sessions.joinToString {
                "${it.packageName}(meta=${it.metadata != null}," +
                    "state=${it.playbackState?.state},actions=${it.playbackState?.actions})"
            }
    )

    // Strictly this provider. The fallback here used to be "any session", which
    // presses play on whatever else happens to be open — in practice the empty
    // Spotify session left behind by the previous provider — instead of the app
    // we just queued.
    val controller = sessions.firstOrNull { it.packageName == packageName }
        ?: return NudgeOutcome(false, false)

    val outcome = NudgeOutcome(
        sessionFound = true,
        metadataPresent = controller.metadata != null,
        loadedTitle = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
    )

    try {
        if (titleMatches(outcome.loadedTitle, title)) {
            // Our song is loaded and sitting in the queue: press play and nothing
            // else. Re-issuing a search on every nudge restarts it, so the 1.5s
            // loop would keep resetting the queue and it would never start.
            controller.transportControls.play()
        } else {
            // Either nothing is loaded, or the app is sitting on a different
            // song. Pressing play here would start someone else's track and the
            // action would then report ours as playing.
            val extras = Bundle().apply {
                putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                putString(MediaStore.EXTRA_MEDIA_TITLE, title)
            }
            // Two ways to get a track into the queue, and Spotify's session
            // advertises both (actions=141312 carries PREPARE_FROM_SEARCH and
            // PLAY_FROM_SEARCH). prepareFromSearch goes first because it is the
            // weaker request — load it, do not demand playback — and an app that
            // refuses to start audio on its own may still accept being queued.
            // Once it lands, metadata appears, the branch above takes over and
            // presses play on a track that is already sitting there.
            controller.transportControls.prepareFromSearch(song, extras)
            controller.transportControls.playFromSearch(song, extras)
        }
    } catch (exc: Exception) {
        Log.e(TAG, "transportControls play failed on ${controller.packageName}", exc)
    }
    return outcome
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

/**
 * Contract action `music_play` (project_context.md 6.6).
 *
 * Reports `playing` only when the audio stack confirms sound is coming out.
 *
 * This handler previously called into the music app and then returned
 * `playback_state: "playing"` unconditionally, discarding the launcher's own
 * boolean. Every failure — wrong catalog id, Spotify Free refusing an on-demand
 * track, the app never starting — was reported to the backend as success, and
 * the glasses told the user their song was playing into silence. Someone who
 * cannot see the screen has no way to catch that lie, so the success path is now
 * gated on [awaitMusicActive], which polls [AudioManager.isMusicActive].
 */
class MusicPlayHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_FAILED", "Invalid music_play params: ${it.message}")
            )
        }

        val song = json.optString("song", "").trim()
        if (song.isEmpty()) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("SONG_NOT_FOUND", "music_play called without a song")
            )
        }
        val requestedVolume = if (json.has("volume")) json.optInt("volume", 60).coerceIn(0, 100) else 60
        // Resolved by the backend from the Spotify Search API; empty when that
        // lookup failed, in which case we fall back to an in-app search.
        val spotifyUri = json.optString("spotify_uri", "")
        val (title, artist) = parseSongQuery(song)

        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_FAILED", "No context available")
            )
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_FAILED", "AudioManager unavailable")
            )

        // Raise the stream before the first note: starting a track into a muted
        // stream looks exactly like "nothing played" to someone listening, and
        // isMusicActive would still read true, so we would report success.
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (requestedVolume * maxVol) / 100, 0)

        val deadline = System.currentTimeMillis() + MUSIC_PLAY_BUDGET_MS
        val attempts = mutableListOf<String>()

        // Spotify App Remote first: the only route that both starts a chosen track
        // and reports back what is actually playing. Everything below it was
        // measured either refusing us or silently leaving the previous song on.
        if (spotifyUri.startsWith("spotify:track:") && isInstalled(context, SPOTIFY_MUSIC_PACKAGE)) {
            val result = com.youreyes.app.media.SpotifyAppRemoteManager.playTrack(context, spotifyUri)
            if (result.started) {
                Log.i(TAG, "music_play: App Remote is playing '${result.playingTitle}'")
                return ActionExecutionResult.Success(
                    mapOf(
                        // Spotify's own answer for what is on, not the request.
                        "title" to (result.playingTitle ?: title),
                        "artist" to artist,
                        "playback_state" to "playing",
                        "provider" to "Spotify",
                        "volume" to requestedVolume
                    )
                )
            }
            attempts += "Spotify App Remote: ${result.detail}"
            Log.w(TAG, "music_play: App Remote did not play - ${result.detail}")
        }

        // Did the music app ever come up, and did it ever accept the track?
        // Together these turn a bare "it did not play" into a reason the user can
        // act on — see the error code chosen at the end.
        var sessionSeen = false
        var trackLoaded = false
        // Last title the provider reported. Audio alone does not prove the right
        // song is playing, so success is gated on this matching the request.
        var lastTitle: String? = null

        for ((index, provider) in MUSIC_PROVIDERS.withIndex()) {
            if (!isInstalled(context, provider.packageName)) {
                attempts += "${provider.label}: not installed"
                continue
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                attempts += "${provider.label}: no time left in the ${MUSIC_PLAY_BUDGET_MS / 1000}s budget"
                break
            }
            // Leave the last provider whatever is left; split evenly before that
            // so a silent first choice cannot eat the whole budget.
            val providersLeft = MUSIC_PROVIDERS.size - index
            val slice = if (providersLeft <= 1) remaining else remaining / providersLeft

            // Preferred route, but both big music apps refuse non-whitelisted
            // MediaBrowser clients on this handset, so a refusal is expected
            // rather than fatal — the search intent still gets the track loaded.
            if (!playViaMediaBrowser(context, provider, song, title, artist)) {
                // An exact track uri names one recording; a search leaves the app
                // choosing between covers and remixes of the same title.
                if (spotifyUri.startsWith("spotify:track:") && provider.packageName == SPOTIFY_MUSIC_PACKAGE) {
                    Log.i(TAG, "${provider.label}: MediaBrowser refused, opening $spotifyUri")
                    openTrackUri(context, provider, spotifyUri, song)
                } else {
                    Log.i(TAG, "${provider.label}: MediaBrowser refused, queueing via search intent")
                    queueViaSearchIntent(context, provider, song, title, artist)
                }
            }

            // Neither route reports whether audio started, and the search intent
            // is known to leave the track paused, so the audio stack is the only
            // source of truth from here. The nudge presses play on the provider's
            // own session once it appears — a global media key cannot be used
            // because it lands on whichever app owns the media button (measured:
            // Spotify), not on the app we just queued.
            val nudge = {
                val seen = pressPlayOnActiveSession(context, provider.packageName, song, title, artist)
                if (seen.sessionFound) sessionSeen = true
                if (seen.metadataPresent) trackLoaded = true
                lastTitle = seen.loadedTitle ?: lastTitle
                Unit
            }
            val providerDeadline = System.currentTimeMillis() + slice

            // Start, then prove it keeps going. A track that plays for a second
            // and pauses itself is not playing, so a single true sample is not
            // enough to answer with — retry inside this provider's slice until
            // the audio sticks or the time is gone.
            var playing = false
            while (!playing && System.currentTimeMillis() < providerDeadline) {
                val began = awaitMusicActive(
                    audioManager,
                    expected = true,
                    timeoutMs = providerDeadline - System.currentTimeMillis(),
                    nudge = nudge,
                )
                if (!began) break
                playing = confirmSustainedPlayback(audioManager, PLAYBACK_HOLD_MS, nudge)
                if (!playing) {
                    Log.i(TAG, "${provider.label}: audio started then stopped, retrying")
                    continue
                }
                // Sound is coming out, but is it OUR song? When the app was
                // already playing something else, isMusicActive goes true on the
                // first sample and this loop would otherwise exit reporting the
                // requested title over the wrong track.
                nudge()
                if (!titleMatches(lastTitle, title)) {
                    Log.i(
                        TAG,
                        "${provider.label}: playing '${lastTitle}', not the requested '$title' - keep asking"
                    )
                    playing = false
                }
            }

            if (playing) {
                Log.i(TAG, "music_play: ${provider.label} is playing '${lastTitle ?: title}'")
                return ActionExecutionResult.Success(
                    mapOf(
                        // The title the handset says it is playing, not the one we
                        // asked for — those are the same thing only once the check
                        // above has passed, and reporting the request would hide it
                        // if they ever diverge again.
                        "title" to (lastTitle ?: title),
                        "artist" to artist,
                        "playback_state" to "playing",
                        "provider" to provider.label,
                        "volume" to requestedVolume
                    )
                )
            }

            attempts += if (sessionSeen && trackLoaded && !titleMatches(lastTitle, title)) {
                "${provider.label}: kept playing '${lastTitle}' instead of the requested track"
            } else {
                "${provider.label}: took the search but never produced audio"
            }
            Log.w(TAG, "music_play: ${provider.label} stayed silent for '$song'")
        }

        // Nothing is playing. Say so, and say WHY — a false success leaves a blind
        // user waiting for sound that never comes, and a vague "try again" sends
        // them to retry something that cannot succeed however many times they ask.
        //
        // The session having come up while never taking the track is the specific,
        // measured signature of an account that may not play a chosen song on
        // demand: on a Spotify Free account this session sat at actions=141312
        // with null metadata for the whole budget, refusing playFromSearch and
        // prepareFromSearch alike. That is a subscription limit, not a fault, and
        // the contract has a code for it (endpoint.md: music_play error codes).
        val code = if (sessionSeen && !trackLoaded) "SUBSCRIPTION_INACTIVE" else "PLAYBACK_FAILED"
        val detail = if (code == "SUBSCRIPTION_INACTIVE") {
            "The music app opened but would not load '$song' - the account cannot " +
                "play a chosen track on demand."
        } else {
            "No music app started playing '$song'."
        }
        Log.w(TAG, "music_play: $code for '$song' - ${attempts.joinToString("; ")}")
        return ActionExecutionResult.Error(
            ReportErrorPayload(code, "$detail ${attempts.joinToString("; ")}")
        )
    }
}

/**
 * Pauses every media session this app is allowed to see.
 *
 * Not just Spotify: [MusicPlayHandler] falls through to YouTube Music, so "stop"
 * has to reach whichever app actually ended up playing.
 */
private fun pauseAllActiveSessions(context: Context): Int {
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        ?: return 0
    val listener = ComponentName(context, MediaControlListenerService::class.java)
    val sessions = try {
        manager.getActiveSessions(listener)
    } catch (exc: SecurityException) {
        Log.w(TAG, "No notification access; cannot reach media sessions to pause", exc)
        return 0
    }
    var paused = 0
    for (controller in sessions) {
        runCatching {
            controller.transportControls.pause()
            paused++
        }.onFailure { Log.w(TAG, "pause() failed on ${controller.packageName}", it) }
    }
    return paused
}

/**
 * Contract action `music_stop` (project_context.md 6.7).
 *
 * Confirms silence before reporting it, for the same reason [MusicPlayHandler]
 * confirms sound: this used to return `stopped` unconditionally, so a pause that
 * never landed still told the user the music had stopped while it kept playing.
 */
class MusicStopHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_STOP_FAILED", "No context available")
            )
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult.Error(
                ReportErrorPayload("PLAYBACK_STOP_FAILED", "AudioManager unavailable")
            )

        // Already quiet counts as stopped. The backend's device state cannot see a
        // track ending on its own, so "tắt nhạc" often arrives for music that
        // finished minutes ago; erroring there would be a failure the user cannot
        // act on.
        if (!audioManager.isMusicActive) {
            return ActionExecutionResult.Success(mapOf("playback_state" to "stopped"))
        }

        val paused = pauseAllActiveSessions(context)
        val stopped = awaitMusicActive(audioManager, expected = false, timeoutMs = PLAYBACK_STOP_TIMEOUT_MS)

        return if (stopped) {
            ActionExecutionResult.Success(mapOf("playback_state" to "stopped"))
        } else {
            ActionExecutionResult.Error(
                ReportErrorPayload(
                    "PLAYBACK_STOP_FAILED",
                    "Paused $paused media session(s) but audio was still playing after " +
                        "${PLAYBACK_STOP_TIMEOUT_MS / 1000}s"
                )
            )
        }
    }
}

/** Legacy local-demo action, updated to use Spotify provider. */
class MediaPlayHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val song = json.optString("song", "Track")
            val spotifyUri = json.optString("spotify_uri", "")
            if (context != null) {
                com.youreyes.app.media.SpotifyRemoteManager.playTrack(context, spotifyUri, song)
            }
            ActionExecutionResult.Success(mapOf("song" to song, "playback_status" to "playing", "app" to "spotify"))
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

class CameraCaptureHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val mode = json.optString("mode", "photo")
            if (context != null) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
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

/** Returns the fixed T23 capability shape used by the glasses server. */
class CapabilitiesGetHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) {
            return ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Context is required for capabilities_get")
            )
        }

        fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val hasForegroundLocation =
            granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val hasBackgroundLocation =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val backgroundLocation = when {
            hasForegroundLocation && hasBackgroundLocation -> "always"
            hasForegroundLocation -> "while_using"
            else -> "never"
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val prefs = context.getSharedPreferences(FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        val capabilities: Map<String, Any> = linkedMapOf(
            "notification_listener" to hasNotificationAccess(context),
            "system_alert_window" to Settings.canDrawOverlays(context),
            "battery_optimization_off" to
                (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true),
            "background_location" to backgroundLocation,
            "fine_location" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
            "read_contacts" to granted(Manifest.permission.READ_CONTACTS),
            "read_phone_state" to granted(Manifest.permission.READ_PHONE_STATE),
            "call_phone" to granted(Manifest.permission.CALL_PHONE),
            "send_sms" to granted(Manifest.permission.SEND_SMS),
            "emergency_contact_set" to
                !prefs.getString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, null).isNullOrBlank(),
        )
        return ActionExecutionResult.Success(mapOf("capabilities" to capabilities))
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
        ActionType.CAPABILITIES_GET.value to CapabilitiesGetHandler(),
        ActionType.CALL_ANSWER.value to CallAnswerHandler(),
        ActionType.CALL_REJECT.value to CallRejectHandler(),
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
