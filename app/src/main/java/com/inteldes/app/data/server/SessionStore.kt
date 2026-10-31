package com.inteldes.app.data.server

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The server login of this install, in its own encrypted file (`session_prefs.xml`, excluded from cloud
 * backup and device transfer — a session token must never be restored onto another phone).
 *
 * Stores tokens, device id and the profile for display. NEVER the password, a pairing code or a claimSecret.
 */
class SessionStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "session_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** True when the server ended the session (device revoked / refresh rejected), until the next login. */
    private val _endedByServer = MutableStateFlow(false)
    val endedByServer: StateFlow<Boolean> = _endedByServer.asStateFlow()

    /** The user chose "Lewati (mode lokal)": the login screen isn't pushed on them at every launch. */
    var loginSkipped: Boolean
        get() = prefs.getBoolean(K_SKIPPED, false)
        set(value) = prefs.edit().putBoolean(K_SKIPPED, value).apply()

    override val accessToken: String? get() = prefs.getString(K_ACCESS, null)
    override val refreshToken: String? get() = prefs.getString(K_REFRESH, null)

    fun saveLogin(result: LoginResult) {
        prefs.edit()
            .putString(K_ACCESS, result.accessToken)
            .putString(K_REFRESH, result.refreshToken)
            .putLong(K_EXPIRES_AT, System.currentTimeMillis() + result.expiresInSec * 1000L)
            .putString(K_DEVICE, result.deviceId)
            .putString(K_NAME, result.user.name)
            .putString(K_EMAIL, result.user.email)
            .putString(K_ORG, result.org.name)
            .putString(K_ROLE, result.role)
            .remove(K_KIND).remove(K_SUPER) // the login answer lacks them: AccountService asks /auth/me right after
            .putBoolean(K_SKIPPED, true) // signed in: nothing to skip any more
            .commit()
        _endedByServer.value = false
        _session.value = readSession()
    }

    /** The account type and platform-admin flag from `/auth/me`, shown in Settings and used to word the org-change warning. */
    fun saveProfile(orgKind: String?, superadmin: Boolean) {
        if (prefs.getString(K_REFRESH, null) == null) return
        prefs.edit().putString(K_KIND, orgKind ?: "team").putBoolean(K_SUPER, superadmin).commit()
        _session.value = readSession()
    }

    override fun saveTokens(deviceId: String, accessToken: String, refreshToken: String, expiresInSec: Int) {
        // commit(), not apply(): the old refresh token is already dead, so the new one must be on disk
        // before anything else can happen (including the process being killed).
        prefs.edit()
            .putString(K_ACCESS, accessToken)
            .putString(K_REFRESH, refreshToken)
            .putLong(K_EXPIRES_AT, System.currentTimeMillis() + expiresInSec * 1000L)
            .putString(K_DEVICE, deviceId)
            .commit()
    }

    override fun clearSession() {
        prefs.edit().remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES_AT).remove(K_DEVICE)
            .remove(K_NAME).remove(K_EMAIL).remove(K_ORG).remove(K_ROLE).remove(K_KIND).remove(K_SUPER).commit()
        _session.value = null
    }

    /** The server rejected the session for good; the UI shows the login screen with an explanation. */
    fun markEndedByServer() {
        _endedByServer.value = true
    }

    private fun readSession(): Session? {
        if (prefs.getString(K_REFRESH, null) == null) return null
        return Session(
            name = prefs.getString(K_NAME, "").orEmpty(),
            email = prefs.getString(K_EMAIL, "").orEmpty(),
            orgName = prefs.getString(K_ORG, "").orEmpty(),
            role = prefs.getString(K_ROLE, "").orEmpty(),
            deviceId = prefs.getString(K_DEVICE, "").orEmpty(),
            orgKind = prefs.getString(K_KIND, "team").orEmpty().ifBlank { "team" },
            superadmin = prefs.getBoolean(K_SUPER, false),
        )
    }

    private companion object {
        const val K_ACCESS = "access_token"
        const val K_REFRESH = "refresh_token"
        const val K_EXPIRES_AT = "access_expires_at"
        const val K_DEVICE = "device_id"
        const val K_NAME = "name"
        const val K_EMAIL = "email"
        const val K_ORG = "org_name"
        const val K_ROLE = "role"
        const val K_KIND = "org_kind"
        const val K_SUPER = "superadmin"
        const val K_SKIPPED = "login_skipped"
    }
}
