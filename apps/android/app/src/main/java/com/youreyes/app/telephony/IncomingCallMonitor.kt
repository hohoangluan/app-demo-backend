package com.youreyes.app.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.model.DeviceEventPayload
import com.youreyes.app.model.IncomingCallerPayload
import com.youreyes.app.network.DeviceApiClient
import java.util.concurrent.Executors

/**
 * T19 bridge from the handset's spontaneous call state into the Device API.
 *
 * TelephonyCallback intentionally reports only state. Android exposes the
 * incoming number separately on ACTION_PHONE_STATE_CHANGED, and only with
 * READ_CALL_LOG + READ_PHONE_STATE. PhoneStateBroadcastReceiver supplies that
 * second half; this object joins both signals and removes the full number before
 * any network call is constructed.
 */
object IncomingCallMonitor {
    private const val TAG = "IncomingCallMonitor"
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val stateExecutor = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    private var appContext: Context? = null
    private var started = false
    private var ringing = false
    private var incomingSent = false
    private var rawIncomingNumber: String? = null
    private var callback: Any? = null

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (started || !hasPermission(app, Manifest.permission.READ_PHONE_STATE)) return
            val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = ModernCallStateCallback()
                telephony.registerTelephonyCallback(app.mainExecutor, listener)
                callback = listener
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Legacy callback for Android 11 and older")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (!phoneNumber.isNullOrBlank()) onIncomingNumber(phoneNumber)
                        onStateChanged(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callback = listener
            }
            started = true
        }
        Log.i(TAG, "Call-state monitoring enabled")
    }

    fun onPhoneStateBroadcast(intent: Intent) {
        val number = if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
            @Suppress("DEPRECATION")
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        } else null
        if (!number.isNullOrBlank()) onIncomingNumber(number)

        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }
        onStateChanged(state)
    }

    private fun onIncomingNumber(number: String) {
        synchronized(lock) {
            rawIncomingNumber = number
        }
        emitIncomingIfReady()
    }

    private fun onStateChanged(state: Int) {
        Log.i(TAG, "Call state changed: $state")
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            synchronized(lock) { ringing = true }
            // The state callback commonly arrives just before the protected
            // broadcast carrying the number. Give that broadcast a short head
            // start, then still notify as "Số lạ" if no number is available.
            stateExecutor.schedule({ emitIncomingIfReady(forceUnknown = true) }, 750, java.util.concurrent.TimeUnit.MILLISECONDS)
            emitIncomingIfReady()
            return
        }

        val shouldSendEnded = synchronized(lock) {
            val send = ringing || incomingSent
            ringing = false
            incomingSent = false
            rawIncomingNumber = null
            send
        }
        if (shouldSendEnded) sendEvent("call_ended", null)
    }

    private fun emitIncomingIfReady(forceUnknown: Boolean = false) {
        val pair = synchronized(lock) {
            if (!ringing || incomingSent) return
            val number = rawIncomingNumber
            if (number.isNullOrBlank() && !forceUnknown) return
            incomingSent = true
            appContext to number
        }
        val context = pair.first ?: return
        val caller = buildPrivacySafeCaller(context, pair.second)
        Log.i(
            TAG,
            "Queue call_incoming: contact=${caller.contactId != null} " +
                "tailDigits=${caller.numberTail?.length ?: 0}",
        )
        sendEvent("call_incoming", caller)
    }

    private fun sendEvent(type: String, caller: IncomingCallerPayload?) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(FcmPushReceiver.KEY_DEVICE_ID, null)
        val baseUrl = prefs.getString(FcmPushReceiver.KEY_BASE_URL, null)
        val bearerToken = prefs.getString(FcmPushReceiver.KEY_BEARER_TOKEN, null)
        if (deviceId.isNullOrBlank() || baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank()) {
            Log.w(TAG, "Call event not sent: device registration settings are incomplete")
            return
        }
        networkExecutor.execute {
            DeviceApiClient().sendEvent(
                baseUrl,
                bearerToken,
                DeviceEventPayload(deviceId = deviceId, type = type, caller = caller),
            ).onFailure { Log.w(TAG, "Failed to send $type", it) }
        }
    }

    private fun buildPrivacySafeCaller(context: Context, fullNumber: String?): IncomingCallerPayload {
        if (fullNumber.isNullOrBlank()) return IncomingCallerPayload(name = "Số lạ")
        val digits = fullNumber.filter(Char::isDigit)
        val tail = digits.takeLast(4).takeIf { it.length >= 3 }
        val contact = lookupContact(context, fullNumber)
        return IncomingCallerPayload(
            contactId = contact?.id,
            name = contact?.name,
            numberTail = tail,
            duplicateName = contact?.duplicateName == true,
        )
    }

    private data class ContactMatch(val id: String, val name: String, val duplicateName: Boolean)

    @SuppressLint("Range")
    private fun lookupContact(context: Context, fullNumber: String): ContactMatch? {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(fullNumber),
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
        )
        val first = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)) to
                    cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
            }
        } ?: return null
        val duplicate = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
            arrayOf(first.second),
            null,
        )?.use { it.count > 1 } ?: false
        return ContactMatch(first.first, first.second, duplicate)
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private class ModernCallStateCallback :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            IncomingCallMonitor.onStateChanged(state)
        }
    }
}

/** Starts the process for protected PHONE_STATE broadcasts and supplies the number half. */
class PhoneStateBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IncomingCallMonitor.start(context)
        IncomingCallMonitor.onPhoneStateBroadcast(intent)
    }
}
