package com.inteldes.app.ui.screens.contexts

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.ContextSourceKind
import com.inteldes.app.data.model.KnowledgeContext
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.ui.components.IdTextField
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatRelativeDay

// ───────────────────────── list of contexts ─────────────────────────

@Composable
fun ContextsScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val vm: ContextsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<KnowledgeContext?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
            Text("Konteks", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        }
        HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    "Gabungkan notulensi dan transkrip dari beberapa rapat menjadi satu pengetahuan yang tersimpan. " +
                        "Teksnya bisa diedit bebas, ditanyakan ke AI, dan diperbaiki dengan bantuan AI. " +
                        "Konteks juga bisa dilampirkan saat bertanya di tab Tanya AI pada sebuah rekaman.",
                    color = IdColor.Neutral700, fontSize = 12.5.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(18.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            }
            if (state.contexts.isEmpty()) {
                item {
                    Text("Belum ada konteks. Buat yang pertama dari rekaman yang sudah ada.", color = IdColor.Neutral600, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
                }
            }
            items(state.contexts, key = { it.id }) { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${c.sources.size} rekaman · ${c.wordCount} kata · diubah ${formatRelativeDay(c.updatedAt)}",
                            color = IdColor.Neutral700, fontSize = 11.5.sp,
                        )
                    }
                    IconButton(onClick = { toDelete = c }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Hapus konteks", tint = IdColor.Neutral600, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
            }
        }

        Column(modifier = Modifier.background(IdColor.Neutral100).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().background(IdColor.Accent).clickable { creating = true }.padding(vertical = 16.dp),
            ) {
                Text("BUAT KONTEKS BARU", color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.6.sp)
            }
        }
    }

    if (creating) {
        MergePickerDialog(
            title = "Buat konteks baru",
            recordings = state.recordings,
            askName = true,
            onDismiss = { creating = false },
            onConfirm = { name, ids, kind ->
                creating = false
                vm.create(name, ids, kind, onCreated = onOpen)
            },
        )
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus konteks “${c.name}”?", fontWeight = FontWeight.Bold) },
            text = { Text("Hanya konteks ini yang dihapus. Rekaman, transkrip, dan notulensi aslinya tetap ada.", color = IdColor.Neutral700, fontSize = 13.sp) },
            confirmButton = { Text("HAPUS", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { vm.delete(c.id); toDelete = null }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { toDelete = null }.padding(10.dp)) },
        )
    }
}

/** Pick recordings (and what of each to merge: notulensi, transkrip, or both). */
@Composable
internal fun MergePickerDialog(
    title: String,
    recordings: List<Recording>,
    askName: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, ids: List<String>, kind: ContextSourceKind) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ContextSourceKind.BOTH) }
    var picked by remember { mutableStateOf(listOf<String>()) }   // keeps tap order = merge order
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (askName) {
                    IdTextField(value = name, onValueChange = { name = it }, placeholder = "Nama konteks, mis. Rapat jalan dusun 2")
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    ContextSourceKind.entries.forEach { k ->
                        val on = kind == k
                        Text(
                            k.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                            color = if (on) IdColor.White else IdColor.Neutral800,
                            modifier = Modifier.background(if (on) IdColor.Accent else IdColor.Neutral200).clickable { kind = k }.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                KickerLabel("Pilih rekaman (${picked.size})")
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(recordings, key = { it.id }) { r ->
                        val order = picked.indexOf(r.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { picked = if (order >= 0) picked - r.id else picked + r.id }.padding(vertical = 9.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(22.dp).background(if (order >= 0) IdColor.Accent else IdColor.Neutral200),
                            ) {
                                if (order >= 0) Text("${order + 1}", color = IdColor.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(r.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                                Text(
                                    formatRelativeDay(r.createdAt) + " · " + when (r.status) {
                                        RecordingStatus.DONE -> "notulensi siap"
                                        RecordingStatus.WORKING -> "diproses"
                                        else -> "belum diproses"
                                    },
                                    color = IdColor.Neutral600, fontSize = 11.sp,
                                )
                            }
                        }
                        HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Urutan angka = urutan penggabungan.", color = IdColor.Neutral600, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Text(
                "GABUNGKAN",
                color = if (picked.isEmpty()) IdColor.Neutral500 else IdColor.Accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = picked.isNotEmpty()) { onConfirm(name, picked, kind) }.padding(10.dp),
            )
        },
        dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp)) },
    )
}

