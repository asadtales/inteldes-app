package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.LocationInfo
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.repository.TemplateChoices
import com.inteldes.app.ui.theme.IdColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Title + category + note template prompt shared by the record flow and the file-upload flow.
 *
 * @param engine/onEngineChange Non-null only for the file-upload flow, which — unlike
 * the record screen — has no earlier step where the user already picked Cloud vs
 * On-device. Without this, uploads silently used whatever engine happened to be the
 * app default, with no way to tell before it failed downstream (e.g. no API key set
 * for that engine).
 */
@Composable
fun RecordingDetailsDialog(
    dialogTitle: String = "Beri nama rapat ini",
    categories: List<String>,
    choices: TemplateChoices,
    onAddCategory: (String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (title: String, kind: String, template: NoteTemplate, location: LocationInfo?) -> Unit,
    initialLocation: LocationInfo? = null,
    initialTitle: String = "",
    summary: String? = null,
    engine: WhisperEngine? = null,
    onEngineChange: (WhisperEngine) -> Unit = {},
) {
    var title by remember { mutableStateOf(initialTitle) }
    // Null means "no explicit pick yet" — falls back to the first category live, without
    // clobbering a real selection whenever the category list changes (e.g. after adding one).
    var pickedKind by remember { mutableStateOf<String?>(null) }
    val kind = pickedKind?.takeIf { it in categories } ?: categories.firstOrNull() ?: "Lainnya"
    var pickingCategory by remember { mutableStateOf(false) }
    var templateId by remember { mutableStateOf(choices.defaultId) }
    val template = choices.templates.firstOrNull { it.id == templateId } ?: choices.default
    // The GPS fix keeps its coordinates; the name is free text the user can overwrite.
    var locationName by remember { mutableStateOf(initialLocation?.name.orEmpty()) }
    var fix by remember { mutableStateOf(initialLocation) }
    // The record screen detects the location in the background — it may land after this opens.
    LaunchedEffect(initialLocation) {
        if (initialLocation != null && locationName.isBlank()) {
            locationName = initialLocation.name
            fix = initialLocation
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                summary?.let {
                    Text(it, color = IdColor.Neutral700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                }
                IdTextField(value = title, onValueChange = { title = it }, placeholder = "Contoh: Musyawarah Desa RKP 2027")
                Spacer(Modifier.height(12.dp))
                CategoryField("KATEGORI", kind, onClick = { pickingCategory = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                KickerLabel("Lokasi")
                Spacer(Modifier.height(6.dp))
                LocationField(
                    name = locationName,
                    onNameChange = { locationName = it },
                    onDetected = { locationName = it.name; fix = it },
                )
                if (engine != null) {
                    Spacer(Modifier.height(14.dp))
                    Text("MESIN TRANSKRIP", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            WhisperEngine.ON_DEVICE to "On-device",
                            WhisperEngine.CLOUD to "Awan",
                        ).forEach { (value, label) ->
                            val selected = engine == value
                            Text(
                                label,
                                color = if (selected) IdColor.White else IdColor.Neutral800,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(if (selected) IdColor.Accent else IdColor.Neutral200)
                                    .clickable { onEngineChange(value) }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
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
                    val finalTitle = title.ifBlank { "Rapat ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date())}" }
                    onConfirm(finalTitle, kind, template, locationName.trim().takeIf { it.isNotEmpty() }?.let { LocationInfo(it, fix?.latitude, fix?.longitude) })
                }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )

    if (pickingCategory) {
        CategoryPickerDialog(
            categories = categories,
            selected = kind,
            onSelect = { pickedKind = it; pickingCategory = false },
            onAdd = onAddCategory,
            onDelete = onDeleteCategory,
            onDismiss = { pickingCategory = false },
        )
    }
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
