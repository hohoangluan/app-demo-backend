package com.youreyes.app.launch

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val CHANNEL_ID = "appdemo_commands"

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Lệnh từ server",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Thông báo mở màn hình thật cho lệnh nhận từ backend"
        setBypassDnd(true)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
}

/**
 * Opens [targetIntent] via a full-screen-intent notification instead of a raw
 * `context.startActivity()` call.
 *
 * Android 10+'s Background Activity Launch restriction silently drops
 * `startActivity()` calls made from a background service (no exception, the
 * screen just never appears) — holding `SYSTEM_ALERT_WINDOW` only exempts an
 * app while it is actively drawing an overlay, not merely for holding the
 * permission. A full-screen-intent notification is the OS-sanctioned escape
 * hatch for exactly this (the same mechanism incoming-call/alarm apps use):
 * it can present [CommandLaunchActivity] over the lock screen and turn the
 * screen on automatically, which then starts the real target intent.
 */
@SuppressLint("MissingPermission") // POST_NOTIFICATIONS requested at startup in MainActivity
fun launchViaFullScreenNotification(
    context: Context,
    targetIntent: Intent,
    notificationId: Int,
    title: String,
    text: String,
) {
    ensureChannel(context)

    val launchIntent = Intent(context, CommandLaunchActivity::class.java).apply {
        putExtra(CommandLaunchActivity.EXTRA_TARGET_INTENT, targetIntent)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setFullScreenIntent(pendingIntent, true)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    runCatching {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
