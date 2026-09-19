package com.inteldes.app.data.network

import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared HTTP client + JSON codec for all provider calls (Whisper cloud, Gemini, Claude). */
object HttpClient {
    val gson: Gson = Gson()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS) // audio upload can be a large body
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

class ApiException(message: String, val httpCode: Int? = null) : Exception(message)
