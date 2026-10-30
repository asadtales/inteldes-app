package com.inteldes.app.data.server

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class MemoryTokens(access: String?, refresh: String?) : TokenStore {
    @Volatile override var accessToken: String? = access
    @Volatile override var refreshToken: String? = refresh
    val saves = CopyOnWriteArrayList<String>()
    @Volatile var cleared = false
    override fun saveTokens(deviceId: String, accessToken: String, refreshToken: String, expiresInSec: Int) {
        saves += "$accessToken/$refreshToken"
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }
    override fun clearSession() { cleared = true; accessToken = null; refreshToken = null }
}

class ServerApiTest {
    // A hung request must fail loudly instead of stalling the whole build.
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout(30, java.util.concurrent.TimeUnit.SECONDS)

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private fun url() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer(); requests.clear() }
    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private val loginOk = """{"user":{"id":"u1","email":"ani@desa.id","name":"Ani"},"org":{"id":"o1","name":"Desa Tales"},
        "role":"admin","deviceId":"d1","accessToken":"A1","refreshToken":"R1","expiresInSec":900}"""

    @Test
    fun loginSendsAndroidPlatformAndParsesTheSession() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return json(200, loginOk) }
        }
        val api = ServerApi(url(), MemoryTokens(null, null))
        val result = runBlocking { api.login(" ani@desa.id ", "rahasia", "Samsung SM-M515F") }
        val sent = JsonParser.parseString(requests.single().body.readUtf8()).asJsonObject
        assertEquals("/v1/auth/login", requests.single().path)
        assertEquals("android", sent["platform"].asString)
        assertEquals("ani@desa.id", sent["email"].asString)
        assertEquals("Samsung SM-M515F", sent["deviceName"].asString)
        assertEquals("R1", result.refreshToken)
        assertEquals("Desa Tales", result.org.name)
        assertEquals("admin", result.role)
        assertNull(requests.single().getHeader("Authorization"))
    }

    @Test
    fun aWrongPasswordIsAnErrorWithTheServersMessageAndNeverTriggersARefresh() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return json(401, """{"error":{"code":"invalid_credentials","message":"Email atau sandi salah","details":null}}""")
            }
        }
        val tokens = MemoryTokens("stale", "refresh")                  // even with an old session stored
        val api = ServerApi(url(), tokens)
        try { runBlocking { api.login("x@y.z", "salah", "HP") }; fail("expected ServerException") }
        catch (e: ServerException) {
            assertEquals(401, e.status); assertEquals("invalid_credentials", e.code); assertEquals("Email atau sandi salah", e.message)
        }
        assertEquals(1, requests.size)                                  // login only: no /refresh attempt
        assertFalse(tokens.cleared)
    }

    @Test
    fun claimSendsOnlyCodeAndDeviceNameNeverAClaimSecret() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return json(200, loginOk) }
        }
        runBlocking { ServerApi(url(), MemoryTokens(null, null)).claim("c".repeat(32), "Redmi 12") }
        val sent = JsonParser.parseString(requests.single().body.readUtf8()).asJsonObject
        assertEquals(setOf("code", "deviceName"), sent.keySet())
        assertNull(requests.single().getHeader("Authorization"))
    }

    @Test
    fun manyCallsGettingA401TogetherCauseExactlyOneRefreshAndAllSucceed() {
        val refreshes = AtomicInteger()
        val tokens = MemoryTokens("OLD", "R1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        // while the answer is "in flight", every other call is also bouncing off a 401
                        Thread.sleep(150)
                        json(200, """{"deviceId":"d1","accessToken":"NEW","refreshToken":"R2","expiresInSec":900}""")
                    }
                    request.getHeader("Authorization") == "Bearer NEW" ->
                        json(200, """{"user":{"id":"u1","email":"e","name":"Ani"},"org":{"id":"o1","name":"Desa"},"role":"admin","deviceId":"d1"}""")
                    else -> json(401, """{"error":{"code":"unauthorized","message":"Kedaluwarsa"}}""")
                }
            }
        }
        val api = ServerApi(url(), tokens)
        val results = runBlocking { (1..6).map { async(Dispatchers.IO) { api.me() } }.awaitAll() }
        assertEquals(6, results.size)
        assertEquals("exactly one refresh", 1, refreshes.get())
        assertEquals(listOf("NEW/R2"), tokens.saves)                    // saved once, as a pair
        assertEquals("R2", tokens.refreshToken)
        assertFalse(tokens.cleared)
    }

    @Test
    fun theNewTokensAreSavedBeforeTheOriginalRequestIsRetried() {
        val tokens = MemoryTokens("OLD", "R1")
        var storedWhenRetried: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/auth/refresh" -> json(200, """{"deviceId":"d1","accessToken":"NEW","refreshToken":"R2","expiresInSec":900}""")
                request.getHeader("Authorization") == "Bearer NEW" -> {
                    storedWhenRetried = tokens.refreshToken                 // what was on "disk" at the moment of the retry
                    json(200, """{"user":{"id":"u1","email":"e","name":"Ani"},"org":{"id":"o1","name":"Desa"},"role":"admin","deviceId":"d1"}""")
                }
                else -> json(401, """{"error":{"code":"unauthorized","message":"x"}}""")
            }
        }
        runBlocking { ServerApi(url(), tokens).me() }
        assertEquals("R2", storedWhenRetried)
    }

    @Test
    fun aRejectedRefreshEndsTheSessionAndReportsIt() {
        val tokens = MemoryTokens("OLD", "R1")
        var ended = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = json(401, """{"error":{"code":"unauthorized","message":"Sesi berakhir"}}""")
        }
        try { runBlocking { ServerApi(url(), tokens, onSessionEnded = { ended = true }).me() }; fail("expected failure") }
        catch (e: ServerException) { assertEquals(401, e.status) }
        assertTrue(tokens.cleared)
        assertTrue(ended)
    }

    @Test
    fun aNetworkFailureWhileRefreshingKeepsTheSession() {
        val tokens = MemoryTokens("OLD", "R1")
        var ended = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/v1/auth/refresh") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)   // the refresh call times out
                else json(401, """{"error":{"code":"unauthorized","message":"x"}}""")
        }
        val quick = ServerApi.defaultClient().newBuilder().readTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        try { runBlocking { ServerApi(url(), tokens, onSessionEnded = { ended = true }, base = quick).me() }; fail("expected failure") }
        catch (e: IOException) { /* a plain 401 ServerException or an IOException — either way, not a logout */ }
        assertFalse("offline is not a logout", tokens.cleared)
        assertFalse(ended)
        assertEquals("R1", tokens.refreshToken)
    }

    @Test
    fun pairingErrorsMapToTheMessagesInTheDocument() {
        assertEquals("Kode tidak berlaku. Buat kode baru di web.", userMessage(ServerException(404, "pair_invalid", "x")))
        assertEquals("Kode kedaluwarsa. Buat kode baru di web.", userMessage(ServerException(410, "pair_expired", "x")))
        assertEquals("Perangkat penyetuju tidak lagi sah.", userMessage(ServerException(403, "approver_invalid", "x")))
        assertEquals("Terlalu banyak percobaan. Coba lagi sebentar.", userMessage(ServerException(429, null, "x")))
        assertEquals("Email atau sandi salah", userMessage(ServerException(401, "invalid_credentials", "Email atau sandi salah")))
        assertTrue(userMessage(IOException("timeout")).contains("terhubung"))
    }
}