// ───────────────────────── one context ─────────────────────────

private enum class ContextTab(val label: String) { TEXT("ISI"), ASK("TANYA AI") }

@Composable
fun ContextDetailScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: ContextDetailViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(ContextTab.TEXT) }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    val context = state.context
    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
            IconButton(onClick = { vm.flush(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
            Column(modifier = Modifier.weight(1f).clickable { renaming = true }) {
                Text(context?.name ?: "", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1)
                Text(
                    "${context?.sources?.size ?: 0} rekaman · ${state.draft.split(Regex("\\s+")).count { it.isNotBlank() }} kata · " + if (state.saved) "tersimpan" else "menyimpan…",
                    color = IdColor.Neutral600, fontSize = 11.sp,
                )
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Hapus konteks", tint = IdColor.Neutral700) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ContextTab.entries.forEach { t ->
                val on = tab == t
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).background(if (on) IdColor.Neutral900 else androidx.compose.ui.graphics.Color.Transparent).clickable { tab = t }.padding(vertical = 13.dp),
                ) {
                    Text(t.label, color = if (on) IdColor.Bg else IdColor.Neutral800, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                }
            }
        }
        HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        when (tab) {
            ContextTab.TEXT -> ContextTextTab(state, vm, onAdd = { adding = true }, modifier = Modifier.weight(1f))
            ContextTab.ASK -> ContextAskTab(state, vm, modifier = Modifier.weight(1f))
        }
    }

    if (renaming && context != null) {
        var name by remember { mutableStateOf(context.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nama konteks", fontWeight = FontWeight.Bold) },
            text = { IdTextField(value = name, onValueChange = { name = it }, placeholder = "Nama konteks") },
            confirmButton = { Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { vm.rename(name); renaming = false }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { renaming = false }.padding(10.dp)) },
        )
    }
    if (adding) {
        MergePickerDialog(
            title = "Tambah rekaman ke konteks",
            recordings = state.recordings,
            askName = false,
            onDismiss = { adding = false },
            onConfirm = { _, ids, kind -> adding = false; vm.addRecordings(ids, kind) },
        )
    }
    if (confirmDelete && context != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus konteks “${context.name}”?", fontWeight = FontWeight.Bold) },
            text = { Text("Rekaman dan notulensi aslinya tetap ada.", color = IdColor.Neutral700, fontSize = 13.sp) },
            confirmButton = { Text("HAPUS", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { confirmDelete = false; vm.delete(onBack) }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { confirmDelete = false }.padding(10.dp)) },
        )
    }
}

@Composable
private fun ContextTextTab(state: ContextDetailState, vm: ContextDetailViewModel, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        val sources = state.context?.sources.orEmpty()
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { s ->
                    Text(
                        s.recordingTitle.take(28) + " · " + s.kind.label,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = IdColor.Neutral800,
                        modifier = Modifier.background(IdColor.Neutral100).padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            BasicTextField(
                value = state.draft,
                onValueChange = vm::edit,
                textStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, color = IdColor.Text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                decorationBox = { inner ->
                    if (state.draft.isEmpty()) Text("Isi konteks…", color = IdColor.Neutral600, fontSize = 13.5.sp)
                    inner()
                },
            )
        }
        Column(modifier = Modifier.background(IdColor.Neutral100).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(
                "TAMBAH REKAMAN",
                color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp,
                modifier = Modifier.clickable(onClick = onAdd).padding(vertical = 6.dp),
            )
        }
    }
}

