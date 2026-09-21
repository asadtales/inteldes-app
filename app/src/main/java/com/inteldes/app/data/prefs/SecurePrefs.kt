package com.inteldes.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the village's own BYO API keys, encrypted at rest on-device — matches the
 * prototype's stated model ("Disimpan terenkripsi di perangkat... ditagih ke akun
 * milik desa"). Nothing here is ever sent anywhere except directly to the
 * corresponding provider's API as the Authorization header for that provider's calls.
 */
class SecurePrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var geminiApiKey: String?
        // Reading also trims: a key saved before this fix (e.g. copy-pasted with a
        // trailing newline) would otherwise keep breaking every call using it as an
        // HTTP header, since header values reject control characters outright —
        // unlike a URL query parameter, which silently percent-encodes them.
        get() = prefs.getString(KEY_GEMINI, null)?.trim()
        set(value) = prefs.edit().putString(KEY_GEMINI, value?.trim()).apply()

    var claudeApiKey: String?
        get() = prefs.getString(KEY_CLAUDE, null)?.trim()
        set(value) = prefs.edit().putString(KEY_CLAUDE, value?.trim()).apply()

    /** Key for the cloud Whisper transcription endpoint (OpenAI-compatible `audio/transcriptions`). */
    var whisperCloudApiKey: String?
        get() = prefs.getString(KEY_WHISPER_CLOUD, null)?.trim()
        set(value) = prefs.edit().putString(KEY_WHISPER_CLOUD, value?.trim()).apply()

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_CLAUDE = "claude_api_key"
        private const val KEY_WHISPER_CLOUD = "whisper_cloud_api_key"

        fun mask(key: String?): String {
            if (key.isNullOrBlank()) return "Belum diatur"
            val tail = key.takeLast(4)
            return "•••• •••• •••• $tail"
        }
    }
}
