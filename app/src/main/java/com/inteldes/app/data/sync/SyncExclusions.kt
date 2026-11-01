package com.inteldes.app.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recordings the user chose NOT to sync (to Google Drive and to the web alike). Everything is included by
 * default, so a new recording follows the usual rules; unchecking one in "Pilih rekaman" keeps it on this
 * phone only: it is neither sent nor overwritten by what the other side holds. The choice is per device and
 * never leaves it.
 */
class SyncExclusions(context: Context) {
    private val prefs = context.getSharedPreferences("inteldes_sync_select", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** The excluded ids right now — what the sync engines ask for at the start of every run. */
    fun snapshot(): Set<String> = _ids.value

    fun setExcluded(id: String, excluded: Boolean) = replace(if (excluded) _ids.value + id else _ids.value - id)

    fun replace(excluded: Set<String>) {
        _ids.value = excluded
        prefs.edit().putStringSet(KEY, excluded).apply()
    }

    private companion object {
        const val KEY = "excluded_ids"
    }
}
