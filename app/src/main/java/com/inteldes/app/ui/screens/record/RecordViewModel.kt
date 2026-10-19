package com.inteldes.app.ui.screens.record

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.RecordingService
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.photo.PhotoStorage
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** A photo taken during the recording; [offsetSec] is how far into the audio it was added. */
data class PendingPhoto(val file: File, val offsetSec: Int)

private data class PhotoState(val photos: List<PendingPhoto> = emptyList(), val error: String? = null)

data class RecordUiState(
    val recorderState: RecorderState = RecorderState.IDLE,
    val elapsedSec: Int = 0,
    val amplitudeHistory: List<Int> = List(40) { 0 },
    val engine: WhisperEngine = WhisperEngine.CLOUD,
    val photos: List<PendingPhoto> = emptyList(),
    val photoError: String? = null,
    val templateChoices: TemplateChoices = TemplateChoices.Initial,
)

class RecordViewModel(
    private val appContext: Context,
    private val recorder: AudioRecorderManager,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    templateRepository: TemplateRepository,
) : ViewModel() {

    private val engine = MutableStateFlow(WhisperEngine.CLOUD)
    private val photoState = MutableStateFlow(PhotoState())
    private val doneChannel = Channel<String>(Channel.BUFFERED)
    val onRecordingReady = doneChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { engine.value = it.defaultEngine }
        }
    }

    private val recorderFlow = combine(
        recorder.state, recorder.elapsedSec, recorder.amplitudeHistory, engine,
    ) { state, elapsed, ampHistory, eng ->
        RecordUiState(recorderState = state, elapsedSec = elapsed, amplitudeHistory = ampHistory, engine = eng)
    }

    val uiState: StateFlow<RecordUiState> = combine(
        recorderFlow, photoState, templateRepository.observeChoices(settingsStore),
    ) { base, photos, choices ->
        base.copy(photos = photos.photos, photoError = photos.error, templateChoices = choices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordUiState())

    fun setEngine(value: WhisperEngine) { engine.value = value }

    fun startRecording() {
        if (recorder.state.value != RecorderState.IDLE) return
        recorder.start()
        RecordingService.start(appContext)
    }

    fun togglePause() {
        when (recorder.state.value) {
            RecorderState.RECORDING -> recorder.pause()
            RecorderState.PAUSED -> recorder.resume()
            RecorderState.IDLE -> Unit
        }
    }

    /** Imports a camera/gallery image and pins it to the current moment of the recording. */
    fun addPhoto(uri: Uri, temporary: Boolean) {
        val offset = recorder.elapsedSec.value
        viewModelScope.launch {
            try {
                val file = PhotoStorage.import(appContext, uri)
                photoState.value = PhotoState(photoState.value.photos + PendingPhoto(file, offset), null)
            } catch (e: Exception) {
                photoState.value = photoState.value.copy(error = e.message ?: "Gagal menambahkan foto.")
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun removePhoto(photo: PendingPhoto) {
        photo.file.delete()
        photoState.value = PhotoState(photoState.value.photos - photo, null)
    }

    fun stopAndProcess(title: String, kind: RecordingKind, template: NoteTemplate) {
        val (file, duration) = recorder.stop() ?: (null to 0)
        RecordingService.stop(appContext)
        val photos = photoState.value.photos
        photoState.value = PhotoState()
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = duration,
                audioFilePath = file?.absolutePath, engine = engine.value, template = template,
            )
            photos.forEach { repository.addPhoto(id, it.file, it.offsetSec) }
            ProcessRecordingWorker.enqueue(appContext, id)
            doneChannel.send(id)
        }
    }

    fun discard() {
        recorder.discard()
        RecordingService.stop(appContext)
        photoState.value.photos.forEach { it.file.delete() }
        photoState.value = PhotoState()
    }
}
