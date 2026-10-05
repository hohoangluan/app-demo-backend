package com.youreyes.app.command

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.youreyes.app.core.AppConfig
import com.youreyes.app.network.DeviceApiClient
import com.youreyes.app.network.DeviceRegisterPayload
import java.util.concurrent.Executors

/**
 * Entry point for server commands (FCM data messages).
 *
 * It only parses the command and hands it to [CommandExecutionService]; it never
 * runs a handler itself, because handlers outlive the receiver's short window.
 * Commands never carry credentials: reports use what was saved at registration.
 */
class FcmPushReceiver : FirebaseMessagingService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onNewToken(token: String) {
        val prefs = AppConfig.prefs(this)
        prefs.edit().putString(AppConfig.KEY_FCM_TOKEN, token).apply()
        val credentials = AppConfig.deviceCredentials(this) ?: return
        val userId = prefs.getString(AppConfig.KEY_USER_ID, null) ?: return
        val deviceId = prefs.getString(AppConfig.KEY_DEVICE_ID, null) ?: return
        executor.execute {
            DeviceApiClient().registerDevice(
                credentials.baseUrl,
                credentials.bearerToken,
                DeviceRegisterPayload(userId = userId, deviceId = deviceId, pushToken = token),
            ).onFailure { Log.w(TAG, "Re-register after token refresh failed", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val command = PushCommand.fromData(message.data)
        if (command == null) {
            Log.w(TAG, "Ignoring FCM message without a complete command, keys=${message.data.keys}")
            return
        }
        CommandExecutionService.start(applicationContext, command)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FcmPushReceiver"
    }
}
