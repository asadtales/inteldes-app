package com.inteldes.app

import android.app.Application
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.sync.DriveAuth
import com.inteldes.app.data.sync.SyncStore
import com.inteldes.app.data.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.inteldes.app.data.repository.TemplateRepository

/** Simple manual service locator — small enough app that a DI framework is unneeded ceremony. */
class IntelDesApp : Application() {
    val database: IntelDesDatabase by lazy { IntelDesDatabase.get(this) }
    val repository: RecordingRepository by lazy {
        RecordingRepository(database).also { repo -> repo.onDeletedHere = { id -> webSyncStore.addPendingDelete(id) } }
    }
    val templateRepository: TemplateRepository by lazy { TemplateRepository(database.templateDao()) }
    val webSyncStore: com.inteldes.app.data.web.WebSyncStore by lazy { com.inteldes.app.data.web.WebSyncStore(this, database) }
    val contextRepository: com.inteldes.app.data.repository.ContextRepository by lazy { com.inteldes.app.data.repository.ContextRepository(database, repository) }
    val securePrefs: SecurePrefs by lazy { SecurePrefs(this) }
    val settingsStore: AppSettingsStore by lazy { AppSettingsStore(this) }
    val audioRecorder: AudioRecorderManager by lazy { AudioRecorderManager(this) }
    val syncStore: SyncStore by lazy { SyncStore(this) }
    val trimDrafts: com.inteldes.app.data.audio.TrimDrafts by lazy { com.inteldes.app.data.audio.TrimDrafts(this) }
    val serverJobs: com.inteldes.app.data.web.ServerJobs by lazy { com.inteldes.app.data.web.ServerJobs() }
    val syncExclusions: com.inteldes.app.data.sync.SyncExclusions by lazy { com.inteldes.app.data.sync.SyncExclusions(this) }
    val sessionStore: com.inteldes.app.data.server.SessionStore by lazy { com.inteldes.app.data.server.SessionStore(this) }
    val accountService: com.inteldes.app.data.server.AccountService by lazy { com.inteldes.app.data.server.AccountService(sessionStore, appLogin = com.inteldes.app.data.server.AppLoginPending(this)) }
    val driveAuth: DriveAuth by lazy { DriveAuth(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { repository.ensureDefaultCategories() }
        // Pick up changes made on other devices (and push ours) whenever the app is opened.
        SyncWorker.enqueue(this)
    }
}
