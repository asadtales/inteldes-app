package com.inteldes.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.export.NotulensiExporter
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.Topic
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.components.TranscribeOptionsDialog
import com.inteldes.app.ui.components.TrimAudioDialog
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatDuration
import com.inteldes.app.ui.util.formatRecordTimer
import com.inteldes.app.ui.util.formatRelativeDay

@Composable
fun DetailScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    initialTab: String,
    onBack: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenProcessing: (String, String, Int?, Int?) -> Unit,
) {
    val vm: DetailViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val playback by vm.playbackState.collectAsStateWithLifecycle()
    val onSeek: (Int) -> Unit = vm::seekPlayback

    androidx.compose.runtime.LaunchedEffect(initialTab) {
        vm.setTab(if (initialTab == "script") DetailTab.SCRIPT else if (initialTab == "ask") DetailTab.ASK else DetailTab.NOTES)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Column(modifier = Modifier.background(IdColor.Bg)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.recording?.title ?: "",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.recording?.let { "${formatDuration(it.durationSec)} · ${it.speakerCount} pembicara" } ?: "",
                        color = IdColor.Neutral600,
                        fontSize = 11.sp,
                    )
                }
                if (state.recording != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Hapus rekaman", tint = IdColor.Neutral700)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TabButton("Notulensi", state.tab == DetailTab.NOTES, Modifier.weight(1f)) { vm.setTab(DetailTab.NOTES) }
                TabButton("Transkrip", state.tab == DetailTab.SCRIPT, Modifier.weight(1f)) { vm.setTab(DetailTab.SCRIPT) }
                TabButton("Tanya AI", state.tab == DetailTab.ASK, Modifier.weight(1f)) { vm.setTab(DetailTab.ASK) }
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
        }

        val recording = state.recording
        val context = LocalContext.current
        val onShareAudio: () -> Unit = onShareAudio@{
            val rec = recording ?: return@onShareAudio
            runCatching {
                val uri = NotulensiExporter.exportAudio(context, rec)
                context.startActivity(NotulensiExporter.shareIntent(uri, rec.audioMimeType, "Bagikan rekaman audio"))
            }
        }
        val actions = PlayerActions(
            marks = state.marks,
            onShare = onShareAudio,
            onTrim = { showTrim = true },
            onAddMark = vm::addMarkAtPlayback,
            onDeleteMark = vm::deleteMark,
        )
        when {
            recording == null -> Unit
            // Transkripsi berhasil (whisper.cpp on-device atau awan sudah menghasilkan
            // segmen) tapi tahap notulensi gagal terpisah (mis. API key AI belum
            // diatur) — jangan sembunyikan transkrip yang sudah ada di balik status
            // "gagal" itu; hanya tab Notulensi yang perlu menunggu proses ulang.
            state.tab == DetailTab.SCRIPT && state.transcript.isNotEmpty() -> ScriptTab(
                segments = state.transcript,
                playback = playback,
                roster = state.speakerRoster,
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onRenameSpeaker = vm::renameSpeaker,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
            recording.status != RecordingStatus.DONE -> NotDoneState(
                statusIsWorking = recording.status == RecordingStatus.WORKING,
                errorMessage = recording.errorMessage,
                currentEngine = recording.engine,
                durationSec = recording.durationSec,
                marks = state.marks,
                playback = playback,
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onOpenProcessing = { onOpenProcessing(recording.id, state.tab.routeName, null, null) },
                onStartProcessing = { engine, start, end ->
                    vm.startProcessing(engine, start, end) { id, tab, s, e -> onOpenProcessing(id, tab, s, e) }
                },
                actions = actions,
            )
            state.tab == DetailTab.NOTES -> NotesTab(
                recording = recording,
                notulensi = state.notulensi,
                history = state.notulensiHistory,
                regenerating = state.regenerating,
                error = state.error,
                onRegenerate = vm::regenerateNotulensi,
                onCancelRegenerate = vm::cancelRegenerate,
                onChangeAgent = onGoSettings,
                modifier = Modifier.weight(1f),
            )
            state.tab == DetailTab.SCRIPT -> ScriptTab(
                segments = state.transcript,
                playback = playback,
                roster = state.speakerRoster,
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onRenameSpeaker = vm::renameSpeaker,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
            state.tab == DetailTab.ASK -> AskTab(
                chat = state.chat,
                thinking = state.thinking,
                providerLabel = providerBadge(state.notulensi?.provider),
                onSend = vm::ask,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showTrim) {
        val rec = state.recording
        val localContext = LocalContext.current
        if (rec != null) {
            TrimAudioDialog(
                durationSec = if (playback.ready) playback.durationSec else rec.durationSec,
                marks = state.marks,
                currentPositionSec = playback.positionSec,
                onDismiss = { showTrim = false },
                onSave = { start, end, onResult -> vm.trimToNewRecording(start, end, onResult) },
                onSaved = {
                    showTrim = false
                    android.widget.Toast.makeText(localContext, "Disimpan sebagai rekaman baru. Rekaman asli tidak berubah.", android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus rekaman ini?", fontWeight = FontWeight.Bold) },
            text = { Text("Audio, transkrip, dan notulensinya akan terhapus permanen dan tidak bisa dikembalikan.") },
            confirmButton = {
                Text(
                    "HAPUS",
                    color = IdColor.Accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        showDeleteConfirm = false
                        vm.delete(onBack)
                    }.padding(10.dp),
                )
            },
            dismissButton = {
                Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { showDeleteConfirm = false }.padding(10.dp))
            },
        )
    }
}

private fun providerBadge(provider: AiProvider?): String = when (provider) {
    AiProvider.GEMINI -> "Gemini 2.5 Pro"
    AiProvider.CLAUDE -> "Claude Sonnet"
    else -> "Agent AI"
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(if (selected) IdColor.Neutral900 else IdColor.Bg)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = if (selected) IdColor.Bg else IdColor.Neutral700,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 11.5.sp,
            letterSpacing = 0.9.sp,
        )
    }
}

/** Everything the audio player card can do beyond play/seek, bundled so it threads through as one value. */
private class PlayerActions(
    val marks: List<com.inteldes.app.data.model.RecordingMark>,
    val onShare: () -> Unit,
    val onTrim: () -> Unit,
    val onAddMark: () -> Unit,
    val onDeleteMark: (com.inteldes.app.data.model.RecordingMark) -> Unit,
)

/**
 * Play/pause + a draggable seek slider, with the recording's bookmarks drawn as ticks on
 * the track and as tappable chips below it (tap = jump the player there; long-press =
 * delete). Shared by the not-done screen and the Transkrip tab.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AudioPlayerBar(
    playback: com.inteldes.app.data.audio.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    actions: PlayerActions? = null,
) {
    // While the user is dragging, show the drag position instead of the player's own
    // (still-ticking) position — otherwise the thumb fights the live playback position.
    var dragPositionSec by remember { mutableStateOf<Int?>(null) }
    val shownPositionSec = dragPositionSec ?: playback.positionSec
    var markToDelete by remember { mutableStateOf<com.inteldes.app.data.model.RecordingMark?>(null) }
    val marks = actions?.marks.orEmpty()
    val duration = playback.durationSec.coerceAtLeast(1)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).background(IdColor.Accent).clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.playing) "Jeda" else "Putar",
                    tint = IdColor.White,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatRecordTimer(shownPositionSec)} / ${formatRecordTimer(playback.durationSec)}",
                color = IdColor.Neutral700,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (actions != null) {
                IconButton(onClick = actions.onTrim, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ContentCut, contentDescription = "Potong audio", tint = IdColor.Neutral700, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = actions.onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = "Bagikan audio", tint = IdColor.Neutral700, modifier = Modifier.size(20.dp))
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = shownPositionSec.toFloat().coerceAtMost(duration.toFloat()),
                onValueChange = { dragPositionSec = it.toInt() },
                onValueChangeFinished = {
                    dragPositionSec?.let(onSeek)
                    dragPositionSec = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = IdColor.Accent, activeTrackColor = IdColor.Accent, inactiveTrackColor = IdColor.Neutral300),
                modifier = Modifier.fillMaxWidth(),
            )
            if (marks.isNotEmpty()) {
                // Drawn over the track (Canvas takes no touch, so dragging still hits the slider).
                // Inset by roughly the slider thumb radius so ticks line up with the thumb.
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    val inset = 10.dp.toPx()
                    val trackWidth = size.width - inset * 2
                    marks.forEach { m ->
                        val x = inset + trackWidth * (m.timestampSec.toFloat() / duration).coerceIn(0f, 1f)
                        drawLine(
                            color = IdColor.Neutral900,
                            start = androidx.compose.ui.geometry.Offset(x, size.height * 0.15f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height * 0.85f),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                    }
                }
            }
        }

        if (actions != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PENANDA${if (marks.isNotEmpty()) " (${marks.size})" else ""}", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "+ TANDAI POSISI INI",
                    color = IdColor.Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    modifier = Modifier.clickable(onClick = actions.onAddMark).padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (marks.isEmpty()) {
                Text(
                    "Belum ada penanda. Tandai saat merekam (tombol di layar rekam atau notifikasi), atau di sini sambil mendengarkan.",
                    color = IdColor.Neutral600,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            } else {
                // The chip for the latest mark at/before the playhead is highlighted, so the
                // list reads as "where you are" against the bookmarks.
                val activeSec = marks.map { it.timestampSec }.lastOrNull { it <= shownPositionSec }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    marks.forEach { m ->
                        val active = m.timestampSec == activeSec
                        Text(
                            com.inteldes.app.ui.util.formatMmSs(m.timestampSec),
                            color = if (active) IdColor.White else IdColor.Neutral800,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .background(if (active) IdColor.Accent else IdColor.Neutral200)
                                .combinedClickable(
                                    onClick = { onSeek(m.timestampSec) },
                                    onLongClick = { markToDelete = m },
                                )
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }

    markToDelete?.let { mark ->
        AlertDialog(
            onDismissRequest = { markToDelete = null },
            title = { Text("Hapus penanda ${com.inteldes.app.ui.util.formatMmSs(mark.timestampSec)}?", fontWeight = FontWeight.Bold) },
            text = { Text("Hanya penandanya yang dihapus, audio tidak berubah.") },
            confirmButton = {
                Text(
                    "HAPUS",
                    color = IdColor.Accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        actions?.onDeleteMark?.invoke(mark)
                        markToDelete = null
                    }.padding(10.dp),
                )
            },
            dismissButton = {
                Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { markToDelete = null }.padding(10.dp))
            },
        )
    }
}

@Composable
private fun NotDoneState(
    statusIsWorking: Boolean,
    errorMessage: String?,
    currentEngine: WhisperEngine,
    durationSec: Int,
    marks: List<com.inteldes.app.data.model.RecordingMark>,
    playback: com.inteldes.app.data.audio.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onOpenProcessing: () -> Unit,
    onStartProcessing: (WhisperEngine, Int?, Int?) -> Unit,
    actions: PlayerActions,
) {
    var showEnginePicker by remember { mutableStateOf(false) }
    val failed = errorMessage != null

    Column(modifier = Modifier.fillMaxSize()) {
        // Status + player scroll together; the action stays pinned at the bottom instead of
        // floating mid-screen above a big empty area.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(16.dp)) {
                Text(
                    "STATUS",
                    color = if (failed) IdColor.Accent else IdColor.Neutral600,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        failed -> "Pemrosesan gagal"
                        statusIsWorking -> "Sedang diproses"
                        else -> "Belum diproses"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    errorMessage ?: "Transkrip dan notulensi akan muncul di sini setelah selesai. Tandai poin penting atau potong audio dulu kalau perlu.",
                    color = IdColor.Neutral700,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }
            if (playback.ready) {
                Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(14.dp)) {
                    Text("REKAMAN ASLI", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(10.dp))
                    AudioPlayerBar(playback, onTogglePlay, onSeek, modifier = Modifier.fillMaxWidth(), actions = actions)
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IdColor.Accent)
                    // Starting or retrying transcription spends real tokens/quota on the cloud
                    // engine — worth an explicit choice every time rather than silently reusing
                    // whatever engine happened to be set before. Only skipped when genuinely just
                    // reopening a run that's already in progress (nothing new gets started then).
                    .clickable(onClick = if (!statusIsWorking) { { showEnginePicker = true } } else onOpenProcessing)
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    when {
                        statusIsWorking -> "LIHAT PROGRES"
                        failed -> "COBA LAGI"
                        else -> "MULAI TRANSKRIP"
                    },
                    color = IdColor.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }

    if (showEnginePicker) {
        TranscribeOptionsDialog(
            currentEngine = currentEngine,
            durationSec = durationSec,
            marks = marks,
            onDismiss = { showEnginePicker = false },
            onConfirm = { engine, start, end ->
                showEnginePicker = false
                onStartProcessing(engine, start, end)
            },
        )
    }
}

// ───────────────────────── Notulensi tab (1A — continuous document) ─────────────────────────

@Composable
private fun NotesTab(
    recording: Recording,
    notulensi: com.inteldes.app.data.model.Notulensi?,
    history: List<com.inteldes.app.data.model.NotulensiHistoryEntry>,
    regenerating: Boolean,
    error: String?,
    onRegenerate: () -> Unit,
    onCancelRegenerate: () -> Unit,
    onChangeAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var viewingHistory by remember { mutableStateOf<com.inteldes.app.data.model.NotulensiHistoryEntry?>(null) }
    if (notulensi == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Notulensi belum tersedia.", color = IdColor.Neutral700, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(modifier = modifier.padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(providerBadge(notulensi.provider), IdColor.Accent, IdColor.White)
                Spacer(Modifier.width(8.dp))
                Text("disusun oleh agent AI", color = IdColor.Neutral600, fontSize = 11.sp)
            }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("VERSI SEBELUMNYA", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    history.forEach { entry ->
                        Text(
                            "${entry.density.label} · ${formatRelativeDay(entry.generatedAt)}",
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
            }
            Spacer(Modifier.height(16.dp))
            KickerLabel("Ringkasan")
            Spacer(Modifier.height(8.dp))
            Text(notulensi.summary, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
            Spacer(Modifier.height(2.dp))
        }
        item {
            KickerLabel("Poin bahasan", modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        }
        items(notulensi.topics) { t -> TopicRow(t) }
        item {
            KickerLabel("Keputusan", modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        }
        items(notulensi.decisions) { d -> DecisionRow(d) }
        item {
            KickerLabel("Poin pertanyaan", modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        }
        items(notulensi.questions) { q -> QuestionRow(q) }
        item {
            KickerLabel("Action item", modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
        }
        items(notulensi.actions) { a -> ActionRow(a) }
        item {
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = IdColor.Accent, fontSize = 12.sp)
            }
            Row(modifier = Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (regenerating) {
                    Text(
                        "MENYUSUN…",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = IdColor.Neutral600,
                        modifier = Modifier.weight(1f).background(IdColor.Bg).padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "BATALKAN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = IdColor.White,
                        modifier = Modifier.weight(1f).background(IdColor.Accent).clickable(onClick = onCancelRegenerate).padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                Text(
                    "SUSUN ULANG",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = IdColor.Text,
                    modifier = Modifier
                        .weight(1f)
                        .background(IdColor.Bg)
                        .clickable(onClick = onRegenerate)
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "GANTI AGENT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = IdColor.Bg,
                    modifier = Modifier
                        .weight(1f)
                        .background(IdColor.Neutral900)
                        .clickable(onClick = onChangeAgent)
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                }
            }
            Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "EKSPOR .TXT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = IdColor.Text,
                    modifier = Modifier
                        .weight(1f)
                        .background(IdColor.Neutral100)
                        .clickable {
                            val uri = NotulensiExporter.exportTxt(context, recording, notulensi)
                            context.startActivity(NotulensiExporter.shareIntent(uri, "text/plain"))
                        }
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "EKSPOR WORD",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = IdColor.Text,
                    modifier = Modifier
                        .weight(1f)
                        .background(IdColor.Neutral100)
                        .clickable {
                            val uri = NotulensiExporter.exportDocx(context, recording, notulensi)
                            context.startActivity(
                                NotulensiExporter.shareIntent(
                                    uri,
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                ),
                            )
                        }
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    viewingHistory?.let { entry ->
        NotulensiHistoryDialog(entry = entry, onDismiss = { viewingHistory = null })
    }
}

@Composable
private fun NotulensiHistoryDialog(entry: com.inteldes.app.data.model.NotulensiHistoryEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Versi ${entry.density.label}", fontWeight = FontWeight.Bold)
                Text(formatRelativeDay(entry.generatedAt), color = IdColor.Neutral600, fontSize = 11.sp)
            }
        },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                item {
                    KickerLabel("Ringkasan")
                    Spacer(Modifier.height(6.dp))
                    Text(entry.notulensi.summary, fontSize = 13.5.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(14.dp))
                    KickerLabel("Poin bahasan")
                }
                items(entry.notulensi.topics) { t -> TopicRow(t) }
                item { KickerLabel("Keputusan", modifier = Modifier.padding(top = 14.dp)) }
                items(entry.notulensi.decisions) { d -> DecisionRow(d) }
            }
        },
        confirmButton = {
            Text("TUTUP", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
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
            Pill(
                q.status.label,
                background = IdColor.Bg,
                contentColor = IdColor.Neutral700,
                bordered = true,
            )
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

// ───────────────────────── Transkrip tab ─────────────────────────

@Composable
private fun ScriptTab(
    segments: List<TranscriptSegment>,
    playback: com.inteldes.app.data.audio.PlaybackState,
    roster: List<String>,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        if (playback.ready) {
            AudioPlayerBar(
                playback,
                onTogglePlay,
                onSeek,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                actions = actions,
            )
            HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(segments) { s ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { renameTarget = s.speaker }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(s.speaker, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Spacer(Modifier.width(9.dp))
                        Text(formatRecordTimer(s.timestampSec), color = IdColor.Accent, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(s.text, fontSize = 13.5.sp, lineHeight = 21.sp)
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            }
            item {
                Text(
                    "Ketuk paragraf untuk memperbaiki nama pembicara.",
                    color = IdColor.Neutral600,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
    }

    renameTarget?.let { current ->
        RenameSpeakerDialog(current, roster, onDismiss = { renameTarget = null }, onConfirm = { newName -> onRenameSpeaker(current, newName); renameTarget = null })
    }
}

@Composable
private fun RenameSpeakerDialog(current: String, roster: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti nama pembicara") },
        text = {
            Column {
            if (roster.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    roster.forEach { r ->
                        Text(
                            r,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (name == r) IdColor.White else IdColor.Neutral800,
                            modifier = Modifier
                                .background(if (name == r) IdColor.Accent else IdColor.Neutral200)
                                .clickable { name = r }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = TextStyle(fontSize = 15.sp, color = IdColor.Text),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(10.dp)) { inner() }
                },
            )
            }
        },
        confirmButton = {
            Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onConfirm(name) }.padding(10.dp))
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

// ───────────────────────── Tanya AI tab ─────────────────────────

private val SUGGESTED_PROMPTS = listOf(
    "Apa yang belum diputuskan?",
    "Siapa saja yang punya tugas?",
    "Buat draf berita acara",
)

@Composable
private fun AskTab(
    chat: List<ChatMessage>,
    thinking: Boolean,
    providerLabel: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 18.dp)) {
            items(chat) { m ->
                if (m.isUser) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                        Text(
                            m.text,
                            color = IdColor.Bg,
                            fontSize = 13.5.sp,
                            modifier = Modifier.background(IdColor.Neutral900).padding(horizontal = 13.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        KickerLabel(providerLabel)
                        Spacer(Modifier.height(6.dp))
                        Text(m.text, fontSize = 13.5.sp, lineHeight = 20.sp)
                    }
                    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (thinking) {
                item {
                    Text("membaca transkrip…", color = IdColor.Neutral600, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
        Column(modifier = Modifier.background(IdColor.Neutral100).padding(top = 10.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SUGGESTED_PROMPTS.forEach { p ->
                    Text(
                        p,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(IdColor.Bg)
                            .clickable { onSend(p) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).background(IdColor.Bg).padding(9.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(fontSize = 13.sp, color = IdColor.Text),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Tanya apa saja soal rapat ini…", color = IdColor.Neutral600, fontSize = 13.sp)
                        inner()
                    },
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(IdColor.Accent)
                        .clickable(enabled = input.isNotBlank()) {
                            onSend(input)
                            input = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim", tint = IdColor.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
