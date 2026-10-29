package com.inteldes.app.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Direct on-device calls to the Claude Messages API using the village's own BYO API key. */
class ClaudeClient(private val apiKey: String, private val model: String = MODEL_SONNET) {

    companion object {
        const val MODEL_SONNET = "claude-sonnet-5"
        /** Cheaper/faster sibling of [MODEL_SONNET] — used when "Mode hemat token" is on. */
        const val MODEL_HAIKU = "claude-haiku-4-5-20251001"
    }

    suspend fun generateNotulensi(
        meetingTitle: String,
        segments: List<TranscriptSegment>,
        density: NotulensiDensity,
        template: NoteTemplate,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        location: String? = null,
    ): Notulensi =
        withContext(Dispatchers.IO) {
            val prompt = buildTranscriptPrompt(meetingTitle, segments, density, template, outputLanguage, location)
            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", 8192)
                add(
                    "tools",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("name", "submit_notulensi")
                                addProperty("description", "Kirim hasil notulensi rapat yang sudah terstruktur.")
                                add("input_schema", notulensiJsonSchema(template))
                            },
                        )
                    },
                )
                add(
                    "tool_choice",
                    JsonObject().apply { addProperty("type", "tool"); addProperty("name", "submit_notulensi") },
                )
                add(
                    "messages",
                    JsonArray().apply {
                        add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) })
                    },
                )
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(HttpClient.gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            HttpClient.client.newCall(request).await().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("Claude API gagal (${resp.code}): ${raw.take(300)}", resp.code)
                val parsed = HttpClient.gson.fromJson(raw, ClaudeResponse::class.java)
                val toolUse = parsed.content?.firstOrNull { it.type == "tool_use" }
                    ?: throw ApiException("Claude tidak mengembalikan notulensi terstruktur.")
                val root = toolUse.input ?: throw ApiException("Claude tidak mengembalikan notulensi terstruktur.")
                parseNotulensi(root, template, AiProvider.CLAUDE)
            }
        }

    /** Free-form chat: [system] instructions, prior [history], then [message]. Used by the Konteks screen. */
    suspend fun chat(system: String, history: List<ChatMessage>, message: String, maxTokens: Int = 4096): String =
        withContext(Dispatchers.IO) {
            val messages = JsonArray().apply {
                history.forEach { m -> add(JsonObject().apply { addProperty("role", if (m.isUser) "user" else "assistant"); addProperty("content", m.text) }) }
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", message) })
            }
            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", maxTokens)
                addProperty("system", system)
                add("messages", messages)
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(HttpClient.gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            HttpClient.client.newCall(request).await().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("Claude API gagal (${resp.code}): ${raw.take(300)}", resp.code)
                HttpClient.gson.fromJson(raw, ClaudeResponse::class.java).content?.firstOrNull { it.type == "text" }?.text
                    ?: throw ApiException("Claude tidak mengembalikan jawaban.")
            }
        }

    suspend fun ask(
        question: String,
        transcript: List<TranscriptSegment>,
        notulensi: Notulensi?,
        history: List<ChatMessage>,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
        extraContext: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val context = buildString {
                appendLine("Kamu menjawab pertanyaan tentang satu rapat, berdasarkan transkrip berikut.")
                appendLine("Jawab dalam ${outputLanguage.promptName}.")
                extraContext?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Berikut KONTEKS TAMBAHAN dari rapat-rapat sebelumnya (pengetahuan latar, bukan rapat ini):")
                    appendLine(it)
                    appendLine("--- akhir konteks tambahan ---")
                }
                notulensi?.let { appendLine("Ringkasan rapat: ${it.summary}") }
                appendLine("Transkrip:")
                appendLine(transcript.joinToString("\n") { "[${formatTimestamp(it.timestampSec)}] ${it.speaker}: ${it.text}" })
            }
            val messages = JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", context) })
                add(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "Siap, saya sudah membaca transkripnya.") })
                history.forEach { m ->
                    add(JsonObject().apply { addProperty("role", if (m.isUser) "user" else "assistant"); addProperty("content", m.text) })
                }
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", question) })
            }
            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", 1024)
                add("messages", messages)
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(HttpClient.gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            HttpClient.client.newCall(request).await().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("Claude API gagal (${resp.code}): ${raw.take(300)}", resp.code)
                val parsed = HttpClient.gson.fromJson(raw, ClaudeResponse::class.java)
                parsed.content?.firstOrNull { it.type == "text" }?.text
                    ?: throw ApiException("Claude tidak mengembalikan jawaban.")
            }
        }
}

private data class ClaudeResponse(val content: List<ClaudeContentBlock>?)
private data class ClaudeContentBlock(val type: String?, val text: String?, val input: JsonObject?)
