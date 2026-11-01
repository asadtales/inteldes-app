package com.inteldes.app.data.server

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The server answers 429 ("too many requests") with how long to wait. A short wait is honoured here, once or
 * twice, so a busy moment does not fail a whole sync; a long one is left to the caller (the worker retries later).
 * Nothing was processed on a 429, so repeating the request is safe.
 */
class RetryAfterInterceptor(private val maxWaitSec: Long = 30, private val maxRetries: Int = 2, private val sleep: (Long) -> Unit = Thread::sleep) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var tries = 0
        while (response.code == 429 && tries < maxRetries && chain.request().body?.isOneShot() != true) {
            val wait = response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..maxWaitSec } ?: break
            response.close()
            sleep(wait * 1000)
            tries++
            response = chain.proceed(chain.request())
        }
        return response
    }
}
