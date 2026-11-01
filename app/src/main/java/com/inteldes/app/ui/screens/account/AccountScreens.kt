package com.inteldes.app.ui.screens.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.inteldes.app.data.server.PairingLink
import com.inteldes.app.ui.components.KickerLabel
import com.inteldes.app.ui.theme.IdColor
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ───────────────────────── login ─────────────────────────

@Composable
fun LoginScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    canSkip: Boolean,
    onDone: () -> Unit,
    onScanQr: () -> Unit,
    onSkip: () -> Unit,
) {
    val vm: AccountViewModel = viewModel(factory = factory)
    val state by vm.login.collectAsStateWithLifecycle()
    val ended by vm.endedByServer.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val openBrowser: (String) -> Unit = { url ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp)) {
        Text("INTELDES", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(18.dp))
        Text("Masuk ke server", fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Untuk memakai versi web dan sinkron antar perangkat. Tanpa masuk, aplikasi tetap bisa merekam dan membuat notulensi di HP ini.",
            color = IdColor.Neutral700, fontSize = 13.sp, lineHeight = 19.sp,
        )
        if (ended) {
            Spacer(Modifier.height(14.dp))
            Text("Sesi berakhir (perangkat ini dicabut atau sesi kedaluwarsa). Masuk lagi untuk melanjutkan.", color = IdColor.Accent, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))

        KickerLabel("Email")
        Spacer(Modifier.height(6.dp))
        FormField(email, { email = it }, KeyboardType.Email, secret = false, placeholder = "nama@desa.id")
        Spacer(Modifier.height(16.dp))
        KickerLabel("Sandi")
        Spacer(Modifier.height(6.dp))
        FormField(password, { password = it }, KeyboardType.Password, secret = true, placeholder = "Sandi")

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = IdColor.Accent, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(20.dp))
        BigButton(if (state.loading) "MASUK…" else "MASUK", enabled = !state.loading && email.isNotBlank() && password.isNotEmpty()) {
            vm.login(email, password, onDone)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = IdColor.Neutral300)
            Text("  atau  ", color = IdColor.Neutral600, fontSize = 12.sp)
            HorizontalDivider(modifier = Modifier.weight(1f), color = IdColor.Neutral300)
        }
        Spacer(Modifier.height(18.dp))
        if (providers.external) {
            if (providers.google) {
                BigButton("MASUK DENGAN GOOGLE", filled = false, enabled = !state.loading) { vm.startExternalLogin("google", openBrowser) }
                Spacer(Modifier.height(10.dp))
            }
            if (providers.sso) {
                BigButton("MASUK DENGAN PORTAL SSO", filled = false, enabled = !state.loading) { vm.startExternalLogin("sso", openBrowser) }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "Login dilakukan di browser lewat server IntelDes; aplikasi tidak menyimpan kunci Google atau portal.",
                color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        if ((providers.google || providers.sso) && !providers.appLogin) {
            // The server knows Google / the portal but has not switched on hand-back to the app: say so instead of hiding it silently.
            Text(
                "Masuk dengan Google atau portal dari aplikasi belum diaktifkan di server. Sementara, masuk dengan email dan sandi, atau pindai QR dari web yang sudah masuk lewat Google.",
                color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
        }
        BigButton("PINDAI QR DARI WEB", filled = false, enabled = !state.loading, onClick = onScanQr)
        if (canSkip) {
            Spacer(Modifier.height(22.dp))
            Text(
                "Lewati (mode lokal)",
                color = IdColor.Neutral700, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.skipLogin(); onSkip() }.padding(vertical = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, type: KeyboardType, secret: Boolean, placeholder: String) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = IdColor.Text),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(14.dp)) {
                if (value.isEmpty()) Text(placeholder, color = IdColor.Neutral600, fontSize = 15.sp)
                inner()
            }
        },
    )
}

@Composable
private fun BigButton(label: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (!enabled) IdColor.Neutral300 else if (filled) IdColor.Accent else IdColor.Neutral100)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
    ) {
        Text(label, color = if (filled && enabled) IdColor.White else IdColor.Text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.6.sp)
    }
}

// ───────────────────────── scan ─────────────────────────

enum class ScanMode(val route: String) {
    /** This (signed-in) phone scans a web login QR and approves it. */
    APPROVE("approve"),

    /** A new, signed-out phone scans an "add phone" QR and gets its own session. */
    CLAIM("claim"),
}

/**
 * One scanner, two modes. The mode is what the USER asked for (which button they pressed) and is checked
 * against the QR's own `k` — a mismatch is refused with an explanation instead of guessing.
 */
