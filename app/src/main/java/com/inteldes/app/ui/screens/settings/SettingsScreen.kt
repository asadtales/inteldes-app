package com.inteldes.app.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.components.Pill
import com.inteldes.app.ui.theme.IdColor

@Composable
fun SettingsScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenTemplates: () -> Unit,
    onLogin: () -> Unit,
    onApproveWebLogin: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val serverSession by vm.serverSession.collectAsStateWithLifecycle()
    val webState by vm.webSyncState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun authFailure(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        vm.onDriveAuthError(
            if (code == 10 || code == 12500) "Aplikasi belum terdaftar di Google Cloud (OAuth client). Lihat panduan penyiapan Drive."
            else "Gagal menghubungkan Google: ${e.message ?: e.javaClass.simpleName}",
        )
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        try {
            vm.onDriveAuthorized(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken)
        } catch (e: ApiException) {
            authFailure(e)
        }
    }
    val connectDrive = {
        Identity.getAuthorizationClient(context).authorize(vm.authRequest())
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                else vm.onDriveAuthorized(r.accessToken)
            }
            .addOnFailureListener { authFailure(it) }
        Unit
    }

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
                SettingRow(
                    "Proses otomatis setelah rekam",
                    "Nonaktifkan untuk hanya menyimpan rekaman dulu tanpa langsung transkrip — berguna saat belum ada internet atau ingin hemat kuota API. Proses bisa dimulai manual kapan saja dari halaman rekaman.",
                    if (state.settings.autoProcessAfterRecording) "Aktif" else "Nonaktif",
                ) { vm.setAutoProcessAfterRecording(!state.settings.autoProcessAfterRecording) }
                SettingRow(
                    "Tandai dengan tombol volume",
                    "Saat layar rekam terbuka, tekan tombol volume juga menandai momen saat itu (seperti tombol rana kamera) — volume tetap berubah seperti biasa. Tidak berfungsi saat layar mati; gunakan tombol TANDAI di notifikasi untuk itu.",
                    if (state.settings.markWithVolumeButton) "Aktif" else "Nonaktif",
                ) { vm.setMarkWithVolumeButton(!state.settings.markWithVolumeButton) }

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
                        KeySlot("API key Gemini", state.keys.geminiEmbedded, state.keys.geminiKey, vm::saveGeminiKey)
                        Spacer(Modifier.height(14.dp))
                        KeySlot("API key Claude", state.keys.claudeEmbedded, state.keys.claudeKey, vm::saveClaudeKey)
                        Spacer(Modifier.height(14.dp))
                        KeySlot("API key transkripsi awan (Deepgram)", state.keys.whisperEmbedded, state.keys.whisperKey, vm::saveWhisperKey)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Kunci yang tertanam di aplikasi dipakai otomatis. Kunci yang diketik di sini disimpan terenkripsi di perangkat dan hanya dipakai bila aplikasi tidak membawa kunci itu.",
                            color = IdColor.Neutral700,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
                Spacer(Modifier.height(16.dp))

                KickerLabel("Template dokumen", modifier = Modifier.padding(bottom = 10.dp))
                SettingRow(
                    "Template bawaan",
                    "Bentuk dokumen hasil AI: notulensi, berita acara, atau template buatan sendiri",
                    state.defaultTemplateName + "  ›",
                ) { onOpenTemplates() }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
                Spacer(Modifier.height(16.dp))

                KickerLabel("Akun & web", modifier = Modifier.padding(bottom = 10.dp))
                AccountSection(
                    serverSession, webState, onLogin = onLogin, onApprove = onApproveWebLogin, onLogout = vm::logoutServer,
                    onWebSync = vm::setWebSync, onWebSyncNow = vm::webSyncNow, onConfirmOrg = vm::confirmOrgSwitch, onDeclineOrg = vm::declineOrgSwitch,
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
                Spacer(Modifier.height(16.dp))

                KickerLabel("Sinkronisasi Google Drive", modifier = Modifier.padding(bottom = 10.dp))
                DriveSection(
                    sync = sync,
                    onConnect = connectDrive,
                    onSyncNow = vm::syncNow,
                    onDisconnect = vm::disconnectDrive,
                    onToggleAuto = { vm.setAutoSync(!sync.autoSync) },
                    onToggleWifi = { vm.setWifiOnly(!sync.wifiOnly) },
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
                Spacer(Modifier.height(16.dp))

                KickerLabel("Transkripsi Whisper", modifier = Modifier.padding(bottom = 10.dp))
                Row {
                    EngineChoice("On-device", "Tanpa internet, satu pembicara", enabled = true, selected = state.settings.defaultEngine == WhisperEngine.ON_DEVICE, modifier = Modifier.weight(1f)) {
                        vm.setDefaultEngine(WhisperEngine.ON_DEVICE)
                    }
                    Spacer(Modifier.width(1.dp))
                    EngineChoice("Awan", "Whisper large + label pembicara", enabled = true, selected = state.settings.defaultEngine == WhisperEngine.CLOUD, modifier = Modifier.weight(1f)) {
                        vm.setDefaultEngine(WhisperEngine.CLOUD)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device mengunduh model (≈140MB) sekali di awal, lalu bekerja tanpa internet. Belum bisa memisahkan label pembicara.",
                    color = IdColor.Neutral600,
                    fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pilihan ini jadi bawaan; tetap bisa diganti di layar rekam.",
                    color = IdColor.Neutral600,
                    fontSize = 11.5.sp,
                )

                Spacer(Modifier.height(18.dp))

                Text("Panjang notulensi", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Gaya ringkas, sedang, atau detail saat AI menyusun notulensi",
                    color = IdColor.Neutral700,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row {
                    NotulensiDensity.entries.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.width(1.dp))
                        EngineChoice(d.label, d.desc, enabled = true, selected = state.settings.density == d, modifier = Modifier.weight(1f)) {
                            vm.setDensity(d)
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
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

@Composable
private fun DriveSection(
    sync: com.inteldes.app.data.sync.SyncSettings,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleAuto: () -> Unit,
    onToggleWifi: () -> Unit,
) {
    Column {
        if (!sync.connected) {
            Text(
                "Cadangkan rekaman, transkrip, notulensi, dan foto ke Google Drive milikmu sendiri, dan buka di perangkat lain dengan akun yang sama. " +
                    "Setiap pengguna / perangkat menghubungkan akun Google-nya masing-masing. IntelDes hanya bisa melihat berkas yang dibuatnya sendiri (folder INTELDES).",
                color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            DriveButton("HUBUNGKAN GOOGLE DRIVE", filled = true, onClick = onConnect)
        } else {
            Text("Terhubung", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
            Text(sync.account ?: "Akun Google", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    sync.running -> sync.progress ?: "Menyinkronkan…"
                    sync.lastSyncAt != null -> "Terakhir sinkron: " + java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(sync.lastSyncAt))
                    else -> "Belum pernah sinkron"
                },
                color = IdColor.Neutral700, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) { DriveButton(if (sync.running) "MENYINKRONKAN…" else "SINKRONKAN SEKARANG", filled = true, enabled = !sync.running, onClick = onSyncNow) }
                Box(modifier = Modifier.weight(0.6f)) { DriveButton("PUTUSKAN", filled = false, onClick = onDisconnect) }
            }
            SettingRow("Sinkron otomatis", "Setelah rekaman selesai diproses, saat aplikasi dibuka, dan setelah menutup rekaman.", if (sync.autoSync) "Aktif" else "Nonaktif", onToggleAuto)
            SettingRow("Hanya lewat Wi-Fi", "Cegah unggah audio besar memakai kuota seluler.", if (sync.wifiOnly) "Aktif" else "Nonaktif", onToggleWifi)
            Text(
                "Menghapus rekaman di satu perangkat ikut menghapusnya dari Drive dan perangkat lain. Perubahan kategori yang dihapus tidak disinkronkan.",
                color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        sync.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = IdColor.Accent, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun DriveButton(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (!enabled) IdColor.Neutral300 else if (filled) IdColor.Accent else IdColor.Neutral100)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
    ) {
        Text(
            label,
            color = if (filled && enabled) IdColor.White else IdColor.Text,
            fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp,
        )
    }
}

/** A key that ships inside the app needs no input; otherwise the usual entry field is shown. */
@Composable
private fun KeySlot(label: String, embedded: Boolean, current: String, onSave: (String) -> Unit) {
    if (embedded) {
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Spacer(Modifier.height(3.dp))
            Text("Sudah tertanam di aplikasi — tidak perlu diisi.", color = IdColor.Neutral700, fontSize = 12.sp)
        }
    } else {
        ApiKeyField(label, current, onSave = onSave)
    }
}

@Composable
private fun AccountSection(
    session: com.inteldes.app.data.server.Session?,
    web: WebSyncUi,
    onLogin: () -> Unit,
    onApprove: () -> Unit,
    onLogout: () -> Unit,
    onWebSync: (Boolean) -> Unit,
    onWebSyncNow: () -> Unit,
    onConfirmOrg: () -> Unit,
    onDeclineOrg: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmEnable by remember { mutableStateOf(false) }
    Column {
        if (session == null) {
            Text(
                "Masuk ke server IntelDes untuk memakai versi web dan sinkron antar perangkat. " +
                    "Tanpa masuk, aplikasi tetap berfungsi penuh di HP ini (mode lokal).",
                color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            DriveButton("MASUK KE SERVER", filled = true, onClick = onLogin)
        } else {
            Text("Masuk sebagai", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
            Text("${session.name} · ${if (session.isPersonal) "Akun pribadi" else session.roleLabel}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (session.isPersonal) "Arsip terpisah, hanya milik Anda" else session.orgName,
                color = IdColor.Neutral700, fontSize = 12.sp,
            )
            if (session.superadmin) Text("Superadmin platform (kelola di web)", color = IdColor.Neutral600, fontSize = 11.5.sp)
            Text(session.email, color = IdColor.Neutral600, fontSize = 11.5.sp)
            Spacer(Modifier.height(10.dp))
            DriveButton("MASUK WEB DENGAN QR", filled = true, onClick = onApprove)
            Spacer(Modifier.height(14.dp))
            WebSyncCard(web, canWrite = session.role != "pembaca", onEnable = { confirmEnable = true }, onDisable = { onWebSync(false) }, onNow = onWebSyncNow, onConfirmOrg = onConfirmOrg, onDeclineOrg = onDeclineOrg)
            Spacer(Modifier.height(14.dp))
            DriveButton("KELUAR DARI SERVER", filled = false, onClick = { confirmLogout = true })
        }
    }
    if (confirmEnable) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Aktifkan sinkron ke web?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Rekaman di HP ini (audio, transkrip, notulensi, penanda, foto, chat, template, konteks) akan dikirim ke server " +
                        (if (session?.isPersonal == true) "akun pribadi ${session.name}" else "organisasi ${session?.orgName.orEmpty()}") +
                        ", dan rekaman dari web diambil ke sini.\n\n" +
                        "Tidak ada rekaman di HP yang dihapus oleh sinkron ini. Rekaman yang dihapus di web tetap ada di HP. " +
                        "Sinkron Google Drive tidak berubah.",
                    fontSize = 13.sp, color = IdColor.Neutral700, lineHeight = 18.sp,
                )
            },
            confirmButton = { Text("AKTIFKAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { confirmEnable = false; onWebSync(true) }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { confirmEnable = false }.padding(10.dp)) },
        )
    }
    if (confirmLogout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Keluar dari server?", fontWeight = FontWeight.Bold) },
            text = { Text("HP ini dicabut dari server dan token dihapus. Rekaman di HP tidak dihapus.", fontSize = 13.sp, color = IdColor.Neutral700) },
            confirmButton = { Text("KELUAR", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { confirmLogout = false; onLogout() }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { confirmLogout = false }.padding(10.dp)) },
        )
    }
}

@Composable
private fun WebSyncCard(
    web: WebSyncUi,
    canWrite: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onNow: () -> Unit,
    onConfirmOrg: () -> Unit,
    onDeclineOrg: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().border(1.dp, IdColor.Neutral300).padding(12.dp)) {
        Text("SINKRON KE WEB", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        if (!canWrite) {
            Text("Peran pembaca hanya melihat. Rekaman HP ini tidak dikirim ke web.", color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp)
            return@Column
        }
        Text(
            if (web.enabled) "Aktif" else "Mati - rekaman hanya ada di HP ini",
            fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        if (web.progress != null) {
            Text(web.progress, color = IdColor.Accent, fontSize = 12.sp)
        } else if (web.enabled) {
            val whenText = if (web.lastRunAt > 0) {
                android.text.format.DateUtils.getRelativeTimeSpanString(web.lastRunAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
            } else "belum pernah"
            Text("Terakhir: $whenText", color = IdColor.Neutral700, fontSize = 12.sp)
            Text(
                "Terkirim ${web.pushed} · Diterima ${web.pulled} · Gagal ${web.failed}",
                color = if (web.failed > 0) IdColor.Accent else IdColor.Neutral700, fontSize = 12.sp,
            )
            if (web.summary.isNotBlank() && web.summary != "Selesai.") Text(web.summary, color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        if (web.otherOrg != null) {
            Spacer(Modifier.height(8.dp))
            val personal = web.otherOrgKind == "personal"
            Text(
                if (personal) {
                    "Akun ini adalah akun pribadi \"${web.otherOrg}\" (arsip terpisah milik satu pengguna), berbeda dari yang terakhir dipakai HP ini. " +
                        "Rekaman HP belum dikirim. Kirim semua rekaman HP ini ke arsip pribadi tersebut? Rekaman organisasi lain di HP ikut masuk bila salah."
                } else {
                    "Akun ini milik organisasi \"${web.otherOrg}\", berbeda dari yang terakhir dipakai HP ini. " +
                        "Rekaman HP belum dikirim. Kirim ke organisasi ini? Data dua desa bisa tercampur bila salah."
                },
                color = IdColor.Accent, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            DriveButton(if (personal) "YA, KIRIM KE AKUN PRIBADI INI" else "YA, KIRIM KE ORGANISASI INI", filled = true, onClick = onConfirmOrg)
            Spacer(Modifier.height(6.dp))
            DriveButton("TIDAK, JANGAN KIRIM (MATIKAN SINKRON)", filled = false, onClick = onDeclineOrg)
        }
        Spacer(Modifier.height(10.dp))
        if (web.enabled) {
            DriveButton("SINKRONKAN SEKARANG", filled = true, onClick = onNow)
            Spacer(Modifier.height(8.dp))
            DriveButton("MATIKAN SINKRON WEB", filled = false, onClick = onDisable)
        } else {
            DriveButton("AKTIFKAN SINKRON KE WEB", filled = true, onClick = onEnable)
        }
    }
}
