package com.inteldes.app.ui.screens.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioFileImporter
import com.inteldes.app.data.audio.ImportedAudio
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
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
    val pendingUpload: ImportedAudio? = null,
    val importing: Boolean = false,
    val uploadError: String? = null,
)

class HomeViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow("Semua")
    private val pendingUpload = MutableStateFlow<ImportedAudio?>(null)
    private val importing = MutableStateFlow(false)
    private val uploadError = MutableStateFlow<String?>(null)

    private val uploadReadyChannel = Channel<String>(Channel.BUFFERED)
    val onUploadProcessed = uploadReadyChannel.receiveAsFlow()

    val filters = listOf("Semua", RecordingKind.MUSDES.label, RecordingKind.BPD.label, RecordingKind.BUMDES.label, RecordingKind.WARGA.label)

    private data class Flags(val pending: ImportedAudio?, val importing: Boolean, val error: String?)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(repository.observeRecordings(), query, filter) { recs, q, f -> Triple(recs, q, f) },
        combine(pendingUpload, importing, uploadError) { p, i, e -> Flags(p, i, e) },
    ) { (recs, q, f), flags ->
        val visible = recs.filter { r ->
            (f == "Semua" || r.kind.label == f) &&
                (q.isBlank() || r.title.contains(q, ignoreCase = true) || (r.summary?.contains(q, ignoreCase = true) == true))
        }
        HomeUiState(
            allCount = recs.size,
            workingCount = recs.count { it.status == RecordingStatus.WORKING },
            visible = visible,
            query = q,
            filter = f,
            pendingUpload = flags.pending,
            importing = flags.importing,
            uploadError = flags.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: String) { filter.value = value }

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            uploadError.value = null
            importing.value = true
            try {
                pendingUpload.value = AudioFileImporter.import(appContext, uri)
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

    fun confirmUpload(title: String, kind: RecordingKind) {
        val audio = pendingUpload.value ?: return
        pendingUpload.value = null
        viewModelScope.launch {
            val engine = settingsStore.settings.first().defaultEngine
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = audio.durationSec,
                audioFilePath = audio.file.absolutePath, audioMimeType = audio.mimeType, engine = engine,
            )
            ProcessRecordingWorker.enqueue(appContext, id)
            uploadReadyChannel.send(id)
        }
    }
}
