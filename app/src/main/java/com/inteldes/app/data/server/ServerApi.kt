package com.inteldes.app.data.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inteldes.app.data.network.await
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the access/refresh tokens live (encrypted on a device; in memory in tests). */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?

    /** Persists a new pair. Called BEFORE the request that needed it is retried (the old refresh token is dead). */
    fun saveTokens(deviceId: String, accessToken: String, refreshToken: String, expiresInSec: Int)

    /** Forgets the whole session (tokens and profile). */
    fun clearSession()
}

/**
 * The server's auth API (https://inteldes.at-in.site, see docs/server/PAIRING_QR_ANDROID.md).
 *
 * Deliberately has NO logging interceptor: bodies of the auth endpoints carry passwords, tokens and pairing
 * codes, which must never reach Logcat.
 */
class ServerApi(
    private val baseUrl: String,
    private val tokens: TokenStore,
    private val onSessionEnded: () -> Unit = {},
    base: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson(),
) {
    val baseUrlString: String get() = baseUrl

    private val plain: OkHttpClient = base
    private val authed: OkHttpClient = base.newBuilder()
        .addInterceptor(BearerInterceptor(tokens, baseUrl.toHttpUrl().host))
        .authenticator(RefreshAuthenticator(tokens, ::refreshBlocking, onSessionEnded))
        .build()

    /** The session-aware client (Bearer + single-flight refresh) for the data sync. */
    val sessionClient: OkHttpClient get() = authed

    // ───────────────────────── no session needed ─────────────────────────

    suspend fun login(email: String, password: String, deviceName: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
            addProperty("platform", "android")     // must be "android": "web" would answer with cookies, not tokens
            addProperty("deviceName", deviceName.take(100))
        }
        return gson.fromJson(call(plain, post("/v1/auth/login", body)), LoginResult::class.java)
    }

    /** Alur B: a new phone claims an `add_phone` QR. Never sends a claimSecret (that belongs to the web in alur A). */
    suspend fun claim(code: String, deviceName: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("code", code)
            addProperty("deviceName", deviceName.take(100))
        }
        return gson.fromJson(call(plain, post("/v1/auth/pair/claim", body)), LoginResult::class.java)
    }

    /** The ways in this server offers; null when it cannot be reached (the login screen then shows email + QR only). */
    suspend fun providers(): Providers? =
        runCatching { gson.fromJson(call(plain, get("/v1/auth/providers")), Providers::class.java) }.getOrNull()

    /**
     * Finishes a Google / portal login that the browser started: trades the one-time [code] and the secret [verifier]
     * for this phone's tokens. The server checks that the verifier matches the challenge sent when the login began.
     */
    suspend fun exchangeAppLogin(code: String, verifier: String, deviceName: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("code", code)
            addProperty("verifier", verifier)
            addProperty("platform", "android")
            addProperty("deviceName", deviceName.take(100))
        }
        return gson.fromJson(call(plain, post("/v1/auth/app/exchange", body)), LoginResult::class.java)
    }

    // ───────────────────────── needs the session (refreshes itself on 401) ─────────────────────────

    /** The account's server-side settings; the app only reads the AI key source, readiness and usage from it. */
    suspend fun settings(): ServerSettingsView = gson.fromJson(call(authed, get("/v1/settings")), ServerSettingsView::class.java)

    suspend fun me(): MeResult = gson.fromJson(call(authed, get("/v1/auth/me")), MeResult::class.java)

    suspend fun inspect(code: String): PairInspect {
        val url = "$baseUrl/v1/auth/pair/inspect".toHttpUrl().newBuilder().addQueryParameter("code", code).build()
        return gson.fromJson(call(authed, Request.Builder().url(url).get().build()), PairInspect::class.java)
    }

    /** Alur A: approve the web login behind [code]. */
    suspend fun approve(code: String) {
        call(authed, post("/v1/auth/pair/approve", JsonObject().apply { addProperty("code", code) }))
    }

    /** Best effort: revokes this device on the server. Errors are ignored by callers. */
    suspend fun logout() {
        call(authed, post("/v1/auth/logout", JsonObject()))
    }

    /** Revokes a session that was never saved (a claim the user backed out of), using its own token. */
    suspend fun logoutWith(accessToken: String) {
        val request = Request.Builder().url("$baseUrl/v1/auth/logout").header("Authorization", "Bearer $accessToken")
            .post("{}".toRequestBody(JSON)).build()
        call(plain, request)
    }

    // ───────────────────────── refresh (blocking: called from the OkHttp Authenticator thread) ─────────────────────────

    /** Throws [ServerException] for a server answer (401 = session dead) and plain [IOException] for network trouble. */
    fun refreshBlocking(refreshToken: String): RefreshResult {
        val body = JsonObject().apply { addProperty("refreshToken", refreshToken) }
        plain.newCall(post("/v1/auth/refresh", body)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toException(resp.code, text)
            return gson.fromJson(text, RefreshResult::class.java)
        }
    }

    // ───────────────────────── plumbing ─────────────────────────

    private fun get(path: String) = Request.Builder().url("$baseUrl$path").get().build()

    private fun post(path: String, body: JsonObject) =
        Request.Builder().url("$baseUrl$path").post(gson.toJson(body).toRequestBody(JSON)).build()

    private suspend fun call(client: OkHttpClient, request: Request): String =
        client.newCall(request).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw toException(resp.code, text)
            text
        }

    fun toException(status: Int, text: String): ServerException {
        val error = runCatching { JsonParser.parseString(text).asJsonObject.getAsJsonObject("error") }.getOrNull()
        val code = error?.get("code")?.takeIf { !it.isJsonNull }?.asString
        val message = error?.get("message")?.takeIf { !it.isJsonNull }?.asString
            ?: when (status) {
                401 -> "Sesi tidak berlaku. Masuk lagi."
                429 -> "Terlalu banyak percobaan. Coba lagi sebentar."
                in 500..599 -> "Server sedang bermasalah. Coba lagi nanti."
                else -> "Permintaan ditolak server ($status)."
            }
        return ServerException(status, code, message)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(RetryAfterInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Adds the Bearer token to calls to our own server only (never leaks it to another host). */
class BearerInterceptor(private val tokens: TokenStore, private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.equals(host, ignoreCase = true) || request.header("Authorization") != null) return chain.proceed(request)
        val token = tokens.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On a 401, trades the refresh token for a new pair and retries once.
 *
 *  - SINGLE-FLIGHT: refresh tokens rotate and reusing an old one is treated as theft (the server revokes the
 *    whole device), so two requests that both got 401 must produce exactly ONE refresh; the second one just
 *    picks up the token the first one saved.
 *  - The new pair is SAVED before the request is retried.
 *  - Only a 401 from the refresh call ends the session. A network failure leaves it intact.
 */
class RefreshAuthenticator(
    private val tokens: TokenStore,
    private val refresh: (String) -> RefreshResult,
    private val onSessionEnded: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/v1/auth/refresh")) return null
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null // not a session call (e.g. a wrong password)
        if (response.priorResponse != null) return null                                        // already retried once
        synchronized(lock) {
            val current = tokens.accessToken
            if (current != null && current != sent) {                                          // another call already refreshed
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refreshToken = tokens.refreshToken ?: return null
            val fresh = try {
                refresh(refreshToken)
            } catch (e: ServerException) {
                if (e.status == 401) { tokens.clearSession(); onSessionEnded() }               // the session is really gone
                return null
            } catch (e: IOException) {
                return null                                                                    // offline / timeout: keep the session
            }
            tokens.saveTokens(fresh.deviceId, fresh.accessToken, fresh.refreshToken, fresh.expiresInSec)
            return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
        }
    }
}
