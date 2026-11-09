package com.inteldes.app.ui.screens.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.photo.GeoFix
import com.inteldes.app.data.photo.PhotoGeo
import com.inteldes.app.data.photo.PhotoStorage
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.repository.TemplateRepository
import com.inteldes.app.data.video.VideoStorage
import com.inteldes.app.data.work.SyncWorker
import com.inteldes.app.ui.util.Wib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One photo or video taken in this visit to the screen. */
data class CapturedItem(val path: String, val isVideo: Boolean, val geo: GeoFix?)

data class CaptureUiState(
    val recordingId: String? = null,
    val title: String = "",
    val items: List<CapturedItem> = emptyList(),
    /** Put the position on each photo/video (and on the photo strip). On by default on this screen. */
    val geotag: Boolean = true,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val photoCount get() = items.count { !it.isVideo }
    val videoCount get() = items.count { it.isVideo }
}

/** Kind label of a recording that only holds photos/videos, with no audio. */
const val DOCUMENTATION_KIND = "Dokumentasi"

/**
 * Field documentation without an audio recording: every photo/video taken while this screen stays open goes into one
 * new "Dokumentasi" entry of the archive (created on the first capture), with the position of where it was taken.
 */
class CaptureViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    private val templateRepository: TemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state
    private val sessionLock = Mutex()

    fun setGeotag(on: Boolean) { _state.value = _state.value.copy(geotag = on) }
    fun clearNotice() { _state.value = _state.value.copy(notice = null) }

    /** Creates the "Dokumentasi" entry once, on the first capture. */
    private suspend fun ensureSession(): String = sessionLock.withLock {
        _state.value.recordingId?.let { return it }
        val settings = settingsStore.settings.first()
        val template = templateRepository.find(settings.defaultTemplateId)
        val title = "Dokumentasi " + Wib.format("d MMM yyyy HH.mm", System.currentTimeMillis()) + " WIB"
        val id = repository.createRecording(
            title = title, kind = DOCUMENTATION_KIND, durationSec = 0, audioFilePath = null,
            engine = WhisperEngine.CLOUD, template = template,
        )
        _state.value = _state.value.copy(recordingId = id, title = title)
        id
    }

    fun addPhoto(uri: Uri, temporary: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val id = ensureSession()
                val file = PhotoStorage.import(appContext, uri)
                val geoOn = _state.value.geotag
                val settings = settingsStore.settings.first().copy(photoGeotag = geoOn, photoGeoStamp = geoOn)
                val geo = PhotoGeo.process(appContext, file, settings, _state.value.title)
                repository.addPhoto(id, file, takenAtSec = null, geo = geo)
                SyncWorker.enqueue(appContext)
                added(CapturedItem(file.absolutePath, false, geo), geoOn)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, notice = e.message ?: "Gagal menyimpan foto.")
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun addVideo(uri: Uri, temporary: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val imported = VideoStorage.import(appContext, uri)
                val id = ensureSession()
                val geoOn = _state.value.geotag
                val geo = PhotoGeo.fixFor(appContext, settingsStore.settings.first().copy(photoGeotag = geoOn))
                repository.addVideo(id, imported, takenAtSec = null, geo = geo)
                SyncWorker.enqueue(appContext)
                added(CapturedItem(imported.file.absolutePath, true, geo), geoOn)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, notice = e.message ?: "Gagal menyimpan video.")
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun added(item: CapturedItem, geoWanted: Boolean) {
        val note = if (geoWanted && item.geo == null) "Tersimpan tanpa lokasi (GPS belum dapat atau izin lokasi belum diberikan)." else null
        _state.value = _state.value.copy(items = listOf(item) + _state.value.items, busy = false, notice = note)
    }
}
