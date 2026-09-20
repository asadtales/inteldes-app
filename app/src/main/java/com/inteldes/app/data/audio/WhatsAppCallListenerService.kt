package com.inteldes.app.data.audio

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.work.ProcessRecordingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detects WhatsApp's own ongoing-call notification (the only public signal Android
 * exposes for a VoIP call in another app — there is no API to tap the call's audio
 * itself) and, when the user has opted in via Settings, records the meeting/call
 * through the normal microphone pipeline for the call's duration.
 *
 * Requires the user to grant Notification Access to this app in system settings —
 * a special, non-runtime permission (see [android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS]).
 */
class WhatsAppCallListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var triggeredByCall = false
    private var callStartedAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isWhatsAppCallNotification(sbn)) return
        scope.launch {
            val app = applicationContext as IntelDesApp
            val settings = app.settingsStore.settings.first()
            if (!settings.autoRecordWhatsAppCalls) return@launch
            if (app.audioRecorder.state.value != RecorderState.IDLE) return@launch
            runCatching {
                app.audioRecorder.start()
                RecordingService.start(applicationContext, isCall = true)
                triggeredByCall = true
                callStartedAt = System.currentTimeMillis()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isWhatsAppCallNotification(sbn) || !triggeredByCall) return
        triggeredByCall = false
        scope.launch {
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
