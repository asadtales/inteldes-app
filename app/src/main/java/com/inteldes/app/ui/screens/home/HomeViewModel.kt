package com.inteldes.app.ui.screens.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioFileImporter
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.ImportedAudio
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.RecordingRepository
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
)

class HomeViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    private val recorder: AudioRecorderManager,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow("Semua")
    private val pendingUpload = MutableStateFlow<ImportedAudio?>(null)
    private val importing = MutableStateFlow(false)
    private val uploadError = MutableStateFlow<String?>(null)
    private val uploadEngine = MutableStateFlow(WhisperEngine.CLOUD)

    private val uploadReadyChannel = Channel<String>(Channel.BUFFERED)
    val onUploadProcessed = uploadReadyChannel.receiveAsFlow()

    private data class Flags(val pending: ImportedAudio?, val importing: Boolean, val error: String?, val engine: WhisperEngine)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(repository.observeRecordings(), query, filter) { recs, q, f -> Triple(recs, q, f) },
        combine(pendingUpload, importing, uploadError, uploadEngine) { p, i, e, eng -> Flags(p, i, e, eng) },
        repository.observeCategories(),
        combine(recorder.state, recorder.elapsedSec) { st, el -> st to el },
    ) { (recs, q, f), flags, categories, (recState, recElapsed) ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: String) { filter.value = value }
    fun setUploadEngine(value: WhisperEngine) { uploadEngine.value = value }
    fun addCategory(label: String) { viewModelScope.launch { repository.addCategory(label) } }

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

    fun cancelPendingUpload() {
        pendingUpload.value?.file?.delete()
        pendingUpload.value = null
    }

    fun confirmUpload(title: String, kind: String) {
        val audio = pendingUpload.value ?: return
        val engine = uploadEngine.value
        pendingUpload.value = null
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = audio.durationSec,
                audioFilePath = audio.file.absolutePath, audioMimeType = audio.mimeType, engine = engine,
            )
            ProcessRecordingWorker.enqueue(appContext, id)
            uploadReadyChannel.send(id)
        }
    }
}
