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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
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
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.components.TranscribeOptionsDialog
import com.inteldes.app.ui.components.TrimEditor
import com.inteldes.app.ui.components.KickerLabel
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
    val serverJob by vm.serverJob.collectAsStateWithLifecycle()
    val hasTrimDraft by vm.hasTrimDraft.collectAsStateWithLifecycle()
    val shareUi by vm.share.collectAsStateWithLifecycle()
    val onSeek: (Int) -> Unit = vm::seekPlayback

    androidx.compose.runtime.LaunchedEffect(initialTab) {
        vm.setTab(if (initialTab == "script") DetailTab.SCRIPT else if (initialTab == "ask") DetailTab.ASK else DetailTab.NOTES)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLocationEdit by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    var trimFromSec by remember { mutableStateOf<Int?>(null) }

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
            onTrim = { trimFromSec = null; showTrim = true },
            hasTrimDraft = hasTrimDraft,
            onAddMark = vm::addMarkAtPlayback,
            onDeleteMark = vm::deleteMark,
            onRenameMark = vm::renameMark,
            onTrimFrom = { sec -> trimFromSec = sec; showTrim = true },
            onSkip = vm::skipPlayback,
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
                onEditText = vm::editTranscriptText,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
            recording.status != RecordingStatus.DONE -> NotDoneState(
                statusIsWorking = recording.status == RecordingStatus.WORKING,
                errorMessage = recording.errorMessage,
                currentEngine = recording.engine,
                durationSec = recording.durationSec,
                marks = state.marks,
                locationName = recording.locationName,
                hasAudio = recording.audioFilePath != null,
                onEditLocation = { showLocationEdit = true },
                photos = state.photos,
                onAddPhoto = vm::addPhoto,
                onUpdateCaption = vm::updatePhotoCaption,
                onDeletePhoto = vm::deletePhoto,
                playback = playback,
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onOpenProcessing = { onOpenProcessing(recording.id, state.tab.routeName, null, null) },
                onStartProcessing = { engine, start, end ->
                    vm.startProcessing(engine, start, end) { id, tab, s, e -> onOpenProcessing(id, tab, s, e) }
                },
                serverJob = serverJob,
                serverOptionOf = { vm.serverOption(recording.audioFilePath != null) },
                onStartServer = vm::startServerProcessing,
                actions = actions,
            )
            state.tab == DetailTab.NOTES -> NotesTab(
                recording = recording,
                notulensi = state.notulensi,
                history = state.notulensiHistory,
                photos = state.photos,
                templates = state.templates,
                regenerating = state.regenerating,
                error = state.error,
                onRegenerate = { vm.regenerateNotulensi() },
                onCancelRegenerate = vm::cancelRegenerate,
                onEditLocation = { showLocationEdit = true },
                onPickTemplate = { vm.regenerateNotulensi(it) },
                onChangeAgent = onGoSettings,
                onAddPhoto = vm::addPhoto,
                onUpdateCaption = vm::updatePhotoCaption,
                onDeletePhoto = vm::deletePhoto,
                onShareLink = vm::openShare,
                modifier = Modifier.weight(1f),
            )
            state.tab == DetailTab.SCRIPT -> ScriptTab(
                segments = state.transcript,
                playback = playback,
                roster = state.speakerRoster,
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onRenameSpeaker = vm::renameSpeaker,
                onEditText = vm::editTranscriptText,
                actions = actions,
                modifier = Modifier.weight(1f),
            )
            state.tab == DetailTab.ASK -> AskTab(
                chat = state.chat,
                thinking = state.thinking,
                providerLabel = providerBadge(state.notulensi?.provider),
                contexts = state.contexts,
                attachedContextId = state.attachedContextId,
                onAttachContext = vm::attachContext,
                onSend = vm::ask,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showTrim) {
        val rec = state.recording
        val localContext = LocalContext.current
        if (rec != null && state.templates.isNotEmpty()) {
            TrimEditor(
                durationSec = if (playback.ready && playback.durationSec > 0) playback.durationSec else rec.durationSec,
                sourceTitle = rec.title,
                marks = state.marks,
                playback = playback,
                templates = state.templates,
                defaultTemplateId = rec.templateId,
                defaultEngine = rec.engine,
                draft = vm.trimDraft(if (playback.ready && playback.durationSec > 0) playback.durationSec else rec.durationSec),
                onDraftChange = vm::saveTrimDraft,
                initialRange = trimFromSec?.let { from ->
                    val total = if (playback.ready && playback.durationSec > 0) playback.durationSec else rec.durationSec
                    // Like the web: a minute from the mark (to the next mark if that comes sooner), then adjustable.
                    val next = state.marks.map { it.timestampSec }.filter { it > from }.minOrNull()
                    com.inteldes.app.data.audio.TimeRange(from, minOf(next ?: Int.MAX_VALUE, from + 60, total))
                },
                onTogglePlay = vm::togglePlayback,
                onSeek = onSeek,
                onDetectSilence = { noiseDb, minSilence, onResult -> vm.detectSilence(noiseDb, minSilence, onResult) },
                onDismiss = { showTrim = false },
                onSave = { keep, title, template, engine, onResult -> vm.trimToNewRecording(keep, title, template, engine, onResult) },
                onSaved = { newId, processNow ->
                    showTrim = false
                    if (processNow) {
                        onOpenProcessing(newId, "script", null, null)
                    } else {
                        android.widget.Toast.makeText(localContext, "Disimpan sebagai rekaman baru. Rekaman asli tidak berubah.", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    if (shareUi.open) {
        com.inteldes.app.ui.components.ShareLinkDialog(
            ui = shareUi,
            onCreate = vm::createShare,
            onRevoke = vm::revokeShare,
            onDismiss = vm::closeShare,
        )
    }

    if (showLocationEdit) {
        com.inteldes.app.ui.components.LocationEditDialog(
            current = state.recording?.locationName,
            onDismiss = { showLocationEdit = false },
            onSave = { vm.setLocation(it); showLocationEdit = false },
        )
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

internal fun providerBadge(provider: AiProvider?): String = when (provider) {
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
    val hasTrimDraft: Boolean,
    val onAddMark: () -> Unit,
    val onDeleteMark: (com.inteldes.app.data.model.RecordingMark) -> Unit,
    val onRenameMark: (com.inteldes.app.data.model.RecordingMark, String) -> Unit,
    /** Opens the trim editor with the stretch starting at this mark already picked. */
    val onTrimFrom: (Int) -> Unit,
    val onSkip: (Int) -> Unit,
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
    var markMenu by remember { mutableStateOf<com.inteldes.app.data.model.RecordingMark?>(null) }
    var markToRename by remember { mutableStateOf<com.inteldes.app.data.model.RecordingMark?>(null) }
    val marks = actions?.marks.orEmpty()
    val duration = playback.durationSec.coerceAtLeast(1)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (actions != null) {
                SkipButton(rewind = true, onClick = { actions.onSkip(-SKIP_SECONDS) })
                Spacer(Modifier.width(4.dp))
            }
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
            if (actions != null) {
                Spacer(Modifier.width(4.dp))
                SkipButton(rewind = false, onClick = { actions.onSkip(SKIP_SECONDS) })
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "${formatRecordTimer(shownPositionSec)} / ${formatRecordTimer(playback.durationSec)}",
                color = IdColor.Neutral700,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (actions != null) {
                IconButton(onClick = actions.onTrim, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ContentCut, contentDescription = if (actions.hasTrimDraft) "Potong audio (ada draf tersimpan)" else "Potong audio", tint = if (actions.hasTrimDraft) IdColor.Accent else IdColor.Neutral700, modifier = Modifier.size(20.dp))
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
                    "Belum ada penanda. Tandai saat merekam (tombol di layar rekam atau notifikasi), atau di sini sambil mendengarkan. Tahan penanda untuk mengubah nama, memotong dari sana, atau menghapus.",
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
                            markChipText(m),
                            color = if (active) IdColor.White else IdColor.Neutral800,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .background(if (active) IdColor.Accent else IdColor.Neutral200)
                                .combinedClickable(
                                    onClick = { onSeek(m.timestampSec) },
                                    onLongClick = { markMenu = m },
                                )
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }

    markMenu?.let { mark ->
        AlertDialog(
            onDismissRequest = { markMenu = null },
            title = { Text("Penanda ${com.inteldes.app.ui.util.formatMmSs(mark.timestampSec)}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(mark.label, color = IdColor.Neutral700, fontSize = 12.5.sp)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "UBAH NAMA" to { markToRename = mark },
                        "POTONG DARI SINI" to { actions?.onTrimFrom?.invoke(mark.timestampSec) },
                        "HAPUS" to { markToDelete = mark },
                    ).forEach { (label, run) ->
                        Text(
                            label, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            color = if (label == "HAPUS") IdColor.Accent else IdColor.Text,
                            modifier = Modifier.fillMaxWidth().clickable { markMenu = null; run() }.padding(vertical = 12.dp),
                        )
                        HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { Text("Tutup", color = IdColor.Neutral700, modifier = Modifier.clickable { markMenu = null }.padding(10.dp)) },
        )
    }

    markToRename?.let { mark ->
        var name by remember(mark.id) { mutableStateOf(mark.label) }
        AlertDialog(
            onDismissRequest = { markToRename = null },
            title = { Text("Nama penanda", fontWeight = FontWeight.Bold) },
            text = { com.inteldes.app.ui.components.IdTextField(value = name, onValueChange = { name = it }, placeholder = "Contoh: Keputusan anggaran") },
            confirmButton = {
                Text(
                    "SIMPAN",
                    color = if (name.isNotBlank()) IdColor.Accent else IdColor.Neutral400,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = name.isNotBlank()) { actions?.onRenameMark?.invoke(mark, name); markToRename = null }.padding(10.dp),
                )
            },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { markToRename = null }.padding(10.dp)) },
        )
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
    locationName: String?,
    hasAudio: Boolean,
    onEditLocation: () -> Unit,
    photos: List<com.inteldes.app.data.model.Photo>,
    onAddPhoto: (android.net.Uri, Boolean) -> Unit,
    onUpdateCaption: (com.inteldes.app.data.model.Photo, String) -> Unit,
    onDeletePhoto: (com.inteldes.app.data.model.Photo) -> Unit,
    playback: com.inteldes.app.data.audio.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onOpenProcessing: () -> Unit,
    onStartProcessing: (WhisperEngine, Int?, Int?) -> Unit,
    serverJob: com.inteldes.app.data.web.ServerJob?,
    serverOptionOf: () -> com.inteldes.app.data.web.ServerOption,
    onStartServer: () -> Unit,
    actions: PlayerActions,
) {
    var showEnginePicker by remember { mutableStateOf(false) }
    val serverRunning = serverJob?.running == true
    val shownError = if (serverJob != null && !serverJob.running) serverJob.message else errorMessage
    val failed = shownError != null

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
                        serverRunning -> "Diproses di server"
                        statusIsWorking -> "Sedang diproses"
                        else -> "Belum diproses"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    shownError ?: serverJob?.message ?: "Transkrip dan notulensi akan muncul di sini setelah selesai. Tandai poin penting atau potong audio dulu kalau perlu.",
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
            Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(horizontal = 14.dp, vertical = 6.dp)) {
                com.inteldes.app.ui.components.LocationRow(locationName, onEdit = onEditLocation)
            }
            PhotoDocumentation(photos, onAddPhoto, onUpdateCaption, onDeletePhoto)
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
                    .clickable(
                        enabled = !serverRunning,
                        onClick = when {
                            statusIsWorking -> onOpenProcessing
                            // An imported conversation has no audio: nothing to transcribe, so no engine to choose.
                            !hasAudio -> { { onStartProcessing(currentEngine, null, null) } }
                            else -> { { showEnginePicker = true } }
                        },
                    )
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    when {
                        serverRunning -> "MENUNGGU SERVER…"
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
            serverOption = serverOptionOf(),
            onConfirmServer = {
                showEnginePicker = false
                onStartServer()
            },
        )
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
    onEditText: (Int, String) -> Unit,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    var editIndex by remember { mutableStateOf<Int?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = LocalContext.current

    // The line being heard: the last one that started at or before the playhead. Only shown once
    // playback has actually begun, so an idle transcript isn't stuck with the first line lit.
    val active = if (playback.ready && (playback.playing || playback.positionSec > 0)) {
        segments.indexOfLast { it.timestampSec <= playback.positionSec }.takeIf { it >= 0 }
    } else null

    // Each bookmark hangs on the line being spoken when it was made (the last one starting at or before it).
    val marksBySegment = remember(segments, actions.marks) {
        actions.marks.groupBy { m -> segments.indexOfLast { it.timestampSec <= m.timestampSec }.coerceAtLeast(0) }
    }

    // Follow the audio while it plays so the highlighted line stays on screen.
    androidx.compose.runtime.LaunchedEffect(active, playback.playing) {
        if (playback.playing && active != null) listState.animateScrollToItem(active)
    }

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
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(segments) { index, s ->
                val isActive = index == active
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(if (isActive) IdColor.Accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent),
                ) {
                    // Accent bar on the line currently playing.
                    Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(if (isActive) IdColor.Accent else androidx.compose.ui.graphics.Color.Transparent))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = playback.ready) { onSeek(s.timestampSec) }
                            .padding(start = 14.dp, end = 4.dp, top = 13.dp, bottom = 13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.speaker, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Spacer(Modifier.width(9.dp))
                            Text(formatRecordTimer(s.timestampSec), color = IdColor.Accent, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (playback.playing) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = "Baris yang sedang diputar",
                                    tint = IdColor.Accent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        marksBySegment[index]?.let { flagged ->
                            Spacer(Modifier.height(5.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                flagged.forEach { m ->
                                    Text(
                                        "⚑ " + markChipText(m), color = IdColor.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                        modifier = Modifier.background(IdColor.Neutral900).clickable { onSeek(m.timestampSec) }.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(s.text, fontSize = 13.5.sp, lineHeight = 21.sp, fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal)
                    }
                    IconButton(onClick = { editIndex = index }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "Perbaiki teks", tint = IdColor.Neutral600, modifier = Modifier.size(17.dp))
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            }
            item {
                Text(
                    if (playback.ready) "Ketuk baris untuk memutar dari sana. Ikon pensil memperbaiki kata-kata atau nama pembicara."
                    else "Ikon pensil memperbaiki kata-kata atau nama pembicara.",
                    color = IdColor.Neutral600,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
    }

    editIndex?.let { i ->
        val seg = segments.getOrNull(i)
        if (seg != null) {
            EditSegmentDialog(
                segment = seg,
                roster = roster,
                canPlay = playback.ready,
                onPlay = { onSeek(seg.timestampSec); if (!playback.playing) onTogglePlay() },
                onDismiss = { editIndex = null },
                onSave = { newText, newSpeaker ->
                    if (newText.trim() != seg.text) {
                        onEditText(i, newText)
                        android.widget.Toast.makeText(context, "Tersimpan. Tekan SUSUN ULANG di tab Notulensi agar ringkasan memakai teks baru.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    if (newSpeaker.isNotBlank() && newSpeaker != seg.speaker) onRenameSpeaker(seg.speaker, newSpeaker)
                    editIndex = null
                },
            )
        }
    }
}

/** Fix one transcript line's wording; changing the speaker name renames that speaker everywhere. */
@Composable
private fun EditSegmentDialog(
    segment: TranscriptSegment,
    roster: List<String>,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (text: String, speaker: String) -> Unit,
) {
    var text by remember { mutableStateOf(segment.text) }
    var speaker by remember { mutableStateOf(segment.speaker) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perbaiki transkrip · ${formatRecordTimer(segment.timestampSec)}", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (canPlay) {
                    Text(
                        "▶ DENGARKAN BARIS INI",
                        color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp,
                        modifier = Modifier.clickable(onClick = onPlay).padding(vertical = 6.dp),
                    )
                }
                Text("TEKS", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontSize = 14.sp, color = IdColor.Text, lineHeight = 20.sp),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).background(IdColor.Neutral100).padding(10.dp)) { inner() }
                    },
                )
                Spacer(Modifier.height(14.dp))
                Text("PEMBICARA (berlaku untuk semua ucapannya)", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(4.dp))
                if (roster.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        roster.forEach { r ->
                            Text(
                                r, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (speaker == r) IdColor.White else IdColor.Neutral800,
                                modifier = Modifier
                                    .background(if (speaker == r) IdColor.Accent else IdColor.Neutral200)
                                    .clickable { speaker = r }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                BasicTextField(
                    value = speaker,
                    onValueChange = { speaker = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = IdColor.Text),
                    decorationBox = { inner -> Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(10.dp)) { inner() } },
                )
            }
        },
        confirmButton = {
            Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(text, speaker) }.padding(10.dp))
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
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
    contexts: List<com.inteldes.app.data.model.KnowledgeContext>,
    attachedContextId: String?,
    onAttachContext: (String?) -> Unit,
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
            if (contexts.isNotEmpty()) {
                // Optional background knowledge: a saved context (merged notes of earlier meetings) that the
                // AI reads along with this recording's transcript when answering.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("KONTEKS", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
                    listOf<com.inteldes.app.data.model.KnowledgeContext?>(null).plus(contexts).forEach { c ->
                        val on = attachedContextId == c?.id
                        Text(
                            c?.name ?: "Tanpa",
                            fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                            color = if (on) IdColor.White else IdColor.Neutral800,
                            modifier = Modifier
                                .background(if (on) IdColor.Accent else IdColor.Neutral200)
                                .clickable { onAttachContext(c?.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
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

private const val SKIP_SECONDS = 15

/** Back/forward 15 s: a circular arrow with "15" in it (Material has no 15 s icon, only 5/10/30). */
@Composable
private fun SkipButton(rewind: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(38.dp).clickable(onClick = onClick),
    ) {
        Icon(
            if (rewind) Icons.Filled.Replay else Icons.Filled.Refresh,
            contentDescription = if (rewind) "Mundur 15 detik" else "Maju 15 detik",
            tint = IdColor.Neutral800,
            modifier = Modifier.size(32.dp),
        )
        Text("15", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = IdColor.Neutral800)
    }
}

/** "03:20", or "03:20 · Keputusan anggaran" once the mark has a name of its own (not the automatic "Penanda N"). */
private fun markChipText(m: com.inteldes.app.data.model.RecordingMark): String {
    val time = com.inteldes.app.ui.util.formatMmSs(m.timestampSec)
    val named = m.label.isNotBlank() && !m.label.startsWith("Penanda")
    return if (named) "$time · ${m.label.take(20)}" else time
}
