package com.inteldes.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.prefs.AppSettings
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.repository.TemplateRepository
import com.inteldes.app.data.sync.DriveAuth
import com.inteldes.app.data.sync.DriveClient
import com.inteldes.app.data.sync.SyncSettings
import com.inteldes.app.data.sync.SyncStore
import com.inteldes.app.data.work.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KeyFields(
    val geminiKey: String,
    val claudeKey: String,
    val whisperKey: String,
    val geminiEmbedded: Boolean = false,
    val claudeEmbedded: Boolean = false,
    val whisperEmbedded: Boolean = false,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val advancedOpen: Boolean = false,
    val keys: KeyFields = KeyFields("", "", ""),
    val rosterDialogOpen: Boolean = false,
    val defaultTemplateName: String = "Notulensi Standar",
)

class SettingsViewModel(
    private val appContext: Context,
    private val store: AppSettingsStore,
    private val securePrefs: SecurePrefs,
    templateRepository: TemplateRepository,
    private val syncStore: SyncStore,
    private val driveAuth: DriveAuth,
    private val account: com.inteldes.app.data.server.AccountService,
) : ViewModel() {

    /** The IntelDes server login of this install (null = local mode). */
    val serverSession: StateFlow<com.inteldes.app.data.server.Session?> = account.session
    fun logoutServer() { viewModelScope.launch { account.logout() } }

    /** Google Drive link + options of THIS device (every device connects its own account). */
    val sync: StateFlow<SyncSettings> = syncStore.settings

    fun authRequest() = driveAuth.request()

    /** Called with the access token once the user granted Drive access in the Google consent screen. */
    fun onDriveAuthorized(token: String?) {
        if (token == null) { syncStore.finish("Google tidak memberi izin akses Drive."); return }
        viewModelScope.launch {
            val label = DriveClient(tokenProvider = { token }).accountLabel()
            syncStore.setConnected(label ?: "Akun Google terhubung")
            SyncWorker.enqueue(appContext, force = true)
        }
    }

    fun onDriveAuthError(message: String) = syncStore.finish(message)
    fun syncNow() = SyncWorker.enqueue(appContext, force = true)
    fun disconnectDrive() = syncStore.disconnect()
    fun setAutoSync(value: Boolean) = syncStore.setAutoSync(value)
    fun setWifiOnly(value: Boolean) = syncStore.setWifiOnly(value)

    private val advancedOpen = MutableStateFlow(false)
    private val keysTick = MutableStateFlow(0)
    private val rosterDialogOpen = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        store.settings, advancedOpen, keysTick, rosterDialogOpen, templateRepository.observeChoices(store),
    ) { settings, open, _, rosterOpen, choices ->
        SettingsUiState(
            settings = settings,
            advancedOpen = open,
            keys = KeyFields(
                geminiKey = securePrefs.geminiApiKey.orEmpty(),
                claudeKey = securePrefs.claudeApiKey.orEmpty(),
                whisperKey = securePrefs.whisperCloudApiKey.orEmpty(),
                geminiEmbedded = securePrefs.hasEmbeddedGemini,
                claudeEmbedded = securePrefs.hasEmbeddedClaude,
                whisperEmbedded = securePrefs.hasEmbeddedWhisperCloud,
            ),
            rosterDialogOpen = rosterOpen,
            defaultTemplateName = choices.default.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun toggleAdvanced() { advancedOpen.value = !advancedOpen.value }
    fun openRosterDialog() { rosterDialogOpen.value = true }
    fun closeRosterDialog() { rosterDialogOpen.value = false }

    fun setProvider(p: AiProvider) { viewModelScope.launch { store.setProvider(p) } }
    fun setDefaultEngine(e: WhisperEngine) { viewModelScope.launch { store.setDefaultEngine(e) } }
    fun setDensity(density: NotulensiDensity) { viewModelScope.launch { store.setDensity(density) } }
    fun setKeepOriginalAudio(keep: Boolean) { viewModelScope.launch { store.setKeepOriginalAudio(keep) } }
    fun setOutputLanguage(language: OutputLanguage) { viewModelScope.launch { store.setOutputLanguage(language) } }
    fun setEconomyMode(enabled: Boolean) { viewModelScope.launch { store.setEconomyMode(enabled) } }
    fun setAutoProcessAfterRecording(enabled: Boolean) { viewModelScope.launch { store.setAutoProcessAfterRecording(enabled) } }
    fun setMarkWithVolumeButton(enabled: Boolean) { viewModelScope.launch { store.setMarkWithVolumeButton(enabled) } }
    fun addSpeakerRosterName(name: String) { viewModelScope.launch { store.addSpeakerRosterName(name) } }
    fun removeSpeakerRosterName(name: String) { viewModelScope.launch { store.removeSpeakerRosterName(name) } }

    fun saveGeminiKey(key: String) { securePrefs.geminiApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveClaudeKey(key: String) { securePrefs.claudeApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveWhisperKey(key: String) { securePrefs.whisperCloudApiKey = key.ifBlank { null }; keysTick.value++ }
}
