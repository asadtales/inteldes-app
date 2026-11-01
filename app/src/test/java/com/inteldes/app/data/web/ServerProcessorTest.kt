package com.inteldes.app.data.web

import com.inteldes.app.data.server.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProcessorTest {
    private val log = mutableListOf<String>()

    private fun processor(statuses: List<Pair<String, String?>?>, maxWaitMs: Long = 90 * 60_000L): ServerProcessor {
        var i = 0
        return ServerProcessor(
            syncThisRecording = { log += "sync" },
            start = { log += "start" },
            status = { log += "status"; statuses[minOf(i++, statuses.lastIndex)] },
            progress = {},
            sleep = { log += "wait" },
            pollMs = 1_000,
            maxWaitMs = maxWaitMs,
        )
    }

    @Test
    fun sendsThenStartsThenWaitsThenReceives() {
        val out = runBlocking { processor(listOf("WORKING" to null, "WORKING" to null, "DONE" to null)).run() }
        assertTrue(out is ServerProcessor.Outcome.Done)
        assertEquals(listOf("sync", "start", "status", "wait", "status", "wait", "status", "sync"), log)
    }

    @Test
    fun aFailureOnTheServerReturnsItsOwnMessage() {
        val out = runBlocking { processor(listOf("RAW" to "Kunci Deepgram belum diisi")).run() }
        assertEquals("Kunci Deepgram belum diisi", (out as ServerProcessor.Outcome.Failed).message)
    }

    @Test
    fun itStopsWaitingAfterTheLimit() {
        val out = runBlocking { processor(listOf("WORKING" to null), maxWaitMs = 3_000).run() }
        assertTrue((out as ServerProcessor.Outcome.Failed).message.contains("belum selesai"))
        assertEquals(3, log.count { it == "wait" })
    }

    @Test
    fun aRecordingTheServerDoesNotKnowIsAFailure() {
        val out = runBlocking { processor(listOf(null)).run() }
        assertTrue(out is ServerProcessor.Outcome.Failed)
    }

    private fun access(source: String = "platform", jobs: Pair<Int, Int?> = 0 to 5, minutes: Pair<Int, Int?>? = 0 to 60) =
        com.inteldes.app.data.server.AiAccess(
            source = source, jobs = com.inteldes.app.data.server.AiUsage(jobs.first, jobs.second),
            minutes = minutes?.let { com.inteldes.app.data.server.AiUsage(it.first, it.second) }, resetsAt = 1_000L,
        )

    @Test
    fun theQuotaIsLookedAtBeforeTheAudioTravels() {
        val at = { t: Long -> "nanti" }
        assertNull(ServerProcessor.quotaBlock(access(), 30 * 60, at))                                   // 30 of 60 minutes: fine
        assertNull(ServerProcessor.quotaBlock(access(minutes = 50 to 60), 10 * 60, at))                // exactly what is left
        assertTrue(ServerProcessor.quotaBlock(access(minutes = 50 to 60), 10 * 60 + 1, at)!!.contains("Sisa menit audio 10"))
        assertTrue(ServerProcessor.quotaBlock(access(jobs = 5 to 5), 60, at)!!.contains("pemrosesan"))
        assertNull(ServerProcessor.quotaBlock(access(minutes = 500 to null), 3 * 3600, at))            // no limit
        assertNull(ServerProcessor.quotaBlock(access(source = "own", minutes = 60 to 60), 3 * 3600, at)) // own key: no package
        assertNull(ServerProcessor.quotaBlock(null, 3600, at))                                          // unknown: let the server decide
    }

    private fun session(role: String = "operator") = Session("Ani", "a@x", "Desa", role, "d1")

    @Test
    fun theServerWayNeedsLoginSyncAndARecordingThatTakesPart() {
        assertNull(ServerProcessor.unavailableReason(session(), webSyncOn = true, pullOnly = false, recordingExcluded = false, hasAudio = true))
        assertTrue(ServerProcessor.unavailableReason(null, true, false, false, true)!!.contains("Masuk"))
        assertTrue(ServerProcessor.unavailableReason(session("pembaca"), true, false, false, true)!!.contains("pembaca"))
        assertTrue(ServerProcessor.unavailableReason(session(), false, false, false, true)!!.contains("sinkron"))
        assertTrue(ServerProcessor.unavailableReason(session(), true, true, false, true)!!.contains("menerima"))
        assertTrue(ServerProcessor.unavailableReason(session(), true, false, true, true)!!.contains("dikeluarkan"))
        assertTrue(ServerProcessor.unavailableReason(session(), true, false, false, false)!!.contains("audio"))
    }

    @Test
    fun theServerIsPreferredOnlyWhenThePhoneLacksWhatItNeeds() {
        assertTrue(ServerProcessor.phoneLacksKeys(hasDeepgram = false, hasGemini = true, hasClaude = false, needsTranscription = true))
        assertTrue(ServerProcessor.phoneLacksKeys(hasDeepgram = true, hasGemini = false, hasClaude = false, needsTranscription = true))
        assertEquals(false, ServerProcessor.phoneLacksKeys(hasDeepgram = true, hasGemini = false, hasClaude = true, needsTranscription = true))
        assertEquals(false, ServerProcessor.phoneLacksKeys(hasDeepgram = false, hasGemini = true, hasClaude = false, needsTranscription = false))
    }
}
