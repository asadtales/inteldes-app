package com.inteldes.app.ui.screens.record

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.RecordingService
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.work.ProcessRecordingWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordUiState(
    val recorderState: RecorderState = RecorderState.IDLE,
    val elapsedSec: Int = 0,
    val amplitudeHistory: List<Int> = List(40) { 0 },
    val engine: WhisperEngine = WhisperEngine.CLOUD,
    val marks: List<Int> = emptyList(),
    val autoProcess: Boolean = true,
    val markWithVolumeButton: Boolean = false,
)

class RecordViewModel(
    private val appContext: Context,
    private val recorder: AudioRecorderManager,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {

    private val engine = MutableStateFlow(WhisperEngine.CLOUD)
    private val autoProcess = MutableStateFlow(true)
    private val markWithVolumeButton = MutableStateFlow(false)
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
            }
        }
    }

    val uiState: StateFlow<RecordUiState> = combine(
        combine(recorder.state, recorder.elapsedSec, recorder.amplitudeHistory) { state, elapsed, amp -> Triple(state, elapsed, amp) },
        engine, recorder.marks, autoProcess, markWithVolumeButton,
    ) { (state, elapsed, ampHistory), eng, m, auto, markVol ->
        RecordUiState(state, elapsed, ampHistory, eng, m, auto, markVol)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordUiState())

    fun addCategory(label: String) {
        viewModelScope.launch { repository.addCategory(label) }
    }

    fun setEngine(value: WhisperEngine) { engine.value = value }

    /** Safe to call whenever the record screen opens: reads the recorder's own state (not
     * [uiState], which still holds its IDLE default on first composition) and does nothing
     * if a recording is already running, so reopening the screen resumes it. */
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

    /** Bookmarks the current moment — e.g. right when someone raises an important point —
     * so it can be jumped to later, or used as a start/end anchor when transcribing only
     * part of the recording instead of the whole thing. Delegates to [AudioRecorderManager]
     * so the recording notification's "TANDAI" action can call the exact same thing. */
    fun addMark() = recorder.addMark()

    fun stopAndProcess(title: String, kind: String) {
        val (file, duration) = recorder.stop() ?: (null to 0)
        RecordingService.stop(appContext)
        val pendingMarks = recorder.marks.value
        val shouldAutoProcess = autoProcess.value
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = duration,
                audioFilePath = file?.absolutePath, engine = engine.value,
            )
            repository.addMarks(id, pendingMarks)
            if (shouldAutoProcess) ProcessRecordingWorker.enqueue(appContext, id)
            doneChannel.send(RecordingReady(id, shouldAutoProcess))
        }
    }

    fun discard() {
        recorder.discard()
        RecordingService.stop(appContext)
    }
}

data class RecordingReady(val recordingId: String, val autoProcessed: Boolean)
