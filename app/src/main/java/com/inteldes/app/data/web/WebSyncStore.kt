package com.inteldes.app.data.web

import android.content.Context
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.WebSyncStateEntity

/**
 * Where the web sync remembers things: per-recording state in Room, and the cursor / retry list / phone-side
 * deletes in a small preferences file. The preferences file is excluded from backup and device transfer: a
 * restored cursor would make a new phone skip everything the server already has.
 */
class WebSyncStore(context: Context, db: IntelDesDatabase) : WebStateStore {
    private val dao = db.webSyncDao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ───────────── user-facing switches and the last result (read by the Settings screen) ─────────────

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)                        // OFF until the user turns it on
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    /** Org the data was last synced with; a different org needs an explicit confirmation before anything is sent. */
    var orgId: String?
        get() = prefs.getString(K_ORG, null)
        set(v) = prefs.edit().putString(K_ORG, v).apply()

    var lastRunAt: Long
        get() = prefs.getLong(K_LAST_AT, 0)
        set(v) = prefs.edit().putLong(K_LAST_AT, v).apply()

    var lastPushed: Int
        get() = prefs.getInt(K_PUSHED, 0)
        set(v) = prefs.edit().putInt(K_PUSHED, v).apply()

    var lastPulled: Int
        get() = prefs.getInt(K_PULLED, 0)
        set(v) = prefs.edit().putInt(K_PULLED, v).apply()

    var lastFailed: Int
        get() = prefs.getInt(K_FAILED, 0)
        set(v) = prefs.edit().putInt(K_FAILED, v).apply()

    /** Name of the other organisation waiting for the user's confirmation, or null. */
    var needsOrgConfirm: String?
        get() = prefs.getString(K_CONFIRM, null)
        set(v) = prefs.edit().putString(K_CONFIRM, v).apply()

    /** The phone only receives from the server and never sends (chosen when it holds recordings of another archive). */
    var pullOnly: Boolean
        get() = prefs.getBoolean(K_PULL_ONLY, false)
        set(v) = prefs.edit().putBoolean(K_PULL_ONLY, v).apply()

    /** `team` or `personal`: what kind of account is waiting in [needsOrgConfirm]. */
    var needsOrgKind: String?
        get() = prefs.getString(K_CONFIRM_KIND, null)
        set(v) = prefs.edit().putString(K_CONFIRM_KIND, v).apply()

    /** What the running sync is doing right now; null when idle. */
    var progress: String?
        get() = prefs.getString(K_PROGRESS, null)
        set(v) = prefs.edit().putString(K_PROGRESS, v).apply()

    var lastSummary: String
        get() = prefs.getString(K_LAST_SUMMARY, "").orEmpty()
        set(v) = prefs.edit().putString(K_LAST_SUMMARY, v).apply()

    /**
     * The user confirmed syncing with a different organisation: what was agreed with the old one means nothing
     * now, so the next run treats every recording as new to the server. Local recordings are not touched.
     */
    suspend fun switchOrg(newOrgId: String, pullOnly: Boolean = false) {
        dao.clear()
        prefs.edit().remove(K_CURSOR).remove(K_RETRY).remove(K_DELETES).putString(K_ORG, newOrgId).putBoolean(K_PULL_ONLY, pullOnly).remove(K_CONFIRM).remove(K_CONFIRM_KIND).commit()
    }

    /** Forget the cursor and retry list (e.g. after logging into another account) so the next run starts from the top. */
    fun resetProgress() {
        prefs.edit().remove(K_CURSOR).remove(K_RETRY).apply()
    }

    // ───────────── WebStateStore ─────────────

    override suspend fun get(id: String): WebState? = dao.get(id)?.toState()
    override suspend fun put(state: WebState) = dao.put(state.toEntity())
    override suspend fun remove(id: String) = dao.remove(id)
    override suspend fun all(): List<WebState> = dao.all().map { it.toState() }

    override fun cursor(): Long = prefs.getLong(K_CURSOR, 0)
    override fun setCursor(value: Long) { prefs.edit().putLong(K_CURSOR, value).commit() }

    override fun pendingDeletes(): Set<String> = prefs.getStringSet(K_DELETES, emptySet()).orEmpty().toSet()
    fun addPendingDelete(id: String) { prefs.edit().putStringSet(K_DELETES, pendingDeletes() + id).commit() }
    override fun removePendingDelete(id: String) { prefs.edit().putStringSet(K_DELETES, pendingDeletes() - id).commit() }

    override fun retryPulls(): Set<String> = prefs.getStringSet(K_RETRY, emptySet()).orEmpty().toSet()
    override fun addRetry(id: String) { prefs.edit().putStringSet(K_RETRY, retryPulls() + id).commit() }
    override fun removeRetry(id: String) { prefs.edit().putStringSet(K_RETRY, retryPulls() - id).commit() }

    private fun WebSyncStateEntity.toState() = WebState(
        recordingId, serverVersion, transcriptVersion, notesVersion, metaHash, transcriptHash, notesHash, marksHash, chatHash,
        audioSha, audioLen, pushedMarksJson, audioMissing, photosHash, pushedPhotosJson, serverUpdatedAt,
    )

    private fun WebState.toEntity() = WebSyncStateEntity(
        recordingId, serverVersion, transcriptVersion, notesVersion, metaHash, transcriptHash, notesHash, marksHash, chatHash,
        audioSha, audioLen, pushedMarksJson, audioMissing, photosHash, pushedPhotosJson, serverUpdatedAt,
    )

    companion object {
        const val PREFS = "inteldes_websync"
        private const val K_ENABLED = "enabled"
        private const val K_ORG = "org"
        private const val K_LAST_AT = "last_at"
        private const val K_LAST_SUMMARY = "last_summary"
        private const val K_PUSHED = "last_pushed"
        private const val K_PULLED = "last_pulled"
        private const val K_FAILED = "last_failed"
        private const val K_CONFIRM = "confirm_org"
        private const val K_CONFIRM_KIND = "confirm_org_kind"
        private const val K_PULL_ONLY = "pull_only"
        private const val K_PROGRESS = "progress"
        private const val K_CURSOR = "cursor"
        private const val K_DELETES = "pending_deletes"
        private const val K_RETRY = "retry_pulls"
    }
}
