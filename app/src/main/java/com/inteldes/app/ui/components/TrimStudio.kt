package com.inteldes.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.inteldes.app.data.audio.PlaybackState
import com.inteldes.app.data.audio.Region
import com.inteldes.app.data.audio.RegionMode
import com.inteldes.app.data.audio.Silence
import com.inteldes.app.data.audio.SilenceCuts
import com.inteldes.app.data.audio.SilenceDetector
import com.inteldes.app.data.audio.SilenceItem
import com.inteldes.app.data.audio.Span
import com.inteldes.app.data.audio.TimeRange
import com.inteldes.app.data.audio.TrimDraftState
import com.inteldes.app.data.audio.TrimModel
import com.inteldes.app.data.audio.TrimPlan
import com.inteldes.app.data.audio.UndoStack
import com.inteldes.app.data.audio.WaveformData
import com.inteldes.app.data.audio.WaveformMath
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatClockD
import com.inteldes.app.ui.util.parseClockD
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the editor knows about the waveform right now. */
data class WaveformInput(val loading: Boolean, val progress: Float, val data: WaveformData?, val error: String?)

/** What the server (or this phone) holds from an earlier, unfinished trim. */
data class DraftInput(val state: TrimDraftState, val savedAt: Long)

private val ZOOMS = listOf(15, 30, 60, 120, 300, 600)
private val BAR_SELECTED = Color(0xFF6B6666)
private val BAR_NORMAL = Color(0xFF8A8585)
private val BAR_REMOVED = Color(0xFFBDB8B8)
private val KEEP_TINT = Color(0x3327A06B)
private val SILENCE_TINT = Color(0x292B6CB0)
private val WIB: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

private data class StudioState(val model: TrimModel, val selStart: Double, val selEnd: Double)
private class Analysis(val noiseDb: Int, val minSilence: Int, val items: List<SilenceItem>)

/**
 * Full-screen trim editor, built like the web's: one big waveform that follows the playhead, an overview of the whole
 * recording with a frame to drag, marked areas (Keep or Cut) with undo and redo, a silence analysis panel, and a
 * draft that is saved by itself (on this phone and, when the recording is on the server, there too). The original
 * recording is never changed: the result is always a new recording.
 */
