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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
    val choice by vm.syncChoice.collectAsStateWithLifecycle()
    val serverAi by vm.serverAi.collectAsStateWithLifecycle()
    LaunchedEffect(serverSession) { vm.refreshServerAi() }
    var pickingSync by rememberSaveable { mutableStateOf(false) }
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

    // One section open at a time keeps the screen short; the open one survives rotation.
    var open by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(webState.otherOrg) { if (webState.otherOrg != null) open = "akun" }
    fun toggle(key: String) { open = if (open == key) "" else key }
    val s = state.settings
    val onOff = { v: Boolean -> if (v) "aktif" else "mati" }

    LazyColumn(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
                Text("Pengaturan", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
        }

        item {
            val me = serverSession
            SettingsSection(
                "Akun & sinkron web",
                when {
                    me == null -> "Belum masuk · mode lokal"
                    webState.otherOrg != null -> "Perlu konfirmasi: akun berbeda dari yang terakhir dipakai"
                    else -> "${me.name} · ${if (me.isPersonal) "Akun pribadi" else me.orgName} · " +
                        if (!webState.enabled) "sinkron web mati" else if (webState.pullOnly) "hanya menerima dari web" else "sinkron web aktif"
                },
                open == "akun", alert = webState.otherOrg != null, onToggle = { toggle("akun") },
            ) {
                AccountSection(
                    serverSession, webState, onLogin = onLogin, onApprove = onApproveWebLogin, onLogout = vm::logoutServer,
                    onWebSync = vm::setWebSync, onWebSyncNow = vm::webSyncNow, onConfirmOrg = vm::confirmOrgSwitch,
                    onDeclineOrg = vm::declineOrgSwitch, onAllowPush = vm::allowPush,
                )
                if (webState.enabled) SyncChoiceRow(choice, "web", onClick = { pickingSync = true })
            }
        }

        item {
            SettingsSection(
                "Perekaman",
                "Proses otomatis ${onOff(s.autoProcessAfterRecording)} · Tombol volume ${onOff(s.markWithVolumeButton)} · Audio asli ${if (s.keepOriginalAudio) "disimpan" else "dihapus"}",
                open == "rekam", onToggle = { toggle("rekam") },
            ) {
                SettingRow(
                    "Proses otomatis setelah rekam",
                    "Nonaktifkan untuk hanya menyimpan rekaman dulu tanpa langsung transkrip — berguna saat belum ada internet atau ingin hemat kuota API. Proses bisa dimulai manual kapan saja dari halaman rekaman.",
                    if (s.autoProcessAfterRecording) "Aktif" else "Nonaktif",
                ) { vm.setAutoProcessAfterRecording(!s.autoProcessAfterRecording) }
                SettingRow(
                    "Tandai dengan tombol volume",
                    "Saat layar rekam terbuka, tekan tombol volume juga menandai momen saat itu (seperti tombol rana kamera) — volume tetap berubah seperti biasa. Tidak berfungsi saat layar mati; gunakan tombol TANDAI di notifikasi untuk itu.",
                    if (s.markWithVolumeButton) "Aktif" else "Nonaktif",
                ) { vm.setMarkWithVolumeButton(!s.markWithVolumeButton) }
                SettingRow(
                    "Simpan audio asli",
                    "Nonaktifkan untuk hapus audio otomatis setelah diproses",
                    if (s.keepOriginalAudio) "Aktif" else "Nonaktif",
                ) { vm.setKeepOriginalAudio(!s.keepOriginalAudio) }
            }
        }

        item {
            SettingsSection(
                "Transkripsi",
                (if (s.defaultEngine == WhisperEngine.ON_DEVICE) "On-device" else "Awan") + " · " +
                    (if (s.speakerRoster.isEmpty()) "daftar pembicara kosong" else "${s.speakerRoster.size} nama pembicara"),
                open == "transkrip", onToggle = { toggle("transkrip") },
            ) {
                Text("Mesin bawaan", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    EngineChoice("On-device", "Tanpa internet, satu pembicara", enabled = true, selected = s.defaultEngine == WhisperEngine.ON_DEVICE, modifier = Modifier.weight(1f)) {
                        vm.setDefaultEngine(WhisperEngine.ON_DEVICE)
                    }
                    Spacer(Modifier.width(1.dp))
                    EngineChoice("Awan", "Whisper large + label pembicara", enabled = true, selected = s.defaultEngine == WhisperEngine.CLOUD, modifier = Modifier.weight(1f)) {
                        vm.setDefaultEngine(WhisperEngine.CLOUD)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device mengunduh model (≈140MB) sekali di awal, lalu bekerja tanpa internet. Belum bisa memisahkan label pembicara. Pilihan ini jadi bawaan; tetap bisa diganti di layar rekam.",
                    color = IdColor.Neutral600, fontSize = 10.5.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                SettingRow(
                    "Label pembicara",
                    "Daftar nama perangkat desa untuk dipilih cepat saat labeli pembicara",
                    if (s.speakerRoster.isEmpty()) "Atur daftar" else "${s.speakerRoster.size} nama",
                ) { vm.openRosterDialog() }
            }
        }

        item {
            SettingsSection(
                "Notulensi & AI",
                "${s.provider.label.substringBefore(" (")} · ${s.density.label} · ${s.outputLanguage.label} · ${state.defaultTemplateName}",
                open == "ai", onToggle = { toggle("ai") },
            ) {
                serverAi?.let { ServerAiCard(it, serverSession?.isPersonal == true) }
                Text("Agent AI", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Spacer(Modifier.height(8.dp))
                ProviderRow("Otomatis (disarankan)", "IntelDes memilih agent sesuai panjang rapat dan sisa kuota.", s.provider == AiProvider.AUTO) { vm.setProvider(AiProvider.AUTO) }
                Spacer(Modifier.height(8.dp))
                ProviderRow("Gemini 2.5 Pro", "Konteks panjang — bagus untuk rapat di atas 90 menit.", s.provider == AiProvider.GEMINI) { vm.setProvider(AiProvider.GEMINI) }
                Spacer(Modifier.height(8.dp))
                ProviderRow("Claude Sonnet", "Ringkasan lebih rapi dan konsisten untuk berita acara.", s.provider == AiProvider.CLAUDE) { vm.setProvider(AiProvider.CLAUDE) }
                SettingRow(
                    "Mode hemat token",
                    "Pakai model AI versi ringan (Gemini Flash / Claude Haiku) — lebih murah, ringkasan sedikit kurang detail",
                    if (s.economyMode) "Aktif" else "Nonaktif",
                ) { vm.setEconomyMode(!s.economyMode) }

                Spacer(Modifier.height(14.dp))
                Text("Panjang notulensi", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Text("Gaya ringkas, sedang, atau detail saat AI menyusun notulensi", color = IdColor.Neutral700, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Row {
                    NotulensiDensity.entries.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.width(1.dp))
                        EngineChoice(d.label, d.desc, enabled = true, selected = s.density == d, modifier = Modifier.weight(1f)) { vm.setDensity(d) }
                    }
                }
                Spacer(Modifier.height(13.dp))
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                SettingRow(
                    "Template bawaan",
                    "Bentuk dokumen hasil AI: notulensi, berita acara, atau template buatan sendiri",
                    state.defaultTemplateName + "  ›",
                ) { onOpenTemplates() }
                SettingRow("Bahasa keluaran", "Bahasa notulensi dan jawaban Tanya AI", s.outputLanguage.label) {
                    vm.setOutputLanguage(if (s.outputLanguage == OutputLanguage.INDONESIA) OutputLanguage.ENGLISH else OutputLanguage.INDONESIA)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.toggleAdvanced() }.padding(vertical = 11.dp),
                ) {
                    Text("KUNCI API (LANJUTAN)", fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.advancedOpen) "–" else "+", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                if (state.advancedOpen) {
                    Column(modifier = Modifier.background(IdColor.Neutral100).padding(14.dp)) {
                        ApiKeyField("API key Gemini", state.keys.geminiKey, vm::saveGeminiKey)
                        Spacer(Modifier.height(14.dp))
                        ApiKeyField("API key Claude", state.keys.claudeKey, vm::saveClaudeKey)
                        Spacer(Modifier.height(14.dp))
                        ApiKeyField("API key transkripsi awan (Deepgram)", state.keys.whisperKey, vm::saveWhisperKey)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Aplikasi tidak membawa kunci apa pun. Kunci yang diketik di sini disimpan terenkripsi di perangkat ini dan hanya dipakai untuk memproses langsung dari HP, tanpa server.",
                            color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                "Google Drive",
                if (!sync.connected) "Belum terhubung" else "${sync.account ?: "Akun Google"} · sinkron otomatis ${onOff(sync.autoSync)}",
                open == "drive", onToggle = { toggle("drive") },
            ) {
                DriveSection(
                    sync = sync,
                    onConnect = connectDrive,
                    onSyncNow = vm::syncNow,
                    onDisconnect = vm::disconnectDrive,
                    onToggleAuto = { vm.setAutoSync(!sync.autoSync) },
                    onToggleWifi = { vm.setWifiOnly(!sync.wifiOnly) },
                )
                if (sync.connected) SyncChoiceRow(choice, "drive", autoOff = !sync.autoSync, onClick = { pickingSync = true })
            }
        }

        item {
            SettingsSection(
                "Tentang",
                "Versi ${com.inteldes.app.BuildConfig.VERSION_NAME} · Asterix Inovasi Teknologi",
                open == "tentang", onToggle = { toggle("tentang") },
            ) { AboutSection() }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickingSync) {
        SyncPickerDialog(choice, onToggle = vm::setSynced, onAll = vm::setAllSynced, onDismiss = { pickingSync = false })
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
private fun AccountSection(
    session: com.inteldes.app.data.server.Session?,
    web: WebSyncUi,
    onLogin: () -> Unit,
    onApprove: () -> Unit,
    onLogout: () -> Unit,
    onWebSync: (Boolean) -> Unit,
    onWebSyncNow: () -> Unit,
    onConfirmOrg: (Boolean) -> Unit,
    onDeclineOrg: () -> Unit,
    onAllowPush: () -> Unit,
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
            WebSyncCard(web, canWrite = session.role != "pembaca", onEnable = { confirmEnable = true }, onDisable = { onWebSync(false) }, onNow = onWebSyncNow, onConfirmOrg = onConfirmOrg, onDeclineOrg = onDeclineOrg, onAllowPush = onAllowPush)
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
    onConfirmOrg: (Boolean) -> Unit,
    onDeclineOrg: () -> Unit,
    onAllowPush: () -> Unit,
) {
    var confirmPush by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, IdColor.Neutral300).padding(12.dp)) {
        Text("SINKRON KE WEB", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        if (!canWrite) {
            Text("Peran pembaca hanya melihat. Rekaman HP ini tidak dikirim ke web.", color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp)
            return@Column
        }
        Text(
            if (!web.enabled) "Mati - rekaman hanya ada di HP ini" else if (web.pullOnly) "Aktif · hanya menerima dari server" else "Aktif",
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
                        "Rekaman HP belum dikirim."
                } else {
                    "Akun ini milik organisasi \"${web.otherOrg}\", berbeda dari yang terakhir dipakai HP ini. Rekaman HP belum dikirim."
                },
                color = IdColor.Accent, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pilih \"Hanya ambil\" bila rekaman di HP ini milik arsip lain: arsip ini hanya MENERIMA dari server, tidak ada rekaman HP yang dikirim. " +
                    "Pilih \"Kirim juga\" hanya bila rekaman HP memang milik arsip ini; kalau salah, data dua arsip tercampur.",
                color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            DriveButton("HANYA AMBIL DARI SERVER (DISARANKAN)", filled = true, onClick = { onConfirmOrg(true) })
            Spacer(Modifier.height(6.dp))
            DriveButton(if (personal) "KIRIM JUGA KE AKUN PRIBADI INI" else "KIRIM JUGA KE ORGANISASI INI", filled = false, onClick = { onConfirmOrg(false) })
            Spacer(Modifier.height(6.dp))
            DriveButton("TIDAK, MATIKAN SINKRON", filled = false, onClick = onDeclineOrg)
        }
        Spacer(Modifier.height(10.dp))
        if (web.enabled) {
            if (web.pullOnly) {
                Text(
                    "Rekaman di HP ini tidak dikirim ke server; yang diambil hanya isi arsip ini.",
                    color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
            DriveButton("SINKRONKAN SEKARANG", filled = true, onClick = onNow)
            Spacer(Modifier.height(8.dp))
            if (web.pullOnly) {
                DriveButton("KIRIM REKAMAN HP JUGA…", filled = false, onClick = { confirmPush = true })
                Spacer(Modifier.height(8.dp))
            }
            DriveButton("MATIKAN SINKRON WEB", filled = false, onClick = onDisable)
        } else {
            DriveButton("AKTIFKAN SINKRON KE WEB", filled = true, onClick = onEnable)
        }
    }
    if (confirmPush) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text("Kirim rekaman HP juga?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Semua rekaman di HP ini akan dikirim ke arsip yang sedang dipakai. Pastikan rekaman itu memang milik arsip tersebut. " +
                        "Yang sudah terkirim tidak bisa ditarik dari server lewat aplikasi.",
                    fontSize = 13.sp, color = IdColor.Neutral700, lineHeight = 18.sp,
                )
            },
            confirmButton = { Text("YA, KIRIM", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { confirmPush = false; onAllowPush() }.padding(10.dp)) },
            dismissButton = { Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable { confirmPush = false }.padding(10.dp)) },
        )
    }
}

/** A collapsible group: the header always shows a one-line summary, the content only when [open]. */
@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    open: Boolean,
    alert: Boolean = false,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(if (open) IdColor.Neutral100 else IdColor.Bg).clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(summary, color = if (alert) IdColor.Accent else IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2)
            }
            Spacer(Modifier.width(10.dp))
            Text(if (open) "–" else "+", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 18.dp)) { content() }
        HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
    }
}

