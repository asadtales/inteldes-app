package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.ui.theme.IdColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Title + category prompt shared by the record flow and the file-upload flow. */
@Composable
fun RecordingDetailsDialog(
    dialogTitle: String = "Beri nama rapat ini",
    categories: List<String>,
    onAddCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    // Null means "no explicit pick yet" — falls back to the first category live, without
    // clobbering a real selection whenever the category list changes (e.g. after adding one).
    var pickedKind by remember { mutableStateOf<String?>(null) }
    val kind = pickedKind ?: categories.firstOrNull() ?: "Lainnya"
    var addingNew by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = IdColor.Text),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(10.dp)) {
                            if (title.isEmpty()) Text("Contoh: Musyawarah Desa RKP 2027", color = IdColor.Neutral600, fontSize = 14.sp)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { c ->
                        val selected = kind == c
                        Text(
                            c,
                            color = if (selected) IdColor.White else IdColor.Neutral800,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(if (selected) IdColor.Accent else IdColor.Neutral200)
                                .clickable { pickedKind = c }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                    item {
                        Text(
                            "+ BARU",
                            color = IdColor.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .wrapContentWidth()
                                .background(IdColor.Neutral100)
                                .clickable { addingNew = !addingNew }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                if (addingNew) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        BasicTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it },
                            textStyle = TextStyle(fontSize = 13.sp, color = IdColor.Text),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(9.dp)) {
                                    if (newCategory.isEmpty()) Text("Nama kategori baru", color = IdColor.Neutral600, fontSize = 13.sp)
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "TAMBAH",
                            color = IdColor.Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                val trimmed = newCategory.trim()
                                if (trimmed.isNotBlank()) {
                                    onAddCategory(trimmed)
                                    pickedKind = trimmed
                                    newCategory = ""
                                    addingNew = false
                                }
                            }.padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "PROSES",
                color = IdColor.Accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val finalTitle = title.ifBlank { "Rapat ${SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date())}" }
                    onConfirm(finalTitle, kind)
                }.padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}
