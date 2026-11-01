package com.inteldes.app.data.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screen shows for a recording being processed on the server. */
data class ServerJob(val message: String, val running: Boolean)

/** In-memory status of the server-side processing runs, by recording id (a run that dies with the app simply restarts). */
class ServerJobs {
    private val _jobs = MutableStateFlow<Map<String, ServerJob>>(emptyMap())
    val jobs: StateFlow<Map<String, ServerJob>> = _jobs.asStateFlow()

    fun running(id: String, message: String) { _jobs.value = _jobs.value + (id to ServerJob(message, true)) }
    fun failed(id: String, message: String) { _jobs.value = _jobs.value + (id to ServerJob(message, false)) }
    fun finish(id: String) { _jobs.value = _jobs.value - id }
}
