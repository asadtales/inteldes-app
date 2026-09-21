package com.inteldes.app.data.network

import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared HTTP client + JSON codec for all provider calls (Whisper cloud, Gemini, Claude). */
object HttpClient {
    val gson: Gson = Gson()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS) // audio upload can be a large body on a slow mobile link
        .readTimeout(600, TimeUnit.SECONDS) // Deepgram returns the full transcript in one response —
        // a long meeting (diarized, whisper-large) can take several minutes server-side before it replies
        .build()
}

class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Cancellable equivalent of [Call.execute]. A plain blocking `execute()` ignores
 * coroutine cancellation entirely — cancelling the enclosing job (e.g. the user
 * tapping "Batal") wouldn't stop the network call, just eventually orphan it once
 * doWork() finally reached the next suspension point. Tying the call to the
 * continuation means Job cancellation cancels the underlying OkHttp call immediately.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        override fun onFailure(call: Call, e: IOException) {
            if (!call.isCanceled()) cont.resumeWithException(e)
        }
    })
}
