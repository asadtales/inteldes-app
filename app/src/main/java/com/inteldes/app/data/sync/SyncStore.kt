package com.inteldes.app.data.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Settings shows about Drive sync. */
data class SyncSettings(
    val connected: Boolean = false,
    val account: String? = null,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val running: Boolean = false,
    val progress: String? = null,
)

/**
 * Per-device sync configuration and bookkeeping, in its own SharedPreferences file: whether Drive is
 * connected, which account, the options, and the state [SyncEngine] needs ("what was synced last
 * time"). Nothing here is shared across devices — each device sets itself up independently.
 */
class SyncStore(context: Context) : SyncStateStorage {
    private val prefs = context.getSharedPreferences("inteldes_sync", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SyncSettings> = _settings.asStateFlow()

    private fun read() = SyncSettings(
        connected = prefs.getBoolean(K_CONNECTED, false),
        account = prefs.getString(K_ACCOUNT, null),
        autoSync = prefs.getBoolean(K_AUTO, true),
        wifiOnly = prefs.getBoolean(K_WIFI, true),
        lastSyncAt = prefs.getLong(K_LAST, 0L).takeIf { it > 0 },
        lastError = prefs.getString(K_ERROR, null),
    )

    private fun refresh(running: Boolean = _settings.value.running, progress: String? = _settings.value.progress) {
        _settings.value = read().copy(running = running, progress = progress)
    }

    fun setConnected(account: String?) {
        prefs.edit().putBoolean(K_CONNECTED, true).putString(K_ACCOUNT, account).remove(K_ERROR).apply()
        refresh()
    }

    /** Forgets the link and the sync bookkeeping; recordings on the device and on Drive are left alone. */
    fun disconnect() {
        prefs.edit().putBoolean(K_CONNECTED, false).remove(K_ACCOUNT).remove(K_STATE).remove(K_ERROR).remove(K_LAST).apply()
        refresh(running = false, progress = null)
    }

    fun setAutoSync(value: Boolean) { prefs.edit().putBoolean(K_AUTO, value).apply(); refresh() }
    fun setWifiOnly(value: Boolean) { prefs.edit().putBoolean(K_WIFI, value).apply(); refresh() }
    fun setRunning(running: Boolean, progress: String? = null) = refresh(running, progress)

    fun finish(error: String?) {
        prefs.edit().apply {
            if (error == null) { putLong(K_LAST, System.currentTimeMillis()); remove(K_ERROR) } else putString(K_ERROR, error)
        }.apply()
        refresh(running = false, progress = null)
    }

    /** Remember that [id] was deleted here so the next sync removes it from Drive and other devices. */
    fun addPendingDelete(id: String) {
        val s = load()
        save(s.copy(pendingDeletes = s.pendingDeletes + id))
    }

    override fun load(): SyncState {
        val json = prefs.getString(K_STATE, null) ?: return SyncState()
        return runCatching { gson.fromJson<SyncState>(json, object : TypeToken<SyncState>() {}.type) }.getOrNull() ?: SyncState()
    }

    override fun save(state: SyncState) {
        prefs.edit().putString(K_STATE, gson.toJson(state)).apply()
    }

    private companion object {
        const val K_CONNECTED = "connected"
        const val K_ACCOUNT = "account"
        const val K_AUTO = "auto"
        const val K_WIFI = "wifi_only"
        const val K_LAST = "last_sync_at"
        const val K_ERROR = "last_error"
        const val K_STATE = "state_json"
    }
}
