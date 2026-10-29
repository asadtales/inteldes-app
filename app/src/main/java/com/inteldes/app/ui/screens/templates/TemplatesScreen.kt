package com.inteldes.app.ui.screens.templates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.TemplateSection
import com.inteldes.app.ui.components.IdTextField
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.theme.IdColor

@Composable
fun TemplatesScreen(factory: androidx.lifecycle.ViewModelProvider.Factory, onBack: () -> Unit) {
    val vm: TemplatesViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val draft = state.draft

    BackHandler(enabled = draft != null) { vm.cancelEdit() }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
        ) {
            IconButton(onClick = { if (draft != null) vm.cancelEdit() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text)
            }
            Text(
                when {
                    draft == null -> "Template dokumen"
                    draft.id == null -> "Template baru"
                    else -> "Ubah template"
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
            )
        }
        HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        if (draft == null) {
            TemplateList(
                templates = state.templates,
                defaultId = state.defaultId,
                onSetDefault = vm::setDefault,
                onEdit = vm::edit,
                onNew = vm::startNew,
            )
        } else {
            TemplateEditor(
                draft = draft,
                error = state.error,
                onChange = vm::updateDraft,
                onSave = vm::save,
                onCancel = vm::cancelEdit,
                onDelete = vm::deleteDraftTemplate,
            )
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<NoteTemplate>,
    defaultId: String,
    onSetDefault: (NoteTemplate) -> Unit,
    onEdit: (NoteTemplate) -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Template menentukan bentuk dokumen yang disusun AI: bagian apa saja yang dibuat dan bagaimana isinya. " +
                    "Ketuk template untuk menjadikannya bawaan; bisa diganti per rekaman saat memproses.",
                color = IdColor.Neutral700, fontSize = 12.5.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "+ TEMPLATE BARU",
                color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.6.sp,
                modifier = Modifier.fillMaxWidth().background(IdColor.Accent).clickable(onClick = onNew).padding(vertical = 14.dp, horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
            KickerLabel("Pilih template bawaan")
            Spacer(Modifier.height(8.dp))
        }
        items(templates, key = { it.id }) { t ->
            val selected = t.id == defaultId
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(if (selected) IdColor.Neutral900 else IdColor.Bg)
                    .let { if (!selected) it.border(BorderStroke(1.dp, IdColor.Divider)) else it }
                    .clickable { onSetDefault(t) }
                    .padding(13.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(14.dp)
                        .background(if (selected) IdColor.Accent else IdColor.Bg)
                        .let { if (!selected) it.border(BorderStroke(1.5.dp, IdColor.Neutral600)) else it },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.name, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = if (selected) IdColor.Bg else IdColor.Text, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            if (t.builtIn) "Bawaan" else "Custom",
                            background = if (selected) IdColor.Neutral700 else IdColor.Neutral200,
                            contentColor = if (selected) IdColor.Bg else IdColor.Neutral800,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.description, fontSize = 11.5.sp, lineHeight = 16.sp,
                        color = if (selected) IdColor.Bg.copy(alpha = 0.8f) else IdColor.Neutral700,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.sections.joinToString(" · ") { it.title },
                        fontSize = 10.5.sp, lineHeight = 15.sp,
                        color = if (selected) IdColor.Bg.copy(alpha = 0.65f) else IdColor.Neutral600,
                    )
                }
                Text(
                    if (t.builtIn) "SALIN" else "UBAH",
                    color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.8.sp,
                    modifier = Modifier.clickable { onEdit(t) }.padding(start = 10.dp, top = 2.dp, bottom = 6.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun typeHint(type: SectionType): String = when (type) {
    SectionType.PARAGRAPH -> "Teks mengalir, satu paragraf atau lebih."
    SectionType.BULLETS -> "Daftar poin singkat."
    SectionType.TOPICS -> "Daftar topik berwaktu: judul, uraian, dan menit mulai."
    SectionType.DECISIONS -> "Daftar keputusan bernomor."
    SectionType.QUESTIONS -> "Pertanyaan beserta status terjawab / belum."
    SectionType.ACTIONS -> "Tugas dengan penanggung jawab dan tenggat."
}

@Composable
private fun TemplateEditor(
    draft: TemplateDraft,
    error: String?,
    onChange: (TemplateDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    fun updateSection(index: Int, change: (TemplateSection) -> TemplateSection) {
        onChange(draft.copy(sections = draft.sections.mapIndexed { i, s -> if (i == index) change(s) else s }))
    }
    fun moveSection(index: Int, delta: Int) {
        val target = index + delta
        if (target !in draft.sections.indices) return
        val list = draft.sections.toMutableList()
        list[index] = list[target].also { list[target] = list[index] }
        onChange(draft.copy(sections = list))
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            KickerLabel("Nama template")
            Spacer(Modifier.height(6.dp))
            IdTextField(draft.name, { onChange(draft.copy(name = it)) }, "Contoh: Laporan Kegiatan Desa")
            Spacer(Modifier.height(14.dp))
            KickerLabel("Deskripsi singkat")
            Spacer(Modifier.height(6.dp))
            IdTextField(
                draft.description, { onChange(draft.copy(description = it)) },
                "Untuk apa template ini dipakai", singleLine = false, minLines = 2,
            )
            Spacer(Modifier.height(18.dp))
            KickerLabel("Bagian dokumen")
            Spacer(Modifier.height(4.dp))
            Text(
                "AI mengisi tiap bagian mengikuti judul, jenis, dan panduannya. Urutan bagian = urutan di dokumen.",
                color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        itemsIndexed(draft.sections) { index, section ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(IdColor.Neutral100)
                    .border(BorderStroke(1.dp, IdColor.Neutral300))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BAGIAN ${index + 1}", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    SmallIcon(Icons.Filled.ArrowUpward, "Naikkan", enabled = index > 0) { moveSection(index, -1) }
                    SmallIcon(Icons.Filled.ArrowDownward, "Turunkan", enabled = index < draft.sections.lastIndex) { moveSection(index, 1) }
                    SmallIcon(Icons.Filled.Close, "Hapus bagian", enabled = draft.sections.size > 1) {
                        onChange(draft.copy(sections = draft.sections.filterIndexed { i, _ -> i != index }))
                    }
                }
                Spacer(Modifier.height(8.dp))
                IdTextField(section.title, { v -> updateSection(index) { it.copy(title = v) } }, "Judul bagian, mis. Pembukaan")
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionType.entries.forEach { type ->
                        val selected = section.type == type
                        Text(
                            type.label,
                            color = if (selected) IdColor.White else IdColor.Neutral800,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(if (selected) IdColor.Accent else IdColor.Neutral200)
                                .clickable { updateSection(index) { it.copy(type = type) } }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(typeHint(section.type), color = IdColor.Neutral600, fontSize = 10.5.sp)
                Spacer(Modifier.height(8.dp))
                IdTextField(
                    section.instruction, { v -> updateSection(index) { it.copy(instruction = v) } },
                    "Panduan untuk AI: apa yang harus ditulis di bagian ini",
                    singleLine = false, minLines = 2,
                )
            }
        }
        item {
            Text(
                "+ TAMBAH BAGIAN",
                color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, IdColor.Divider))
                    .clickable { onChange(draft.copy(sections = draft.sections + TemplateSection("", SectionType.PARAGRAPH, ""))) }
                    .padding(vertical = 12.dp, horizontal = 16.dp),
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = IdColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "BATAL",
                    color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.weight(1f).border(BorderStroke(1.dp, IdColor.Divider)).clickable(onClick = onCancel).padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "SIMPAN TEMPLATE",
                    color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                    modifier = Modifier.weight(1.6f).background(IdColor.Accent).clickable(onClick = onSave).padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (draft.id != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "HAPUS TEMPLATE INI",
                    color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.6.sp,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = if (enabled) IdColor.Text else IdColor.Neutral400,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .size(18.dp),
    )
}
