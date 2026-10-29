package com.inteldes.app.ui.screens.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioFileImporter
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.ImportedAudio
import com.inteldes.app.data.importer.ChatImport
import com.inteldes.app.data.importer.ParsedChat
import com.inteldes.app.data.importer.TextFileImporter
import com.inteldes.app.data.model.LocationInfo
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.repository.TemplateChoices
import com.inteldes.app.data.repository.TemplateRepository
import com.inteldes.app.data.work.ProcessRecordingWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val allCount: Int = 0,
    val workingCount: Int = 0,
    val visible: List<Recording> = emptyList(),
    val query: String = "",
    val filter: String = "Semua",
    val filters: List<String> = listOf("Semua"),
    val categories: List<String> = emptyList(),
    val pendingUpload: ImportedAudio? = null,
    val importing: Boolean = false,
    val uploadError: String? = null,
    val uploadEngine: WhisperEngine = WhisperEngine.CLOUD,
    val recorderState: RecorderState = RecorderState.IDLE,
    val recordingElapsedSec: Int = 0,
    /** A conversation read from a text file, waiting for the user to name it. */
    val pendingChat: ParsedChat? = null,
    val pendingChatName: String = "",
)

class HomeViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    private val recorder: AudioRecorderManager,
    templateRepository: TemplateRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow("Semua")
    private val pendingUpload = MutableStateFlow<ImportedAudio?>(null)
    private val importing = MutableStateFlow(false)
    private val uploadError = MutableStateFlow<String?>(null)
    private val uploadEngine = MutableStateFlow(WhisperEngine.CLOUD)
    private val pendingChat = MutableStateFlow<Pair<ParsedChat, String>?>(null)

    private val uploadReadyChannel = Channel<String>(Channel.BUFFERED)
    val onUploadProcessed = uploadReadyChannel.receiveAsFlow()

    val templateChoices: StateFlow<TemplateChoices> = templateRepository.observeChoices(settingsStore)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TemplateChoices.Initial)

    private data class Flags(val pending: ImportedAudio?, val importing: Boolean, val error: String?, val engine: WhisperEngine)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(repository.observeRecordings(), query, filter) { recs, q, f -> Triple(recs, q, f) },
        combine(pendingUpload, importing, uploadError, uploadEngine) { p, i, e, eng -> Flags(p, i, e, eng) },
        repository.observeCategories(),
        combine(recorder.state, recorder.elapsedSec, pendingChat) { st, el, chat -> Triple(st, el, chat) },
    ) { (recs, q, filterRaw), flags, categories, (recState, recElapsed, chat) ->
        val f = if (filterRaw == "Semua" || filterRaw in categories) filterRaw else "Semua"
        val visible = recs.filter { r ->
            (f == "Semua" || r.kind == f) &&
                (q.isBlank() || r.title.contains(q, ignoreCase = true) || (r.summary?.contains(q, ignoreCase = true) == true))
        }
        HomeUiState(
            allCount = recs.size,
            workingCount = recs.count { it.status == RecordingStatus.WORKING },
            visible = visible,
            query = q,
            filter = f,
            filters = listOf("Semua") + categories,
            categories = categories,
            pendingUpload = flags.pending,
            importing = flags.importing,
            uploadError = flags.error,
            uploadEngine = flags.engine,
            recorderState = recState,
            recordingElapsedSec = recElapsed,
            pendingChat = chat?.first,
            pendingChatName = chat?.second.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: String) { filter.value = value }
    fun setUploadEngine(value: WhisperEngine) { uploadEngine.value = value }
    fun addCategory(label: String) { viewModelScope.launch { repository.addCategory(label) } }
    fun deleteCategory(label: String) { viewModelScope.launch { repository.deleteCategory(label) } }

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            uploadError.value = null
            importing.value = true
            try {
                pendingUpload.value = AudioFileImporter.import(appContext, uri)
                // Seed the picker with the app's default, but let the user override it in the
                // dialog — unlike the record screen, upload has no earlier step to pick this.
                uploadEngine.value = settingsStore.settings.first().defaultEngine
            } catch (e: Exception) {
                uploadError.value = e.message ?: "Gagal membaca berkas audio."
            } finally {
                importing.value = false
            }
        }
    }

    /** Reads a WhatsApp export / plain dialogue file; [displayName] seeds the title suggestion. */
    fun onTextPicked(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            uploadError.value = null
            importing.value = true
            try {
                val chat = ChatImport.parse(TextFileImporter.read(appContext, uri))
                    ?: throw java.io.IOException("Tidak ada percakapan yang bisa dibaca di berkas itu. Dukungan: ekspor chat WhatsApp (.txt/.zip) atau teks \"Nama: ucapan\".")
                pendingChat.value = chat to (displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "")
            } catch (e: Exception) {
                uploadError.value = e.message ?: "Gagal membaca berkas teks."
            } finally {
                importing.value = false
            }
        }
    }

    fun cancelPendingChat() { pendingChat.value = null }

    /** Saves the conversation as a recording without audio and runs only the notulensi step on it. */
    fun confirmChat(title: String, kind: String, template: NoteTemplate, location: LocationInfo?) {
        val (chat, _) = pendingChat.value ?: return
        pendingChat.value = null
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = chat.durationSec, audioFilePath = null,
                engine = WhisperEngine.CLOUD, template = template, location = location, createdAt = chat.startMillis,
            )
            repository.saveTranscript(id, chat.participants, chat.segments)
            ProcessRecordingWorker.enqueue(appContext, id)
            uploadReadyChannel.send(id)
        }
    }

    fun cancelPendingUpload() {
        pendingUpload.value?.file?.delete()
        pendingUpload.value = null
    }

    fun confirmUpload(title: String, kind: String, template: NoteTemplate, location: LocationInfo?) {
        val audio = pendingUpload.value ?: return
        val engine = uploadEngine.value
        pendingUpload.value = null
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = audio.durationSec,
                audioFilePath = audio.file.absolutePath, audioMimeType = audio.mimeType, engine = engine, template = template, location = location,
            )
            ProcessRecordingWorker.enqueue(appContext, id)
            uploadReadyChannel.send(id)
        }
    }
}
