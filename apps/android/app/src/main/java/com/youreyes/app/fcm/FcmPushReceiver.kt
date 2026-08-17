package com.youreyes.app.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.youreyes.app.network.DeviceApiClient
import com.youreyes.app.service.CommandExecutionService
import java.util.concurrent.Executors

// FCM Push Receiver - entry point for commands pushed from the backend.
// Backend sends DATA messages: request_id, user_id, device_id, action, params_json, base_url, bearer_token
class FcmPushReceiver : FirebaseMessagingService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token received - saving and re-registering")
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        val savedUserId     = prefs.getString(KEY_USER_ID, null)
        val savedDeviceId   = prefs.getString(KEY_DEVICE_ID, null)
        val baseUrl         = prefs.getString(KEY_BASE_URL, null)
        val bearerToken     = prefs.getString(KEY_BEARER_TOKEN, null)
        if (savedUserId != null && savedDeviceId != null && baseUrl != null && bearerToken != null) {
            executor.execute {
                DeviceApiClient().register(
                    userId      = savedUserId,
                    deviceId    = savedDeviceId,
                    platform    = "android",
                    pushToken   = token,
                    baseUrl     = baseUrl,
                    bearerToken = bearerToken,
                )
                Log.i(TAG, "Re-registered with backend after token refresh")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.d(TAG, "FCM message received, keys=${data.keys}")

        val requestId   = data["request_id"]   ?: return logMissing("request_id")
        val userId      = data["user_id"]       ?: return logMissing("user_id")
        val deviceId    = data["device_id"]     ?: return logMissing("device_id")
        val action      = data["action"]        ?: return logMissing("action")
        val paramsJson  = data["params_json"]   ?: "{}"

        // Push commands never carry credentials (architeture.md 13.4): the device
        // resolves its own base_url/bearer_token from local config saved at register().
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baseUrl     = data["base_url"] ?: prefs.getString(KEY_BASE_URL, null)
        val bearerToken = data["bearer_token"] ?: prefs.getString(KEY_BEARER_TOKEN, null)

        // Handed to a foreground service rather than run on this receiver's own
        // executor: handlers now wait for the handset to confirm the action
        // (audio actually playing, dialler actually off-hook), which outlives
        // the short grace period this process keeps after onMessageReceived
        // returns — lowmemorykiller was seen killing it mid-command.
        CommandExecutionService.start(
            context     = applicationContext,
            requestId   = requestId,
            userId      = userId,
            deviceId    = deviceId,
            action      = action,
            paramsJson  = paramsJson,
            baseUrl     = baseUrl,
            bearerToken = bearerToken,
        )
    }

    private fun logMissing(field: String) {
        Log.w(TAG, "FCM message missing field: $field - ignoring")
    }

    companion object {
        const val PREFS_NAME       = "appdemo_prefs"
        const val KEY_FCM_TOKEN    = "fcm_token"
        const val KEY_USER_ID      = "user_id"
        const val KEY_DEVICE_ID    = "device_id"
        const val KEY_BASE_URL     = "base_url"
        const val KEY_BEARER_TOKEN = "bearer_token"
        const val KEY_EMERGENCY_CONTACT = "emergency_contact"
        private const val TAG      = "FcmPushReceiver"
    }
}
