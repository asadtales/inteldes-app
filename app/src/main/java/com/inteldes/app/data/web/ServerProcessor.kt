package com.inteldes.app.data.web

import com.inteldes.app.data.server.Session
import kotlinx.coroutines.delay

/** Whether "process on the server" can be offered for a recording, why not, and whether the phone has no keys of its own. */
data class ServerOption(val available: Boolean, val reason: String?, val preferred: Boolean)

/**
 * Processing one recording on the SERVER with the server's AI keys (the IntelDes key, with its quota), for a phone
 * that has no key of its own. The phone's own processing (its own keys) stays as it was; this is the alternative.
 *
 * Order: send the recording up, ask the server to process it, wait while it works, bring the result back.
 */
class ServerProcessor(
    /** Sends / receives just this one recording. */
    private val syncThisRecording: suspend () -> Unit,
    private val start: suspend () -> Unit,
    /** The server's status of the recording and its error message; null when the server does not know it. */
    private val status: suspend () -> Pair<String, String?>?,
    private val progress: (String) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val pollMs: Long = 5_000,
    private val maxWaitMs: Long = 90 * 60_000L,
) {
    sealed interface Outcome {
        data object Done : Outcome
        class Failed(val message: String) : Outcome
    }

    suspend fun run(): Outcome {
        progress("Mengirim rekaman ke server…")
        syncThisRecording()
        progress("Meminta server memproses…")
        start()
        var waited = 0L
        var last: Pair<String, String?>
        while (true) {
            last = status() ?: return Outcome.Failed("Rekaman tidak ditemukan di server.")
            if (last.first != "WORKING") break
            if (waited >= maxWaitMs) return Outcome.Failed("Server belum selesai setelah ${maxWaitMs / 60_000} menit. Cek lagi nanti; hasilnya tetap datang lewat sinkron.")
            progress("Diproses di server…")
            sleep(pollMs)
            waited += pollMs
        }
        progress("Mengambil hasil dari server…")
        syncThisRecording()
        return if (last.first == "DONE") Outcome.Done
        else Outcome.Failed(last.second?.takeIf { it.isNotBlank() } ?: "Server tidak berhasil memprosesnya.")
    }

    companion object {
        /**
         * Why this recording cannot go the server way right now (a sentence for the user), or null when it can.
         * Needs: signed in, allowed to write, the web sync on and sending (not receive-only), the recording not
         * kept out of the sync, and some audio or a transcript to work from.
         */
        fun unavailableReason(session: Session?, webSyncOn: Boolean, pullOnly: Boolean, recordingExcluded: Boolean, hasAudio: Boolean): String? = when {
            session == null -> "Masuk ke server dulu (Pengaturan > Akun)."
            session.role == "pembaca" -> "Peran pembaca tidak bisa memproses."
            !webSyncOn -> "Aktifkan sinkron ke web dulu (Pengaturan > Akun)."
            pullOnly -> "Sinkron sedang hanya menerima dari server; rekaman HP tidak bisa dikirim untuk diproses."
            recordingExcluded -> "Rekaman ini dikeluarkan dari sinkron. Centang lagi di Pengaturan > Rekaman yang ikut sinkron."
            !hasAudio -> "Rekaman ini tidak punya audio untuk dikirim."
            else -> null
        }

        /**
         * Looks at the account's quota BEFORE the recording is uploaded, so a long audio is not sent only to be refused.
         * The server decides in the end (and says so with its own wording); this only avoids the wasted upload.
         * Null = go ahead. Only the IntelDes key is limited by a package.
         */
        fun quotaBlock(access: com.inteldes.app.data.server.AiAccess?, durationSec: Int, resetText: (Long) -> String): String? {
            if (access == null || access.source != "platform") return null
            val when_ = access.resetsAt?.let { " Pulih ${resetText(it)}." }.orEmpty()
            if (access.jobs.left == 0) return "Batas pemrosesan paket sudah tercapai (${access.jobs.used} dari ${access.jobs.limit}).$when_"
            val minutes = access.minutes
            val needed = (durationSec + 59) / 60
            val left = minutes?.left
            if (left != null && needed > left) {
                return "Sisa menit audio $left, rekaman ini $needed menit.$when_ Gunakan kunci API sendiri di HP, atau minta paket yang lebih besar."
            }
            return null
        }

        /**
         * True when the phone lacks what it needs to do the work itself: the cloud transcription key (for a recording
         * that still has to be transcribed) or any key that writes the notulensi.
         */
        fun phoneLacksKeys(hasDeepgram: Boolean, hasGemini: Boolean, hasClaude: Boolean, needsTranscription: Boolean): Boolean =
            (needsTranscription && !hasDeepgram) || (!hasGemini && !hasClaude)
    }
}
