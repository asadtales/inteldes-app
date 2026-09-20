package com.inteldes.app.data.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.inteldes.app.MainActivity
import com.inteldes.app.R

/**
 * Foreground service whose only job is to keep this process alive (and the OS aware
 * that mic capture is in progress) while [AudioRecorderManager] — owned by the
 * Application — records in the background, e.g. when the user switches away from
 * the app mid-meeting.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isCall = intent?.getBooleanExtra(EXTRA_IS_CALL, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification(isCall))
        return START_STICKY
    }

    private fun buildNotification(isCall: Boolean): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = if (isCall) {
            "IntelDes sedang merekam panggilan WhatsApp" to "Beri tahu lawan bicara bila diperlukan. Ketuk untuk membuka."
        } else {
            "IntelDes sedang merekam" to "Ketuk untuk kembali ke rapat yang sedang direkam."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Perekaman rapat", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_IS_CALL = "is_call"

        fun start(context: Context, isCall: Boolean = false) {
            val intent = Intent(context, RecordingService::class.java).putExtra(EXTRA_IS_CALL, isCall)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
