package com.inteldes.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inteldes.app.data.audio.PlaybackState
import com.inteldes.app.data.audio.TimeRange
import com.inteldes.app.data.audio.TrimMode
import com.inteldes.app.data.audio.TrimPlan
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatMmSs
import com.inteldes.app.ui.util.validRange

/**
 * Full-screen trim editor. Step 1 picks what to cut — either throw parts away ([TrimMode.REMOVE]) or keep
 * only parts ([TrimMode.KEEP]) — from a timeline, from the pieces between bookmarks, or by typing times.
 * Step 2 chooses how the result is saved: name, note template, transcription engine, and whether to start
 * processing right away. The original recording is never changed; the result is always a new recording.
 */
@Composable
fun TrimEditor(
    durationSec: Int,
    sourceTitle: String,
    marks: List<RecordingMark>,
    playback: PlaybackState,
    templates: List<NoteTemplate>,
    defaultTemplateId: String,
    defaultEngine: WhisperEngine,
    /** A stretch picked from the start (the "Potong dari sini" entry); it opens in keep mode. */
    initialRange: TimeRange? = null,
    /** The saved state of an earlier, unfinished trim of this recording, if any. */
    draft: com.inteldes.app.data.audio.TrimDraft? = null,
    /** Called as the user works (null = nothing to keep any more). */
    onDraftChange: (com.inteldes.app.data.audio.TrimDraft?) -> Unit = {},
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    /** Finds the speech ranges of the recording on the phone: (noise dB, minimum silence seconds, result callback). */
    onDetectSilence: (Int, Int, (Result<List<TimeRange>>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (keep: List<TimeRange>, title: String, template: NoteTemplate, engine: WhisperEngine, onResult: (Result<String>) -> Unit) -> Unit,
    onSaved: (newId: String, processNow: Boolean) -> Unit,
) {
    // "Potong dari sini" starts fresh; opening with the scissors continues the saved draft, if there is one.
    val resume = if (initialRange == null) draft else null
    var restored by remember { mutableStateOf(resume != null) }
    var mode by remember { mutableStateOf(resume?.trimMode() ?: if (initialRange != null) TrimMode.KEEP else TrimMode.REMOVE) }
    var picked by remember { mutableStateOf(resume?.timeRanges()?.let { TrimPlan.normalize(it, durationSec) } ?: initialRange?.let { TrimPlan.normalize(listOf(it), durationSec) } ?: emptyList()) }
    var startText by remember { mutableStateOf(formatMmSs(0)) }
    var endText by remember { mutableStateOf(formatMmSs(durationSec)) }
    var step by remember { mutableStateOf(resume?.step?.coerceIn(0, 1) ?: 0) }
    var typedTitle by remember { mutableStateOf(resume?.typedTitle) }
    var templateId by remember { mutableStateOf(resume?.templateId?.takeIf { id -> templates.any { it.id == id } } ?: defaultTemplateId) }
    var engine by remember { mutableStateOf(resume?.engine?.let { e -> runCatching { WhisperEngine.valueOf(e) }.getOrNull() } ?: defaultEngine) }
    var processNow by remember { mutableStateOf(resume?.processNow ?: true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sensitivity by remember { mutableStateOf(com.inteldes.app.data.audio.SilenceDetector.Sensitivity.NORMAL) }
    var minSilence by remember { mutableStateOf(3) }
    var detecting by remember { mutableStateOf(false) }
    var detectNote by remember { mutableStateOf<String?>(null) }
    var beforeDetect by remember { mutableStateOf<Pair<TrimMode, List<TimeRange>>?>(null) }

    fun currentDraft() = com.inteldes.app.data.audio.TrimDraft.of(durationSec, mode, picked, typedTitle, templateId, engine.name, processNow, step)
    // Opened from a bookmark and not touched yet: do not overwrite an older draft with this untouched start.
    val untouched = initialRange != null && mode == TrimMode.KEEP && picked == TrimPlan.normalize(listOf(initialRange), durationSec) && step == 0
    androidx.compose.runtime.LaunchedEffect(mode, picked, step, typedTitle, templateId, engine, processNow) {
        kotlinx.coroutines.delay(500)
        if (!untouched && !saving) onDraftChange(if (picked.isEmpty()) null else currentDraft())
    }
    fun leave() {
        if (!untouched && picked.isNotEmpty()) onDraftChange(currentDraft())
        onDismiss()
    }

    val keep = TrimPlan.keepRanges(mode, picked, durationSec)
    val keptSec = TrimPlan.totalSec(keep)
    val canContinue = picked.isNotEmpty() && keep.isNotEmpty()
    val template = templates.firstOrNull { it.id == templateId } ?: templates.firstOrNull()
    // Follows the choice until the user types a name of their own.
    val title = typedTitle ?: TrimPlan.defaultTitle(sourceTitle, keep)

    Dialog(
        onDismissRequest = { if (!saving) { if (step == 1) step = 0 else leave() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg).statusBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(
                    if (step == 0) "✕" else "‹",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IdColor.Text,
                    modifier = Modifier.clickable(enabled = !saving) { if (step == 1) step = 0 else leave() }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("Potong audio", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(if (step == 0) "Langkah 1 dari 2 · pilih bagian" else "Langkah 2 dari 2 · simpan & proses", color = IdColor.Neutral600, fontSize = 11.sp)
                }
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (restored) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Dilanjutkan dari draf yang Anda simpan.", color = IdColor.Neutral800, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        Text(
                            "MULAI ULANG", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                mode = TrimMode.REMOVE; picked = emptyList(); step = 0; typedTitle = null; restored = false
                                onDraftChange(null)
                            }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (step == 0) {
                    Row {
                        ModeButton("HAPUS BAGIAN", "Pilih yang dibuang", mode == TrimMode.REMOVE, Modifier.weight(1f)) { mode = TrimMode.REMOVE }
                        Spacer(Modifier.width(2.dp))
                        ModeButton("SIMPAN BAGIAN", "Pilih yang dipakai", mode == TrimMode.KEEP, Modifier.weight(1f)) { mode = TrimMode.KEEP }
                    }
                    Spacer(Modifier.height(14.dp))

                    Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(12.dp)) {
                        Label("DETEKSI BAGIAN HENING")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Memilih otomatis bagian yang ada suaranya dan membuang jeda panjang, supaya rekaman lebih pendek dan transkrip lebih murah. Bisa Anda ubah sesudahnya.",
                            color = IdColor.Neutral700, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            com.inteldes.app.data.audio.SilenceDetector.Sensitivity.entries.forEach {
                                Choice("${it.label} ${it.noiseDb} dB", sensitivity == it) { sensitivity = it }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Hening minimal", color = IdColor.Neutral700, fontSize = 11.sp)
                            listOf(2, 3, 5, 10).forEach { Choice("$it dtk", minSilence == it) { minSilence = it } }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (detecting) "MENGANALISIS…" else "CARI BAGIAN BICARA",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = IdColor.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (detecting) IdColor.Neutral400 else IdColor.Neutral900)
                                .clickable(enabled = !detecting) {
                                    detecting = true
                                    detectNote = null
                                    onDetectSilence(sensitivity.noiseDb, minSilence) { result ->
                                        detecting = false
                                        result.fold(
                                            onSuccess = { speech ->
                                                if (speech.isEmpty()) {
                                                    detectNote = "Tidak ada bagian bicara yang terdeteksi. Coba kepekaan \"Berisik\"."
                                                } else {
                                                    beforeDetect = mode to picked
                                                    mode = TrimMode.KEEP
                                                    picked = speech
                                                    val saved = durationSec - TrimPlan.totalSec(speech)
                                                    detectNote = "${speech.size} bagian bicara dipilih; ${formatMmSs(saved)} hening dibuang."
                                                }
                                            },
                                            onFailure = { detectNote = it.message ?: "Gagal menganalisis audio." },
                                        )
                                    }
                                }
                                .padding(vertical = 11.dp),
                        )
                        detectNote?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = IdColor.Neutral800, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        beforeDetect?.let { (m, p) ->
                            Text(
                                "URUNGKAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                modifier = Modifier.clickable { mode = m; picked = p; beforeDetect = null; detectNote = null }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    Timeline(durationSec, marks, picked, mode, playback.positionSec, onSeek)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (playback.playing) "❚❚" else "▶",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = IdColor.White, textAlign = TextAlign.Center,
                            modifier = Modifier.width(44.dp).background(IdColor.Neutral900).clickable(onClick = onTogglePlay).padding(vertical = 8.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("${formatMmSs(playback.positionSec)} / ${formatMmSs(durationSec)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("Ketuk garis waktu untuk memutar dari sana", color = IdColor.Neutral600, fontSize = 10.sp)
                    }

                    val segments = TrimPlan.segmentsBetween(marks.map { it.timestampSec }, durationSec)
                    if (marks.isNotEmpty() && segments.size > 1) {
                        Spacer(Modifier.height(16.dp))
                        Label("BAGIAN ANTAR PENANDA — ketuk untuk memilih atau membatalkan")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            segments.forEach { seg ->
                                val on = TrimPlan.covers(picked, seg)
                                Text(
                                    "${formatMmSs(seg.start)}–${formatMmSs(seg.end)}",
                                    fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    color = if (on) IdColor.White else IdColor.Neutral800,
                                    modifier = Modifier
                                        .background(if (on) (if (mode == TrimMode.REMOVE) IdColor.Accent else IdColor.Neutral900) else IdColor.Neutral100)
                                        .clickable { picked = TrimPlan.toggle(picked, seg, durationSec) }
                                        .padding(horizontal = 11.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Label("ATAU ATUR SENDIRI")
                    Spacer(Modifier.height(6.dp))
                    RangeSelector(
                        durationSec = durationSec,
                        marks = emptyList(),
                        startText = startText,
                        endText = endText,
                        onStartChange = { startText = it },
                        onEndChange = { endText = it },
                        currentPositionSec = playback.positionSec,
                    )
                    val range = validRange(startText, endText, durationSec)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mode == TrimMode.REMOVE) "+ TAMBAHKAN KE BAGIAN YANG DIHAPUS" else "+ TAMBAHKAN KE BAGIAN YANG DISIMPAN",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        color = if (range != null) IdColor.White else IdColor.Neutral400,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (range != null) IdColor.Neutral900 else IdColor.Neutral200)
                            .clickable(enabled = range != null) {
                                range?.let { r ->
                                    picked = TrimPlan.add(picked, TimeRange(r.first, r.second), durationSec)
                                    startText = formatMmSs(r.second)
                                    endText = formatMmSs(durationSec)
                                }
                            }
                            .padding(vertical = 11.dp),
                    )

                    Spacer(Modifier.height(18.dp))
                    Label(if (mode == TrimMode.REMOVE) "BAGIAN YANG DIHAPUS (${picked.size})" else "BAGIAN YANG DISIMPAN (${picked.size})")
                    Spacer(Modifier.height(6.dp))
                    if (picked.isEmpty()) {
                        Text("Belum ada. Ketuk bagian di atas atau tambahkan rentang waktu.", color = IdColor.Neutral600, fontSize = 12.sp)
                    } else {
                        picked.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(IdColor.Neutral100)) {
                                Text(
                                    "${formatMmSs(r.start)} → ${formatMmSs(r.end)}  ·  ${formatMmSs(r.length)}",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f).clickable { onSeek(r.start) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                                Text(
                                    "✕", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = IdColor.Accent,
                                    modifier = Modifier.clickable { picked = picked - r }.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        Text(
                            "Hapus semua", color = IdColor.Neutral700, fontSize = 11.5.sp,
                            modifier = Modifier.clickable { picked = emptyList() }.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    Text(
                        "Hasil potong disimpan sebagai rekaman BARU. Rekaman asli tidak berubah.",
                        color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Label("NAMA REKAMAN BARU")
                    Spacer(Modifier.height(6.dp))
                    IdTextField(value = title, onValueChange = { typedTitle = it }, placeholder = "Nama rekaman")

                    Spacer(Modifier.height(16.dp))
                    Label("TEMPLATE DOKUMEN")
                    Spacer(Modifier.height(7.dp))
                    TemplateChips(templates, templateId) { templateId = it.id }
                    template?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it.description, color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                    Label("MESIN TRANSKRIP")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice("On-device", engine == WhisperEngine.ON_DEVICE) { engine = WhisperEngine.ON_DEVICE }
                        Choice("Awan", engine == WhisperEngine.CLOUD) { engine = WhisperEngine.CLOUD }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (engine == WhisperEngine.CLOUD) "Lebih akurat, ada label pembicara, memakai kuota API." else "Tanpa internet, satu pembicara, gratis.",
                        color = IdColor.Neutral600, fontSize = 11.sp,
                    )

                    Spacer(Modifier.height(16.dp))
                    Label("SETELAH DISIMPAN")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice("Proses sekarang", processNow) { processNow = true }
                        Choice("Simpan saja dulu", !processNow) { processNow = false }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (processNow) "Transkrip dan notulensi langsung disusun dengan pilihan di atas."
                        else "Tidak ada yang diproses. Mulai kapan saja dari halaman rekaman baru.",
                        color = IdColor.Neutral600, fontSize = 11.sp,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = IdColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(
                    when {
                        picked.isEmpty() -> "Durasi asli ${formatMmSs(durationSec)}"
                        keep.isEmpty() -> "Tidak ada bagian tersisa — kurangi bagian yang dihapus."
                        else -> "Hasil ${formatMmSs(keptSec)} dari ${formatMmSs(durationSec)} · ${keep.size} bagian" +
                            if (keptSec < durationSec) " · ${formatMmSs(durationSec - keptSec)} dibuang" else ""
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (picked.isNotEmpty() && keep.isEmpty()) IdColor.Accent else IdColor.Neutral800,
                )
                Spacer(Modifier.height(8.dp))
                if (step == 0 && picked.isNotEmpty()) {
                    Text(
                        "SIMPAN DRAF & TUTUP — lanjutkan nanti",
                        color = IdColor.Neutral800, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).clickable { leave() }.padding(vertical = 12.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                val enabled = if (step == 0) canContinue else (!saving && template != null)
                Text(
                    when {
                        step == 0 -> "LANJUT"
                        saving -> "MEMOTONG…"
                        processNow -> "SIMPAN & PROSES"
                        else -> "SIMPAN"
                    },
                    color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (enabled) IdColor.Accent else IdColor.Neutral400)
                        .clickable(enabled = enabled) {
                            if (step == 0) {
                                step = 1
                                error = null
                            } else {
                                val chosen = template ?: return@clickable
                                saving = true
                                error = null
                                onSave(keep, title, chosen, engine) { result ->
                                    saving = false
                                    result.fold(
                                        onSuccess = { onSaved(it, processNow) },
                                        onFailure = { error = it.message ?: "Gagal memotong audio." },
                                    )
                                }
                            }
                        }
                        .padding(vertical = 15.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
}

@Composable
private fun ModeButton(label: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.background(if (selected) IdColor.Neutral900 else IdColor.Neutral100).clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Text(label, color = if (selected) IdColor.Bg else IdColor.Neutral800, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, letterSpacing = 0.6.sp)
        Text(hint, color = if (selected) IdColor.Bg.copy(alpha = 0.8f) else IdColor.Neutral600, fontSize = 10.5.sp)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) IdColor.White else IdColor.Neutral800,
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(if (selected) IdColor.Accent else IdColor.Neutral200).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/**
 * The whole recording as one bar: chosen stretches are filled (red when they will be removed, dark when they
 * will be kept), bookmarks are ticks, the line is the playhead. Tapping seeks the player there.
 */
@Composable
private fun Timeline(
    durationSec: Int,
    marks: List<RecordingMark>,
    picked: List<TimeRange>,
    mode: TrimMode,
    positionSec: Int,
    onSeek: (Int) -> Unit,
) {
    val fill = if (mode == TrimMode.REMOVE) IdColor.Accent else IdColor.Neutral900
    val base = IdColor.Neutral200
    val tick = IdColor.Neutral700
    val head = IdColor.Text
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(durationSec) {
                detectTapGestures { offset -> onSeek(((offset.x / size.width) * durationSec).toInt().coerceIn(0, durationSec)) }
            },
    ) {
        val w = size.width
        val d = durationSec.coerceAtLeast(1).toFloat()
        drawRect(base, Offset.Zero, size)
        picked.forEach { r ->
            drawRect(fill, Offset(w * r.start / d, 0f), Size(w * r.length / d, size.height))
        }
        marks.forEach { m ->
            val x = w * m.timestampSec / d
            drawRect(tick, Offset(x - 1f, size.height * 0.55f), Size(2f, size.height * 0.45f))
        }
        val px = w * positionSec.coerceIn(0, durationSec) / d
        drawRect(head, Offset(px - 1.5f, 0f), Size(3f, size.height))
    }
}
