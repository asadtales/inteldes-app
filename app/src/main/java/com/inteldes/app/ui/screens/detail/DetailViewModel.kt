package com.inteldes.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioPlayerManager
import com.inteldes.app.data.audio.PlaybackState
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.network.NotulensiGenerator
import com.inteldes.app.data.photo.PhotoStorage
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.model.KnowledgeContext
import com.inteldes.app.data.repository.ContextRepository
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.repository.TemplateRepository
import com.inteldes.app.data.work.ProcessRecordingWorker
import com.inteldes.app.data.work.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DetailTab { NOTES, SCRIPT, ASK }

/** Matches the `tab=` query values `Routes.Detail`/`Routes.Processing` expect. */
val DetailTab.routeName: String
    get() = when (this) {
        DetailTab.NOTES -> "notes"
        DetailTab.SCRIPT -> "script"
        DetailTab.ASK -> "ask"
    }

data class DetailUiState(
    val recording: Recording? = null,
    val notulensi: Notulensi? = null,
    val transcript: List<TranscriptSegment> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val templates: List<NoteTemplate> = emptyList(),
    val tab: DetailTab = DetailTab.SCRIPT,
    val thinking: Boolean = false,
    val regenerating: Boolean = false,
    val error: String? = null,
    val speakerRoster: List<String> = emptyList(),
    val notulensiHistory: List<com.inteldes.app.data.model.NotulensiHistoryEntry> = emptyList(),
    val marks: List<com.inteldes.app.data.model.RecordingMark> = emptyList(),
    val transcribeFirst: Boolean = false,
    /** Saved knowledge contexts, and the one (if any) attached to Tanya AI questions on this recording. */
    val contexts: List<KnowledgeContext> = emptyList(),
    val attachedContextId: String? = null,
)

class DetailViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val templateRepository: TemplateRepository,
    private val contextRepository: ContextRepository,
    private val settingsStore: AppSettingsStore,
    private val securePrefs: SecurePrefs,
    private val recordingId: String,
) : ViewModel() {

    private val tab = MutableStateFlow(DetailTab.SCRIPT)
    private val thinking = MutableStateFlow(false)
    private val regenerating = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val attachedContextId = MutableStateFlow<String?>(null)

    private val player = AudioPlayerManager()
    val playbackState: StateFlow<PlaybackState> = player.state

    init {
        viewModelScope.launch {
            val recording = repository.observeRecording(recordingId).first()
            recording?.audioFilePath?.let { player.load(it, recording.durationSec) }
        }
    }

    private val app get() = appContext.applicationContext as com.inteldes.app.IntelDesApp

    /** True while an unfinished trim of this recording is saved on this phone (marks its scissors button). */
    val hasTrimDraft: StateFlow<Boolean> = app.trimDrafts.ids.map { recordingId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    class LoadedDraft(val state: com.inteldes.app.data.audio.TrimDraftState, val savedAt: Long)

    /** Whether the trim draft may also live on the server: signed in, allowed to write, and the recording is there. */
    private suspend fun serverDraftPossible(): Boolean {
        val me = app.accountService.session.value ?: return false
        return me.role != "pembaca" && app.webSyncStore.enabled && app.webSyncStore.get(recordingId) != null
    }

    /** The saved trim: the newer of the copy on this phone and the one on the server (so it can be continued from another device). */
    suspend fun loadTrimDraft(durationSec: Int): LoadedDraft? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val local = app.trimDrafts.get(recordingId, durationSec)?.let { LoadedDraft(it.state, it.savedAt) }
        val remote = runCatching {
            if (!serverDraftPossible()) null
            else com.inteldes.app.data.web.WebApi(app.accountService.api).getTrimDraft(recordingId)?.let { (json, at) ->
                com.inteldes.app.data.audio.TrimDraftState.fromJson(json)?.let { LoadedDraft(it, at) }
            }
        }.getOrNull()
        listOfNotNull(local, remote).maxByOrNull { it.savedAt }
    }

    /** Keeps the draft on this phone and, when possible, on the server. Returns whether the server copy is current (null = not applicable). */
    suspend fun saveTrimDraft(state: com.inteldes.app.data.audio.TrimDraftState, durationSec: Int): Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        app.trimDrafts.put(recordingId, com.inteldes.app.data.audio.StoredDraft(durationSec, System.currentTimeMillis(), state))
        if (!serverDraftPossible()) null
        else runCatching { com.inteldes.app.data.web.WebApi(app.accountService.api).putTrimDraft(recordingId, com.inteldes.app.data.audio.TrimDraftState.toJson(state)) }.isSuccess
    }

    fun clearTrimDraft() {
        app.trimDrafts.clear(recordingId)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (serverDraftPossible()) com.inteldes.app.data.web.WebApi(app.accountService.api).deleteTrimDraft(recordingId) }
        }
    }

    // ───────────────────────── waveform of the trim editor ─────────────────────────

    data class WaveformUi(val loading: Boolean = false, val progress: Float = 0f, val data: com.inteldes.app.data.audio.WaveformData? = null, val error: String? = null)

    private val _waveform = MutableStateFlow(WaveformUi())
    val waveform: StateFlow<WaveformUi> = _waveform

    /** Reads the waveform from the cache, or builds it from the audio file (a long meeting takes a while; progress is shown). */
    fun loadWaveform() {
        if (_waveform.value.data != null || _waveform.value.loading) return
        _waveform.value = WaveformUi(loading = true)
        viewModelScope.launch {
            _waveform.value = try {
                val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val rec = repository.getRecording(recordingId) ?: error("Rekaman tidak ditemukan.")
                    val file = rec.audioFilePath?.let { java.io.File(it) }?.takeIf { it.exists() } ?: error("Berkas audio tidak ditemukan di perangkat.")
                    val key = "${recordingId}_${file.length()}"
                    com.inteldes.app.data.audio.WaveformBuilder.cached(appContext, key)
                        ?: com.inteldes.app.data.audio.WaveformBuilder.build(file, rec.durationSec) { p -> _waveform.value = WaveformUi(loading = true, progress = p) }
                            .also { com.inteldes.app.data.audio.WaveformBuilder.store(appContext, key, it) }
                }
                WaveformUi(data = data)
            } catch (e: Exception) {
                WaveformUi(error = e.message ?: "Gelombang tidak bisa dibuat.")
            }
        }
    }

    /** Progress or failure of this recording's processing on the server, if one was started. */
    val serverJob: StateFlow<com.inteldes.app.data.web.ServerJob?> =
        app.serverJobs.jobs.map { it[recordingId] }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Can this recording be processed with the server's keys, and is that the way to go because the phone has none of its own? */
    fun serverOption(hasAudio: Boolean): com.inteldes.app.data.web.ServerOption {
        val reason = com.inteldes.app.data.web.ServerProcessor.unavailableReason(
            session = app.accountService.session.value, webSyncOn = app.webSyncStore.enabled, pullOnly = app.webSyncStore.pullOnly,
            recordingExcluded = recordingId in app.syncExclusions.snapshot(), hasAudio = hasAudio,
        )
        val lacks = com.inteldes.app.data.web.ServerProcessor.phoneLacksKeys(
            hasDeepgram = securePrefs.effectiveWhisperCloudKey != null, hasGemini = securePrefs.effectiveGeminiKey != null,
            hasClaude = securePrefs.effectiveClaudeKey != null, needsTranscription = hasAudio,
        )
        return com.inteldes.app.data.web.ServerOption(reason == null, reason, preferred = lacks)
    }

    // ───────────────────────── public share links ─────────────────────────

    private val _share = MutableStateFlow(com.inteldes.app.ui.components.ShareUi(open = false))
    val share: StateFlow<com.inteldes.app.ui.components.ShareUi> = _share

    /** Opens the share dialog: says why sharing is impossible right now, or lists the links that already exist. */
    fun openShare() {
        val reason = com.inteldes.app.data.web.ServerProcessor.unavailableReason(
            app.accountService.session.value, app.webSyncStore.enabled, app.webSyncStore.pullOnly, recordingId in app.syncExclusions.snapshot(), hasAudio = true,
        )
        if (reason != null) { _share.value = com.inteldes.app.ui.components.ShareUi(reason = reason); return }
        _share.value = com.inteldes.app.ui.components.ShareUi(loading = true)
        viewModelScope.launch {
            _share.value = try {
                com.inteldes.app.ui.components.ShareUi(items = com.inteldes.app.data.web.WebApi(app.accountService.api).listShares(recordingId))
            } catch (e: Exception) {
                com.inteldes.app.ui.components.ShareUi(error = com.inteldes.app.data.server.userMessage(e))
            }
        }
    }

    fun closeShare() { _share.value = com.inteldes.app.ui.components.ShareUi(open = false) }

    /** Sends this recording to the server if it is not there yet, then asks for a link. The link's secret part is shown once. */
    fun createShare(includeTranscript: Boolean, includeAudio: Boolean, expiresInDays: Int?) {
        _share.value = _share.value.copy(busy = true, error = null, newUrl = null)
        viewModelScope.launch {
            try {
                val created = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = com.inteldes.app.data.web.WebApi(app.accountService.api)
                    val everyId = app.database.recordingDao().ids().toSet()
                    com.inteldes.app.data.work.WebSyncWorker.mutex.withLock {
                        com.inteldes.app.data.web.WebSyncEngine(com.inteldes.app.data.web.RoomWebLocal(appContext, app.database), api, app.webSyncStore, excluded = { everyId - recordingId }).run()
                    }
                    api.createShare(recordingId, includeTranscript, includeAudio, expiresInDays)
                }
                val list = runCatching { com.inteldes.app.data.web.WebApi(app.accountService.api).listShares(recordingId) }.getOrDefault(listOf(created.info()))
                _share.value = com.inteldes.app.ui.components.ShareUi(items = list, newUrl = created.url)
            } catch (e: Exception) {
                _share.value = _share.value.copy(busy = false, error = com.inteldes.app.data.server.userMessage(e))
            }
        }
    }

    fun revokeShare(shareId: String) {
        viewModelScope.launch {
            try {
                val api = com.inteldes.app.data.web.WebApi(app.accountService.api)
                api.revokeShare(shareId)
                _share.value = _share.value.copy(items = api.listShares(recordingId), error = null)
            } catch (e: Exception) {
                _share.value = _share.value.copy(error = com.inteldes.app.data.server.userMessage(e))
            }
        }
    }

    /** Hands the recording to the server (its keys, its quota). The result comes back by itself when it is done. */
    fun startServerProcessing(transcribeOnly: Boolean = false) {
        app.serverJobs.running(recordingId, "Menyiapkan…")
        viewModelScope.launch {
            repository.setEngine(recordingId, com.inteldes.app.data.model.WhisperEngine.CLOUD)       // the server transcribes CLOUD recordings with its own key
            com.inteldes.app.data.work.ServerProcessWorker.enqueue(
                appContext, recordingId,
                if (transcribeOnly) com.inteldes.app.data.work.ProcessMode.TRANSCRIBE_ONLY else com.inteldes.app.data.work.ProcessMode.FULL,
            )
        }
    }

    /** Writes the notulensi on the server from the transcript the user corrected (the audio is not processed again). */
    fun startServerNotes() {
        app.serverJobs.running(recordingId, "Menyiapkan…")
        com.inteldes.app.data.work.ServerProcessWorker.enqueue(appContext, recordingId, com.inteldes.app.data.work.ProcessMode.NOTES_ONLY)
    }

    fun togglePlayback() = player.togglePlay()
    fun skipPlayback(deltaSec: Int) = player.skipBy(deltaSec)

    /** Fixes the wording of one transcript line; notulensi isn't rewritten until the user presses SUSUN ULANG. */
    fun editTranscriptText(index: Int, text: String) {
        viewModelScope.launch { repository.updateTranscriptText(recordingId, index, text) }
    }

    fun attachContext(id: String?) { attachedContextId.value = id }
    fun seekPlayback(sec: Int) = player.seekTo(sec)

    /** Marks wherever the player currently is — for flagging moments while listening back. */
    fun addMarkAtPlayback() {
        val sec = player.state.value.positionSec
        viewModelScope.launch { repository.addMark(recordingId, sec, "Penanda") }
    }

    // ───────────────────────── short videos ─────────────────────────

    val videos: StateFlow<List<com.inteldes.app.data.model.Video>> =
        repository.observeVideos(recordingId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A short message for the user that must be seen on every tab (a video that was refused, one that was added). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    /** Checks the limits, copies the video into the app and attaches it; the sync sends it to the web. */
    fun addVideo(uri: Uri, temporary: Boolean) {
        viewModelScope.launch {
            try {
                val imported = com.inteldes.app.data.video.VideoStorage.import(appContext, uri)
                val geo = com.inteldes.app.data.photo.PhotoGeo.fixFor(appContext, settingsStore.settings.first())
                repository.addVideo(recordingId, imported, takenAtSec = null, geo = geo)
                SyncWorker.enqueue(appContext)
                _notice.value = "Video ditambahkan. Dikirim ke web lewat sinkron."
            } catch (e: Exception) {
                _notice.value = e.message ?: "Gagal menambahkan video."
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun updateVideoCaption(video: com.inteldes.app.data.model.Video, caption: String) {
        viewModelScope.launch { repository.updateVideoCaption(video.id, caption) }
    }

    fun deleteVideo(video: com.inteldes.app.data.model.Video) {
        viewModelScope.launch { repository.deleteVideo(video) }
    }

    fun setTitle(title: String) {
        viewModelScope.launch { repository.setTitle(recordingId, title) }
    }

    fun setLocation(name: String) {
        viewModelScope.launch { repository.setLocation(recordingId, name) }
    }

    fun renameMark(mark: com.inteldes.app.data.model.RecordingMark, label: String) {
        viewModelScope.launch { repository.renameMark(mark.id, label) }
    }

    fun deleteMark(mark: com.inteldes.app.data.model.RecordingMark) {
        viewModelScope.launch { repository.deleteMark(mark, recordingId) }
    }

    /** The quiet stretches of this recording, found on the phone (works offline). */
    fun analyzeSilences(noiseDb: Int, minSilenceSec: Int, onResult: (Result<List<com.inteldes.app.data.audio.Silence>>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val rec = repository.getRecording(recordingId) ?: error("Rekaman tidak ditemukan.")
                    val file = rec.audioFilePath?.let { java.io.File(it) }?.takeIf { it.exists() } ?: error("Berkas audio tidak ditemukan di perangkat.")
                    com.inteldes.app.data.audio.SilenceDetector.analyzeSilences(file, rec.durationSec, noiseDb, minSilenceSec)
                }
            }
            onResult(result)
        }
    }

    /**
     * Saves the [keep] parts as a new recording (the original is never touched), applies the chosen template and
     * engine, and reports the new id, or the failure, through [onResult].
     */
    fun trimToNewRecording(
        keep: List<com.inteldes.app.data.audio.Span>,
        title: String,
        template: com.inteldes.app.data.model.NoteTemplate,
        engine: com.inteldes.app.data.model.WhisperEngine,
        onResult: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val source = repository.getRecording(recordingId) ?: error("Rekaman tidak ditemukan.")
                try {
                    repository.createTrimmedFromSpans(source, keep, java.io.File(appContext.filesDir, "recordings"), title, template, engine)
                } catch (e: com.inteldes.app.data.audio.AudioTrimmer.UnsupportedFormatException) {
                    trimOnServer(source, keep, title, template, engine, e)       // mp3, wav: the phone cannot cut these, the server can
                }
            }.onSuccess { clearTrimDraft() }
            onResult(result)
        }
    }

    /**
     * Cuts a recording the phone cannot cut itself (mp3, wav) on the server: sends this one recording, has the
     * server cut it into a new recording, and brings that back. Needs the same things as processing on the server.
     */
    private suspend fun trimOnServer(
        source: com.inteldes.app.data.model.Recording, keep: List<com.inteldes.app.data.audio.Span>, title: String,
        template: com.inteldes.app.data.model.NoteTemplate, engine: com.inteldes.app.data.model.WhisperEngine,
        phoneError: Exception,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val supported = com.inteldes.app.data.audio.AudioFormats.table.filter { it.onPhone }.joinToString(", ") { it.label }
        val reason = com.inteldes.app.data.web.ServerProcessor.unavailableReason(
            app.accountService.session.value, app.webSyncStore.enabled, app.webSyncStore.pullOnly, recordingId in app.syncExclusions.snapshot(), source.audioFilePath != null,
        )
        if (!com.inteldes.app.data.audio.AudioFormats.serverCanCut(source.audioMimeType)) {
            throw java.io.IOException("Format ${source.audioMimeType} tidak bisa dipotong, di HP maupun di server. Yang bisa di HP: $supported. Server juga: MP3, WAV.")
        }
        if (reason != null) throw java.io.IOException("${phoneError.message} Bisa dipotong lewat server, tetapi: $reason")
        val api = com.inteldes.app.data.web.WebApi(app.accountService.api)
        val local = com.inteldes.app.data.web.RoomWebLocal(appContext, app.database)
        val everyId = app.database.recordingDao().ids().toSet()
        // 1) send just this recording, 2) have the server cut it, 3) receive what is new (the cut recording).
        com.inteldes.app.data.work.WebSyncWorker.mutex.withLock {
            com.inteldes.app.data.web.WebSyncEngine(local, api, app.webSyncStore, excluded = { everyId - recordingId }).run()
        }
        val newId = api.trimRecording(recordingId, keep.map { it.start to it.end }, title, source.kind)
        com.inteldes.app.data.work.WebSyncWorker.mutex.withLock {
            com.inteldes.app.data.web.WebSyncEngine(local, api, app.webSyncStore, excluded = { everyId }).run()
        }
        if (!local.exists(newId)) throw java.io.IOException("Hasil potong sudah dibuat di server tetapi belum sampai ke HP. Sinkronkan lagi dari Pengaturan.")
        repository.setTemplate(newId, template)
        repository.setEngine(newId, engine)
        newId
    }

    /** Playback at the precision the trim editor needs, and how often the position is published meanwhile. */
    fun seekToMs(ms: Int) = player.seekToMs(ms)
    fun pausePlayback() = player.pause()
    fun setFastTick(fast: Boolean) { player.tickMs = if (fast) 60 else 500 }

    override fun onCleared() {
        super.onCleared()
        player.release()
        // Edits made while this screen was open (photos, location, speaker names…) go to Drive on the way out.
        SyncWorker.enqueue(appContext)
    }

    private data class DetailData(
        val recording: Recording?,
        val notulensi: Notulensi?,
        val transcript: List<TranscriptSegment>,
        val chat: List<ChatMessage>,
        val history: List<com.inteldes.app.data.model.NotulensiHistoryEntry>,
        val photos: List<Photo>,
    )
    private data class DetailFlags(val tab: DetailTab, val thinking: Boolean, val regenerating: Boolean, val error: String?)

    private val dataFlow = combine(
        repository.observeRecording(recordingId),
        repository.observeNotulensi(recordingId),
        repository.observeTranscript(recordingId),
        repository.observeChat(recordingId),
        combine(repository.observeNotulensiHistory(recordingId), repository.observePhotos(recordingId)) { h, p -> h to p },
    ) { recording, notulensi, transcript, chat, (history, photos) -> DetailData(recording, notulensi, transcript, chat, history, photos) }

    private val flagsFlow = combine(tab, thinking, regenerating, error) { t, think, regen, err -> DetailFlags(t, think, regen, err) }

    private val extrasFlow = combine(templateRepository.templates, contextRepository.observeAll(), attachedContextId) { t, c, a -> Triple(t, c, a) }

    val uiState: StateFlow<DetailUiState> = combine(
        dataFlow, flagsFlow, settingsStore.settings, repository.observeMarks(recordingId), extrasFlow,
    ) { data, flags, settings, marks, (templates, contexts, attached) ->
        DetailUiState(
            recording = data.recording, notulensi = data.notulensi, transcript = data.transcript, chat = data.chat,
            photos = data.photos, templates = templates,
            tab = flags.tab, thinking = flags.thinking, regenerating = flags.regenerating, error = flags.error,
            speakerRoster = settings.speakerRoster, notulensiHistory = data.history, marks = marks,
            contexts = contexts, attachedContextId = attached, transcribeFirst = settings.transcribeFirst,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    fun setTab(value: DetailTab) { tab.value = value }

    /** Persists the chosen engine + range before handing back to the caller to actually
     * enqueue/navigate. rangeStartSec/rangeEndSec null = transcribe the whole recording. */
    fun startProcessing(
        engine: com.inteldes.app.data.model.WhisperEngine,
        rangeStartSec: Int?,
        rangeEndSec: Int?,
        onReady: (String, String, Int?, Int?) -> Unit,
    ) {
        viewModelScope.launch {
            repository.setEngine(recordingId, engine)
            onReady(recordingId, tab.value.routeName, rangeStartSec, rangeEndSec)
        }
    }

    fun renameSpeaker(oldName: String, newName: String) {
        viewModelScope.launch {
            repository.renameSpeaker(recordingId, oldName, newName)
            settingsStore.addSpeakerRosterName(newName)
        }
    }

    fun ask(question: String) {
        viewModelScope.launch {
            error.value = null
            repository.addChatMessage(recordingId, isUser = true, text = question)
            thinking.value = true
            try {
                val recording = repository.getRecording(recordingId) ?: return@launch
                val settings = settingsStore.settings.first()
                val transcript = repository.observeTranscript(recordingId).first()
                val notulensi = repository.observeNotulensi(recordingId).first()
                val history = repository.observeChat(recordingId).first()
                val generator = NotulensiGenerator(securePrefs)
                val extra = attachedContextId.value?.let { contextRepository.get(it)?.text }
                val answer = generator.ask(
                    provider = settings.provider,
                    durationSec = recording.durationSec,
                    question = question,
                    transcript = transcript,
                    notulensi = notulensi,
                    history = history,
                    outputLanguage = settings.outputLanguage,
                    economyMode = settings.economyMode,
                    extraContext = extra,
                )
                repository.addChatMessage(recordingId, isUser = false, text = answer)
            } catch (e: Exception) {
                repository.addChatMessage(recordingId, isUser = false, text = "Maaf, gagal menjawab: ${e.message}")
            } finally {
                thinking.value = false
            }
        }
    }

    private var regenerateJob: Job? = null

    /** Re-runs the AI over the existing transcript; with [newTemplate] the document is re-laid-out in that template. */
    fun regenerateNotulensi(newTemplate: NoteTemplate? = null) {
        regenerateJob?.cancel()
        regenerateJob = viewModelScope.launch {
            error.value = null
            regenerating.value = true
            try {
                val recording = repository.getRecording(recordingId) ?: return@launch
                val transcript = repository.observeTranscript(recordingId).first()
                if (transcript.isEmpty()) {
                    error.value = "Belum ada transkrip untuk disusun ulang."
                    return@launch
                }
                val template = newTemplate ?: templateRepository.find(recording.templateId)
                val settings = settingsStore.settings.first()
                val generator = NotulensiGenerator(securePrefs)
                val notulensi = generator.generateNotulensi(
                    provider = settings.provider,
                    durationSec = recording.durationSec,
                    meetingTitle = recording.title,
                    segments = transcript,
                    density = settings.density,
                    template = template,
                    location = recording.locationName,
                    outputLanguage = settings.outputLanguage,
                    economyMode = settings.economyMode,
                )
                val resolved = generator.resolve(settings.provider, recording.durationSec)
                if (newTemplate != null) repository.setTemplate(recordingId, newTemplate)
                repository.saveNotulensi(recordingId, resolved, notulensi, settings.density)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { error.value = "Dibatalkan oleh pengguna." }
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Gagal menyusun ulang notulensi."
            } finally {
                regenerating.value = false
            }
        }
    }

    fun cancelRegenerate() {
        regenerateJob?.cancel()
    }

    /** Stops any in-flight processing work and removes the recording (audio + all derived data). */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            ProcessRecordingWorker.cancel(appContext, recordingId)
            repository.deleteRecording(recordingId)
            // Tell Drive and the other devices too (only matters when Drive sync is connected).
            val sync = (appContext.applicationContext as com.inteldes.app.IntelDesApp).syncStore
            if (sync.settings.value.connected) sync.addPendingDelete(recordingId)
            SyncWorker.enqueue(appContext)
            onDone()
        }
    }

    /** Adds a photo after the fact; it isn't tied to a moment of the audio, so it has no timestamp. */
    fun addPhoto(uri: Uri, temporary: Boolean) {
        viewModelScope.launch {
            try {
                val file = PhotoStorage.import(appContext, uri)
                val title = repository.getRecording(recordingId)?.title
                val geo = com.inteldes.app.data.photo.PhotoGeo.process(appContext, file, settingsStore.settings.first(), title)
                repository.addPhoto(recordingId, file, takenAtSec = null, geo = geo)
            } catch (e: Exception) {
                error.value = e.message ?: "Gagal menambahkan foto."
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun updatePhotoCaption(photo: Photo, caption: String) {
        viewModelScope.launch { repository.updatePhotoCaption(photo.id, caption) }
    }

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch { repository.deletePhoto(photo) }
    }
}
