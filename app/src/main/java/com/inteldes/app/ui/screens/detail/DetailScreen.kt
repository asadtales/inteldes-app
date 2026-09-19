package com.inteldes.app.ui.screens.detail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.Topic
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatDuration
import com.inteldes.app.ui.util.formatRecordTimer

@Composable
fun DetailScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    initialTab: String,
    onBack: () -> Unit,
    onGoSettings: () -> Unit,
    onOpenProcessing: (String) -> Unit,
) {
    val vm: DetailViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val playback by vm.playbackState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(initialTab) {
        vm.setTab(if (initialTab == "script") DetailTab.SCRIPT else if (initialTab == "ask") DetailTab.ASK else DetailTab.NOTES)
    }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Column(modifier = Modifier.background(IdColor.Bg)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text)
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
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TabButton("Notulensi", state.tab == DetailTab.NOTES, Modifier.weight(1f)) { vm.setTab(DetailTab.NOTES) }
                TabButton("Transkrip", state.tab == DetailTab.SCRIPT, Modifier.weight(1f)) { vm.setTab(DetailTab.SCRIPT) }
                TabButton("Tanya AI", state.tab == DetailTab.ASK, Modifier.weight(1f)) { vm.setTab(DetailTab.ASK) }
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
        }

        val recording = state.recording
        when {
            recording == null -> Unit
            recording.status != RecordingStatus.DONE -> NotDoneState(
                statusIsWorking = recording.status == RecordingStatus.WORKING,
                errorMessage = recording.errorMessage,
                onOpenProcessing = { onOpenProcessing(recording.id) },
            )
            state.tab == DetailTab.NOTES -> NotesTab(
                notulensi = state.notulensi,
                regenerating = state.regenerating,
                error = state.error,
                onRegenerate = vm::regenerateNotulensi,
                onChangeAgent = onGoSettings,
                modifier = Modifier.weight(1f),
            )
            state.tab == DetailTab.SCRIPT -> ScriptTab(
                segments = state.transcript,
                playback = playback,
                onTogglePlay = vm::togglePlayback,
                onRenameSpeaker = vm::renameSpeaker,
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

@Composable
private fun NotDoneState(statusIsWorking: Boolean, errorMessage: String?, onOpenProcessing: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text(
            when {
                errorMessage != null -> "Pemrosesan gagal"
                statusIsWorking -> "Sedang diproses"
                else -> "Belum diproses"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            errorMessage ?: "Transkrip dan notulensi akan muncul di sini setelah selesai.",
            color = IdColor.Neutral700,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            if (errorMessage != null) "COBA LAGI" else "LIHAT PROGRES",
            color = IdColor.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.background(IdColor.Accent).clickable(onClick = onOpenProcessing).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ───────────────────────── Notulensi tab (1A — continuous document) ─────────────────────────

@Composable
private fun NotesTab(
    notulensi: com.inteldes.app.data.model.Notulensi?,
    regenerating: Boolean,
    error: String?,
    onRegenerate: () -> Unit,
    onChangeAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Text(
                    if (regenerating) "MENYUSUN…" else "SUSUN ULANG",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = IdColor.Text,
                    modifier = Modifier
                        .weight(1f)
                        .background(IdColor.Bg)
                        .let { m -> if (!regenerating) m.clickable(onClick = onRegenerate) else m }
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
    }
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
    onTogglePlay: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        if (playback.ready) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(IdColor.Accent).clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = IdColor.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${formatRecordTimer(playback.positionSec)} / ${formatRecordTimer(playback.durationSec)}",
                    color = IdColor.Neutral700,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
        RenameSpeakerDialog(current, onDismiss = { renameTarget = null }, onConfirm = { newName -> onRenameSpeaker(current, newName); renameTarget = null })
    }
}

@Composable
private fun RenameSpeakerDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti nama pembicara") },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = TextStyle(fontSize = 15.sp, color = IdColor.Text),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(10.dp)) { inner() }
                },
            )
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
                    Icon(Icons.Filled.Send, contentDescription = "Kirim", tint = IdColor.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
