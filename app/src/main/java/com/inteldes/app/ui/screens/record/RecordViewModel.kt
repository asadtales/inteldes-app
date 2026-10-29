package com.inteldes.app.ui.screens.record

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.MicOption
import com.inteldes.app.data.audio.MicSources
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.RecordingService
import com.inteldes.app.data.location.LocationHelper
import com.inteldes.app.data.model.LocationInfo
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.photo.PhotoStorage
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.repository.TemplateChoices
import com.inteldes.app.data.repository.TemplateRepository
import com.inteldes.app.data.work.ProcessRecordingWorker
import com.inteldes.app.data.work.SyncWorker
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
    val marks: List<Int> = emptyList(),
    val autoProcess: Boolean = true,
    val markWithVolumeButton: Boolean = false,
    val photos: List<PendingPhoto> = emptyList(),
    val photoError: String? = null,
    val templateChoices: TemplateChoices = TemplateChoices.Initial,
    /** GPS fix taken when recording started (only when location permission is already granted). */
    val detectedLocation: LocationInfo? = null,
    /** Microphones Android offers right now, the user's saved pick ("" = automatic) and what is really in use. */
    val mics: List<MicOption> = emptyList(),
    val selectedMicKey: String = "",
    val activeMic: String? = null,
) {
    /** The saved microphone is chosen but not connected (e.g. earbuds in their case): recording falls back to automatic. */
    val selectedMicMissing: Boolean get() = selectedMicKey.isNotEmpty() && mics.none { it.key == selectedMicKey }
}

class RecordViewModel(
    private val appContext: Context,
    private val recorder: AudioRecorderManager,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    templateRepository: TemplateRepository,
) : ViewModel() {

    private val engine = MutableStateFlow(WhisperEngine.CLOUD)
    private val autoProcess = MutableStateFlow(true)
    private val markWithVolumeButton = MutableStateFlow(false)
    private val photoState = MutableStateFlow(PhotoState())
    private val detectedLocation = MutableStateFlow<LocationInfo?>(null)
    private val mics = MicSources.observe(appContext)
    private val selectedMicKey = MutableStateFlow("")
    private val doneChannel = Channel<RecordingReady>(Channel.BUFFERED)
    val onRecordingReady = doneChannel.receiveAsFlow()

    val categories: StateFlow<List<String>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            settingsStore.settings.collect {
                engine.value = it.defaultEngine
                autoProcess.value = it.autoProcessAfterRecording
                markWithVolumeButton.value = it.markWithVolumeButton
                selectedMicKey.value = it.preferredMicKey
            }
        }
    }

    private val recorderFlow = combine(
        combine(recorder.state, recorder.elapsedSec, recorder.amplitudeHistory) { state, elapsed, amp -> Triple(state, elapsed, amp) },
        engine, recorder.marks, autoProcess, markWithVolumeButton,
    ) { (state, elapsed, ampHistory), eng, m, auto, markVol ->
        RecordUiState(
            recorderState = state, elapsedSec = elapsed, amplitudeHistory = ampHistory, engine = eng,
            marks = m, autoProcess = auto, markWithVolumeButton = markVol,
        )
    }

    private val micFlow = combine(mics, selectedMicKey, recorder.activeMic) { list, key, active -> Triple(list, key, active) }

    val uiState: StateFlow<RecordUiState> = combine(
        recorderFlow, photoState, templateRepository.observeChoices(settingsStore), detectedLocation, micFlow,
    ) { base, photos, choices, location, (micList, micKey, activeMic) ->
        base.copy(
            photos = photos.photos, photoError = photos.error, templateChoices = choices, detectedLocation = location,
            mics = micList, selectedMicKey = micKey, activeMic = activeMic,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordUiState())

    fun addCategory(label: String) {
        viewModelScope.launch { repository.addCategory(label) }
    }

    fun deleteCategory(label: String) {
        viewModelScope.launch { repository.deleteCategory(label) }
    }

    fun setEngine(value: WhisperEngine) { engine.value = value }

    /** Safe to call whenever the record screen opens: reads the recorder's own state (not
     * [uiState], which still holds its IDLE default on first composition) and does nothing
     * if a recording is already running, so reopening the screen resumes it. */
    fun startRecording() {
        if (recorder.state.value != RecorderState.IDLE) return
        val chosen = MicSources.list(appContext).firstOrNull { it.key == selectedMicKey.value }
        // A chosen microphone that refuses to start (earbud asleep, SCO link down) must not block recording:
        // fall back to the system's automatic choice rather than losing the meeting.
        try {
            recorder.start(chosen)
        } catch (e: Exception) {
            if (chosen == null) throw e
            recorder.start(null)
        }
        RecordingService.start(appContext)
        detectedLocation.value = null
        // Tag where the meeting is happening; silently skipped unless permission was already granted.
        viewModelScope.launch { detectedLocation.value = LocationHelper.detect(appContext) }
    }

    /** [key] = a [MicOption.key], or "" for automatic. Remembered, and applied immediately if already recording. */
    fun selectMic(key: String) {
        selectedMicKey.value = key
        viewModelScope.launch { settingsStore.setPreferredMicKey(key) }
        recorder.selectMic(MicSources.list(appContext).firstOrNull { it.key == key })
    }

    fun togglePause() {
        when (recorder.state.value) {
            RecorderState.RECORDING -> recorder.pause()
            RecorderState.PAUSED -> recorder.resume()
            RecorderState.IDLE -> Unit
        }
    }

    /** Bookmarks the current moment — e.g. right when someone raises an important point —
     * so it can be jumped to later, or used as a start/end anchor when transcribing only
     * part of the recording instead of the whole thing. Delegates to [AudioRecorderManager]
     * so the recording notification's "TANDAI" action can call the exact same thing. */
    fun addMark() = recorder.addMark()

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

    fun stopAndProcess(title: String, kind: String, template: NoteTemplate, location: LocationInfo?) {
        val (file, duration) = recorder.stop() ?: (null to 0)
        RecordingService.stop(appContext)
        val pendingMarks = recorder.marks.value
        val shouldAutoProcess = autoProcess.value
        val photos = photoState.value.photos
        photoState.value = PhotoState()
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = duration,
                audioFilePath = file?.absolutePath, engine = engine.value, template = template, location = location,
            )
            repository.addMarks(id, pendingMarks)
            photos.forEach { repository.addPhoto(id, it.file, it.offsetSec) }
            if (shouldAutoProcess) ProcessRecordingWorker.enqueue(appContext, id) else SyncWorker.enqueue(appContext)
            doneChannel.send(RecordingReady(id, shouldAutoProcess))
        }
    }

    fun discard() {
        recorder.discard()
        RecordingService.stop(appContext)
        photoState.value.photos.forEach { it.file.delete() }
        photoState.value = PhotoState()
    }
}

data class RecordingReady(val recordingId: String, val autoProcessed: Boolean)
