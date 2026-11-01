package com.inteldes.app.data.audio

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A trim in progress, kept so the user can leave the editor and come back to it: which parts are picked, in which
 * mode, and how far the second step (name, template, engine) got. Nothing is cut until the draft is saved as a
 * recording; the original is never touched either way.
 */
data class TrimDraft(
    /** Length of the recording when the draft was made; a draft for an audio of another length is ignored. */
    val durationSec: Int,
    val mode: String,
    /** [start, end] pairs. */
    val ranges: List<List<Int>>,
    val typedTitle: String? = null,
    val templateId: String? = null,
    val engine: String? = null,
    val processNow: Boolean = true,
    val step: Int = 0,
    val updatedAt: Long = 0,
) {
    fun timeRanges(): List<TimeRange> = ranges.filter { it.size == 2 }.map { TimeRange(it[0], it[1]) }
    fun trimMode(): TrimMode = runCatching { TrimMode.valueOf(mode) }.getOrDefault(TrimMode.REMOVE)

    companion object {
        fun of(
            durationSec: Int, mode: TrimMode, picked: List<TimeRange>, typedTitle: String?, templateId: String?,
            engine: String?, processNow: Boolean, step: Int, now: Long = System.currentTimeMillis(),
        ) = TrimDraft(durationSec, mode.name, picked.map { listOf(it.start, it.end) }, typedTitle, templateId, engine, processNow, step, now)
    }
}

/** Text form of all drafts (recording id → draft); pure so it can be tested without Android. */
object TrimDraftCodec {
    private val gson = Gson()

    fun encode(drafts: Map<String, TrimDraft>): String = gson.toJson(drafts.map { (id, d) -> Entry(id, d) })

    fun decode(text: String?): Map<String, TrimDraft> =
        runCatching { gson.fromJson(text, Array<Entry>::class.java).associate { it.id to it.draft } }.getOrDefault(emptyMap())

    private class Entry(val id: String, val draft: TrimDraft)
}

/** One draft per recording, on this phone only. */
class TrimDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("inteldes_trim_drafts", Context.MODE_PRIVATE)
    private val all = TrimDraftCodec.decode(prefs.getString(KEY, null)).toMutableMap()
    private val _ids = MutableStateFlow(all.keys.toSet())

    /** Recordings that have a draft, for the little marker on their scissors button. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** The draft of [recordingId], unless the audio is no longer the length it was made for. */
    fun get(recordingId: String, durationSec: Int): TrimDraft? =
        all[recordingId]?.takeIf { kotlin.math.abs(it.durationSec - durationSec) <= 2 && it.timeRanges().isNotEmpty() }

    @Synchronized
    fun put(recordingId: String, draft: TrimDraft) { all[recordingId] = draft; persist() }

    @Synchronized
    fun clear(recordingId: String) { if (all.remove(recordingId) != null) persist() }

    private fun persist() {
        prefs.edit().putString(KEY, TrimDraftCodec.encode(all)).apply()
        _ids.value = all.keys.toSet()
    }

    private companion object { const val KEY = "drafts" }
}
