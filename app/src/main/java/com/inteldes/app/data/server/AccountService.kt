package com.inteldes.app.data.server

import android.os.Build
import com.inteldes.app.BuildConfig
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Turns any failure of a server call into the sentence shown to the user (see the error table in PAIRING_QR_ANDROID.md §7). */
fun userMessage(e: Throwable): String = when (e) {
    is ServerException -> when (e.code) {
        "pair_invalid" -> "Kode tidak berlaku. Buat kode baru di web."
        "pair_expired" -> "Kode kedaluwarsa. Buat kode baru di web."
        "approver_invalid" -> "Perangkat penyetuju tidak lagi sah."
        "phone_required" -> "Hanya HP yang bisa menyetujui. Ini seharusnya tidak terjadi — laporkan sebagai bug."
        "no_membership" -> "Akun ini belum tergabung di organisasi mana pun. Hubungi admin."
        else -> if (e.status == 429) "Terlalu banyak percobaan. Coba lagi sebentar." else e.message.orEmpty()
    }
    is IOException -> "Tidak bisa terhubung ke server. Periksa koneksi internet."
    else -> e.message ?: "Terjadi kesalahan."
}

/**
 * Login, QR pairing and sign-out for this install. The server login is OPTIONAL: nothing in the app
 * requires it, and a user who never signs in keeps recording and processing locally as before.
 */
class AccountService(
    private val store: SessionStore,
    private val serverUrl: String = BuildConfig.SERVER_URL,
) {
    val session: StateFlow<Session?> = store.session
    val endedByServer: StateFlow<Boolean> = store.endedByServer
    val loggedIn: Boolean get() = store.session.value != null

    var loginSkipped: Boolean
        get() = store.loginSkipped
        set(value) { store.loginSkipped = value }

    /** The authenticated API, for the data sync. */
    val api = ServerApi(serverUrl, store, onSessionEnded = { store.markEndedByServer() })

    val deviceName: String get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(100)

    /** `null` when [raw] isn't a QR of OUR server — no request is made for those. */
    fun parseQr(raw: String): PairingLink? = PairingLink.parse(raw, serverUrl)

    suspend fun login(email: String, password: String) {
        val result = api.login(email, password, deviceName)
        replaceSession(result)
    }

    /** Alur B, step 1: claim the QR. The result is NOT saved yet: the user confirms the account first ([commit]) or backs out ([discard]). */
    suspend fun claim(code: String): LoginResult = api.claim(code, deviceName)

    suspend fun commit(result: LoginResult) = replaceSession(result)

    /** The user backed out after a claim: revoke the device the server just created. Best effort. */
    suspend fun discard(result: LoginResult) {
        runCatching { api.logoutWith(result.accessToken) }
    }

    /** The organisation this session belongs to (asks the server). */
    suspend fun currentOrg(): ServerOrg = api.me().org

    suspend fun inspect(code: String): PairInspect = api.inspect(code)

    suspend fun approve(code: String) = api.approve(code)

    /** Best-effort revoke on the server, then wipe every trace of the session locally. */
    suspend fun logout() {
        runCatching { api.logout() }
        store.clearSession()
    }

    /** One session per install: a new login/claim replaces the old one, and the old device is revoked server-side. */
    private suspend fun replaceSession(result: LoginResult) {
        val old = store.accessToken
        store.saveLogin(result)
        if (old != null) runCatching { api.logoutWith(old) }
    }
}
