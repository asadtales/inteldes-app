package com.inteldes.app.data.audio

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.work.ProcessRecordingWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "WhatsAppCallListener"

/**
 * Detects WhatsApp's own ongoing-call notification (the only public signal Android
 * exposes for a VoIP call in another app — there is no API to tap the call's audio
 * itself) and, when the user has opted in via Settings, records the meeting/call
 * through the normal microphone pipeline for the call's duration.
 *
 * Requires the user to grant Notification Access to this app in system settings —
 * a special, non-runtime permission (see [android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS]).
 *
 * Everything here runs unattended, triggered by the system posting/removing a
 * notification while the user is mid-call — there's no UI to surface a crash to, so
 * every path is defensive on purpose: a failure here must never take the whole app
 * down (matches a real crash report where the recorder starting while WhatsApp
 * itself holds the microphone brought the process down).
 */
class WhatsAppCallListenerService : NotificationListenerService() {

    private val exceptionHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error", e) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private var triggeredByCall = false
    private var callStartedAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isWhatsAppCallNotification(sbn)) return
        scope.launch {
            try {
                val app = applicationContext as IntelDesApp
                val settings = app.settingsStore.settings.first()
                if (!settings.autoRecordWhatsAppCalls) return@launch
                if (app.audioRecorder.state.value != RecorderState.IDLE) return@launch
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Auto-record is on but RECORD_AUDIO isn't granted; skipping.")
                    return@launch
                }
                // WhatsApp itself holds the microphone for the call, so the recorder can
                // legitimately fail to start here (device/OEM dependent) — that's an expected
                // outcome, not a crash.
                app.audioRecorder.start()
                RecordingService.start(applicationContext, isCall = true)
                triggeredByCall = true
                callStartedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start auto-recording for a WhatsApp call", e)
                runCatching { (applicationContext as IntelDesApp).audioRecorder.discard() }
                triggeredByCall = false
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isWhatsAppCallNotification(sbn) || !triggeredByCall) return
        triggeredByCall = false
        scope.launch {
            try {
                val app = applicationContext as IntelDesApp
                val (file, duration) = app.audioRecorder.stop() ?: return@launch
                RecordingService.stop(applicationContext)
                if (file == null || duration < MIN_CALL_DURATION_SEC) {
                    file?.delete()
                    return@launch
                }
                app.repository.addCategory(CALL_CATEGORY)
                val settings = app.settingsStore.settings.first()
                val title = "Panggilan WhatsApp ${TITLE_FORMAT.format(Date(callStartedAt))}"
                val id = app.repository.createRecording(
                    title = title,
                    kind = CALL_CATEGORY,
                    durationSec = duration,
                    audioFilePath = file.absolutePath,
                    engine = settings.defaultEngine,
                )
                ProcessRecordingWorker.enqueue(applicationContext, id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save the recorded WhatsApp call", e)
                runCatching { RecordingService.stop(applicationContext) }
            }
        }
    }

    /** WhatsApp tags its active-call notification with [Notification.CATEGORY_CALL] — the only reliable public signal. */
    private fun isWhatsAppCallNotification(sbn: StatusBarNotification): Boolean =
        (sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b") &&
            sbn.notification.category == Notification.CATEGORY_CALL

    companion object {
        private const val CALL_CATEGORY = "Panggilan WhatsApp"
        private const val MIN_CALL_DURATION_SEC = 5
        private val TITLE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale("id", "ID"))
    }
}