@Composable
fun TrimStudio(
    recordingTitle: String,
    durationSec: Int,
    kind: String,
    marks: List<RecordingMark>,
    playback: PlaybackState,
    waveform: WaveformInput,
    templates: List<NoteTemplate>,
    defaultTemplateId: String,
    defaultEngine: WhisperEngine,
    initialDraft: DraftInput?,
    /** The "Potong dari sini" entry: start a fresh selection of a minute at this mark. */
    startAtSec: Double?,
    onTogglePlay: () -> Unit,
    onPause: () -> Unit,
    onSeekMs: (Int) -> Unit,
    onFastTick: (Boolean) -> Unit,
    onAnalyzeSilences: (noiseDb: Int, minSilence: Int, onResult: (Result<List<Silence>>) -> Unit) -> Unit,
    /** Returns whether the copy on the server is current; null when there is no server copy to keep. */
    onDraftSave: suspend (TrimDraftState) -> Boolean?,
    onDraftClear: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (keep: List<Span>, title: String, template: NoteTemplate, engine: WhisperEngine, onResult: (Result<String>) -> Unit) -> Unit,
    onSaved: (newId: String, processNow: Boolean) -> Unit,
) {
    val dur = durationSec.toDouble().coerceAtLeast(1.0)
    val resume = if (startAtSec == null) initialDraft?.state else null
    val history = remember {
        val m = resume?.model() ?: TrimModel()
        val ss = resume?.sel?.start?.coerceIn(0.0, dur) ?: (startAtSec ?: 0.0)
        val se = resume?.sel?.end?.coerceIn(0.0, dur) ?: (if (startAtSec != null) min(dur, startAtSec + 60.0) else dur)
        UndoStack(StudioState(m, ss, max(ss, se)))
    }
    var state by remember { mutableStateOf(history.current) }
    var zoomIdx by remember { mutableStateOf((resume?.zoomIdx ?: 2).coerceIn(0, ZOOMS.lastIndex)) }
    var analysis by remember {
        mutableStateOf(resume?.analysis?.let { a -> Analysis(a.noiseDb, a.minSilence, a.silences.map { SilenceItem(it.startSec, it.endSec, it.use) }) })
    }
    var panelOpen by remember { mutableStateOf(resume?.analysis != null) }
    var restored by remember { mutableStateOf(resume != null) }
    var manualOpen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var saveOpen by remember { mutableStateOf(false) }
    var exitAsk by remember { mutableStateOf(false) }
    var draftNote by remember { mutableStateOf<String?>(null) }
    var lastSavedAt by remember { mutableStateOf(initialDraft?.savedAt) }
    var sensitivity by remember { mutableStateOf(SilenceDetector.Sensitivity.NORMAL) }
    var minSilence by remember { mutableStateOf(3) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun change(next: StudioState) { history.push(next); state = history.current }
    val model = state.model
    val posSec = (scrub ?: (playback.positionMs / 1000.0)).coerceIn(0.0, dur)
    val kept = model.kept(dur)
    val hasWork = model.regions.isNotEmpty() || analysis != null

    // The position is published often while this editor is open, and playback skips what will be removed.
    DisposableEffect(Unit) { onFastTick(true); onDispose { onFastTick(false) } }
    LaunchedEffect(playback.positionMs, playback.playing) {
        if (playback.playing && scrub == null && model.isRemoved(playback.positionMs / 1000.0, dur, toleranceSec = 0.15)) {
            val next = model.nextKeptStart(playback.positionMs / 1000.0, dur)
            if (next == null) onPause() else onSeekMs((next * 1000).toInt())
        }
    }
    LaunchedEffect(startAtSec) { if (startAtSec != null) onSeekMs((startAtSec * 1000).toInt()) }

    fun currentDraft() = TrimDraftState.of(
        model, state.selStart, state.selEnd, posSec, zoomIdx,
        analysis?.let { a -> TrimDraftState.DraftAnalysis(a.noiseDb, a.minSilence, a.items.map { TrimDraftState.DraftSilence(it.startSec, it.endSec, it.use) }) },
        recordingTitle, kind, true,
    )

    // Autosave 1.5 s after the last change, and once more when the app goes to the background.
    val clockFormat = remember { com.inteldes.app.ui.util.Wib.formatter("d MMM yyyy HH.mm") }
    suspend fun saveNow() {
        if (!hasWork) { onDraftClear(); draftNote = null; return }
        draftNote = "Menyimpan draf…"
        val server = runCatching { onDraftSave(currentDraft()) }.getOrNull()
        lastSavedAt = System.currentTimeMillis()
        draftNote = if (server == false) "Draf tersimpan di HP, gagal tersimpan di server" else "Draf tersimpan ${clockFormat.format(Date(lastSavedAt!!))} WIB"
    }
    LaunchedEffect(state, zoomIdx, analysis) {
        delay(1500)
        saveNow()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The observer lives as long as the screen, so it must call the CURRENT saver, not the one from the first composition
    // (that one would see an empty editor and wipe the draft).
    val stopSaver = androidx.compose.runtime.rememberUpdatedState<() -> Unit>({ scope.launch { saveNow() } })
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) stopSaver.value() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun leave() { if (hasWork) exitAsk = true else onDismiss() }

    Dialog(
        onDismissRequest = { if (saveOpen) saveOpen = false else leave() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg).statusBarsPadding().imePadding()) {
            // ── top bar
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text("Batal", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable { leave() }.padding(horizontal = 10.dp, vertical = 10.dp))
                Spacer(Modifier.weight(1f))
                IconText("↶", history.canUndo) { state = history.undo().also { } }
                IconText("↷", history.canRedo) { state = history.redo() }
                Spacer(Modifier.width(6.dp))
                val canSave = model.canApply(dur) && (model.regions.isNotEmpty() || model.uncovered != RegionMode.KEEP)
                Text(
                    "Simpan sebagai…", color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                    modifier = Modifier.background(if (canSave) IdColor.Accent else IdColor.Neutral400).clickable(enabled = canSave) { saveOpen = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(recordingTitle, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 2)
                Text(
                    "Durasi asli ${formatClockD(dur)}" + (draftNote?.let { " · $it" } ?: ""),
                    color = if (draftNote?.contains("gagal") == true) IdColor.Accent else IdColor.Neutral600, fontSize = 11.sp,
                )
                if (restored) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(
                            "Melanjutkan draf yang disimpan" + (lastSavedAt?.let { " ${clockFormat.format(Date(it))} WIB" } ?: "") + ".",
                            fontSize = 11.5.sp, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "MULAI DARI AWAL", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                history.clear(StudioState(TrimModel(), 0.0, dur)); state = history.current
                                analysis = null; panelOpen = false; restored = false; onDraftClear(); draftNote = null
                            }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ── the big waveform
                if (waveform.data == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(IdColor.Neutral100), contentAlignment = Alignment.Center) {
                        Text(
                            waveform.error ?: if (waveform.loading) "Memuat gelombang… ${(waveform.progress * 100).toInt()}%" else "Gelombang belum ada",
                            color = if (waveform.error != null) IdColor.Accent else IdColor.Neutral700, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    val data = waveform.data
                    val ref = remember(data) { WaveformMath.reference(data) }
                    val winLen = min(ZOOMS[zoomIdx].toDouble(), dur)
                    MainWave(
                        data, ref, dur, winLen, posSec, model, kept, state, analysis?.items.orEmpty().takeIf { panelOpen }.orEmpty(), marks,
                        onSeek = { sec -> scrub = null; onSeekMs((sec * 1000).toInt()) },
                        onScrub = { scrub = it },
                        onScrubEnd = { sec -> scrub = null; onSeekMs((sec * 1000).toInt()) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClockD(posSec) + " / " + formatClockD(dur), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = zoomIdx < ZOOMS.lastIndex) { zoomIdx++ }.padding(horizontal = 14.dp, vertical = 4.dp))
                    Text("${ZOOMS[zoomIdx]} dtk", fontSize = 11.sp, color = IdColor.Neutral700)
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = zoomIdx > 0) { zoomIdx-- }.padding(horizontal = 14.dp, vertical = 4.dp))
                }

                // ── play + set the selection at the playhead
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        "Mulai di sini", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(IdColor.Neutral100).clickable {
                            val s = posSec.coerceIn(0.0, dur)
                            change(state.copy(selStart = s, selEnd = if (state.selEnd - s < 0.2) dur else state.selEnd))
                        }.padding(vertical = 14.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(64.dp).background(IdColor.Neutral900, androidx.compose.foundation.shape.CircleShape).clickable(onClick = onTogglePlay),
                    ) { Text(if (playback.playing) "❚❚" else "▶", color = IdColor.White, fontSize = 22.sp) }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Selesai di sini", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(IdColor.Neutral100).clickable {
                            val e = posSec.coerceIn(0.0, dur)
                            change(state.copy(selEnd = e, selStart = if (e - state.selStart < 0.2) 0.0 else state.selStart))
                        }.padding(vertical = 14.dp),
                    )
                }

                // ── overview of the whole recording
                if (waveform.data != null) {
                    Overview(
                        waveform.data, dur, posSec, model, state, analysis?.items.orEmpty().takeIf { panelOpen }.orEmpty(),
                        onSeek = { sec -> onSeekMs((sec * 1000).toInt()) },
                        onSelection = { a, b -> change(state.copy(selStart = a, selEnd = b)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hasil: ${formatClockD(model.keptSec(dur))} dipertahankan dalam ${kept.size} bagian · ${formatClockD(model.removedSec(dur))} dihapus",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (!model.canApply(dur)) IdColor.Accent else IdColor.Neutral800,
                )
                if (!model.canApply(dur)) Text("Sisakan setidaknya satu bagian.", color = IdColor.Accent, fontSize = 11.sp)
                Text(
                    "Pilihan ${formatClockD(state.selStart)} – ${formatClockD(state.selEnd)} (${formatClockD(max(0.0, state.selEnd - state.selStart))})",
                    fontSize = 11.5.sp, color = IdColor.Neutral700,
                )

                // ── three actions
                Spacer(Modifier.height(10.dp))
                val selOk = state.selEnd - state.selStart >= 0.2
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionCard("Deteksi hening", "▤", true, Modifier.weight(1f)) { panelOpen = !panelOpen }
                    ActionCard("Pertahankan", "✓", selOk, Modifier.weight(1f)) {
                        val m = model.addRegion(state.selStart, state.selEnd, RegionMode.KEEP, dur)
                        change(StudioState(m, min(state.selEnd, dur), dur))
                    }
                    ActionCard("Hapus", "✕", selOk, Modifier.weight(1f)) {
                        val m = model.addRegion(state.selStart, state.selEnd, RegionMode.CUT, dur)
                        change(StudioState(m, min(state.selEnd, dur), dur))
                    }
                }

                // ── silence analysis panel
                if (panelOpen) {
                    Spacer(Modifier.height(12.dp))
                    SilencePanel(
                        analysis = analysis, dur = dur, sensitivity = sensitivity, minSilence = minSilence, analyzing = analyzing, error = analysisError,
                        onSensitivity = { sensitivity = it }, onMinSilence = { minSilence = it },
                        onAnalyze = {
                            analyzing = true; analysisError = null
                            onAnalyzeSilences(sensitivity.noiseDb, minSilence) { r ->
                                analyzing = false
                                r.fold(
                                    onSuccess = { found -> analysis = Analysis(sensitivity.noiseDb, minSilence, found.map { SilenceItem(it.startSec, it.endSec, true) }) },
                                    onFailure = { analysisError = it.message ?: "Gagal menganalisis audio." },
                                )
                            }
                        },
                        onToggle = { i -> analysis = analysis?.let { a -> Analysis(a.noiseDb, a.minSilence, a.items.mapIndexed { n, s -> if (n == i) s.copy(use = !s.use) else s }) } },
                        onAll = { on -> analysis = analysis?.let { a -> Analysis(a.noiseDb, a.minSilence, a.items.map { it.copy(use = on) }) } },
                        onListen = { s -> onSeekMs(((s.startSec - 1.0).coerceAtLeast(0.0) * 1000).toInt()); if (!playback.playing) onTogglePlay() },
                        onApply = {
                            analysis?.let { a -> change(StudioState(SilenceCuts.apply(model, a.items, dur), state.selStart, state.selEnd)) }
                            analysis = null; panelOpen = false
                        },
                    )
                }

                // ── marked areas
                Spacer(Modifier.height(14.dp))
                SectionLabel("AREA BERTANDA (${model.regions.size})")
                Spacer(Modifier.height(6.dp))
                if (model.regions.isEmpty()) {
                    Text("Belum ada. Atur pilihan lalu ketuk Pertahankan atau Hapus.", color = IdColor.Neutral600, fontSize = 12.sp)
                }
                model.regions.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(IdColor.Neutral100)) {
                        Text(
                            if (r.mode == RegionMode.KEEP) "PERTAHANKAN" else "HAPUS", color = IdColor.White, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.background(if (r.mode == RegionMode.KEEP) Color(0xFF27A06B) else IdColor.Neutral900).padding(horizontal = 7.dp, vertical = 12.dp),
                        )
                        Text(
                            "${formatClockD(r.start)} – ${formatClockD(r.end)} · ${formatClockD(r.length)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        MiniAction("Dengar") { onSeekMs((r.start * 1000).toInt()); if (!playback.playing) onTogglePlay() }
                        MiniAction("Ubah") { change(StudioState(model.removeRegionAt(i), r.start, r.end)) }
                        MiniAction("Buang", accent = true) { change(state.copy(model = model.removeRegionAt(i))) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("BAGIAN YANG BELUM DITANDAI")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("Dipertahankan", model.uncovered == RegionMode.KEEP) { change(state.copy(model = model.withUncovered(RegionMode.KEEP))) }
                    SelChip("Dihapus", model.uncovered == RegionMode.CUT) { change(state.copy(model = model.withUncovered(RegionMode.CUT))) }
                }

                // ── manual times
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { manualOpen = !manualOpen }.padding(vertical = 6.dp)) {
                    SectionLabel("ATUR WAKTU PILIHAN SECARA MANUAL")
                    Spacer(Modifier.weight(1f))
                    Text(if (manualOpen) "–" else "+", color = IdColor.Accent, fontWeight = FontWeight.Bold)
                }
                if (manualOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeField("Dari", state.selStart, Modifier.weight(1f)) { v -> change(state.copy(selStart = v.coerceIn(0.0, dur), selEnd = max(state.selEnd, v.coerceIn(0.0, dur)))) }
                        TimeField("Sampai", state.selEnd, Modifier.weight(1f)) { v -> change(state.copy(selEnd = v.coerceIn(0.0, dur), selStart = min(state.selStart, v.coerceIn(0.0, dur)))) }
                    }
                    Text("Contoh: 1:02:05 atau 23:10.5", color = IdColor.Neutral600, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (exitAsk) {
        AlertDialog(
            onDismissRequest = { exitAsk = false },
            title = { Text("Keluar dari editor potong?", fontWeight = FontWeight.Bold) },
            text = { Text("Pekerjaan Anda belum disimpan sebagai rekaman. Draf dapat dilanjutkan nanti.", fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                Text("SIMPAN DRAF DAN KELUAR", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable {
                    exitAsk = false
                    scope.launch { saveNow(); onDismiss() }
                }.padding(10.dp))
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Lanjut mengedit", color = IdColor.Neutral800, fontSize = 12.sp, modifier = Modifier.clickable { exitAsk = false }.padding(10.dp))
                    Text("Buang", color = IdColor.Neutral700, fontSize = 12.sp, modifier = Modifier.clickable { exitAsk = false; onDraftClear(); onDismiss() }.padding(10.dp))
                }
            },
        )
    }

    if (saveOpen) {
        TrimSaveDialog(
            kept = kept, durationSec = dur, sourceTitle = recordingTitle, templates = templates,
            defaultTemplateId = defaultTemplateId, defaultEngine = defaultEngine,
            onDismiss = { saveOpen = false }, onSave = onSave,
            onSaved = { id, now -> saveOpen = false; onSaved(id, now) },
        )
    }
}

// ───────────────────────── waveform drawing ─────────────────────────

@Composable
private fun MainWave(
    data: WaveformData, ref: Float, dur: Double, winLen: Double, pos: Double, model: TrimModel, kept: List<Span>, state: StudioState,
    silences: List<SilenceItem>, marks: List<RecordingMark>,
    onSeek: (Double) -> Unit, onScrub: (Double) -> Unit, onScrubEnd: (Double) -> Unit,
) {
    val density = LocalDensity.current
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B5750.toInt() } }
    val winStart = (pos - winLen / 2).coerceIn(0.0, max(0.0, dur - winLen))
    var dragPos by remember { mutableStateOf(pos) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth().height(210.dp).background(Color.White).border(1.dp, IdColor.Neutral300)
            .pointerInput(winStart, winLen) {
                detectTapGestures { o -> onSeek((winStart + o.x / size.width * winLen).coerceIn(0.0, dur)) }
            }
            .pointerInput(winLen) {
                detectDragGestures(
                    onDragStart = { dragPos = pos },
                    onDragEnd = { onScrubEnd(dragPos) },
                    onDragCancel = { onScrubEnd(dragPos) },
                ) { change, drag ->
                    change.consume()
                    // dragging right moves the wave right, so the playhead goes earlier
                    dragPos = (dragPos - drag.x / size.width * winLen).coerceIn(0.0, dur)
                    onScrub(dragPos)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val ruler = with(density) { 24.dp.toPx() }
        fun x(t: Double) = ((t - winStart) / winLen * w).toFloat()
        val winEnd = winStart + winLen

        // areas
        for (r in model.regions) {
            if (r.end < winStart || r.start > winEnd) continue
            val x1 = x(r.start).coerceIn(0f, w); val x2 = x(r.end).coerceIn(0f, w)
            if (r.mode == RegionMode.KEEP) drawRect(KEEP_TINT, Offset(x1, ruler), Size(x2 - x1, h - ruler))
            else hatch(x1, x2, ruler, h)
        }
        for (s in silences.filter { it.use }) {
            if (s.endSec < winStart || s.startSec > winEnd) continue
            val x1 = x(s.startSec).coerceIn(0f, w); val x2 = x(s.endSec).coerceIn(0f, w)
            drawRect(SILENCE_TINT, Offset(x1, ruler), Size(x2 - x1, h - ruler))
        }
        // selection frame
        if (state.selEnd > state.selStart && state.selEnd >= winStart && state.selStart <= winEnd) {
            val x1 = x(state.selStart).coerceIn(0f, w); val x2 = x(state.selEnd).coerceIn(0f, w)
            drawRect(IdColor.Accent.copy(alpha = 0.10f), Offset(x1, ruler), Size(x2 - x1, h - ruler))
            drawRect(IdColor.Accent, Offset(x1, ruler), Size(2f, h - ruler))
            drawRect(IdColor.Accent, Offset(x2 - 2f, ruler), Size(2f, h - ruler))
        }
        // bars
        val barW = with(density) { 2.dp.toPx() }
        val gap = with(density) { 1.dp.toPx() }
        val n = (w / (barW + gap)).toInt().coerceAtLeast(1)
        val heights = WaveformMath.columns(data, ref, winStart, winEnd, n)
        val mid = ruler + (h - ruler) / 2
        val maxH = (h - ruler) * 0.9f
        for (c in 0 until n) {
            val t = winStart + (c + 0.5) / n * winLen
            val removed = kept.none { t >= it.start && t < it.end }
            val inSel = t >= state.selStart && t <= state.selEnd
            val color = if (removed) BAR_REMOVED else if (inSel) BAR_SELECTED else BAR_NORMAL
            val bx = c * (barW + gap)
            val f = heights[c]
            if (f < WaveformMath.DOT) drawRect(color, Offset(bx, mid - 1f), Size(barW, 2f))
            else drawRect(color, Offset(bx, mid - f * maxH / 2), Size(barW, f * maxH))
        }
        // marks
        for (m in marks) {
            if (m.timestampSec < winStart || m.timestampSec > winEnd) continue
            val mx = x(m.timestampSec.toDouble())
            drawRect(IdColor.Accent, Offset(mx - 1f, ruler), Size(2f, h - ruler))
            drawRect(IdColor.Accent, Offset(mx, ruler), Size(with(density) { 10.dp.toPx() }, with(density) { 7.dp.toPx() }))
        }
        // ruler
        drawRect(Color(0xFFF3F2F2), Offset.Zero, Size(w, ruler))
        val step = niceStep(winLen * with(density) { 64.dp.toPx() } / w)
        var t = Math.ceil(winStart / step) * step
        labelPaint.textSize = with(density) { 10.sp.toPx() }
        while (t <= winEnd) {
            val tx = x(t)
            drawRect(Color(0xFF8A8585), Offset(tx, ruler - 6f), Size(1f, 6f))
            drawIntoCanvas { it.nativeCanvas.drawText(formatClockD(t, withFraction = false), tx + 3f, ruler - 9f, labelPaint) }
            t += step
        }
        // playhead
        val px = x(pos)
        drawRect(IdColor.Text, Offset(px - 1.5f, ruler), Size(3f, h - ruler))
        drawCircle(IdColor.Text, with(density) { 5.dp.toPx() }, Offset(px, ruler + 2f))
        drawCircle(IdColor.Text, with(density) { 5.dp.toPx() }, Offset(px, h - 2f))
    }
}

private fun DrawScope.hatch(x1: Float, x2: Float, top: Float, bottom: Float) {
    drawRect(Color(0x26201E1D), Offset(x1, top), Size(x2 - x1, bottom - top))
    var x = x1 - (bottom - top)
    while (x < x2) {
        val ax = max(x1, x); val bx = min(x2, x + (bottom - top))
        if (bx > ax) drawLine(Color(0x40201E1D), Offset(ax, bottom - (ax - x)), Offset(bx, bottom - (bx - x)), strokeWidth = 1.5f)
        x += 12f
    }
}

private fun niceStep(minSec: Double): Double = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0).firstOrNull { it >= minSec } ?: 3600.0

@Composable
private fun Overview(
    data: WaveformData, dur: Double, pos: Double, model: TrimModel, state: StudioState, silences: List<SilenceItem>,
    onSeek: (Double) -> Unit, onSelection: (Double, Double) -> Unit,
) {
    val density = LocalDensity.current
    val ref = remember(data) { WaveformMath.reference(data) }
    var dragSel by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var which by remember { mutableStateOf(0) }
    val sel = dragSel ?: (state.selStart to state.selEnd)
    Canvas(
        modifier = Modifier
            .fillMaxWidth().height(72.dp).background(Color.White).border(1.dp, IdColor.Neutral300)
            .pointerInput(dur) { detectTapGestures { o -> onSeek((o.x / size.width * dur).coerceIn(0.0, dur)) } }
            .pointerInput(state.selStart, state.selEnd, dur) {
                val grab = 24.dp.toPx()
                detectDragGestures(
                    onDragStart = { o ->
                        val a = (state.selStart / dur * size.width).toFloat(); val b = (state.selEnd / dur * size.width).toFloat()
                        which = when {
                            abs(o.x - a) <= grab && abs(o.x - a) <= abs(o.x - b) -> 1
                            abs(o.x - b) <= grab -> 2
                            else -> 0
                        }
                        dragSel = state.selStart to state.selEnd
                    },
                    onDragEnd = { dragSel?.let { (a, b) -> if (which != 0) onSelection(a, b) }; dragSel = null },
                    onDragCancel = { dragSel = null },
                ) { change, drag ->
                    change.consume()
                    val d = drag.x / size.width * dur
                    dragSel = dragSel?.let { (a, b) ->
                        when (which) {
                            1 -> min(max(0.0, a + d), b - 0.2) to b
                            2 -> a to max(min(dur, b + d), a + 0.2)
                            else -> a to b
                        }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val strip = with(density) { 6.dp.toPx() }
        fun x(t: Double) = (t / dur * w).toFloat()
        val n = (w / 2f).toInt().coerceAtLeast(1)
        val heights = WaveformMath.columns(data, ref, 0.0, dur, n)
        val mid = (h - strip) / 2
        val kept = model.kept(dur)
        for (c in 0 until n) {
            val t = (c + 0.5) / n * dur
            val removed = kept.none { t >= it.start && t < it.end }
            val color = if (removed) BAR_REMOVED else BAR_NORMAL
            val f = heights[c]
            val hh = max(2f, f * (h - strip) * 0.9f)
            drawRect(color, Offset(c * (w / n), mid - hh / 2), Size(max(1f, w / n - 0.5f), hh))
        }
        for (s in silences.filter { it.use }) drawRect(SILENCE_TINT, Offset(x(s.startSec), 0f), Size(max(2f, x(s.endSec) - x(s.startSec)), h - strip))
        // area strip: green = keep, dark = cut
        for (r in model.regions) drawRect(if (r.mode == RegionMode.KEEP) Color(0xFF27A06B) else IdColor.Neutral900, Offset(x(r.start), h - strip), Size(max(2f, x(r.end) - x(r.start)), strip))
        // selection frame with two handles
        val a = x(sel.first); val b = x(sel.second)
        drawRect(IdColor.Accent.copy(alpha = 0.12f), Offset(a, 0f), Size(max(2f, b - a), h - strip))
        drawRect(IdColor.Accent, Offset(a, 0f), Size(max(2f, b - a), 2f))
        drawRect(IdColor.Accent, Offset(a, h - strip - 2f), Size(max(2f, b - a), 2f))
        val hw = with(density) { 10.dp.toPx() }
        drawRect(IdColor.Accent, Offset(a - hw / 2, 0f), Size(hw, h - strip))
        drawRect(IdColor.Accent, Offset(b - hw / 2, 0f), Size(hw, h - strip))
        drawRect(Color.White, Offset(a - 1f, h * 0.25f), Size(2f, h * 0.3f))
        drawRect(Color.White, Offset(b - 1f, h * 0.25f), Size(2f, h * 0.3f))
        drawRect(IdColor.Text, Offset(x(pos) - 1f, 0f), Size(2f, h))
    }
}

// ───────────────────────── silence panel ─────────────────────────

@Composable
private fun SilencePanel(
    analysis: Analysis?, dur: Double, sensitivity: SilenceDetector.Sensitivity, minSilence: Int, analyzing: Boolean, error: String?,
    onSensitivity: (SilenceDetector.Sensitivity) -> Unit, onMinSilence: (Int) -> Unit, onAnalyze: () -> Unit,
    onToggle: (Int) -> Unit, onAll: (Boolean) -> Unit, onListen: (SilenceItem) -> Unit, onApply: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0x142B6CB0)).padding(12.dp)) {
        SectionLabel("HASIL ANALISA HENING")
        Spacer(Modifier.height(4.dp))
        Text("Pilih bagian hening yang akan dihapus. Setengah detik di tiap sisi tetap dipertahankan supaya kata tidak terpotong.", fontSize = 11.sp, color = IdColor.Neutral700, lineHeight = 15.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SilenceDetector.Sensitivity.entries.forEach { SelChip("${it.label} ${it.noiseDb} dB", sensitivity == it) { onSensitivity(it) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hening minimal", fontSize = 11.sp, color = IdColor.Neutral700)
            listOf(2, 3, 5, 10).forEach { SelChip("$it dtk", minSilence == it) { onMinSilence(it) } }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (analyzing) "MENGANALISIS…" else if (analysis == null) "ANALISIS" else "ANALISIS ULANG",
            color = IdColor.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(if (analyzing) IdColor.Neutral400 else IdColor.Neutral900).clickable(enabled = !analyzing, onClick = onAnalyze).padding(vertical = 11.dp),
        )
        error?.let { Text(it, color = IdColor.Accent, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (analysis != null) {
            val items = analysis.items
            val used = items.filter { it.use }
            val total = items.sumOf { it.length }
            // what would really be removed after the half-second margins
            val removed = used.sumOf { max(0.0, it.length - 2 * SilenceCuts.PAD) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCard("${items.size}", "bagian hening", Modifier.weight(1f))
                StatCard(formatClockD(total, withFraction = false), "${(total / dur * 100).toInt()}% dari rekaman", Modifier.weight(1f))
                StatCard(formatClockD(max(0.0, dur - removed), withFraction = false), "sisa bila diterapkan", Modifier.weight(1f))
            }
            if (items.isEmpty()) {
                Text("Tidak ada bagian hening dengan pengaturan ini. Coba kepekaan Berisik atau hening minimal lebih pendek.", fontSize = 11.5.sp, color = IdColor.Neutral700, modifier = Modifier.padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                Row {
                    MiniAction("Pilih semua") { onAll(true) }
                    MiniAction("Kosongkan") { onAll(false) }
                }
                items.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(Color.White)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp).background(if (s.use) IdColor.Accent else IdColor.Neutral200).clickable { onToggle(i) },
                        ) { if (s.use) Text("✓", color = IdColor.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Text(
                            "${formatClockD(s.startSec, withFraction = false)} – ${formatClockD(s.endSec, withFraction = false)}  ·  ${"%.0f".format(s.length)} dtk",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable { onToggle(i) }.padding(10.dp),
                        )
                        MiniAction("Dengar") { onListen(s) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tandai ${used.size} bagian hening sebagai Hapus",
                    color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(if (used.isEmpty()) IdColor.Neutral400 else IdColor.Accent).clickable(enabled = used.isNotEmpty(), onClick = onApply).padding(vertical = 12.dp),
                )
            }
        }
    }
}

// ───────────────────────── save dialog ─────────────────────────

@Composable
private fun TrimSaveDialog(
    kept: List<Span>, durationSec: Double, sourceTitle: String, templates: List<NoteTemplate>,
    defaultTemplateId: String, defaultEngine: WhisperEngine,
    onDismiss: () -> Unit,
    onSave: (List<Span>, String, NoteTemplate, WhisperEngine, (Result<String>) -> Unit) -> Unit,
    onSaved: (String, Boolean) -> Unit,
) {
    var typedTitle by remember { mutableStateOf<String?>(null) }
    var templateId by remember { mutableStateOf(defaultTemplateId) }
    var engine by remember { mutableStateOf(defaultEngine) }
    var processNow by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val template = templates.firstOrNull { it.id == templateId } ?: templates.firstOrNull()
    val title = typedTitle ?: TrimPlan.defaultTitle(sourceTitle, kept.map { TimeRange(Math.round(it.start).toInt(), Math.round(it.end).toInt()) })

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Simpan sebagai rekaman baru", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Hasil ${formatClockD(kept.sumOf { it.length })} dari ${formatClockD(durationSec)} dalam ${kept.size} bagian. Rekaman asli tidak berubah.",
                    color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel("NAMA REKAMAN BARU")
                Spacer(Modifier.height(6.dp))
                IdTextField(value = title, onValueChange = { typedTitle = it }, placeholder = "Nama rekaman")
                Spacer(Modifier.height(14.dp))
                SectionLabel("TEMPLATE DOKUMEN")
                Spacer(Modifier.height(6.dp))
                TemplateChips(templates, templateId) { templateId = it.id }
                template?.let { Text(it.description, color = IdColor.Neutral700, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp)) }
                Spacer(Modifier.height(14.dp))
                SectionLabel("MESIN TRANSKRIP")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("On-device", engine == WhisperEngine.ON_DEVICE) { engine = WhisperEngine.ON_DEVICE }
                    SelChip("Awan", engine == WhisperEngine.CLOUD) { engine = WhisperEngine.CLOUD }
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel("SETELAH DISIMPAN")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("Proses sekarang", processNow) { processNow = true }
                    SelChip("Simpan saja dulu", !processNow) { processNow = false }
                }
                error?.let { Text(it, color = IdColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = {
            Text(
                if (saving) "MEMOTONG…" else if (processNow) "SIMPAN & PROSES" else "SIMPAN",
                color = if (saving || template == null) IdColor.Neutral400 else IdColor.Accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = !saving && template != null) {
                    saving = true; error = null
                    onSave(kept, title, template!!, engine) { r ->
                        saving = false
                        r.fold(onSuccess = { onSaved(it, processNow) }, onFailure = { error = it.message ?: "Gagal memotong audio." })
                    }
                }.padding(10.dp),
            )
        },
        dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(enabled = !saving, onClick = onDismiss).padding(10.dp)) },
    )
}

// ───────────────────────── small pieces ─────────────────────────

@Composable
private fun IconText(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(glyph, fontSize = 22.sp, color = if (enabled) IdColor.Text else IdColor.Neutral400, modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
}

@Composable
private fun SelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) IdColor.White else IdColor.Neutral800, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.background(if (selected) IdColor.Accent else IdColor.Neutral200).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun MiniAction(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (accent) IdColor.Accent else IdColor.Neutral800, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 12.dp))
}

@Composable
private fun ActionCard(label: String, glyph: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.background(IdColor.Neutral100).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
    ) {
        Text(glyph, fontSize = 20.sp, color = if (enabled) IdColor.Text else IdColor.Neutral400)
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (enabled) IdColor.Text else IdColor.Neutral400)
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.background(Color.White).padding(vertical = 8.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, fontSize = 9.5.sp, color = IdColor.Neutral600, textAlign = TextAlign.Center)
    }
}

/** A time field that accepts "mm:ss", "h:mm:ss" and "mm:ss.d"; a value that does not parse is ignored. */
@Composable
private fun TimeField(label: String, valueSec: Double, modifier: Modifier, onValid: (Double) -> Unit) {
    var text by remember(valueSec) { mutableStateOf(formatClockD(valueSec)) }
    Column(modifier) {
        Text(label, color = IdColor.Neutral600, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = text,
            onValueChange = { new -> text = new.filter { it.isDigit() || it == ':' || it == '.' }; parseClockD(text)?.let(onValid) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = IdColor.Text, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}
