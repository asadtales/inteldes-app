package com.inteldes.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inteldes_settings")
private val gson = Gson()
private val stringListType = object : TypeToken<List<String>>() {}.type

data class AppSettings(
    val provider: AiProvider = AiProvider.AUTO,
    val defaultEngine: WhisperEngine = WhisperEngine.CLOUD,
    val densityDetailed: Boolean = false,
    val showTimestamps: Boolean = true,
    val keepOriginalAudio: Boolean = true,
    val outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
    val economyMode: Boolean = false,
    val speakerRoster: List<String> = emptyList(),
)

/** Non-secret app settings — everything except API keys, which live in [SecurePrefs]. */
class AppSettingsStore(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val ENGINE = stringPreferencesKey("default_engine")
        val DENSITY_DETAILED = booleanPreferencesKey("density_detailed")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
        val KEEP_AUDIO = booleanPreferencesKey("keep_original_audio")
        val OUTPUT_LANGUAGE = stringPreferencesKey("output_language")
        val ECONOMY_MODE = booleanPreferencesKey("economy_mode")
        val SPEAKER_ROSTER = stringPreferencesKey("speaker_roster_json")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            provider = p[Keys.PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: AiProvider.AUTO,
            defaultEngine = p[Keys.ENGINE]?.let { runCatching { WhisperEngine.valueOf(it) }.getOrNull() } ?: WhisperEngine.CLOUD,
            densityDetailed = p[Keys.DENSITY_DETAILED] ?: false,
            showTimestamps = p[Keys.SHOW_TIMESTAMPS] ?: true,
            keepOriginalAudio = p[Keys.KEEP_AUDIO] ?: true,
            outputLanguage = p[Keys.OUTPUT_LANGUAGE]?.let { runCatching { OutputLanguage.valueOf(it) }.getOrNull() } ?: OutputLanguage.INDONESIA,
            economyMode = p[Keys.ECONOMY_MODE] ?: false,
            speakerRoster = p[Keys.SPEAKER_ROSTER]?.let { runCatching { gson.fromJson<List<String>>(it, stringListType) }.getOrNull() } ?: emptyList(),
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

    suspend fun setOutputLanguage(language: OutputLanguage) {
        context.dataStore.edit { it[Keys.OUTPUT_LANGUAGE] = language.name }
    }

    suspend fun setEconomyMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ECONOMY_MODE] = enabled }
    }

    suspend fun addSpeakerRosterName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        context.dataStore.edit { p ->
            val current = p[Keys.SPEAKER_ROSTER]?.let { runCatching { gson.fromJson<List<String>>(it, stringListType) }.getOrNull() } ?: emptyList()
            if (current.any { it.equals(trimmed, ignoreCase = true) }) return@edit
            p[Keys.SPEAKER_ROSTER] = gson.toJson(current + trimmed)
        }
    }

    suspend fun removeSpeakerRosterName(name: String) {
        context.dataStore.edit { p ->
            val current = p[Keys.SPEAKER_ROSTER]?.let { runCatching { gson.fromJson<List<String>>(it, stringListType) }.getOrNull() } ?: emptyList()
            p[Keys.SPEAKER_ROSTER] = gson.toJson(current.filterNot { it == name })
        }
    }
}
