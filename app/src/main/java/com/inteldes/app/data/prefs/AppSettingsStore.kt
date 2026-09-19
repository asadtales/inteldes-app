package com.inteldes.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inteldes_settings")

data class AppSettings(
    val provider: AiProvider = AiProvider.AUTO,
    val defaultEngine: WhisperEngine = WhisperEngine.CLOUD,
    val densityDetailed: Boolean = false,
    val showTimestamps: Boolean = true,
    val keepOriginalAudio: Boolean = true,
)

/** Non-secret app settings — everything except API keys, which live in [SecurePrefs]. */
class AppSettingsStore(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val ENGINE = stringPreferencesKey("default_engine")
        val DENSITY_DETAILED = booleanPreferencesKey("density_detailed")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
        val KEEP_AUDIO = booleanPreferencesKey("keep_original_audio")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            provider = p[Keys.PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: AiProvider.AUTO,
            defaultEngine = p[Keys.ENGINE]?.let { runCatching { WhisperEngine.valueOf(it) }.getOrNull() } ?: WhisperEngine.CLOUD,
            densityDetailed = p[Keys.DENSITY_DETAILED] ?: false,
            showTimestamps = p[Keys.SHOW_TIMESTAMPS] ?: true,
            keepOriginalAudio = p[Keys.KEEP_AUDIO] ?: true,
        )
    }

    suspend fun setProvider(provider: AiProvider) {
        context.dataStore.edit { it[Keys.PROVIDER] = provider.name }
    }

    suspend fun setDefaultEngine(engine: WhisperEngine) {
        context.dataStore.edit { it[Keys.ENGINE] = engine.name }
    }

    suspend fun setDensityDetailed(detailed: Boolean) {
        context.dataStore.edit { it[Keys.DENSITY_DETAILED] = detailed }
    }

    suspend fun setKeepOriginalAudio(keep: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_AUDIO] = keep }
    }
}
