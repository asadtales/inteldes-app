package com.inteldes.app.data.network

import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.prefs.SecurePrefs

/** 90 minutes — matches the prototype's stated auto-pilih rule ("Gemini... rapat di atas 90 menit"). */
private const val LONG_MEETING_THRESHOLD_SEC = 90 * 60

/**
 * Resolves which provider actually handles a request. AUTO picks Gemini for long
 * meetings (long-context strength) and Claude otherwise (tighter berita-acara style),
 * exactly as the prototype's Settings copy describes each provider.
 */
class NotulensiGenerator(private val securePrefs: SecurePrefs) {

    fun resolve(provider: AiProvider, durationSec: Int): AiProvider = when (provider) {
        AiProvider.AUTO -> if (durationSec > LONG_MEETING_THRESHOLD_SEC) AiProvider.GEMINI else AiProvider.CLAUDE
        else -> provider
    }

    private fun clientFor(resolved: AiProvider): Any {
        return when (resolved) {
            AiProvider.GEMINI -> {
                val key = securePrefs.geminiApiKey
                    ?: throw ApiException("API key Gemini belum diatur. Buka Pengaturan → Agent AI.")
                GeminiClient(key)
            }
            AiProvider.CLAUDE -> {
                val key = securePrefs.claudeApiKey
                    ?: throw ApiException("API key Claude belum diatur. Buka Pengaturan → Agent AI.")
                ClaudeClient(key)
            }
            AiProvider.AUTO -> throw IllegalStateException("resolve() must be called before clientFor()")
        }
    }

    suspend fun generateNotulensi(
        provider: AiProvider,
        durationSec: Int,
        meetingTitle: String,
        segments: List<TranscriptSegment>,
        densityDetailed: Boolean,
    ): Notulensi {
        val resolved = resolve(provider, durationSec)
        return when (val client = clientFor(resolved)) {
            is GeminiClient -> client.generateNotulensi(meetingTitle, segments, densityDetailed)
            is ClaudeClient -> client.generateNotulensi(meetingTitle, segments, densityDetailed)
            else -> error("unreachable")
        }
    }

    suspend fun ask(
        provider: AiProvider,
        durationSec: Int,
        question: String,
        transcript: List<TranscriptSegment>,
        notulensi: Notulensi?,
        history: List<ChatMessage>,
    ): String {
        val resolved = resolve(provider, durationSec)
        return when (val client = clientFor(resolved)) {
            is GeminiClient -> client.ask(question, transcript, notulensi, history)
            is ClaudeClient -> client.ask(question, transcript, notulensi, history)
            else -> error("unreachable")
        }
    }
}
