package com.inteldes.app.ui.screens.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.audio.AudioPlayerManager
import com.inteldes.app.data.audio.PlaybackState
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.network.NotulensiGenerator
import com.inteldes.app.data.photo.PhotoStorage
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.repository.RecordingRepository
import com.inteldes.app.data.repository.TemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DetailTab { NOTES, SCRIPT, ASK }

data class DetailUiState(
    val recording: Recording? = null,
    val notulensi: Notulensi? = null,
    val transcript: List<TranscriptSegment> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val templates: List<NoteTemplate> = emptyList(),
    val tab: DetailTab = DetailTab.NOTES,
    val thinking: Boolean = false,
    val regenerating: Boolean = false,
    val error: String? = null,
)

class DetailViewModel(
    private val appContext: Context,
    private val repository: RecordingRepository,
    private val templateRepository: TemplateRepository,
    private val settingsStore: AppSettingsStore,
    private val securePrefs: SecurePrefs,
    private val recordingId: String,
) : ViewModel() {

    private val tab = MutableStateFlow(DetailTab.NOTES)
    private val thinking = MutableStateFlow(false)
    private val regenerating = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val player = AudioPlayerManager()
    val playbackState: StateFlow<PlaybackState> = player.state

    init {
        viewModelScope.launch {
            val recording = repository.observeRecording(recordingId).first()
            recording?.audioFilePath?.let { player.load(it) }
        }
    }

    fun togglePlayback() = player.togglePlay()

    override fun onCleared() {
        super.onCleared()
        player.release()
    }

    private data class DetailData(
        val recording: Recording?,
        val notulensi: Notulensi?,
        val transcript: List<TranscriptSegment>,
        val chat: List<ChatMessage>,
        val photos: List<Photo>,
    )
    private data class DetailFlags(val tab: DetailTab, val thinking: Boolean, val regenerating: Boolean, val error: String?)

    private val dataFlow = combine(
        repository.observeRecording(recordingId),
        repository.observeNotulensi(recordingId),
        repository.observeTranscript(recordingId),
        repository.observeChat(recordingId),
        repository.observePhotos(recordingId),
    ) { recording, notulensi, transcript, chat, photos -> DetailData(recording, notulensi, transcript, chat, photos) }

    private val flagsFlow = combine(tab, thinking, regenerating, error) { t, think, regen, err -> DetailFlags(t, think, regen, err) }

    val uiState: StateFlow<DetailUiState> = combine(dataFlow, flagsFlow, templateRepository.templates) { data, flags, templates ->
        DetailUiState(
            recording = data.recording, notulensi = data.notulensi, transcript = data.transcript, chat = data.chat,
            photos = data.photos, templates = templates,
            tab = flags.tab, thinking = flags.thinking, regenerating = flags.regenerating, error = flags.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    fun setTab(value: DetailTab) { tab.value = value }

    fun renameSpeaker(oldName: String, newName: String) {
        viewModelScope.launch { repository.renameSpeaker(recordingId, oldName, newName) }
    }

    fun ask(question: String) {
        viewModelScope.launch {
            error.value = null
            repository.addChatMessage(recordingId, isUser = true, text = question)
            thinking.value = true
            try {
                val recording = repository.getRecording(recordingId) ?: return@launch
                val settings = settingsStore.settings.first()
                val transcript = repository.observeTranscript(recordingId).first()
                val notulensi = repository.observeNotulensi(recordingId).first()
                val history = repository.observeChat(recordingId).first()
                val generator = NotulensiGenerator(securePrefs)
                val answer = generator.ask(
                    provider = settings.provider,
                    durationSec = recording.durationSec,
                    question = question,
                    transcript = transcript,
                    notulensi = notulensi,
                    history = history,
                )
                repository.addChatMessage(recordingId, isUser = false, text = answer)
            } catch (e: Exception) {
                repository.addChatMessage(recordingId, isUser = false, text = "Maaf, gagal menjawab: ${e.message}")
            } finally {
                thinking.value = false
            }
        }
    }

    /** Re-runs the AI over the existing transcript; with [newTemplate] the document is re-laid-out in that template. */
    fun regenerateNotulensi(newTemplate: NoteTemplate? = null) {
        viewModelScope.launch {
            error.value = null
            regenerating.value = true
            try {
                val recording = repository.getRecording(recordingId) ?: return@launch
                val transcript = repository.observeTranscript(recordingId).first()
                if (transcript.isEmpty()) {
                    error.value = "Belum ada transkrip untuk disusun ulang."
                    return@launch
                }
                val template = newTemplate ?: templateRepository.find(recording.templateId)
                val settings = settingsStore.settings.first()
                val generator = NotulensiGenerator(securePrefs)
                val notulensi = generator.generateNotulensi(
                    provider = settings.provider,
                    durationSec = recording.durationSec,
                    meetingTitle = recording.title,
                    segments = transcript,
                    densityDetailed = settings.densityDetailed,
                    template = template,
                )
                val resolved = generator.resolve(settings.provider, recording.durationSec)
                if (newTemplate != null) repository.setTemplate(recordingId, newTemplate)
                repository.saveNotulensi(recordingId, resolved, notulensi)
            } catch (e: Exception) {
                error.value = e.message ?: "Gagal menyusun ulang notulensi."
            } finally {
                regenerating.value = false
            }
        }
    }

    /** Adds a photo after the fact; it isn't tied to a moment of the audio, so it has no timestamp. */
    fun addPhoto(uri: Uri, temporary: Boolean) {
        viewModelScope.launch {
            try {
                val file = PhotoStorage.import(appContext, uri)
                repository.addPhoto(recordingId, file, takenAtSec = null)
            } catch (e: Exception) {
                error.value = e.message ?: "Gagal menambahkan foto."
            } finally {
                if (temporary) runCatching { appContext.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun updatePhotoCaption(photo: Photo, caption: String) {
        viewModelScope.launch { repository.updatePhotoCaption(photo.id, caption) }
    }

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch { repository.deletePhoto(photo) }
    }
}
