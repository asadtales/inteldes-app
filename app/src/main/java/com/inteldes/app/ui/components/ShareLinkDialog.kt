package com.inteldes.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.web.ShareLinkInfo
import com.inteldes.app.ui.theme.IdColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the share dialog shows: why sharing is not possible right now, the links that exist, and the one just made. */
data class ShareUi(
    /** False = the dialog is closed. */
    val open: Boolean = true,
    val reason: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val items: List<ShareLinkInfo> = emptyList(),
    val newUrl: String? = null,
    val error: String? = null,
)

/**
 * Public download links for a notulensi (anyone holding the link can download it without signing in, until it is
 * revoked or expires). The server makes and checks the links; this phone only asks, shows and revokes them.
 */
@Composable
fun ShareLinkDialog(
    ui: ShareUi,
    onCreate: (includeTranscript: Boolean, includeAudio: Boolean, expiresInDays: Int?) -> Unit,
    onRevoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var transcript by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf<Int?>(30) }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale("id", "ID")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bagikan lewat tautan", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Siapa pun yang memegang tautan bisa mengunduh notulensi tanpa masuk, sampai tautan dicabut atau kedaluwarsa. Bagikan hanya ke orang yang berhak.",
                    color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
                if (ui.reason != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(ui.reason, color = IdColor.Accent, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Spacer(Modifier.height(14.dp))
                    Label("YANG DAPAT DIUNDUH")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Transkrip", transcript) { transcript = !transcript }
                        Chip("Audio", audio) { audio = !audio }
                    }
                    Text("Notulensi selalu ikut.", color = IdColor.Neutral600, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    Label("BERLAKU")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf<Pair<String, Int?>>("7 hari" to 7, "30 hari" to 30, "90 hari" to 90, "Tanpa batas" to null).forEach { (label, d) ->
                            Chip(label, days == d) { days = d }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (ui.busy) "MENGIRIM KE SERVER…" else "BUAT TAUTAN",
                        color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (ui.busy) IdColor.Neutral400 else IdColor.Accent)
                            .clickable(enabled = !ui.busy) { onCreate(transcript, audio, days) }
                            .padding(vertical = 13.dp),
                    )
                }

                ui.newUrl?.let { url ->
                    Spacer(Modifier.height(14.dp))
                    Column(modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(12.dp)) {
                        Label("TAUTAN BARU — hanya tampil sekarang")
                        Spacer(Modifier.height(4.dp))
                        Text(url, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text("SALIN", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable { copy(context, url) }.padding(vertical = 6.dp, horizontal = 2.dp))
                            Spacer(Modifier.width(20.dp))
                            Text("BAGIKAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable { send(context, url) }.padding(vertical = 6.dp, horizontal = 2.dp))
                        }
                    }
                }
                ui.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = IdColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (ui.items.isNotEmpty() || ui.loading) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(thickness = 1.dp, color = IdColor.Neutral300)
                    Spacer(Modifier.height(10.dp))
                    Label("TAUTAN YANG ADA")
                    if (ui.loading) Text("Memuat…", color = IdColor.Neutral600, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                ui.items.forEach { s ->
                    val revoked = s.revokedAt != null
                    val expired = s.expiresAt != null && s.expiresAt < System.currentTimeMillis()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Dibuat ${dateFormat.format(Date(s.createdAt))} · " +
                                    (if (revoked) "dicabut" else if (expired) "kedaluwarsa" else s.expiresAt?.let { "sampai ${dateFormat.format(Date(it))}" } ?: "tanpa batas"),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (revoked || expired) IdColor.Neutral600 else IdColor.Text,
                            )
                            Text(
                                listOfNotNull("notulensi", "transkrip".takeIf { s.includeTranscript }, "audio".takeIf { s.includeAudio }).joinToString(", ") +
                                    " · diunduh ${s.downloadCount}×",
                                color = IdColor.Neutral600, fontSize = 10.5.sp,
                            )
                        }
                        if (!revoked && !expired) {
                            Text("CABUT", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.clickable { onRevoke(s.id) }.padding(10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { Text("TUTUP", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp)) },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) IdColor.White else IdColor.Neutral800,
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(if (selected) IdColor.Accent else IdColor.Neutral200).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private fun copy(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tautan notulensi", url))
    android.widget.Toast.makeText(context, "Tautan disalin", android.widget.Toast.LENGTH_SHORT).show()
}

private fun send(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
    context.startActivity(Intent.createChooser(send, "Bagikan tautan"))
}
