package com.inteldes.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.BuiltInTemplates
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inteldes_settings")
private val gson = Gson()
private val stringListType = object : TypeToken<List<String>>() {}.type

data class AppSettings(
    val provider: AiProvider = AiProvider.AUTO,
    val defaultEngine: WhisperEngine = WhisperEngine.CLOUD,
    val density: NotulensiDensity = NotulensiDensity.SEDANG,
    val showTimestamps: Boolean = true,
    val keepOriginalAudio: Boolean = true,
    val outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
    val economyMode: Boolean = false,
    val speakerRoster: List<String> = emptyList(),
    /** Off during development/testing (or wherever internet at record-time isn't a given):
     * a finished recording is just saved as-is, and transcription is triggered manually
     * later — avoids spending cloud tokens (or waiting on a connection) on every take. */
    val autoProcessAfterRecording: Boolean = true,
    /** While the record screen is open, volume up/down also drops a mark (like a camera
     * shutter button) — the volume action itself still goes through as normal. Only works
     * while the app is actually open and in the foreground; Android gives apps no way to
     * intercept a physical button system-wide while backgrounded/screen-off without an
     * Accessibility Service, which is a much bigger, more invasive ask than this feature
     * warrants — the notification's own "TANDAI" action covers that case instead. */
    val markWithVolumeButton: Boolean = false,
    val defaultTemplateId: String = BuiltInTemplates.STANDARD_ID,
    /** [com.inteldes.app.data.audio.MicOption.key] of the microphone the user picked; "" = automatic. */
    val preferredMicKey: String = "",
)

/** Non-secret app settings — everything except API keys, which live in [SecurePrefs]. */
class AppSettingsStore(private val context: Context) {
    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val ENGINE = stringPreferencesKey("default_engine")
        val DENSITY_DETAILED = booleanPreferencesKey("density_detailed") // legacy 2-level toggle, migrated below
        val DENSITY = stringPreferencesKey("density_level")
        val SHOW_TIMESTAMPS = booleanPreferencesKey("show_timestamps")
        val KEEP_AUDIO = booleanPreferencesKey("keep_original_audio")
        val OUTPUT_LANGUAGE = stringPreferencesKey("output_language")
        val ECONOMY_MODE = booleanPreferencesKey("economy_mode")
        val SPEAKER_ROSTER = stringPreferencesKey("speaker_roster_json")
        val AUTO_PROCESS = booleanPreferencesKey("auto_process_after_recording")
        val MARK_WITH_VOLUME = booleanPreferencesKey("mark_with_volume_button")
        val DEFAULT_TEMPLATE = stringPreferencesKey("default_template")
        val PREFERRED_MIC = stringPreferencesKey("preferred_mic")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            provider = p[Keys.PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: AiProvider.AUTO,
            defaultEngine = p[Keys.ENGINE]?.let { runCatching { WhisperEngine.valueOf(it) }.getOrNull() } ?: WhisperEngine.CLOUD,
            density = p[Keys.DENSITY]?.let { runCatching { NotulensiDensity.valueOf(it) }.getOrNull() }
                // Migrate the old on/off toggle for anyone who set it before this was 3 levels.
                ?: p[Keys.DENSITY_DETAILED]?.let { if (it) NotulensiDensity.DETAIL else NotulensiDensity.RINGKAS }
                ?: NotulensiDensity.SEDANG,
            showTimestamps = p[Keys.SHOW_TIMESTAMPS] ?: true,
            keepOriginalAudio = p[Keys.KEEP_AUDIO] ?: true,
            outputLanguage = p[Keys.OUTPUT_LANGUAGE]?.let { runCatching { OutputLanguage.valueOf(it) }.getOrNull() } ?: OutputLanguage.INDONESIA,
            economyMode = p[Keys.ECONOMY_MODE] ?: false,
            speakerRoster = p[Keys.SPEAKER_ROSTER]?.let { runCatching { gson.fromJson<List<String>>(it, stringListType) }.getOrNull() } ?: emptyList(),
            autoProcessAfterRecording = p[Keys.AUTO_PROCESS] ?: true,
            markWithVolumeButton = p[Keys.MARK_WITH_VOLUME] ?: false,
            defaultTemplateId = p[Keys.DEFAULT_TEMPLATE] ?: BuiltInTemplates.STANDARD_ID,
            preferredMicKey = p[Keys.PREFERRED_MIC] ?: "",
        )
    }

    suspend fun setProvider(provider: AiProvider) {
        context.dataStore.edit { it[Keys.PROVIDER] = provider.name }
    }

    suspend fun setDefaultEngine(engine: WhisperEngine) {
        context.dataStore.edit { it[Keys.ENGINE] = engine.name }
    }

    suspend fun setDensity(density: NotulensiDensity) {
        context.dataStore.edit { it[Keys.DENSITY] = density.name }
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

    suspend fun setAutoProcessAfterRecording(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PROCESS] = enabled }
    }

    suspend fun setMarkWithVolumeButton(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MARK_WITH_VOLUME] = enabled }
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

    suspend fun setPreferredMicKey(key: String) {
        context.dataStore.edit { it[Keys.PREFERRED_MIC] = key }
    }

    suspend fun setDefaultTemplateId(id: String) {
        context.dataStore.edit { it[Keys.DEFAULT_TEMPLATE] = id }
    }
}
