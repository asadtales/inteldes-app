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
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val advancedOpen: Boolean = false,
    val keys: KeyFields = KeyFields("", "", ""),
    val rosterDialogOpen: Boolean = false,
    val defaultTemplateName: String = "Notulensi Standar",
)

data class WebSyncUi(
    val enabled: Boolean = false,
    val progress: String? = null,
    val lastRunAt: Long = 0,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val failed: Int = 0,
    val summary: String = "",
    val otherOrg: String? = null,
    val otherOrgKind: String? = null,
    val pullOnly: Boolean = false,
)

data class SyncChoiceUi(
    val recordings: List<com.inteldes.app.data.model.Recording> = emptyList(),
    val excluded: Set<String> = emptySet(),
) {
    val included: Int get() = recordings.count { it.id !in excluded }
}

class SettingsViewModel(
    private val appContext: Context,
    private val store: AppSettingsStore,
    private val securePrefs: SecurePrefs,
    templateRepository: TemplateRepository,
    private val syncStore: SyncStore,
    private val driveAuth: DriveAuth,
    private val account: com.inteldes.app.data.server.AccountService,
    private val webSync: com.inteldes.app.data.web.WebSyncStore,
    recordings: com.inteldes.app.data.repository.RecordingRepository,
    private val exclusions: com.inteldes.app.data.sync.SyncExclusions,
) : ViewModel() {

    /** Which recordings take part in the sync (Drive and web), for the "Pilih rekaman" list. */
    val syncChoice: StateFlow<SyncChoiceUi> = combine(recordings.observeRecordings(), exclusions.ids) { list, excluded ->
        SyncChoiceUi(list, excluded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncChoiceUi())

    fun setSynced(id: String, synced: Boolean) = exclusions.setExcluded(id, !synced)

    /** Checks or unchecks every recording at once. */
    fun setAllSynced(synced: Boolean) {
        exclusions.replace(if (synced) emptySet() else syncChoice.value.recordings.map { it.id }.toSet())
    }

    /** The IntelDes server login of this install (null = local mode). */
    val serverSession: StateFlow<com.inteldes.app.data.server.Session?> = account.session
    fun logoutServer() { viewModelScope.launch { account.logout() } }

    /** Live state of the web sync for the Settings card (the sync runs in a worker, so this is polled from its store). */
    val webSyncState: StateFlow<WebSyncUi> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(
                WebSyncUi(
                    enabled = webSync.enabled, progress = webSync.progress, lastRunAt = webSync.lastRunAt,
                    pushed = webSync.lastPushed, pulled = webSync.lastPulled, failed = webSync.lastFailed,
                    summary = webSync.lastSummary, otherOrg = webSync.needsOrgConfirm, otherOrgKind = webSync.needsOrgKind, pullOnly = webSync.pullOnly,
                ),
            )
            kotlinx.coroutines.delay(1500)
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), WebSyncUi())

    /** The server's AI key source, readiness and 24-hour usage for the signed-in account (null: not signed in / unreachable). */
    private val _serverAi = MutableStateFlow<com.inteldes.app.data.server.ServerSettingsView?>(null)
    val serverAi: StateFlow<com.inteldes.app.data.server.ServerSettingsView?> = _serverAi

    fun refreshServerAi() {
        if (!account.loggedIn) { _serverAi.value = null; return }
        viewModelScope.launch { _serverAi.value = runCatching { account.api.settings() }.getOrNull() }
    }

    // ───────────────────────── AI model per provider (server) ─────────────────────────

    data class AiModelsUi(
        val view: com.inteldes.app.data.server.AiModelsView? = null,
        /** What is typed in the fields: "gemini" | "claude" | "openrouter" to its two ids. */
        val draft: Map<String, com.inteldes.app.data.server.AiTier> = emptyMap(),
        val saving: Boolean = false,
        val message: String? = null,
        /** The key of the test that is running, e.g. "claude/hemat". */
        val checking: String? = null,
        val checks: Map<String, com.inteldes.app.data.server.AiCheck> = emptyMap(),
    ) {
        val dirty: Boolean get() = view != null && draft != draftOf(view)
        companion object {
            fun draftOf(v: com.inteldes.app.data.server.AiModelsView) = mapOf(
                "gemini" to (v.choice?.gemini ?: com.inteldes.app.data.server.AiTier()),
                "claude" to (v.choice?.claude ?: com.inteldes.app.data.server.AiTier()),
                "openrouter" to (v.choice?.openrouter ?: com.inteldes.app.data.server.AiTier()),
            ).mapValues { (_, t) -> com.inteldes.app.data.server.AiTier(t.normal.orEmpty(), t.economy.orEmpty()) }
        }
    }

    private val _aiModels = MutableStateFlow(AiModelsUi())
    val aiModels: StateFlow<AiModelsUi> = _aiModels

    fun refreshAiModels() {
        if (!account.loggedIn) { _aiModels.value = AiModelsUi(); return }
        viewModelScope.launch {
            runCatching { com.inteldes.app.data.web.WebApi(account.api).aiModels() }.onSuccess { v ->
                _aiModels.value = AiModelsUi(view = v, draft = AiModelsUi.draftOf(v))
            }
        }
    }

    fun editModel(provider: String, economy: Boolean, value: String) {
        val cur = _aiModels.value
        val tier = cur.draft[provider] ?: com.inteldes.app.data.server.AiTier("", "")
        val next = if (economy) tier.copy(economy = value.take(120)) else tier.copy(normal = value.take(120))
        _aiModels.value = cur.copy(draft = cur.draft + (provider to next), message = null)
    }

    fun saveAiModels() {
        val cur = _aiModels.value
        if (cur.saving || cur.view == null) return
        _aiModels.value = cur.copy(saving = true, message = null)
        fun tier(p: String) = cur.draft[p]?.let { t ->
            val n = t.normal.orEmpty().trim(); val e = t.economy.orEmpty().trim()
            if (n.isEmpty() && e.isEmpty()) null else com.inteldes.app.data.server.AiTier(n.ifEmpty { null }, e.ifEmpty { null })
        }
        viewModelScope.launch {
            try {
                val saved = com.inteldes.app.data.web.WebApi(account.api).putAiModels(
                    com.inteldes.app.data.server.AiModelChoice(tier("gemini"), tier("claude"), tier("openrouter")),
                )
                _aiModels.value = AiModelsUi(view = saved, draft = AiModelsUi.draftOf(saved), message = "Model tersimpan.")
            } catch (e: Exception) {
                _aiModels.value = _aiModels.value.copy(saving = false, message = com.inteldes.app.data.server.userMessage(e))
            }
        }
    }

    /** Asks the server to try [provider] with the id typed in the field (or the one in use when the field is empty). */
    fun checkModel(provider: String, economy: Boolean) {
        val cur = _aiModels.value
        val key = provider + if (economy) "/hemat" else ""
        val typed = cur.draft[provider]?.let { if (economy) it.economy else it.normal }
        _aiModels.value = cur.copy(checking = key)
        viewModelScope.launch {
            val result = try {
                com.inteldes.app.data.web.WebApi(account.api).checkAiModel(provider.uppercase(), typed, economy)
            } catch (e: Exception) {
                com.inteldes.app.data.server.AiCheck(false, com.inteldes.app.data.server.userMessage(e))
            }
            _aiModels.value = _aiModels.value.copy(checking = null, checks = _aiModels.value.checks + (key to result))
        }
    }

    fun setWebSync(on: Boolean) {
        webSync.enabled = on
        if (on) com.inteldes.app.data.work.WebSyncWorker.enqueue(appContext)
    }

    fun webSyncNow() = com.inteldes.app.data.work.WebSyncWorker.enqueue(appContext)

    /** The user does not want this phone's recordings in the account they just signed in to: sync goes back to off. */
    /** The user now accepts sending this phone's recordings too (after receiving only). */
    fun allowPush() {
        webSync.pullOnly = false
        com.inteldes.app.data.work.WebSyncWorker.enqueue(appContext)
    }

    /** The server lost its data: forget everything agreed with it and send the whole archive again. Nothing on the phone changes. */
    fun resendEverything() {
        viewModelScope.launch {
            webSync.resetForResend()
            webSync.lastSummary = "Mengirim ulang semua rekaman ke server…"
            com.inteldes.app.data.work.WebSyncWorker.enqueue(appContext)
        }
    }

    fun declineOrgSwitch() {
        webSync.needsOrgConfirm = null
        webSync.needsOrgKind = null
        webSync.enabled = false
    }

    /** The user agreed to send this phone's recordings to the OTHER organisation they are now signed in to. */
    fun confirmOrgSwitch(pullOnly: Boolean = false) {
        viewModelScope.launch {
            runCatching { account.currentOrg() }.onSuccess {
                webSync.switchOrg(it.id, pullOnly)
                webSync.needsOrgConfirm = null
                webSync.needsOrgKind = null
                com.inteldes.app.data.work.WebSyncWorker.enqueue(appContext)
            }
        }
    }

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
    fun setTranscribeFirst(enabled: Boolean) { viewModelScope.launch { store.setTranscribeFirst(enabled) } }
    fun setMarkWithVolumeButton(enabled: Boolean) { viewModelScope.launch { store.setMarkWithVolumeButton(enabled) } }
    // ───────────────────────── photo geotag and logo ─────────────────────────

    fun setPhotoGeotag(on: Boolean) { viewModelScope.launch { store.setPhotoGeotag(on); if (!on) store.setPhotoGeoStamp(false) } }
    fun setPhotoGeoStamp(on: Boolean) { viewModelScope.launch { store.setPhotoGeoStamp(on) } }
    fun setPhotoLogo(on: Boolean) { viewModelScope.launch { store.setPhotoLogo(on) } }

    private val _logoPresent = MutableStateFlow(com.inteldes.app.data.photo.PhotoLogo.exists(appContext))
    val logoPresent: StateFlow<Boolean> = _logoPresent
    private val _logoMessage = MutableStateFlow<String?>(null)
    val logoMessage: StateFlow<String?> = _logoMessage

    fun chooseLogo(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                com.inteldes.app.data.photo.PhotoLogo.save(appContext, uri)
                _logoPresent.value = true
                _logoMessage.value = "Logo tersimpan."
                store.setPhotoLogo(true)
            } catch (e: Exception) {
                _logoMessage.value = e.message ?: "Logo tidak bisa dipakai."
            }
        }
    }

    fun clearLogo() {
        com.inteldes.app.data.photo.PhotoLogo.clear(appContext)
        _logoPresent.value = false
        _logoMessage.value = "Logo dihapus; foto memakai logo IntelDes."
    }

    fun addSpeakerRosterName(name: String) { viewModelScope.launch { store.addSpeakerRosterName(name) } }
    fun removeSpeakerRosterName(name: String) { viewModelScope.launch { store.removeSpeakerRosterName(name) } }

    fun saveGeminiKey(key: String) { securePrefs.geminiApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveClaudeKey(key: String) { securePrefs.claudeApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveWhisperKey(key: String) { securePrefs.whisperCloudApiKey = key.ifBlank { null }; keysTick.value++ }
}
