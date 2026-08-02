package com.youreyes.app.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.youreyes.app.data.AppDatabaseHelper
import com.youreyes.app.dispatcher.CommandDispatcher

// FCM Push Receiver - entry point for commands pushed from the backend.
// Backend sends DATA messages: request_id, user_id, device_id, action, params_json, base_url, bearer_token
class FcmPushReceiver : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM token: $token")
        // TODO: re-register with backend /api/v1/device/register
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.d(TAG, "FCM message received, keys=${data.keys}")

        val requestId   = data["request_id"]   ?: return logMissing("request_id")
        val userId      = data["user_id"]       ?: return logMissing("user_id")
        val deviceId    = data["device_id"]     ?: return logMissing("device_id")
        val action      = data["action"]        ?: return logMissing("action")
        val paramsJson  = data["params_json"]   ?: "{}"
        val baseUrl     = data["base_url"]
        val bearerToken = data["bearer_token"]

        val ctx: Context = applicationContext
        val dispatcher = CommandDispatcher(
            dbHelper = AppDatabaseHelper(ctx),
            context  = ctx,
        )

        val result = dispatcher.processPushCommand(
            requestId   = requestId,
            userId      = userId,
            deviceId    = deviceId,
            action      = action,
            paramsJson  = paramsJson,
            baseUrl     = baseUrl,
            bearerToken = bearerToken,
        )

        Log.i(TAG, "Dispatch result for $requestId")
        Log.d(TAG, result.toString())
    }

    private fun logMissing(field: String) {
        Log.w(TAG, "FCM message missing field: $field - ignoring")
    }

    companion object {
        private const val TAG = "FcmPushReceiver"
    }
}
