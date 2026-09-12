package com.youreyes.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.youreyes.app.data.AppDatabaseHelper
import com.youreyes.app.dispatcher.CommandDispatcher
import com.youreyes.app.dispatcher.PendingReportFlusher
import java.util.concurrent.Executors

/**
 * Runs one pushed command to completion in a foreground service.
 *
 * Handlers now wait on the handset before reporting (audio actually playing, the
 * dialler actually off-hook), so execution takes seconds rather than
 * milliseconds. Running that on the FCM receiver's own executor is not safe:
 * once `onMessageReceived` returns, the process drops back to a cached state and
 * becomes a prime target — lowmemorykiller was observed killing it at
 * `oom_score_adj 955` about 1.5s after it launched YouTube Music, taking the
 * pending verification and the report to the backend with it.
 *
 * A foreground service keeps the process at a priority the killer skips for as
 * long as the command is in flight, and no longer.
 */
class CommandExecutionService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (requestId == null || userId == null || deviceId == null || action == null) {
            Log.w(TAG, "Missing command extras; nothing to execute")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val paramsJson = intent.getStringExtra(EXTRA_PARAMS_JSON) ?: "{}"
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
        val bearerToken = intent.getStringExtra(EXTRA_BEARER_TOKEN)
        val appContext = applicationContext

        executor.execute {
            try {
                // 🔴 Xả hàng đợi báo cáo TRƯỚC khi làm việc mới.
                //
                // `CommandDispatcher` thử gửi mỗi báo cáo đúng MỘT lần; hỏng thì
                // nó nằm lại ở `FAILED` và trước 2026-08-25 không ai đọc lại.
                // Server kính chờ `ActionResult` để biết việc đã xong — không có
                // nó thì nó hứa "tôi sẽ cập nhật khi có kết quả" và không bao giờ
                // giữ lời.
                //
                // Đặt ở đây vì đây là lúc chắc chắn có mạng và có đủ giấy tờ.
                // Rỗng thì chỉ tốn một lượt đọc SQLite.
                PendingReportFlusher.flush(appContext, baseUrl, bearerToken)

                val result = CommandDispatcher(
                    dbHelper = AppDatabaseHelper(appContext),
                    context = appContext,
                ).processPushCommand(
                    requestId = requestId,
                    userId = userId,
                    deviceId = deviceId,
                    action = action,
                    paramsJson = paramsJson,
                    baseUrl = baseUrl,
                    bearerToken = bearerToken,
                )
                Log.i(TAG, "Dispatch result for $action: $result")
            } catch (exc: Exception) {
                Log.e(TAG, "Command $action ($requestId) failed to execute", exc)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dang thuc hien lenh")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Deliberately low importance: this one only exists to satisfy the
        // foreground-service requirement, unlike the high-importance channel the
        // command launcher uses to actually get the user's attention.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Thuc hien lenh", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "CommandExecutionService"
        private const val CHANNEL_ID = "appdemo_execution"
        private const val NOTIFICATION_ID = 4001

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PARAMS_JSON = "params_json"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_BEARER_TOKEN = "bearer_token"

        fun start(
            context: Context,
            requestId: String,
            userId: String,
            deviceId: String,
            action: String,
            paramsJson: String,
            baseUrl: String?,
            bearerToken: String?,
        ) {
            val intent = Intent(context, CommandExecutionService::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_PARAMS_JSON, paramsJson)
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_BEARER_TOKEN, bearerToken)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
