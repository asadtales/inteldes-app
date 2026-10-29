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
import kotlinx.coroutines.flow.stateIn
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
            recording?.audioFilePath?.let { player.load(it) }
        }
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

    fun setLocation(name: String) {
        viewModelScope.launch { repository.setLocation(recordingId, name) }
    }

    fun deleteMark(mark: com.inteldes.app.data.model.RecordingMark) {
        viewModelScope.launch { repository.deleteMark(mark, recordingId) }
    }

    /** Cuts [startSec, endSec) into a new recording; the original is never touched. */
    fun trimToNewRecording(startSec: Int, endSec: Int, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val source = repository.getRecording(recordingId) ?: error("Rekaman tidak ditemukan.")
                repository.createTrimmedRecording(source, startSec, endSec, java.io.File(appContext.filesDir, "recordings"))
            }
            onResult(result)
        }
    }

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
            contexts = contexts, attachedContextId = attached,
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
                repository.addPhoto(recordingId, file, takenAtSec = null)
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
