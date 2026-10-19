package com.inteldes.app.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct on-device calls to the Gemini API using the village's own BYO API key.
 * Default model per Google's own 404 guidance when gemini-2.5-pro was retired for new users.
 */
class GeminiClient(private val apiKey: String, private val model: String = MODEL_PRO) {

    companion object {
        const val MODEL_PRO = "gemini-3.1-pro-preview"
        /** Cheaper/faster sibling of [MODEL_PRO] — used when "Mode hemat token" is on. */
        const val MODEL_FLASH = "gemini-3.8-flash"
    }

    suspend fun generateNotulensi(
        meetingTitle: String,
        segments: List<TranscriptSegment>,
        density: NotulensiDensity,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
    ): Notulensi =
        withContext(Dispatchers.IO) {
            val prompt = buildTranscriptPrompt(meetingTitle, segments, density, outputLanguage)
            val body = JsonObject().apply {
                add(
                    "contents",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", prompt) }) })
                            },
                        )
                    },
                )
                add(
                    "generationConfig",
                    JsonObject().apply {
                        addProperty("responseMimeType", "application/json")
                        add("responseSchema", notulensiJsonSchema())
                        addProperty("temperature", 0.4)
                    },
                )
            }
            val text = callText(body)
            val json = HttpClient.gson.fromJson(text, NotulensiJson::class.java)
            json.toDomain(AiProvider.GEMINI)
        }

    suspend fun ask(
        question: String,
        transcript: List<TranscriptSegment>,
        notulensi: Notulensi?,
        history: List<ChatMessage>,
        outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
    ): String =
        withContext(Dispatchers.IO) {
            val context = buildString {
                appendLine("Kamu menjawab pertanyaan tentang satu rapat desa, berdasarkan transkrip berikut.")
                appendLine("Jawab dalam ${outputLanguage.promptName}.")
                notulensi?.let { appendLine("Ringkasan rapat: ${it.summary}") }
                appendLine("Transkrip:")
                appendLine(transcript.joinToString("\n") { "[${formatTimestamp(it.timestampSec)}] ${it.speaker}: ${it.text}" })
            }
            val contents = JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", context) }) })
                    },
                )
                history.forEach { m ->
                    add(
                        JsonObject().apply {
                            addProperty("role", if (m.isUser) "user" else "model")
                            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", m.text) }) })
                        },
                    )
                }
                add(
                    JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", question) }) })
                    },
                )
            }
            val body = JsonObject().apply { add("contents", contents) }
            callText(body)
        }

    private suspend fun callText(body: JsonObject): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(HttpClient.gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        HttpClient.client.newCall(request).await().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("Gemini API gagal (${resp.code}): ${raw.take(300)}", resp.code)
            val parsed = HttpClient.gson.fromJson(raw, GeminiResponse::class.java)
            val text = parsed.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw ApiException("Gemini tidak mengembalikan jawaban.")
            return text
        }
    }
}

private data class GeminiResponse(val candidates: List<GeminiCandidate>?)
private data class GeminiCandidate(val content: GeminiContent?)
private data class GeminiContent(val parts: List<GeminiPart>?)
private data class GeminiPart(val text: String?)
