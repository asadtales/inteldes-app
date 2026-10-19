package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.repository.TemplateChoices
import com.inteldes.app.ui.theme.IdColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Title, kind and note template prompt shared by the record flow and the file-upload flow. */
@Composable
fun RecordingDetailsDialog(
    dialogTitle: String = "Beri nama rapat ini",
    choices: TemplateChoices,
    onDismiss: () -> Unit,
    onConfirm: (title: String, kind: RecordingKind, template: NoteTemplate) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RecordingKind.MUSDES) }
    var templateId by remember { mutableStateOf(choices.defaultId) }
    val template = choices.templates.firstOrNull { it.id == templateId } ?: choices.default

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                IdTextField(value = title, onValueChange = { title = it }, placeholder = "Contoh: Musyawarah Desa RKP 2027")
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RecordingKind.entries.filter { it != RecordingKind.LAINNYA }.forEach { k ->
                        val selected = kind == k
                        Text(
                            k.label,
                            color = if (selected) IdColor.White else IdColor.Neutral800,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(if (selected) IdColor.Accent else IdColor.Neutral200)
                                .clickable { kind = k }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                KickerLabel("Template dokumen")
                Spacer(Modifier.height(7.dp))
                TemplateChips(choices.templates, templateId) { templateId = it.id }
                Spacer(Modifier.height(6.dp))
                Text(template.description, color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = {
            Text(
                "PROSES",
                color = IdColor.Accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val finalTitle = title.ifBlank { "Rapat ${SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date())}" }
                    onConfirm(finalTitle, kind, template)
                }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

/** Horizontally scrolling single-choice chips for picking a note template. */
@Composable
fun TemplateChips(templates: List<NoteTemplate>, selectedId: String, onSelect: (NoteTemplate) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        templates.forEach { t ->
            val selected = t.id == selectedId
            Text(
                t.name,
                color = if (selected) IdColor.White else IdColor.Neutral800,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(if (selected) IdColor.Accent else IdColor.Neutral200)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}
