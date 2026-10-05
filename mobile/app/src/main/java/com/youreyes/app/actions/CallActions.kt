package com.youreyes.app.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.youreyes.app.core.AppConfig
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CallActions"

/**
 * How long we wait for the line to go off-hook after dialling. Covers a person
 * tapping the launch notification, and stays under the server's 60s
 * `contact_call` deadline so a real result is reported before the timeout.
 */
private const val CALL_SETTLE_TIMEOUT_MS = 45_000L
private const val CALL_POLL_INTERVAL_MS = 500L
private const val CALL_CONTROL_TIMEOUT_MS = 2_000L
private const val SMS_RESULT_TIMEOUT_MS = 20_000L

/** Contacts lookup by display name, case-insensitive ("Em iu" == "em iu"). */
object ContactLookup {
    data class Match(val name: String, val phoneNumber: String)

    fun findByName(context: Context, query: String): List<Match> {
        val wanted = query.trim().lowercase(Locale.getDefault())
        if (wanted.isEmpty() || !hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return emptyList()
        }
        val matches = mutableListOf<Match>()
        val seenNumbers = mutableSetOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) return@use
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                if (name.trim().lowercase(Locale.getDefault()) != wanted) continue
                // The same person often has one number stored twice.
                if (seenNumbers.add(number.filter(Char::isDigit))) matches += Match(name, number)
            }
        }
        return matches
    }

    /** "***768": enough to tell numbers apart without leaking them. */
    fun maskPhoneNumber(number: String): String {
        val digits = number.filter(Char::isDigit)
        return if (digits.length < 3) "***" else "***" + digits.takeLast(3)
    }

    fun looksLikePhoneNumber(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && trimmed.all { it.isDigit() || it in "+-() " }
    }
}

/** Telecom and SmsManager want "+84797173768", not the "+84 797 173 768" people save. */
private fun dialable(phoneNumber: String): String = PhoneNumberUtils.stripSeparators(phoneNumber)

private fun callInProgress(audioManager: AudioManager): Boolean =
    audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_RINGTONE

/**
 * Dials [phoneNumber] and waits until the handset is really in a call.
 *
 * Uses [TelecomManager.placeCall] because it is not an activity start, so it
 * works from the background with the screen on or off. Falls back to an
 * `ACTION_CALL` notification only if Telecom is unavailable.
 */
private fun placeRealCall(context: Context, phoneNumber: String): Boolean {
    if (!hasPermission(context, Manifest.permission.CALL_PHONE)) return false
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    val placed = telecom != null && runCatching {
        @SuppressLint("MissingPermission") // CALL_PHONE checked above
        telecom.placeCall(Uri.fromParts("tel", dialable(phoneNumber), null), Bundle())
    }.onFailure { Log.e(TAG, "TelecomManager.placeCall failed", it) }.isSuccess

    if (!placed) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${dialable(phoneNumber)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchUiIntent(context, intent, "Đang gọi điện", ContactLookup.maskPhoneNumber(phoneNumber))
    }

    val deadline = System.currentTimeMillis() + CALL_SETTLE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (callInProgress(audioManager)) return true
        Thread.sleep(CALL_POLL_INTERVAL_MS)
    }
    return callInProgress(audioManager)
}

/**
 * Sends an SMS (split into parts when needed) and waits for the radio's verdict.
 *
 * Vietnamese text is sent as UCS-2, where one part holds only 70 characters, so
 * the message is always divided. `send*TextMessage` returns before anything is
 * sent; only the sent-broadcasts say whether every part really left the phone.
 */