private val ASK_SUGGESTIONS = listOf("Apa saja keputusan yang sudah diambil?", "Apa yang masih belum tuntas?", "Rangkum perkembangan dari rapat ke rapat")
private val REVISE_SUGGESTIONS = listOf("Rapikan dan hapus pengulangan", "Perbaiki ejaan dan istilah", "Ringkas menjadi poin-poin")

@Composable
private fun ContextAskTab(state: ContextDetailState, vm: ContextDetailViewModel, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var reviseMode by remember { mutableStateOf(false) }
    val send: (String) -> Unit = { text -> if (reviseMode) vm.revise(text) else vm.ask(text) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Keep the newest message, the revision proposal and its TERAPKAN/BUANG buttons in view.
    androidx.compose.runtime.LaunchedEffect(state.chat.size, state.thinking, state.proposal, state.canUndo) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 18.dp)) {
            if (state.chat.isEmpty()) {
                item {
                    Text(
                        "Tanya apa saja tentang isi konteks ini, atau pilih “Perbaiki konteks” dan beri instruksi — AI mengusulkan hasil revisinya, " +
                            "dan baru berlaku setelah kamu menekan TERAPKAN.",
                        color = IdColor.Neutral700, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
            }
            items(state.chat) { m ->
                if (m.isUser) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                        Text(m.text, color = IdColor.Bg, fontSize = 13.5.sp, modifier = Modifier.background(IdColor.Neutral900).padding(horizontal = 13.dp, vertical = 10.dp))
                    }
                } else {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(m.text, fontSize = 13.5.sp, lineHeight = 20.sp)
                    }
                    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (state.thinking) item { Text("membaca konteks…", color = IdColor.Neutral600, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp)) }
            state.proposal?.let { proposal ->
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).background(IdColor.Neutral100).padding(12.dp)) {
                        KickerLabel("Usulan perubahan")
                        Spacer(Modifier.height(6.dp))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).background(IdColor.Accent).clickable { vm.applyProposal() }.padding(vertical = 12.dp)) {
                                Text("TERAPKAN", color = IdColor.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).background(IdColor.Bg).clickable { vm.discardProposal() }.padding(vertical = 12.dp)) {
                                Text("BUANG", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(proposal.take(1500) + if (proposal.length > 1500) "\n…(${proposal.length - 1500} karakter lagi)" else "", fontSize = 12.5.sp, lineHeight = 18.sp, color = IdColor.Neutral800)
                    }
                }
            }
            if (state.canUndo) {
                item {
                    Text(
                        "Perubahan sudah diterapkan. BATALKAN PERUBAHAN",
                        color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clickable { vm.undoApply() }.padding(vertical = 10.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.background(IdColor.Neutral100).padding(top = 10.dp, bottom = 12.dp)) {
            Row(modifier = Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "Tanya", true to "Perbaiki konteks").forEach { (mode, label) ->
                    val on = reviseMode == mode
                    Text(
                        label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        color = if (on) IdColor.White else IdColor.Neutral800,
                        modifier = Modifier.background(if (on) IdColor.Accent else IdColor.Neutral200).clickable { reviseMode = mode }.padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (reviseMode) REVISE_SUGGESTIONS else ASK_SUGGESTIONS).forEach { p ->
                    Text(
                        p, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(IdColor.Bg).clickable(enabled = !state.thinking) { send(p) }.padding(horizontal = 10.dp, vertical = 6.dp),
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
                        if (input.isEmpty()) Text(if (reviseMode) "Instruksi perbaikan, mis. “gabungkan keputusan yang sama”" else "Tanya soal isi konteks…", color = IdColor.Neutral600, fontSize = 13.sp)
                        inner()
                    },
                )
                Box(
                    modifier = Modifier.size(32.dp).background(IdColor.Accent).clickable(enabled = input.isNotBlank() && !state.thinking) {
                        send(input.trim()); input = ""
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim", tint = IdColor.White, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