@Composable
private fun AboutSection() {
    Column {
        Text("IntelDes", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("Perekam rapat dan penyusun notulensi untuk pemerintahan desa.", color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(12.dp))
        AboutLine("Versi", "${com.inteldes.app.BuildConfig.VERSION_NAME} (build ${com.inteldes.app.BuildConfig.VERSION_CODE})")
        AboutLine(
            "Dibangun",
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(com.inteldes.app.BuildConfig.BUILD_TIME_MS)),
        )
        AboutLine("Kunci AI", "tidak ada di dalam aplikasi")
        AboutLine("Pengembang", "Asterix Inovasi Teknologi")
        AboutLine("Server web", com.inteldes.app.BuildConfig.SERVER_URL.removePrefix("https://"))
        Spacer(Modifier.height(14.dp))
        Text("DISCLAIMER DATA", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(6.dp))
        listOf(
            "Rekaman, transkrip, notulensi, foto, dan penanda disimpan di HP ini. Data hanya keluar dari HP bila Anda menjalankan fitur yang memerlukannya, seperti di bawah.",
            "Transkripsi awan mengirim audio rekaman ke penyedia layanan transkripsi (Deepgram). Transkripsi on-device tidak mengirim audio ke mana pun. Bila Anda memilih \"Proses di server\", audio dan teks rapat dikirim ke server IntelDes, yang memprosesnya dengan kunci milik server dan dibatasi paket kuota akun Anda.",
            "Penyusunan notulensi dan Tanya AI mengirim teks transkrip ke penyedia AI yang dipilih (Google Gemini atau Anthropic Claude).",
            "Sinkron ke web mengirim rekaman, notulensi, foto, dan penanda ke server IntelDes untuk akun yang sedang masuk. Sinkron Google Drive menyimpan salinan di Drive milik akun Google Anda.",
            "Menghapus rekaman di HP ikut menghapus salinannya di server web dan Drive bila sinkron itu aktif. Rekaman yang dihapus di web tidak dihapus dari HP. Template dan konteks yang dihapus di HP ikut dihapus di server.",
            "Kunci API yang Anda ketik disimpan terenkripsi di perangkat. Sesi login disimpan terenkripsi dan tidak ikut cadangan Android. Aplikasi ini tidak membawa kunci AI di dalamnya, dan login Google atau portal dilakukan di browser lewat server (aplikasi tidak menyimpan kunci Google).",
            "Hasil transkripsi dan notulensi dibuat otomatis oleh AI dan bisa keliru, terutama nama, angka, dan istilah setempat. Periksa dan koreksi sebelum dipakai sebagai dokumen resmi.",
            "Tautan bagikan bersifat publik: siapa pun yang memegangnya bisa mengunduh notulensi (dan transkrip atau audio bila disertakan) tanpa masuk, sampai tautan dicabut atau kedaluwarsa.",
            "Anda bertanggung jawab atas izin merekam dan kerahasiaan isi rapat, serta atas siapa yang diberi akses ke tautan bagikan atau akun web.",
        ).forEach { line ->
            Row(Modifier.padding(bottom = 7.dp)) {
                Text("•", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(14.dp))
                Text(line, color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("© Asterix Inovasi Teknologi", color = IdColor.Neutral600, fontSize = 11.sp)
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = IdColor.Neutral600, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    }
    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
}

/** One line under a sync card: how many recordings take part, and the way into the picker. */
@Composable
private fun SyncChoiceRow(choice: SyncChoiceUi, target: String, autoOff: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Rekaman yang ikut sinkron", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Text(
                    "${choice.included} dari ${choice.recordings.size} rekaman" +
                        if (autoOff) " · sinkron otomatis mati: hanya yang dicentang diproses saat Anda menekan SINKRONKAN SEKARANG" else "",
                    color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }
            Text("PILIH ›", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Checklist of this phone's recordings; unchecked ones are kept out of both the Drive and the web sync. */
@Composable
private fun SyncPickerDialog(choice: SyncChoiceUi, onToggle: (String, Boolean) -> Unit, onAll: (Boolean) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rekaman yang ikut sinkron", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text(
                    "Yang dicentang ikut sinkron ke Google Drive dan ke web. Yang tidak dicentang tetap hanya di HP ini: tidak dikirim dan tidak ditimpa dari server. " +
                        "Rekaman yang sudah terlanjur terkirim tetap ada di server.",
                    color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("PILIH SEMUA", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.clickable { onAll(true) }.padding(vertical = 6.dp, horizontal = 2.dp))
                    Spacer(Modifier.width(18.dp))
                    Text("KOSONGKAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.clickable { onAll(false) }.padding(vertical = 6.dp, horizontal = 2.dp))
                }
                HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choice.recordings, key = { it.id }) { rec ->
                        val on = rec.id !in choice.excluded
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onToggle(rec.id, !on) }.padding(vertical = 9.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(20.dp).background(if (on) IdColor.Accent else IdColor.Neutral200),
                            ) { if (on) Text("✓", color = IdColor.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rec.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(rec.createdAt)) +
                                        " · " + com.inteldes.app.ui.util.formatMmSs(rec.durationSec),
                                    color = IdColor.Neutral600, fontSize = 10.5.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Text("SELESAI", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp)) },
    )
}

/** Read-only: which AI key the SERVER uses for this account and how much of its quota is left. */
@Composable
private fun ServerAiCard(view: com.inteldes.app.data.server.ServerSettingsView, personal: Boolean) {
    val access = view.aiAccess ?: return
    val platform = access.source == "platform"
    fun of(u: com.inteldes.app.data.server.AiUsage) = "${u.used} dari ${u.limit ?: "tanpa batas"}"
    val dateFormat = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id", "ID"))
    Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(14.dp)) {
        Text("KUNCI AI DI SERVER", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        Text(if (platform) "Kunci IntelDes" else "Kunci milik ${if (personal) "akun ini" else "organisasi"}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        view.ai?.let { ready ->
            Text(
                "Siap: " + listOf("Transkripsi awan" to ready.deepgram, "Gemini" to ready.gemini, "Claude" to ready.claude)
                    .joinToString(" · ") { (name, ok) -> name + if (ok) " ✓" else " ✗" },
                color = IdColor.Neutral700, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (platform) {
            Text(
                "Paket: " + (access.plan?.name?.takeIf { it.isNotBlank() } ?: "bawaan sistem"),
                fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
            access.minutes?.let { Text("Menit audio: " + (if (it.limit == null) "${it.used} terpakai (tanpa batas)" else of(it)), color = IdColor.Neutral700, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
            Text("Pemrosesan: ${of(access.jobs)}  ·  Pertanyaan AI: ${of(access.asks)}", color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            access.resetsAt?.let { Text("Kuota pulih ${dateFormat.format(java.util.Date(it))}", color = IdColor.Neutral700, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
        } else {
            Text(
                "Tanpa paket. Pagar 24 jam terakhir: pemrosesan ${of(access.jobs)}, pertanyaan AI ${of(access.asks)}.",
                color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "Diatur di web (Pengaturan > Kunci AI), bukan di aplikasi. Kolom kunci di bawah hanya dipakai bila memproses langsung dari HP tanpa server.",
            color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
}
