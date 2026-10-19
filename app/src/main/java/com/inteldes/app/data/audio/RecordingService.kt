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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.inteldes.app.IntelDesApp
import com.inteldes.app.MainActivity
import com.inteldes.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers

/**
 * Foreground service whose only job is to keep this process alive (and the OS aware
 * that mic capture is in progress) while [AudioRecorderManager] — owned by the
 * Application — records in the background, e.g. when the user switches away from
 * the app mid-meeting or the screen turns off.
 *
 * Holds a partial wake lock for the same duration: a foreground service alone keeps the
 * *process* from being killed, but doesn't stop the CPU from suspending once the screen
 * is off — WAKE_LOCK was declared in the manifest but never actually used before this,
 * so recording could genuinely stall (not crash, just stop progressing) once the screen
 * went dark. The lock is released on stop so it never outlives the recording itself.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(0))
        acquireWakeLock()
        // Keeps the notification's mark count current — the point of surfacing it there at
        // all is so a tap gives visible confirmation ("Ditandai") without opening the app.
        (application as IntelDesApp).audioRecorder.marks
            .onEach { marks -> notify(buildNotification(marks.size)) }
            .launchIn(scope)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntelDes:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_RECORDING_MS)
        }
    }

    private fun buildNotification(markCount: Int): Notification {
        ensureChannel()
        // Same deep link the home-screen shortcut uses: MainActivity.onNewIntent routes it to
        // the record screen, so tapping the notification returns to the recording in progress
        // instead of landing on Home.
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("inteldes://record"), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_MARK_MOMENT).setPackage(packageName).setClass(this, MarkActionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IntelDes sedang merekam")
            .setContentText(
                if (markCount > 0) "$markCount penanda tersimpan. Ketuk untuk kembali ke rapat." else "Ketuk untuk kembali ke rapat yang sedang direkam.",
            )
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "TANDAI", markIntent)
            // Visible (and actionable) on the lock screen without unlocking first — the whole
            // point of the notification action is marking a moment without stopping to unlock.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        // Safety cap so the wake lock can never leak forever even if release() is somehow
        // never reached — no real meeting should run this long anyway.
        private const val MAX_RECORDING_MS = 4 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
