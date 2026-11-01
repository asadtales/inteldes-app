package com.inteldes.app.data.server

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryAfterInterceptorTest {
    private val server = MockWebServer().apply { start() }
    private val waits = mutableListOf<Long>()
    private val client = OkHttpClient.Builder().addInterceptor(RetryAfterInterceptor(sleep = { waits += it })).build()

    @After fun stop() = server.shutdown()

    private fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    @Test
    fun aShortRetryAfterIsWaitedOutAndTheRequestRepeated() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val r = get()
        assertEquals(200, r.code)
        assertEquals(listOf(2000L), waits)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aLongRetryAfterIsLeftToTheCaller() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val r = get()
        assertEquals(429, r.code)
        assertEquals(emptyList<Long>(), waits)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun itGivesUpAfterTwoRepeats() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1")) }
        assertEquals(429, get().code)
        assertEquals(3, server.requestCount)
    }
}
