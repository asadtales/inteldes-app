package com.inteldes.app.ui.screens.detail

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.inteldes.app.data.export.NotulensiExporter
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.NotulensiHistoryEntry
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.SectionContent
import com.inteldes.app.data.model.Topic
import com.inteldes.app.ui.components.IdTextField
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.LocationRow
import com.inteldes.app.ui.components.PhotoImage
import com.inteldes.app.ui.components.PhotoPickerButtons
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.components.rememberPhotoPicker
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatRelativeDay
import com.inteldes.app.ui.util.formatRecordTimer

/** The generated document: one block per template section, then the photo documentation. */
@Composable
internal fun NotesTab(
    recording: Recording,
    notulensi: Notulensi?,
    history: List<NotulensiHistoryEntry>,
    photos: List<Photo>,
    templates: List<NoteTemplate>,
    regenerating: Boolean,
    error: String?,
    onRegenerate: () -> Unit,
    onCancelRegenerate: () -> Unit,
    onEditLocation: () -> Unit,
    onPickTemplate: (NoteTemplate) -> Unit,
    onChangeAgent: () -> Unit,
    onAddPhoto: (Uri, Boolean) -> Unit,
    onUpdateCaption: (Photo, String) -> Unit,
    onDeletePhoto: (Photo) -> Unit,
    onShareLink: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showTemplatePicker by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    var viewingHistory by remember { mutableStateOf<NotulensiHistoryEntry?>(null) }
    val context = LocalContext.current
    val photoPicker = rememberPhotoPicker(onAddPhoto)

    if (notulensi == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Notulensi belum tersedia.", color = IdColor.Neutral700, fontSize = 13.sp)
        }
    } else {
        LazyColumn(modifier = modifier.padding(horizontal = 18.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(providerBadge(notulensi.provider), IdColor.Accent, IdColor.White)
                    Spacer(Modifier.width(8.dp))
                    Text("disusun oleh agent AI", color = IdColor.Neutral600, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                LocationRow(recording.locationName, onEdit = onEditLocation)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Template: ${recording.templateName}",
                        color = IdColor.Neutral800, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "GANTI",
                        color = IdColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        modifier = Modifier.clickable(enabled = !regenerating) { showTemplatePicker = true }.padding(8.dp),
                    )
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("VERSI SEBELUMNYA", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        history.forEach { entry ->
                            Text(
                                "${entry.templateName} · ${entry.density.label} · ${formatRelativeDay(entry.generatedAt)}",
                                color = IdColor.Neutral800,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .background(IdColor.Neutral100)
                                    .clickable { viewingHistory = entry }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
            }
            itemsIndexed(notulensi.sections) { index, section -> SectionBlock(section, isFirst = index == 0) }

            item {
                KickerLabel("Dokumentasi", modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                PhotoPickerButtons(photoPicker)
                Spacer(Modifier.height(10.dp))
                if (photos.isEmpty()) {
                    Text(
                        "Belum ada foto. Foto yang ditambahkan tampil di sini sebagai bagian notulensi.",
                        color = IdColor.Neutral600, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
            items(photos.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    pair.forEach { photo -> PhotoCard(photo, Modifier.weight(1f)) { viewing = photo } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = IdColor.Accent, fontSize = 12.sp)
                }
                Row(modifier = Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (regenerating) {
                        Text(
                            "MENYUSUN…",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Neutral600,
                            modifier = Modifier.weight(1f).background(IdColor.Bg).padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            "BATALKAN",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.White,
                            modifier = Modifier.weight(1f).background(IdColor.Accent).clickable(onClick = onCancelRegenerate).padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    } else {
                        Text(
                            "SUSUN ULANG",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Text,
                            modifier = Modifier.weight(1f).background(IdColor.Bg).clickable(onClick = onRegenerate).padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            "GANTI AGENT",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Bg,
                            modifier = Modifier.weight(1f).background(IdColor.Neutral900).clickable(onClick = onChangeAgent).padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "EKSPOR .TXT",
                        fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Text,
                        modifier = Modifier
                            .weight(1f)
                            .background(IdColor.Neutral100)
                            .clickable {
                                val uri = NotulensiExporter.exportTxt(context, recording, notulensi, photos)
                                context.startActivity(NotulensiExporter.shareIntent(uri, "text/plain"))
                            }
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "EKSPOR PDF",
                        fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Text,
                        modifier = Modifier
                            .weight(1f)
                            .background(IdColor.Neutral100)
                            .clickable {
                                runCatching { NotulensiExporter.exportPdf(context, recording, notulensi, photos) }
                                    .onSuccess { uri -> context.startActivity(NotulensiExporter.shareIntent(uri, "application/pdf")) }
                                    .onFailure { android.widget.Toast.makeText(context, "Gagal membuat PDF: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "EKSPOR WORD",
                        fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Text,
                        modifier = Modifier
                            .weight(1f)
                            .background(IdColor.Neutral100)
                            .clickable {
                                val uri = NotulensiExporter.exportDocx(context, recording, notulensi, photos)
                                context.startActivity(
                                    NotulensiExporter.shareIntent(
                                        uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    ),
                                )
                            }
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Text(
                    "BAGIKAN LEWAT TAUTAN",
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = IdColor.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .background(IdColor.Neutral100)
                        .clickable(onClick = onShareLink)
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    if (showTemplatePicker) {
        TemplatePickerDialog(
            templates = templates,
            currentId = recording.templateId,
            onDismiss = { showTemplatePicker = false },
            onPick = {
                showTemplatePicker = false
                onPickTemplate(it)
            },
        )
    }

    viewingHistory?.let { entry ->
        NotulensiHistoryDialog(entry = entry, onDismiss = { viewingHistory = null })
    }

    viewing?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewing = null },
            onSaveCaption = {
                onUpdateCaption(photo, it)
                viewing = null
            },
            onDelete = {
                onDeletePhoto(photo)
                viewing = null
            },
        )
    }
}

@Composable
private fun SectionBlock(section: NoteSection, isFirst: Boolean) {
    Column {
        KickerLabel(section.title, modifier = Modifier.padding(top = if (isFirst) 14.dp else 22.dp, bottom = 8.dp))
        when (val content = section.content) {
            is SectionContent.Paragraph ->
                if (content.text.isBlank()) EmptyHint() else Text(content.text, fontSize = 14.sp, lineHeight = 22.sp)
            is SectionContent.Bullets ->
                if (content.items.isEmpty()) EmptyHint() else content.items.forEach { BulletRow(it) }
            is SectionContent.Topics ->
                if (content.items.isEmpty()) EmptyHint() else content.items.forEach { TopicRow(it) }
            is SectionContent.Decisions ->
                if (content.items.isEmpty()) EmptyHint() else content.items.forEach { DecisionRow(it) }
            is SectionContent.Questions ->
                if (content.items.isEmpty()) EmptyHint() else content.items.forEach { QuestionRow(it) }
            is SectionContent.Actions ->
                if (content.items.isEmpty()) EmptyHint() else content.items.forEach { ActionRow(it) }
        }
    }
}

@Composable
private fun EmptyHint() {
    Text("Tidak ada.", color = IdColor.Neutral600, fontSize = 12.sp)
}

@Composable
private fun BulletRow(text: String) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("•", color = IdColor.Accent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(18.dp))
        Text(text, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

@Composable
private fun TopicRow(t: Topic) {
    Row(modifier = Modifier.padding(vertical = 11.dp)) {
        Text(t.timestampLabel, color = IdColor.Accent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.width(46.dp))
        Column {
            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(t.body, fontSize = 13.sp, color = IdColor.Neutral800, lineHeight = 19.sp)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

@Composable
private fun DecisionRow(d: Decision) {
    Row(modifier = Modifier.padding(vertical = 11.dp)) {
        Text(d.number, color = IdColor.Accent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(30.dp))
        Text(d.text, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

@Composable
private fun QuestionRow(q: Question) {
    Column(modifier = Modifier.padding(vertical = 11.dp)) {
        Row {
            Text("?", color = IdColor.Accent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(23.dp))
            Text(q.question, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp)
        }
        Row(modifier = Modifier.padding(start = 23.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(q.status.label, background = IdColor.Bg, contentColor = IdColor.Neutral700, bordered = true)
            Spacer(Modifier.width(8.dp))
            Text(q.note, color = IdColor.Neutral700, fontSize = 11.5.sp)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

@Composable
private fun ActionRow(a: ActionItem) {
    Column(modifier = Modifier.background(IdColor.Neutral100).padding(13.dp).fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(16.dp).background(IdColor.Bg))
            Spacer(Modifier.width(10.dp))
            Text(a.task, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
        }
        Row(modifier = Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(a.pic, IdColor.Neutral900, IdColor.Bg)
            Spacer(Modifier.width(8.dp))
            Text("Tenggat ${a.due}", color = IdColor.Neutral700, fontSize = 11.5.sp)
        }
    }
}

@Composable
internal fun PhotoCard(photo: Photo, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.33f)) {
            PhotoImage(photo.filePath, Modifier.fillMaxSize())
            photo.takenAtSec?.let {
                Text(
                    "Menit ${formatRecordTimer(it)}",
                    color = IdColor.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(IdColor.Neutral900.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        if (photo.caption.isNotBlank()) {
            Text(photo.caption, fontSize = 11.5.sp, lineHeight = 16.sp, color = IdColor.Neutral800, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TemplatePickerDialog(
    templates: List<NoteTemplate>,
    currentId: String,
    onDismiss: () -> Unit,
    onPick: (NoteTemplate) -> Unit,
) {
    var selectedId by remember { mutableStateOf(currentId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti template dokumen", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Dokumen disusun ulang dari transkrip yang sama dengan template yang dipilih.",
                    color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                templates.forEach { t ->
                    val selected = t.id == selectedId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(if (selected) IdColor.Neutral900 else IdColor.Neutral100)
                            .clickable { selectedId = t.id }
                            .padding(11.dp),
                    ) {
                        Text(t.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (selected) IdColor.Bg else IdColor.Text)
                        Text(
                            t.description, fontSize = 11.sp, lineHeight = 15.sp,
                            color = if (selected) IdColor.Bg.copy(alpha = 0.8f) else IdColor.Neutral700,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "SUSUN ULANG", color = IdColor.Accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { templates.firstOrNull { it.id == selectedId }?.let(onPick) }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

@Composable
internal fun PhotoViewerDialog(
    photo: Photo,
    onDismiss: () -> Unit,
    onSaveCaption: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var caption by remember(photo.id) { mutableStateOf(photo.caption) }
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.background(IdColor.Bg).padding(12.dp)) {
            PhotoImage(photo.filePath, Modifier.fillMaxWidth().heightIn(max = 380.dp), ContentScale.Fit)
            Spacer(Modifier.height(10.dp))
            photo.takenAtSec?.let {
                Text("Menit ${formatRecordTimer(it)}", color = IdColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
            }
            IdTextField(caption, { caption = it }, "Tambah keterangan foto…", singleLine = false, minLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HAPUS", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDelete).padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("TUTUP", color = IdColor.Neutral700, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable { onSaveCaption(caption) }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun NotulensiHistoryDialog(entry: NotulensiHistoryEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("${entry.templateName} · ${entry.density.label}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(formatRelativeDay(entry.generatedAt), color = IdColor.Neutral600, fontSize = 11.sp)
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                KickerLabel("Ringkasan")
                Spacer(Modifier.height(6.dp))
                Text(entry.notulensi.summary, fontSize = 13.5.sp, lineHeight = 20.sp)
                entry.notulensi.sections.forEach { SectionBlock(it, isFirst = false) }
            }
        },
        confirmButton = {
            Text("TUTUP", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

/** Photo documentation as a plain (non-lazy) block, for screens that already scroll as a whole. */
@Composable
internal fun PhotoDocumentation(
    photos: List<Photo>,
    onAddPhoto: (Uri, Boolean) -> Unit,
    onUpdateCaption: (Photo, String) -> Unit,
    onDeletePhoto: (Photo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photoPicker = rememberPhotoPicker(onAddPhoto)
    var viewing by remember { mutableStateOf<Photo?>(null) }
    Column(modifier = modifier.fillMaxWidth().background(IdColor.Neutral100).padding(14.dp)) {
        Text("FOTO DOKUMENTASI (${photos.size})", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))
        PhotoPickerButtons(photoPicker)
        Spacer(Modifier.height(10.dp))
        if (photos.isEmpty()) {
            Text(
                "Foto bisa ditambahkan kapan saja, sebelum atau sesudah transkrip. Foto ikut masuk ke ekspor Word.",
                color = IdColor.Neutral600, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        photos.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                pair.forEach { photo -> PhotoCard(photo, Modifier.weight(1f)) { viewing = photo } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    viewing?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewing = null },
            onSaveCaption = { onUpdateCaption(photo, it); viewing = null },
            onDelete = { onDeletePhoto(photo); viewing = null },
        )
    }
}
