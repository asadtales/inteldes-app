package com.inteldes.app.data.network

import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
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

    private fun clientFor(resolved: AiProvider, economyMode: Boolean): Any {
        return when (resolved) {
            AiProvider.GEMINI -> {
                val key = securePrefs.effectiveGeminiKey
                    ?: throw ApiException("API key Gemini belum diatur. Buka Pengaturan → Agent AI.")
                GeminiClient(key, model = if (economyMode) GeminiClient.MODEL_FLASH else GeminiClient.MODEL_PRO)
            }
            AiProvider.CLAUDE -> {
                val key = securePrefs.effectiveClaudeKey
                    ?: throw ApiException("API key Claude belum diatur. Buka Pengaturan → Agent AI.")
                ClaudeClient(key, model = if (economyMode) ClaudeClient.MODEL_HAIKU else ClaudeClient.MODEL_SONNET)
            }
            AiProvider.AUTO -> throw IllegalStateException("resolve() must be called before clientFor()")
        }
    }

    suspend fun generateNotulensi(
        provider: AiProvider,
        durationSec: Int,
        meetingTitle: String,
        segments: List<TranscriptSegment>,
        density: NotulensiDensity,
        template: NoteTemplate,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        economyMode: Boolean = false,
        location: String? = null,
    ): Notulensi {
        val resolved = resolve(provider, durationSec)
        return when (val client = clientFor(resolved, economyMode)) {
            is GeminiClient -> client.generateNotulensi(meetingTitle, segments, density, template, outputLanguage, location)
            is ClaudeClient -> client.generateNotulensi(meetingTitle, segments, density, template, outputLanguage, location)
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
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        economyMode: Boolean = false,
        extraContext: String? = null,
    ): String {
        val resolved = resolve(provider, durationSec)
        return when (val client = clientFor(resolved, economyMode)) {
            is GeminiClient -> client.ask(question, transcript, notulensi, history, outputLanguage, extraContext)
            is ClaudeClient -> client.ask(question, transcript, notulensi, history, outputLanguage, extraContext)
            else -> error("unreachable")
        }
    }

    /** Answers a question using only a saved knowledge context (merged notes of several meetings). */
    suspend fun askContext(
        provider: AiProvider,
        contextName: String,
        context: String,
        history: List<ChatMessage>,
        question: String,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        economyMode: Boolean = false,
    ): String = chat(provider, context.length, economyMode, ContextPrompts.askSystem(contextName, context, outputLanguage), history, question)

    /** Rewrites [context] following [instruction] and returns the WHOLE revised context. */
    suspend fun reviseContext(
        provider: AiProvider,
        contextName: String,
        context: String,
        instruction: String,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        economyMode: Boolean = false,
    ): String = chat(
        provider, context.length, economyMode,
        ContextPrompts.reviseSystem(contextName, outputLanguage), emptyList(), ContextPrompts.reviseMessage(context, instruction),
    ).let(ContextPrompts::stripFences)

    private suspend fun chat(provider: AiProvider, sizeHint: Int, economyMode: Boolean, system: String, history: List<ChatMessage>, message: String): String {
        // Long merged contexts favour the long-context agent: treat very large text like a very long meeting.
        val resolved = resolve(provider, if (sizeHint > 60_000) LONG_MEETING_THRESHOLD_SEC + 1 else 0)
        return when (val client = clientFor(resolved, economyMode)) {
            is GeminiClient -> client.chat(system, history, message)
            is ClaudeClient -> client.chat(system, history, message, maxTokens = 16_000)
            else -> error("unreachable")
        }
    }
}
