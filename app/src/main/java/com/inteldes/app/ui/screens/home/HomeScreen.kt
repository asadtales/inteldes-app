package com.inteldes.app.ui.screens.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.components.RecordingDetailsDialog
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatDuration
import com.inteldes.app.ui.util.formatRelativeDay

private fun Modifier.hairline(color: Color) = this.border(BorderStroke(1.dp, color))

@Composable
fun HomeScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    engineShortLabel: String,
    onOpenRecording: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onUploadReady: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.onFilePicked(it) }
    }

    LaunchedEffect(Unit) {
        vm.onUploadProcessed.collect { id -> onUploadReady(id) }
    }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(IdColor.Bg)
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("INTELDES", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "DESA SUKAMAJU",
                        color = IdColor.Neutral600,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Pengaturan", tint = IdColor.Text)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Arsip rapat", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.allCount} rekaman" + if (state.workingCount > 0) " · ${state.workingCount} sedang diproses" else "",
                color = IdColor.Neutral700,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(13.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .hairline(IdColor.Divider)
                    .background(IdColor.Neutral100)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = IdColor.Neutral600, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    textStyle = androidx.compose.ui.text.TextStyle(color = IdColor.Text, fontSize = 13.sp),
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text("Cari judul, nama, atau isi transkrip", color = IdColor.Neutral600, fontSize = 13.sp)
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            vm.filters.forEach { f ->
                val on = state.filter == f
                Box(
                    modifier = Modifier
                        .clickable { vm.setFilter(f) }
                        .background(if (on) IdColor.Accent else Color.Transparent)
                        .let { if (!on) it.hairline(IdColor.Divider) else it }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    Text(
                        f.uppercase(),
                        color = if (on) IdColor.White else IdColor.Neutral800,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.visible, key = { it.id }) { r ->
                RecordingRow(r, onClick = { onOpenRecording(r.id) })
                androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        Column(modifier = Modifier.background(IdColor.Neutral100).padding(horizontal = 18.dp, vertical = 11.dp)) {
            androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Divider, modifier = Modifier.padding(bottom = 11.dp))
            if (state.uploadError != null) {
                Text(state.uploadError!!, color = IdColor.Accent, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1.6f)
                        .clickable(onClick = onRecord)
                        .background(IdColor.Accent)
                        .padding(vertical = 15.dp, horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = IdColor.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("REKAM RAPAT", color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(1.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .hairline(IdColor.Divider)
                        .clickable(enabled = !state.importing) { filePicker.launch(arrayOf("audio/*")) }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, tint = IdColor.Text, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (state.importing) "MEMBACA…" else "UNGGAH",
                        color = IdColor.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(engineShortLabel, color = IdColor.Neutral600, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
        }
    }

    state.pendingUpload?.let {
        RecordingDetailsDialog(
            dialogTitle = "Beri nama rekaman ini",
            onDismiss = { vm.cancelPendingUpload() },
            onConfirm = { title, kind -> vm.confirmUpload(title, kind) },
        )
    }
}

@Composable
private fun RecordingRow(r: Recording, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(
                r.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(formatDuration(r.durationSec), color = IdColor.Neutral600, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            r.summary ?: r.errorMessage ?: "Belum ditranskrip.",
            color = IdColor.Neutral700,
            fontSize = 12.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (r.status) {
                RecordingStatus.DONE -> Pill("Notulensi siap", IdColor.Neutral900, IdColor.Bg)
                RecordingStatus.WORKING -> Pill("Memproses", IdColor.Accent, IdColor.White)
                RecordingStatus.RAW -> Pill(if (r.errorMessage != null) "Gagal" else "Belum diproses", IdColor.Bg, IdColor.Neutral700, bordered = true)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "${formatRelativeDay(r.createdAt)} · ${r.speakerCount} pembicara",
                color = IdColor.Neutral600,
                fontSize = 11.sp,
            )
        }
    }
}
