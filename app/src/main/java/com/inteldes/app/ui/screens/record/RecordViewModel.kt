package com.inteldes.app.ui.screens.record

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioRecorderManager
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.audio.RecordingService
import com.inteldes.app.data.model.RecordingKind
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
)

class RecordViewModel(
    private val appContext: Context,
    private val recorder: AudioRecorderManager,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {

    private val engine = MutableStateFlow(WhisperEngine.CLOUD)
    private val doneChannel = Channel<String>(Channel.BUFFERED)
    val onRecordingReady = doneChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { engine.value = it.defaultEngine }
        }
    }

    val uiState: StateFlow<RecordUiState> = combine(
        recorder.state, recorder.elapsedSec, recorder.amplitudeHistory, engine,
    ) { state, elapsed, ampHistory, eng ->
        RecordUiState(state, elapsed, ampHistory, eng)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordUiState())

    fun setEngine(value: WhisperEngine) { engine.value = value }

    fun startRecording() {
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

    fun stopAndProcess(title: String, kind: RecordingKind) {
        val (file, duration) = recorder.stop() ?: (null to 0)
        RecordingService.stop(appContext)
        viewModelScope.launch {
            val id = repository.createRecording(
                title = title, kind = kind, durationSec = duration,
                audioFilePath = file?.absolutePath, engine = engine.value,
            )
            ProcessRecordingWorker.enqueue(appContext, id)
            doneChannel.send(id)
        }
    }

    fun discard() {
        recorder.discard()
        RecordingService.stop(appContext)
    }
}
