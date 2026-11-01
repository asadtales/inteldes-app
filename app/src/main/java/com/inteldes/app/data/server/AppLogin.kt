package com.inteldes.app.data.server

import android.content.Context
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign in with Google or the IntelDes portal (SSO) from the app, without any OAuth secret or client id on the phone.
 *
 * The phone only opens the SERVER's own login in the browser and gets a one-time code back through the app link
 * `inteldes://auth/callback`. Everything OAuth (client id, client secret, redirect to Google / the portal, token
 * exchange) happens on the server. PKCE ties the code to this phone: the server only hands the tokens to whoever
 * holds the secret `verifier` whose hash was sent when the login started.
 */
object Pkce {
    private val random = SecureRandom()

    /** 43 URL-safe characters (RFC 7636 asks for 43 to 128). */
    fun newVerifier(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newState(): String = newVerifier()
}

/** Which ways in the server offers (GET /v1/auth/providers). [appLogin]: the server can hand a login back to the app. */
data class Providers(
    val password: Boolean = true,
    val sso: Boolean = false,
    val google: Boolean = false,
    val googleSignup: Boolean = false,
    val appLogin: Boolean = false,
) {
    /** Buttons for Google / the portal are only shown when the server can finish them for the app. */
    val external: Boolean get() = appLogin && (sso || google)
}

sealed interface AppLoginCallback {
    data class Code(val code: String) : AppLoginCallback
    data class Error(val message: String) : AppLoginCallback
}

object AppLoginLink {
    const val SCHEME = "inteldes"
    const val HOST = "auth"
    private const val PATH = "/callback"
    private val CODE = Regex("[A-Za-z0-9_-]{16,200}")

    /** The server page that starts the login for [provider] ("google" or "sso"). */
    fun startUrl(serverUrl: String, provider: String, challenge: String, state: String): String =
        serverUrl.trimEnd('/') + "/auth/app/start?provider=" + enc(provider) + "&challenge=" + enc(challenge) + "&state=" + enc(state)

    /**
     * What the browser handed back, or null when this is not our callback or its `state` is not the one this phone
     * started (a stray or forged link): such a link must not trigger anything.
     */
    fun parse(uri: String, expectedState: String?): AppLoginCallback? {
        if (expectedState.isNullOrEmpty()) return null
        val u = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!SCHEME.equals(u.scheme, ignoreCase = true) || !HOST.equals(u.host, ignoreCase = true) || u.path != PATH) return null
        val q = (u.rawQuery ?: return null).split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
        if (q["state"] != expectedState) return null
        q["error"]?.takeIf { it.isNotBlank() }?.let { return AppLoginCallback.Error(it.take(300)) }
        val code = q["code"]?.takeIf { CODE.matches(it) } ?: return null
        return AppLoginCallback.Code(code)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** The secret half of a login that is waiting for the browser, kept for ten minutes (the app may be closed meanwhile). */
class AppLoginPending(context: Context) {
    private val prefs = context.getSharedPreferences("inteldes_applogin", Context.MODE_PRIVATE)

    class Pending(val verifier: String, val state: String)

    /** Starts a login: remembers the verifier and state, and returns the URL to open. */
    fun begin(serverUrl: String, provider: String): String {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        prefs.edit().putString("verifier", verifier).putString("state", state).putLong("at", System.currentTimeMillis()).apply()
        return AppLoginLink.startUrl(serverUrl, provider, Pkce.challenge(verifier), state)
    }

    fun current(): Pending? {
        val at = prefs.getLong("at", 0)
        if (System.currentTimeMillis() - at > TTL_MS) { clear(); return null }
        val v = prefs.getString("verifier", null) ?: return null
        val s = prefs.getString("state", null) ?: return null
        return Pending(v, s)
    }

    fun clear() { prefs.edit().clear().apply() }

    private companion object { const val TTL_MS = 10 * 60_000L }
}
