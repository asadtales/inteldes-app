package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.repository.FALLBACK_CATEGORY
import com.inteldes.app.ui.theme.IdColor

/** Compact "KATEGORI  Musdes ▾" button that opens [CategoryPickerDialog] — replaces a long scrolling chip row. */
@Composable
fun CategoryField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(IdColor.Neutral100)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(label, color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.padding(horizontal = 5.dp))
        Text(value, color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.padding(horizontal = 3.dp))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = IdColor.Neutral700, modifier = Modifier.size(18.dp))
    }
}

/**
 * Searchable category list: type to filter, tap to choose, "+ Tambah" when the typed name is new, and
 * × to delete a category (its recordings move to [FALLBACK_CATEGORY]). [allLabel] adds a leading
 * "show everything" entry for the Home filter.
 */
@Composable
fun CategoryPickerDialog(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    allLabel: String? = null,
) {
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val trimmed = query.trim()
    val shown = categories.filter { trimmed.isEmpty() || it.contains(trimmed, ignoreCase = true) }
    val canAdd = trimmed.isNotEmpty() && categories.none { it.equals(trimmed, ignoreCase = true) } && trimmed != allLabel

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kategori rapat", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                IdTextField(value = query, onValueChange = { query = it }, placeholder = "Cari atau tambah kategori")
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (allLabel != null && trimmed.isEmpty()) {
                        item { CategoryRow(allLabel, selected == allLabel, onClick = { onSelect(allLabel) }, onDelete = null) }
                    }
                    items(shown) { c ->
                        CategoryRow(
                            c, selected == c,
                            onClick = { onSelect(c) },
                            onDelete = if (c == FALLBACK_CATEGORY) null else ({ pendingDelete = c }),
                        )
                    }
                    if (canAdd) {
                        item {
                            Text(
                                "+ Tambah “$trimmed”",
                                color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(trimmed); onSelect(trimmed) }
                                    .padding(vertical = 13.dp),
                            )
                        }
                    }
                    if (shown.isEmpty() && !canAdd) {
                        item { Text("Tidak ada kategori.", color = IdColor.Neutral600, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            Text("TUTUP", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )

    pendingDelete?.let { label ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus kategori “$label”?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Rekaman dengan kategori ini tidak dihapus — dipindahkan ke “$FALLBACK_CATEGORY”.",
                    color = IdColor.Neutral700, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Text(
                    "HAPUS", color = IdColor.Accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        onDelete(label)
                        pendingDelete = null
                    }.padding(10.dp),
                )
            },
            dismissButton = {
                Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { pendingDelete = null }.padding(10.dp))
            },
        )
    }
}

@Composable
private fun CategoryRow(label: String, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().background(if (selected) IdColor.Neutral100 else androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (selected) IdColor.Accent else IdColor.Text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 13.dp),
        )
        if (onDelete != null) {
            Icon(
                Icons.Filled.Close, contentDescription = "Hapus kategori $label", tint = IdColor.Neutral600,
                modifier = Modifier.clickable(onClick = onDelete).padding(10.dp).size(18.dp),
            )
        }
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}
