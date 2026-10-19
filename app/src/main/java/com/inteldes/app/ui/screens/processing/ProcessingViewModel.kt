package com.inteldes.app.ui.screens.processing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.ProcessingStep
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.work.ProcessRecordingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProcessingUiState(
    val recording: Recording? = null,
    val currentStep: ProcessingStep? = null,
    val error: String? = null,
    val marks: List<RecordingMark> = emptyList(),
) {
    val isDone: Boolean get() = recording?.status == RecordingStatus.DONE
}

class ProcessingViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    recordingId: String,
) : ViewModel() {

    val uiState: StateFlow<ProcessingUiState> = combine(
        repository.observeRecording(recordingId),
        ProcessRecordingWorker.observeStep(appContext, recordingId),
        ProcessRecordingWorker.observeError(appContext, recordingId),
        repository.observeMarks(recordingId),
    ) { recording, step, error, marks ->
        ProcessingUiState(recording, step, error, marks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProcessingUiState())

    fun retry(recordingId: String) {
        ProcessRecordingWorker.enqueue(appContext, recordingId)
    }

    /** Retry, but with an explicit engine + range choice first — a retry re-spends cloud
     * tokens/quota, so it shouldn't silently reuse whatever was set before without asking. */
    fun retryWithOptions(recordingId: String, engine: WhisperEngine, rangeStartSec: Int?, rangeEndSec: Int?) {
        viewModelScope.launch {
            repository.setEngine(recordingId, engine)
            ProcessRecordingWorker.enqueue(appContext, recordingId, rangeStartSec, rangeEndSec)
        }
    }

    fun cancel(recordingId: String) {
        ProcessRecordingWorker.cancel(appContext, recordingId)
        // Don't wait on the worker noticing its job got cancelled — an in-flight network
        // call won't unblock until it actually returns, which could take up to its own
        // timeout. Mark it failed here so the UI reflects "Batal" immediately.
        viewModelScope.launch { repository.markFailed(recordingId, "Dibatalkan oleh pengguna.") }
    }
}
