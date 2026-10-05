package com.youreyes.app.command

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
import androidx.core.content.ContextCompat
import com.youreyes.app.actions.ActionRegistry
import com.youreyes.app.core.AppConfig
import com.youreyes.app.network.DeviceApiClient
import java.util.concurrent.Executors

/**
 * Runs pushed commands one at a time in a foreground service.
 *
 * Handlers wait seconds for the phone to confirm an outcome; a foreground
 * service keeps the process alive for that long (a cached process was seen
 * being killed mid-command). Before each command, unsent reports are retried.
 */
class CommandExecutionService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val command = intent?.let(::readCommand)
        if (command == null) {
            Log.w(TAG, "Started without a complete command")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val context = applicationContext
        executor.execute {
            try {
                val store = CommandStore(context)
                val sender = DeviceApiClient().asReportSender()
                val credentials = AppConfig.deviceCredentials(context)
                if (credentials != null) ReportFlusher(store, sender).flush(credentials)
                val result = CommandDispatcher(store, ActionRegistry(), sender, context)
                    .dispatch(command, credentials)
                Log.i(TAG, "${command.action} ${command.requestId}: $result")
            } catch (exc: Exception) {
                Log.e(TAG, "Command ${command.action} ${command.requestId} failed", exc)
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
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Thực hiện lệnh", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Đang thực hiện lệnh")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun readCommand(intent: Intent): PushCommand? = PushCommand.fromData(
        listOf("request_id", "user_id", "device_id", "action", "params_json")
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }
            .toMap()
    )

    companion object {
        private const val TAG = "CommandExecution"
        private const val CHANNEL_ID = "appdemo_execution"
        private const val NOTIFICATION_ID = 4001

        fun start(context: Context, command: PushCommand) {
            val intent = Intent(context, CommandExecutionService::class.java)
                .putExtra("request_id", command.requestId)
                .putExtra("user_id", command.userId)
                .putExtra("device_id", command.deviceId)
                .putExtra("action", command.action)
                .putExtra("params_json", command.paramsJson)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