@Composable
fun ScanPairScreen(
    mode: ScanMode,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onBack: () -> Unit,
    onLink: (PairingLink) -> Unit,
) {
    val vm: AccountViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun scan() {
        if (scanning) return
        scanning = true
        message = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                scanning = false
                val link = vm.parseQr(barcode.rawValue.orEmpty())
                when {
                    link == null -> message = "QR ini bukan dari server IntelDes. Tidak ada yang dikirim."
                    mode == ScanMode.APPROVE && link.kind != PairingLink.Kind.WEB_LOGIN ->
                        message = "QR ini untuk menambah HP baru, bukan untuk masuk web. Buat QR \"Masuk\" di halaman login web."
                    mode == ScanMode.CLAIM && link.kind != PairingLink.Kind.ADD_PHONE ->
                        message = "QR ini untuk masuk web, bukan menambah HP. Buat QR \"Tambah HP\" di menu Perangkat pada web."
                    else -> onLink(link)
                }
            }
            .addOnCanceledListener { scanning = false }
            .addOnFailureListener { scanning = false; message = "Pemindai tidak tersedia di perangkat ini (butuh Google Play services)." }
    }

    LaunchedEffect(Unit) { scan() }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
            Text("Pindai kode QR", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                if (mode == ScanMode.APPROVE) "Arahkan kamera ke QR di halaman login web (komputer atau HP lain). Anda akan diminta menyetujui sebelum web masuk."
                else "Arahkan kamera ke QR \"Tambah HP\" di menu Perangkat pada web. HP ini akan langsung masuk tanpa mengetik sandi.",
                color = IdColor.Neutral800, fontSize = 14.sp, lineHeight = 21.sp,
            )
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = IdColor.Accent, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
            BigButton(if (scanning) "MEMINDAI…" else "BUKA PEMINDAI", enabled = !scanning, onClick = ::scan)
        }
    }
}

// ───────────────────────── alur B: confirm the account after claiming ─────────────────────────

@Composable
fun ClaimScreen(
    code: String,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: AccountViewModel = viewModel(factory = factory)
    val state by vm.claim.collectAsStateWithLifecycle()
    LaunchedEffect(code) { vm.startClaim(code) }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(if (code.startsWith(APP_LOGIN_PREFIX)) "Masuk ke IntelDes" else "Tambah HP ini", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            ClaimUiState.Working -> Text("Menghubungkan ke server…", color = IdColor.Neutral700, fontSize = 14.sp)
            is ClaimUiState.Failed -> {
                Text(s.message, color = IdColor.Accent, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                BigButton("KEMBALI", filled = false, onClick = onBack)
            }
            is ClaimUiState.Ready -> {
                Text("HP ini akan masuk sebagai:", color = IdColor.Neutral700, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(16.dp)) {
                    Text(s.result.user.name, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(s.result.user.email, color = IdColor.Neutral700, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.result.org.name} · ${Session(s.result.role)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("Bukan akun Anda? Pilih Batal — HP ini langsung dicabut dari server.", color = IdColor.Neutral600, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(20.dp))
                BigButton("LANJUT") { vm.confirmClaim(s.result, onDone) }
                Spacer(Modifier.height(10.dp))
                BigButton("BATAL", filled = false) { vm.cancelClaim(s.result, onBack) }
            }
            ClaimUiState.Done -> Text("Selesai.", fontSize = 14.sp)
        }
    }
}

private fun Session(role: String) = com.inteldes.app.data.server.Session("", "", "", role, "").roleLabel

// ───────────────────────── alur A: approve a web login ─────────────────────────

@Composable
fun ApproveScreen(
    code: String,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: AccountViewModel = viewModel(factory = factory)
    val state by vm.approve.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(code) { vm.loadApprove(code) }

    // The screen shows who is asking to get in — keep it out of screenshots and the recents thumbnail.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Setujui masuk web?", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
        Spacer(Modifier.height(14.dp))
        when (val s = state) {
            ApproveUiState.Loading -> Text("Memeriksa kode…", color = IdColor.Neutral700, fontSize = 14.sp)
            is ApproveUiState.Failed -> {
                Text(s.message, color = IdColor.Accent, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                BigButton("KEMBALI", filled = false, onClick = onBack)
            }
            is ApproveUiState.Ready -> {
                Text("Seseorang meminta masuk ke web sebagai ${session?.name.orEmpty().ifBlank { "akun ini" }}.", fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow("Dari", s.info.requesterIp)
                    InfoRow("Peramban", s.info.requesterUa)
                    Countdown(s.info.expiresAt)
                }
                Spacer(Modifier.height(14.dp))
                Text("Setujui hanya bila ini perangkat Anda sendiri.", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                s.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = IdColor.Accent, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) { BigButton("TOLAK", filled = false, enabled = !s.approving, onClick = onBack) }
                    Box(modifier = Modifier.weight(1.4f)) {
                        BigButton(if (s.approving) "MENYETUJUI…" else "SETUJUI", enabled = !s.approving) {
                            val activity = context.findActivity() as? FragmentActivity
                            if (activity == null) vm.approve(code)
                            else confirmDeviceOwner(activity) { ok -> if (ok) vm.approve(code) }
                        }
                    }
                }
            }
            ApproveUiState.Done -> {
                Text("Web sudah disetujui dan sebentar lagi masuk.", fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                BigButton("SELESAI", onClick = onDone)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = IdColor.Neutral600, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 14.dp).then(Modifier))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    }
}

/** "Berlaku 1:42 lagi" counting down to the code's expiry (2 minutes after the QR was shown). */
@Composable
private fun Countdown(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    InfoRow("Berlaku", if (left == 0L) "kedaluwarsa — buat QR baru di web" else "%d:%02d lagi".format(left / 60, left % 60))
}

/**
 * Fingerprint / face / device PIN before approving. On a phone with no lock screen set up there is nothing to ask,
 * so approval goes ahead (the confirmation screen above is still shown).
 */
private fun confirmDeviceOwner(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(true)
        return
    }
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Setujui masuk web")
            .setSubtitle("Konfirmasi bahwa ini Anda")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