@SuppressLint("MissingPermission") // caller checks SEND_SMS
private fun sendSmsAndAwaitResult(context: Context, phoneNumber: String, text: String): Boolean {
    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    } ?: return false

    val parts = smsManager.divideMessage(text)
    val latch = CountDownLatch(parts.size)
    val failures = AtomicInteger(0)
    val action = "com.youreyes.app.SMS_SENT.${UUID.randomUUID()}"
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "SOS SMS part failed with result $resultCode")
                failures.incrementAndGet()
            }
            latch.countDown()
        }
    }
    ContextCompat.registerReceiver(
        context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
    )
    return try {
        val sentIntents = ArrayList(
            parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context, index, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        )
        smsManager.sendMultipartTextMessage(dialable(phoneNumber), null, parts, sentIntents, null)
        latch.await(SMS_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && failures.get() == 0
    } catch (exc: Exception) {
        Log.e(TAG, "SOS SMS failed", exc)
        false
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

/**
 * `emergency_call`: text the emergency contact a location link and call them.
 *
 * The contact is local phone config (Safety screen), not a request param; it may
 * be a number or a contact name. The SMS runs in parallel so a slow radio never
 * delays the call.
 */
class EmergencyCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("CALL_FAILED", "No context available")

        val configured = AppConfig.prefs(context)
            .getString(AppConfig.KEY_EMERGENCY_CONTACT, null)?.trim().orEmpty()
        if (configured.isEmpty()) {
            return failure("EMERGENCY_CONTACT_NOT_CONFIGURED", "No emergency contact configured on device")
        }
        val number = if (ContactLookup.looksLikePhoneNumber(configured)) {
            configured
        } else {
            ContactLookup.findByName(context, configured).firstOrNull()?.phoneNumber
                ?: return failure(
                    "EMERGENCY_CONTACT_NOT_CONFIGURED",
                    "Configured emergency contact '$configured' not found in device contacts",
                )
        }
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return failure("CALL_PERMISSION_DENIED", "CALL_PHONE permission not granted")
        }

        val smsResult = CompletableFuture<Boolean>()
        if (hasPermission(context, Manifest.permission.SEND_SMS)) {
            Thread {
                val location = currentLocation(context, timeoutMs = 5_000L)
                val where = location?.let { "vị trí: https://maps.google.com/?q=${it.latitude},${it.longitude}" }
                    ?: "vị trí không khả dụng"
                smsResult.complete(sendSmsAndAwaitResult(context, number, "[SOS] Cần hỗ trợ khẩn cấp. $where"))
            }.start()
        } else {
            smsResult.complete(false)
        }

        val called = placeRealCall(context, number)
        val smsSent = runCatching { smsResult.get(SMS_RESULT_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!called) {
            return failure(
                "CALL_FAILED",
                "SOS SMS sent=$smsSent, but no call started within ${CALL_SETTLE_TIMEOUT_MS / 1000}s",
            )
        }
        return success(
            "emergency_state" to "completed",
            "attempt" to 1,
            "answered" to false,
            "cycle" to "stopped",
            "contact" to ContactLookup.maskPhoneNumber(number),
            "sms_sent" to smsSent,
        )
    }
}

/** `contact_call`: call exactly one contact whose name matches. */
class ContactCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        val name = runCatching { JSONObject(paramsJson).optString("name") }.getOrDefault("").trim()
        if (name.isEmpty()) return failure("CONTACT_NOT_FOUND", "Contact name must not be empty")
        if (context == null) return failure("CALL_FAILED", "No context available")
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return failure("CONTACT_PERMISSION_DENIED", "READ_CONTACTS permission not granted")
        }

        val matches = ContactLookup.findByName(context, name)
        if (matches.isEmpty()) return failure("CONTACT_NOT_FOUND", "No contact matched '$name'")
        if (matches.size > 1) {
            return failure(
                "MULTIPLE_CONTACTS_FOUND",
                "Multiple contacts matched",
                mapOf(
                    "candidates" to matches.map {
                        mapOf("name" to it.name, "phone_number" to ContactLookup.maskPhoneNumber(it.phoneNumber))
                    }
                ),
            )
        }
        val match = matches.single()
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return failure("CALL_PERMISSION_DENIED", "CALL_PHONE permission not granted")
        }
        if (!placeRealCall(context, match.phoneNumber)) {
            return failure(
                "CALL_FAILED",
                "Resolved '${match.name}' but no call started within ${CALL_SETTLE_TIMEOUT_MS / 1000}s",
            )
        }
        return success(
            "call_state" to "calling",
            "contact_name" to match.name,
            "phone_number" to ContactLookup.maskPhoneNumber(match.phoneNumber),
        )
    }
}

