package com.inteldes.app.ui.screens.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.audio.RecorderState
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.components.MicPickerDialog
import com.inteldes.app.ui.components.PhotoImage
import com.inteldes.app.ui.components.PhotoPickerButtons
import com.inteldes.app.ui.components.RecordingDetailsDialog
import com.inteldes.app.ui.components.rememberPhotoPicker
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatRecordTimer

@Composable
fun RecordScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onCancel: () -> Unit,
    onRecordingReady: (String, Boolean) -> Unit,
) {
    val vm: RecordViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) vm.startRecording() else onCancel()
    }

    LaunchedEffect(Unit) {
        if (hasPermission) {
            vm.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        vm.onRecordingReady.collect { ready -> onRecordingReady(ready.recordingId, ready.autoProcessed) }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        // Reads live state off vm directly (not the local `state` delegate) — this effect
        // only runs once, so a lambda closing over `state` would freeze at whatever the
        // setting was when the screen first opened instead of tracking later toggles.
        VolumeMarkBridge.onVolumeKey = { if (vm.uiState.value.markWithVolumeButton) vm.addMark() }
        onDispose { VolumeMarkBridge.onVolumeKey = null }
    }

    val photoPicker = rememberPhotoPicker { uri, temporary -> vm.addPhoto(uri, temporary) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var showMicPicker by remember { mutableStateOf(false) }
    var pendingMicKey by remember { mutableStateOf<String?>(null) }
    // Talking to a Bluetooth earbud needs the "nearby devices" permission on Android 12+.
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingMicKey?.let { vm.selectMic(it) }
        pendingMicKey = null
    }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Neutral900)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Text(
                "Batal",
                color = IdColor.Neutral400,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(10.dp).clickable {
                    vm.discard()
                    onCancel()
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.engine == WhisperEngine.CLOUD) "● AWAN · AUDIO TERENKRIPSI" else "● ON-DEVICE · TANPA UPLOAD",
                color = IdColor.Accent.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                if (state.recorderState == RecorderState.PAUSED) "DIJEDA" else "SEDANG MEREKAM",
                color = IdColor.Accent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                formatRecordTimer(state.elapsedSec),
                color = IdColor.Bg,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 62.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Neutral700)
        }

        Waveform(state.amplitudeHistory, animated = state.recorderState == RecorderState.RECORDING, modifier = Modifier.padding(20.dp).fillMaxWidth().height(54.dp))

        // Large and on its own, away from Jeda/Selesai — it used to sit squeezed between
        // them at the bottom, an easy target to hit by mistake right when someone actually
        // meant to pause or finish. Also reachable without opening the app at all, via the
        // recording notification's own "TANDAI" action (see RecordingService).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .background(IdColor.Accent)
                .clickable(enabled = state.recorderState != RecorderState.IDLE) { vm.addMark() }
                .padding(vertical = 16.dp),
        ) {
            Icon(Icons.Filled.Bookmark, contentDescription = null, tint = IdColor.White, modifier = Modifier.width(18.dp).height(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (state.marks.isEmpty()) "TANDAI POIN PENTING" else "TANDAI (${state.marks.size})",
                color = IdColor.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
            )
        }

        // Which microphone is capturing. Shows what Android is really using, and warns when the chosen one isn't connected.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp)
                .fillMaxWidth()
                .background(IdColor.Neutral900)
                .clickable { showMicPicker = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SUMBER MIKROFON", color = IdColor.Neutral500, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    micSummary(state),
                    color = if (state.selectedMicMissing) IdColor.Accent else IdColor.Neutral100,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
            }
            Text("UBAH ›", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("MESIN TRANSKRIP", color = IdColor.Neutral500, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(9.dp))
            Row {
                EngineOption(
                    label = "On-device", note = "Tanpa internet, satu pembicara", selected = state.engine == WhisperEngine.ON_DEVICE,
                    enabled = true, modifier = Modifier.weight(1f),
                    onClick = { vm.setEngine(WhisperEngine.ON_DEVICE) },
                )
                Spacer(Modifier.width(1.dp))
                EngineOption(
                    label = "Awan", note = "Lebih akurat, ada label pembicara", selected = state.engine == WhisperEngine.CLOUD,
                    enabled = true, modifier = Modifier.weight(1f),
                    onClick = { vm.setEngine(WhisperEngine.CLOUD) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (state.marks.isNotEmpty()) {
                Text("PENANDA", color = IdColor.Neutral500, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    state.marks.forEach { sec ->
                        Text(
                            formatRecordTimer(sec),
                            color = IdColor.Neutral300,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            modifier = Modifier.background(IdColor.Neutral900).padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                "FOTO DOKUMENTASI (${state.photos.size})",
                color = IdColor.Neutral500, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(9.dp))
            PhotoPickerButtons(photoPicker, onDark = true)
            Spacer(Modifier.height(10.dp))
            if (state.photos.isEmpty()) {
                Text(
                    "Foto yang ditambahkan saat rapat akan masuk ke bagian Dokumentasi di notulensi, lengkap dengan menit pengambilannya.",
                    color = IdColor.Neutral400,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.photos.forEach { photo -> PendingPhotoThumb(photo, onRemove = { vm.removePhoto(photo) }) }
                }
            }
            state.photoError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = IdColor.Accent, fontSize = 11.5.sp)
            }
        }

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                if (state.recorderState == RecorderState.PAUSED) "Lanjutkan" else "Jeda",
                color = IdColor.Bg,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.6.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(IdColor.Neutral900)
                    .clickable { vm.togglePause() }
                    .padding(18.dp),
            )
            Spacer(Modifier.width(1.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier
                    .weight(1.5f)
                    .background(IdColor.Accent)
                    .clickable { showSaveDialog = true }
                    .padding(18.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = IdColor.White, modifier = Modifier.width(14.dp).height(14.dp))
                Spacer(Modifier.width(9.dp))
                Text(if (state.autoProcess) "SELESAI & PROSES" else "SELESAI", color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
        }
    }

    if (showMicPicker) {
        MicPickerDialog(
            options = state.mics,
            selectedKey = state.selectedMicKey,
            activeName = state.activeMic,
            onSelect = { key ->
                showMicPicker = false
                val isBluetooth = state.mics.firstOrNull { it.key == key }?.kind == com.inteldes.app.data.audio.MicKind.BLUETOOTH
                val needsPermission = isBluetooth && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    pendingMicKey = key
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    vm.selectMic(key)
                }
            },
            onDismiss = { showMicPicker = false },
        )
    }

    if (showSaveDialog) {
        RecordingDetailsDialog(
            categories = categories,
            choices = state.templateChoices,
            initialLocation = state.detectedLocation,
            onAddCategory = vm::addCategory,
            onDeleteCategory = vm::deleteCategory,
            onDismiss = { showSaveDialog = false },
            onConfirm = { title, kind, template, location ->
                showSaveDialog = false
                vm.stopAndProcess(title, kind, template, location)
            },
        )
    }
}

@Composable
private fun EngineOption(label: String, note: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(if (selected) IdColor.Accent else IdColor.Neutral900)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (selected) IdColor.White else if (enabled) IdColor.Neutral300 else IdColor.Neutral600,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            note,
            color = if (selected) IdColor.White.copy(alpha = 0.9f) else IdColor.Neutral500,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun Waveform(history: List<Int>, animated: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawWaveform(history, animated, this)
    }
}

private fun drawWaveform(history: List<Int>, animated: Boolean, scope: DrawScope) {
    if (history.isEmpty()) return
    val barCount = history.size
    val gap = with(scope) { 2.dp.toPx() }
    val barWidth = (scope.size.width - gap * (barCount - 1)) / barCount
    val maxAmp = 32767f
    history.forEachIndexed { i, amp ->
        val ratio = (amp / maxAmp).coerceIn(0.04f, 1f)
        val barHeight = scope.size.height * ratio
        val x = i * (barWidth + gap)
        val color = if (animated) IdColor.Accent.copy(alpha = 0.35f + (i.toFloat() / barCount) * 0.5f) else IdColor.Neutral400.copy(alpha = 0.8f)
        scope.drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(x, (scope.size.height - barHeight) / 2f),
            size = Size(barWidth, barHeight),
        )
    }
}

@Composable
private fun PendingPhotoThumb(photo: PendingPhoto, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(76.dp)) {
        PhotoImage(photo.file.absolutePath, Modifier.fillMaxSize())
        Text(
            formatRecordTimer(photo.offsetSec),
            color = IdColor.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).background(IdColor.Neutral900.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = "Hapus foto",
            tint = IdColor.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(IdColor.Neutral900.copy(alpha = 0.75f))
                .clickable(onClick = onRemove)
                .padding(3.dp)
                .size(14.dp),
        )
    }
}

/** One line for the mic row: what is capturing now, or what will be used / why the pick isn't active. */
private fun micSummary(state: RecordUiState): String {
    val chosen = state.mics.firstOrNull { it.key == state.selectedMicKey }
    return when {
        state.selectedMicMissing -> "Mic pilihan tidak tersambung — memakai otomatis"
        state.recorderState != RecorderState.IDLE && state.activeMic != null -> state.activeMic
        chosen != null -> chosen.label
        else -> "Otomatis" + (state.mics.firstOrNull { it.kind == com.inteldes.app.data.audio.MicKind.BLUETOOTH }?.let { " (${it.label} tersambung)" } ?: "")
    }
}
