package com.inteldes.app.ui.screens.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inteldes.app.data.model.BuiltInTemplates
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.TemplateSection
import com.inteldes.app.data.prefs.AppSettingsStore
import com.inteldes.app.data.repository.TemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A template being edited; [id] is null for a brand-new one (or a copy of a built-in). */
data class TemplateDraft(
    val id: String?,
    val name: String,
    val description: String,
    val sections: List<TemplateSection>,
)

data class TemplatesUiState(
    val templates: List<NoteTemplate> = BuiltInTemplates.all,
    val defaultId: String = BuiltInTemplates.STANDARD_ID,
    val draft: TemplateDraft? = null,
    val error: String? = null,
)

class TemplatesViewModel(
    private val repository: TemplateRepository,
    private val settings: AppSettingsStore,
) : ViewModel() {

    private val draft = MutableStateFlow<TemplateDraft?>(null)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TemplatesUiState> = combine(
        repository.observeChoices(settings), draft, error,
    ) { choices, d, e ->
        TemplatesUiState(choices.templates, choices.defaultId, d, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TemplatesUiState())

    fun setDefault(template: NoteTemplate) {
        viewModelScope.launch { settings.setDefaultTemplateId(template.id) }
    }

    fun startNew() {
        error.value = null
        draft.value = TemplateDraft(null, "", "", listOf(TemplateSection("", SectionType.PARAGRAPH, "")))
    }

    /** Custom templates are edited in place; built-ins can't change, so editing one starts a copy. */
    fun edit(template: NoteTemplate) {
        error.value = null
        draft.value = if (template.builtIn) {
            TemplateDraft(null, "${template.name} (salinan)", template.description, template.sections)
        } else {
            TemplateDraft(template.id, template.name, template.description, template.sections)
        }
    }

    fun updateDraft(updated: TemplateDraft) {
        draft.value = updated
    }

    fun cancelEdit() {
        draft.value = null
        error.value = null
    }

    fun save() {
        val d = draft.value ?: return
        val problem = validate(d)
        if (problem != null) {
            error.value = problem
            return
        }
        viewModelScope.launch {
            repository.saveCustom(
                NoteTemplate(
                    id = d.id ?: repository.newId(),
                    name = d.name.trim(),
                    description = d.description.trim(),
                    sections = d.sections.map { it.copy(title = it.title.trim(), instruction = it.instruction.trim()) },
                    builtIn = false,
                ),
            )
            draft.value = null
            error.value = null
        }
    }

    fun deleteDraftTemplate() {
        val id = draft.value?.id ?: return
        viewModelScope.launch {
            repository.deleteCustom(id)
            if (uiState.value.defaultId == id) settings.setDefaultTemplateId(BuiltInTemplates.STANDARD_ID)
            draft.value = null
            error.value = null
        }
    }

    private fun validate(d: TemplateDraft): String? = when {
        d.name.isBlank() -> "Nama template wajib diisi."
        d.sections.isEmpty() -> "Tambahkan minimal satu bagian."
        d.sections.any { it.title.isBlank() } -> "Setiap bagian harus punya judul."
        else -> null
    }
}