@SuppressLint("MissingPermission") // READ_PHONE_STATE checked
private fun phoneCallState(context: Context): Int? {
    if (!hasPermission(context, Manifest.permission.READ_PHONE_STATE)) return null
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    @Suppress("DEPRECATION")
    return telephony.callState
}

private fun awaitPhoneCallState(context: Context, expected: Int): Boolean {
    val deadline = System.currentTimeMillis() + CALL_CONTROL_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (phoneCallState(context) == expected) return true
        Thread.sleep(100)
    }
    return phoneCallState(context) == expected
}

private sealed interface RingingCall {
    data class Ready(val context: Context, val telecom: TelecomManager) : RingingCall
    data class Refused(val error: ActionExecutionResult.Error) : RingingCall
}

/** Shared checks before answering or rejecting: permissions and a call actually ringing. */
private fun ringingCall(context: Context?, verb: String): RingingCall {
    fun refuse(code: String, message: String) = RingingCall.Refused(failure(code, message))
    if (context == null) return refuse("CALL_CONTROL_FAILED", "No context available")
    if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ||
        !hasPermission(context, Manifest.permission.READ_PHONE_STATE)
    ) {
        return refuse("CALL_PERMISSION_DENIED", "ANSWER_PHONE_CALLS and READ_PHONE_STATE permissions are required")
    }
    if (phoneCallState(context) != TelephonyManager.CALL_STATE_RINGING) {
        return refuse("NO_INCOMING_CALL", "No ringing call is available to $verb")
    }
    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        ?: return refuse("CALL_CONTROL_FAILED", "TelecomManager unavailable")
    return RingingCall.Ready(context, telecom)
}

/** `call_answer`: pick up the ringing call (sent while the glasses announce it). */
class CallAnswerHandler : ActionHandler {
    @SuppressLint("MissingPermission")
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("CALL_CONTROL_FAILED", "No context available")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return failure("CALL_CONTROL_UNSUPPORTED", "Answering calls needs Android 8 or newer")
        }
        val call = when (val check = ringingCall(context, "answer")) {
            is RingingCall.Refused -> return check.error
            is RingingCall.Ready -> check
        }
        return runCatching {
            @Suppress("DEPRECATION")
            call.telecom.acceptRingingCall()
            if (awaitPhoneCallState(call.context, TelephonyManager.CALL_STATE_OFFHOOK)) {
                success("call_state" to "answered")
            } else {
                failure("CALL_CONTROL_FAILED", "The call remained ringing after answer")
            }
        }.getOrElse { failure("CALL_CONTROL_FAILED", "Unable to answer call: ${it.message}") }
    }
}

/** `call_reject`: decline the ringing call. */
class CallRejectHandler : ActionHandler {
    @SuppressLint("MissingPermission")
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("CALL_CONTROL_FAILED", "No context available")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return failure("CALL_CONTROL_UNSUPPORTED", "Rejecting calls needs Android 9 or newer")
        }
        val call = when (val check = ringingCall(context, "reject")) {
            is RingingCall.Refused -> return check.error
            is RingingCall.Ready -> check
        }
        return runCatching {
            @Suppress("DEPRECATION")
            if (call.telecom.endCall()) {
                success("call_state" to "rejected")
            } else {
                failure("CALL_CONTROL_FAILED", "Telecom did not reject a call")
            }
        }.getOrElse { failure("CALL_CONTROL_FAILED", "Unable to reject call: ${it.message}") }
    }
}
