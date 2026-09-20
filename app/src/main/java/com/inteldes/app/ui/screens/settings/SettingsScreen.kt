package com.inteldes.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.theme.IdColor

@Composable
fun SettingsScreen(factory: androidx.lifecycle.ViewModelProvider.Factory, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
                Text("Pengaturan", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                KickerLabel("Agent AI", modifier = Modifier.padding(bottom = 10.dp))

                ProviderRow("Otomatis (disarankan)", "IntelDes memilih agent sesuai panjang rapat dan sisa kuota.", state.settings.provider == AiProvider.AUTO) { vm.setProvider(AiProvider.AUTO) }
                Spacer(Modifier.height(8.dp))
                ProviderRow("Gemini 2.5 Pro", "Konteks panjang — bagus untuk rapat di atas 90 menit.", state.settings.provider == AiProvider.GEMINI) { vm.setProvider(AiProvider.GEMINI) }
                Spacer(Modifier.height(8.dp))
                ProviderRow("Claude Sonnet", "Ringkasan lebih rapi dan konsisten untuk berita acara.", state.settings.provider == AiProvider.CLAUDE) { vm.setProvider(AiProvider.CLAUDE) }

                Spacer(Modifier.height(8.dp))
                SettingRow(
                    "Mode hemat token",
                    "Pakai model AI versi ringan (Gemini Flash / Claude Haiku) — lebih murah, ringkasan sedikit kurang detail",
                    if (state.settings.economyMode) "Aktif" else "Nonaktif",
                ) { vm.setEconomyMode(!state.settings.economyMode) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { vm.toggleAdvanced() }
                        .padding(vertical = 11.dp),
                ) {
                    Text("OPSI LANJUTAN", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.advancedOpen) "–" else "+", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)

                if (state.advancedOpen) {
                    Column(modifier = Modifier.background(IdColor.Neutral100).padding(14.dp)) {
                        ApiKeyField("API key Gemini", state.keys.geminiKey, onSave = vm::saveGeminiKey)
                        Spacer(Modifier.height(14.dp))
                        ApiKeyField("API key Claude", state.keys.claudeKey, onSave = vm::saveClaudeKey)
                        Spacer(Modifier.height(14.dp))
                        ApiKeyField("API key transkripsi awan (Deepgram)", state.keys.whisperKey, onSave = vm::saveWhisperKey)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Disimpan terenkripsi di perangkat. Biaya ditagih langsung ke akun milik desa.",
                            color = IdColor.Neutral700,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
                Spacer(Modifier.height(16.dp))

                KickerLabel("Transkripsi Whisper", modifier = Modifier.padding(bottom = 10.dp))
                Row {
                    EngineChoice("On-device", "Segera hadir", enabled = false, selected = false, modifier = Modifier.weight(1f)) {}
                    Spacer(Modifier.width(1.dp))
                    EngineChoice("Awan", "Whisper large + label pembicara", enabled = true, selected = state.settings.defaultEngine == WhisperEngine.CLOUD, modifier = Modifier.weight(1f)) {
                        vm.setDefaultEngine(WhisperEngine.CLOUD)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pilihan ini jadi bawaan; tetap bisa diganti di layar rekam.",
                    color = IdColor.Neutral600,
                    fontSize = 11.5.sp,
                )

                Spacer(Modifier.height(18.dp))

                SettingRow(
                    "Panjang notulensi",
                    "Gaya ringkas atau detail saat AI menyusun notulensi",
                    if (state.settings.densityDetailed) "Detail" else "Ringkas",
                ) { vm.setDensityDetailed(!state.settings.densityDetailed) }
                SettingRow(
                    "Simpan audio asli",
                    "Nonaktifkan untuk hapus audio otomatis setelah diproses",
                    if (state.settings.keepOriginalAudio) "Aktif" else "Nonaktif",
                ) { vm.setKeepOriginalAudio(!state.settings.keepOriginalAudio) }
                SettingRow(
                    "Label pembicara",
                    "Daftar nama perangkat desa untuk dipilih cepat saat labeli pembicara",
                    if (state.settings.speakerRoster.isEmpty()) "Atur daftar" else "${state.settings.speakerRoster.size} nama",
                ) { vm.openRosterDialog() }
                SettingRow("Ekspor otomatis", "Kirim notulensi ke grup WhatsApp desa", "Nonaktif", onClick = null)
                SettingRow("Sinkron Google Drive", "Folder Arsip Rapat Desa", "Belum diatur", onClick = null)
                SettingRow(
                    "Bahasa keluaran",
                    "Bahasa notulensi dan jawaban Tanya AI",
                    state.settings.outputLanguage.label,
                ) {
                    val next = if (state.settings.outputLanguage == OutputLanguage.INDONESIA) OutputLanguage.ENGLISH else OutputLanguage.INDONESIA
                    vm.setOutputLanguage(next)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (state.rosterDialogOpen) {
        SpeakerRosterDialog(
            roster = state.settings.speakerRoster,
            onAdd = vm::addSpeakerRosterName,
            onRemove = vm::removeSpeakerRosterName,
            onDismiss = vm::closeRosterDialog,
        )
    }
}

@Composable
private fun SpeakerRosterDialog(
    roster: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daftar perangkat desa", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Nama-nama di sini muncul sebagai pilihan cepat saat memperbaiki label pembicara di Transkrip.",
                    color = IdColor.Neutral700,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                if (roster.isEmpty()) {
                    Text("Belum ada nama tersimpan.", color = IdColor.Neutral600, fontSize = 12.sp)
                } else {
                    roster.forEach { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        ) {
                            Text(name, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                            Text(
                                "HAPUS",
                                color = IdColor.Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                modifier = Modifier.clickable { onRemove(name) }.padding(6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        textStyle = TextStyle(fontSize = 13.sp, color = IdColor.Text),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(9.dp)) {
                                if (newName.isEmpty()) Text("Nama perangkat desa baru", color = IdColor.Neutral600, fontSize = 13.sp)
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
                            if (newName.isNotBlank()) { onAdd(newName); newName = "" }
                        }.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Text("SELESAI", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

@Composable
private fun ProviderRow(name: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) IdColor.Neutral900 else IdColor.Bg)
            .clickable(onClick = onClick)
            .padding(13.dp),
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = if (selected) IdColor.Bg else IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(3.dp))
            Text(desc, color = if (selected) IdColor.Bg.copy(alpha = 0.8f) else IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .width(14.dp).height(14.dp)
            .background(if (selected) IdColor.Accent else androidx.compose.ui.graphics.Color.Transparent)
            .let { if (!selected) it.border(BorderStroke(1.5.dp, IdColor.Neutral600)) else it },
    )
}

@Composable
private fun EngineChoice(label: String, note: String, enabled: Boolean, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(if (selected) IdColor.Accent else IdColor.Bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(label, color = if (selected) IdColor.White else if (enabled) IdColor.Text else IdColor.Neutral500, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(note, color = if (selected) IdColor.White.copy(alpha = 0.9f) else IdColor.Neutral600, fontSize = 10.5.sp)
    }
}

@Composable
private fun SettingRow(label: String, desc: String, value: String, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 13.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = IdColor.Neutral700, fontSize = 11.5.sp)
        }
        Text(value, color = IdColor.Accent, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

@Composable
private fun ApiKeyField(label: String, currentValue: String, onSave: (String) -> Unit) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var visible by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp)
            Spacer(Modifier.weight(1f))
            Pill(
                if (currentValue.isNotBlank()) "Terhubung" else "Belum diatur",
                background = if (currentValue.isNotBlank()) IdColor.Accent else IdColor.Bg,
                contentColor = if (currentValue.isNotBlank()) IdColor.White else IdColor.Neutral700,
                bordered = currentValue.isBlank(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(IdColor.Bg).padding(10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(fontSize = 13.sp, color = IdColor.Text),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (visible) "SEMBUNYIKAN" else "LIHAT",
                color = IdColor.Neutral600,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { visible = !visible }.padding(horizontal = 6.dp),
            )
            Text(
                "SIMPAN",
                color = IdColor.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onSave(value) }.padding(start = 4.dp),
            )
        }
    }
}
