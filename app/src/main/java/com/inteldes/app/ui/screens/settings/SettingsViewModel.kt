package com.inteldes.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.prefs.AppSettings
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
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
)

class SettingsViewModel(
    private val store: AppSettingsStore,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    private val advancedOpen = MutableStateFlow(false)
    private val keysTick = MutableStateFlow(0)

    val uiState: StateFlow<SettingsUiState> = combine(store.settings, advancedOpen, keysTick) { settings, open, _ ->
        SettingsUiState(
            settings = settings,
            advancedOpen = open,
            keys = KeyFields(
                geminiKey = securePrefs.geminiApiKey.orEmpty(),
                claudeKey = securePrefs.claudeApiKey.orEmpty(),
                whisperKey = securePrefs.whisperCloudApiKey.orEmpty(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun toggleAdvanced() { advancedOpen.value = !advancedOpen.value }

    fun setProvider(p: AiProvider) { viewModelScope.launch { store.setProvider(p) } }
    fun setDefaultEngine(e: WhisperEngine) { viewModelScope.launch { store.setDefaultEngine(e) } }
    fun setDensityDetailed(detailed: Boolean) { viewModelScope.launch { store.setDensityDetailed(detailed) } }
    fun setKeepOriginalAudio(keep: Boolean) { viewModelScope.launch { store.setKeepOriginalAudio(keep) } }

    fun saveGeminiKey(key: String) { securePrefs.geminiApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveClaudeKey(key: String) { securePrefs.claudeApiKey = key.ifBlank { null }; keysTick.value++ }
    fun saveWhisperKey(key: String) { securePrefs.whisperCloudApiKey = key.ifBlank { null }; keysTick.value++ }
}
