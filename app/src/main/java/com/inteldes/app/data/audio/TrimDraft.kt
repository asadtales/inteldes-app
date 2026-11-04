package com.inteldes.app.data.audio

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A trim in progress as kept on this phone: the state the server also keeps, with the time it was saved. */
data class StoredDraft(
    /** Length of the audio when the draft was made; a draft for an audio of another length is ignored. */
    val durationSec: Int,
    val savedAt: Long,
    val state: TrimDraftState,
)

/** Text form of all drafts (recording id to draft); pure so it can be tested without Android. */
object TrimDraftCodec {
    private val gson = Gson()

    fun encode(drafts: Map<String, StoredDraft>): String = gson.toJson(drafts.map { (id, d) -> Entry(id, d) })

    fun decode(text: String?): Map<String, StoredDraft> =
        runCatching { gson.fromJson(text, Array<Entry>::class.java).associate { it.id to it.draft } }.getOrDefault(emptyMap())

    private class Entry(val id: String, val draft: StoredDraft)
}

/** One draft per recording, on this phone: the offline copy next to the one on the server. */
class TrimDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("inteldes_trim_drafts_v2", Context.MODE_PRIVATE)
    private val all = TrimDraftCodec.decode(prefs.getString(KEY, null)).toMutableMap()
    private val _ids = MutableStateFlow(all.keys.toSet())

    /** Recordings that have a draft, for the little marker on their scissors button. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** The draft of [recordingId], unless the audio is no longer the length it was made for or nothing is marked in it. */
    fun get(recordingId: String, durationSec: Int): StoredDraft? =
        all[recordingId]?.takeIf { kotlin.math.abs(it.durationSec - durationSec) <= 2 && it.state.regions.isNotEmpty() }

    @Synchronized
    fun put(recordingId: String, draft: StoredDraft) { all[recordingId] = draft; persist() }

    @Synchronized
    fun clear(recordingId: String) { if (all.remove(recordingId) != null) persist() }

    private fun persist() {
        prefs.edit().putString(KEY, TrimDraftCodec.encode(all)).apply()
        _ids.value = all.keys.toSet()
    }

    private companion object { const val KEY = "drafts" }
}
