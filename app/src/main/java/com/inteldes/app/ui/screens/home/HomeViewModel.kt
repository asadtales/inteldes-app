package com.inteldes.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.repository.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val allCount: Int = 0,
    val workingCount: Int = 0,
    val visible: List<Recording> = emptyList(),
    val query: String = "",
    val filter: String = "Semua",
)

class HomeViewModel(repository: RecordingRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow("Semua")

    val filters = listOf("Semua", RecordingKind.MUSDES.label, RecordingKind.BPD.label, RecordingKind.BUMDES.label, RecordingKind.WARGA.label)

    val uiState: StateFlow<HomeUiState> = combine(repository.observeRecordings(), query, filter) { recs, q, f ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: String) { filter.value = value }
}
