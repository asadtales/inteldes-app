package com.inteldes.app.ui.screens.contexts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.ContextSourceKind
import com.inteldes.app.data.model.KnowledgeContext
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.network.ContextPrompts
import com.inteldes.app.data.network.NotulensiGenerator
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.prefs.SecurePrefs
import com.inteldes.app.data.repository.ContextRepository
import com.inteldes.app.data.repository.RecordingRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextsUiState(
    val contexts: List<KnowledgeContext> = emptyList(),
    val recordings: List<Recording> = emptyList(),
)

/** The list of saved contexts, plus creating one from a set of recordings. */
class ContextsViewModel(
    private val contexts: ContextRepository,
    recordings: RecordingRepository,
) : ViewModel() {
    val uiState: StateFlow<ContextsUiState> = combine(contexts.observeAll(), recordings.observeRecordings()) { c, r ->
        ContextsUiState(c, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContextsUiState())

    fun create(name: String, recordingIds: List<String>, kind: ContextSourceKind, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(contexts.create(name, recordingIds, kind)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { contexts.delete(id) }
    }
}

data class ContextDetailState(
    val context: KnowledgeContext? = null,
    val draft: String = "",
    val saved: Boolean = true,
    val recordings: List<Recording> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val thinking: Boolean = false,
    val error: String? = null,
    /** AI's rewrite of the whole context, waiting for the user to accept or discard it. */
    val proposal: String? = null,
    val canUndo: Boolean = false,
)

/**
 * One saved context: the editable text (autosaved), questions to the AI about it, and AI-assisted
 * rewrites that only take effect when the user accepts them (and can be undone once).
 */
@OptIn(FlowPreview::class)
class ContextDetailViewModel(
    private val contexts: ContextRepository,
    recordings: RecordingRepository,
    private val settingsStore: AppSettingsStore,
    private val securePrefs: SecurePrefs,
    private val contextId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ContextDetailState())
    val state: StateFlow<ContextDetailState> = _state

    private val draft = MutableStateFlow<String?>(null)
    private var lastSaved: String? = null
    private var previousText: String? = null

    init {
        viewModelScope.launch {
            contexts.observe(contextId).collect { c ->
                _state.update { it.copy(context = c) }
                if (c != null && draft.value == null) {
                    lastSaved = c.text
                    draft.value = c.text
                    _state.update { it.copy(draft = c.text) }
                }
            }
        }
        viewModelScope.launch { recordings.observeRecordings().collect { r -> _state.update { it.copy(recordings = r) } } }
        // Autosave a moment after the user stops typing.
        viewModelScope.launch {
            draft.drop(1).debounce(900).collect { text ->
                if (text != null && text != lastSaved) {
                    contexts.updateText(contextId, text)
                    lastSaved = text
                    _state.update { it.copy(saved = true) }
                }
            }
        }
    }

    fun edit(text: String) {
        draft.value = text
        _state.update { it.copy(draft = text, saved = text == lastSaved) }
    }

    /** Writes immediately (leaving the screen shouldn't wait for the debounce). */
    fun flush() {
        val text = draft.value ?: return
        if (text != lastSaved) {
            lastSaved = text
            viewModelScope.launch { contexts.updateText(contextId, text) }
            _state.update { it.copy(saved = true) }
        }
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    fun rename(name: String) { viewModelScope.launch { contexts.rename(contextId, name) } }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { contexts.delete(contextId); onDone() }
    }

    fun addRecordings(ids: List<String>, kind: ContextSourceKind) {
        viewModelScope.launch {
            flush()
            contexts.addRecordings(contextId, ids, kind)
            val text = contexts.get(contextId)?.text.orEmpty()
            lastSaved = text
            draft.value = text
            _state.update { it.copy(draft = text, saved = true) }
        }
    }

    /** Removes one recording from this context; [onDone] says whether its text section was found and removed too. */
    fun removeRecording(recordingId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            flush()
            val removedText = contexts.removeRecording(contextId, recordingId)
            val text = contexts.get(contextId)?.text.orEmpty()
            lastSaved = text
            draft.value = text
            _state.update { it.copy(draft = text, saved = true) }
            onDone(removedText)
        }
    }

    // ───────────────────────── AI ─────────────────────────

    fun ask(question: String) {
        val current = _state.value
        val name = current.context?.name ?: return
        val history = current.chat
        _state.update { it.copy(chat = it.chat + ChatMessage(true, question, System.currentTimeMillis()), thinking = true, error = null) }
        viewModelScope.launch {
            try {
                val settings = settingsStore.settings.first()
                val answer = NotulensiGenerator(securePrefs).askContext(
                    provider = settings.provider, contextName = name, context = current.draft, history = history, question = question,
                    outputLanguage = settings.outputLanguage, economyMode = settings.economyMode,
                )
                reply(answer)
            } catch (e: Exception) {
                reply("Maaf, gagal menjawab: ${e.message}")
            }
        }
    }

    /** Asks the AI to rewrite the context per [instruction]; the result is only a proposal until [applyProposal]. */
    fun revise(instruction: String) {
        val current = _state.value
        val name = current.context?.name ?: return
        _state.update { it.copy(chat = it.chat + ChatMessage(true, "✎ $instruction", System.currentTimeMillis()), thinking = true, error = null, proposal = null) }
        if (current.draft.length > ContextPrompts.MAX_REVISE_CHARS) {
            reply("Konteks terlalu panjang untuk diperbaiki sekaligus (${current.draft.length / 1000} ribu karakter; batas ${ContextPrompts.MAX_REVISE_CHARS / 1000} ribu). Edit manual di tab Isi, atau buat konteks yang lebih kecil.")
            return
        }
        viewModelScope.launch {
            try {
                val settings = settingsStore.settings.first()
                val revised = NotulensiGenerator(securePrefs).reviseContext(
                    provider = settings.provider, contextName = name, context = current.draft, instruction = instruction,
                    outputLanguage = settings.outputLanguage, economyMode = settings.economyMode,
                )
                if (revised.isBlank()) throw IllegalStateException("AI mengembalikan teks kosong.")
                _state.update { it.copy(proposal = revised) }
                reply("Usulan perubahan siap. Periksa dulu, lalu tekan TERAPKAN — atau BUANG kalau tidak sesuai.")
            } catch (e: Exception) {
                reply("Maaf, gagal memperbaiki konteks: ${e.message}")
            }
        }
    }

    fun applyProposal() {
        val proposal = _state.value.proposal ?: return
        previousText = _state.value.draft
        edit(proposal)
        flush()
        _state.update { it.copy(proposal = null, canUndo = true) }
    }

    fun discardProposal() { _state.update { it.copy(proposal = null) } }

    fun undoApply() {
        val back = previousText ?: return
        previousText = null
        edit(back)
        flush()
        _state.update { it.copy(canUndo = false) }
    }

    private fun reply(text: String) {
        _state.update { it.copy(chat = it.chat + ChatMessage(false, text, System.currentTimeMillis()), thinking = false) }
    }
}
