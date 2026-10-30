package com.inteldes.app.data.web

import com.google.gson.JsonElement
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Wire shapes of the IntelDes server's recording API (docs/server/openapi.snapshot.yaml). Times are epoch ms. */

data class WebRecording(
    val id: String,
    val title: String,
    val kind: String,
    val createdAt: Long,
    val durationSec: Int,
    val audioBlobSha256: String?,
    val audioMimeType: String,
    val status: String,
    val speakerCount: Int,
    val engine: String,
    val templateId: String,
    val templateName: String,
    val summary: String?,
    val provider: String?,
    val notesGeneratedAt: Long?,
    val notulensiDensity: String?,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val errorMessage: String?,
    val version: Int,
    val transcriptVersion: Int,
    val notesVersion: Int,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** What a client may send for a recording's metadata (`RecordingInput`): only RAW or DONE, never WORKING. */
data class WebRecordingInput(
    val title: String,
    val kind: String,
    val createdAt: Long,
    val durationSec: Int,
    val audioBlobSha256: String?,
    val audioMimeType: String,
    val status: String,
    val speakerCount: Int,
    val engine: String,
    val templateId: String,
    val templateName: String,
    val summary: String?,
    val provider: String?,
    val notesGeneratedAt: Long?,
    val notulensiDensity: String?,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val errorMessage: String?,
)

data class WebSegment(val id: String, val orderIndex: Int, val speaker: String, val timestampSec: Int, val text: String)
data class WebSection(val id: String, val orderIndex: Int, val title: String, val type: String, val payload: JsonElement)
data class WebMark(val id: String, val timestampSec: Int, val label: String, val createdAt: Long, val deletedAt: Long? = null)
data class WebPhoto(val id: String, val blobSha256: String, val takenAtSec: Int?, val caption: String, val createdAt: Long, val deletedAt: Long? = null)
data class WebChat(val id: String, val orderIndex: Int, val isUser: Boolean, val text: String, val createdAt: Long)

data class WebTranscriptPart(val version: Int, val segments: List<WebSegment>)
data class WebNotesPart(val version: Int, val sections: List<WebSection>)

data class WebAggregate(
    val recording: WebRecording,
    val transcript: WebTranscriptPart,
    val notes: WebNotesPart,
    val marks: List<WebMark>,
    val photos: List<WebPhoto>,
    val chat: List<WebChat>,
)

data class WebCategory(val id: String, val label: String, val orderIndex: Int, val version: Int, val updatedAt: Long, val deletedAt: Long?)
data class WebChange(val entity: String, val id: String, val op: String, val version: Int?)
data class WebChangesPage(val items: List<WebChange>, val next_seq: Long, val more: Boolean)

/**
 * IDs the server needs for rows the phone keeps with an autoincrement key. They are derived from the row's
 * CONTENT, so two phones holding the same recording (e.g. via Google Drive) produce the same id for the same
 * mark and the server's merge-by-id never duplicates it. Rows that came from the server keep the server's id.
 */
object WebIds {
    fun forMark(recordingId: String, timestampSec: Int, label: String, createdAt: Long) =
        derive("$recordingId|mark|$createdAt|$timestampSec|$label")

    fun forPhoto(recordingId: String, takenAtSec: Int?, createdAt: Long) =
        derive("$recordingId|photo|$createdAt|${takenAtSec ?: -1}")

    fun forChat(recordingId: String, orderIndex: Int, isUser: Boolean, createdAt: Long) =
        derive("$recordingId|chat|$createdAt|$orderIndex|$isUser")

    /** Transcript lines and note sections are replaced as whole sets, so a position-based id is enough. */
    fun forSegment(recordingId: String, orderIndex: Int) = derive("$recordingId|seg|$orderIndex")
    fun forSection(recordingId: String, orderIndex: Int) = derive("$recordingId|sec|$orderIndex")

    private fun derive(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()
}
