package com.inteldes.app

import android.app.Application
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.repository.RecordingRepository

/** Simple manual service locator — small enough app that a DI framework is unneeded ceremony. */
class IntelDesApp : Application() {
    val database: IntelDesDatabase by lazy { IntelDesDatabase.get(this) }
    val repository: RecordingRepository by lazy { RecordingRepository(database) }
    val securePrefs: SecurePrefs by lazy { SecurePrefs(this) }
    val settingsStore: AppSettingsStore by lazy { AppSettingsStore(this) }
    val audioRecorder: AudioRecorderManager by lazy { AudioRecorderManager(this) }
}
