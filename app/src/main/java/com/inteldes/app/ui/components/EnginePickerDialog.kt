package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatMmSs
import com.inteldes.app.ui.util.validRange

/**
 * Shared by the Detail and Processing screens' "MULAI TRANSKRIP"/"COBA LAGI" — starting
 * or retrying transcription spends real tokens/quota on the cloud engine, so both the
 * engine and how much of the recording to actually send are worth an explicit choice
 * each time rather than silently reusing whatever was set before.
 *
 * @param durationSec Full recording length, so range input can be validated.
 * @param marks Bookmarks — quick-fill chips for the range fields.
 * @param onConfirm engine, then rangeStartSec/rangeEndSec (both null = whole recording).
 */
@Composable
fun TranscribeOptionsDialog(
    currentEngine: WhisperEngine,
    durationSec: Int,
    marks: List<RecordingMark>,
    onDismiss: () -> Unit,
    onConfirm: (WhisperEngine, Int?, Int?) -> Unit,
    /** When given, the dialog also offers processing on the server with the server's keys (see ServerOption). */
    serverOption: com.inteldes.app.data.web.ServerOption? = null,
    onConfirmServer: () -> Unit = {},
) {
    var onServer by remember { mutableStateOf(serverOption?.available == true && serverOption.preferred) }
    var selectedEngine by remember { mutableStateOf(currentEngine) }
    var partial by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf(formatMmSs(0)) }
    var endText by remember { mutableStateOf(formatMmSs(durationSec)) }

    val range = validRange(startText, endText, durationSec)
    val canConfirm = !partial || range != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transkrip dengan mesin apa?", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            // Scrolls on small screens / with the keyboard open instead of pushing the
            // Batal/PROSES buttons off-screen.
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (serverOption != null) {
                    Text("DIPROSES DI", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                    Spacer(Modifier.height(6.dp))
                    EnginePickRow("HP ini", "Memakai kunci API yang Anda isi sendiri di Pengaturan.", !onServer) { onServer = false }
                    Spacer(Modifier.height(8.dp))
                    EnginePickRow(
                        "Server IntelDes",
                        serverOption.reason ?: "Memakai kunci milik server, dibatasi kuota atau paket akun Anda. Seluruh rekaman diproses.",
                        onServer && serverOption.available,
                    ) { if (serverOption.available) onServer = true }
                    if (serverOption.preferred && serverOption.available) {
                        Spacer(Modifier.height(4.dp))
                        Text("HP ini belum punya kunci API, jadi server dipilih.", color = IdColor.Neutral600, fontSize = 10.5.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (onServer) return@Column
                EnginePickRow("On-device", "Tanpa internet, satu pembicara, gratis.", selectedEngine == WhisperEngine.ON_DEVICE) { selectedEngine = WhisperEngine.ON_DEVICE }
                Spacer(Modifier.height(8.dp))
                EnginePickRow("Awan", "Lebih akurat, ada label pembicara, pakai kuota API.", selectedEngine == WhisperEngine.CLOUD) { selectedEngine = WhisperEngine.CLOUD }

                Spacer(Modifier.height(16.dp))
                Text("BAGIAN YANG DITRANSKRIP", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(6.dp))
                Row {
                    RangePickRow("Semua", !partial, Modifier.weight(1f)) { partial = false }
                    Spacer(Modifier.width(6.dp))
                    RangePickRow("Sebagian", partial, Modifier.weight(1f)) { partial = true }
                }

                if (partial) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Hanya rentang ini yang ditranskrip — lebih cepat dan hemat kuota. Memproses ulang akan mengganti transkrip yang sudah ada.",
                        color = IdColor.Neutral600,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    RangeSelector(
                        durationSec = durationSec,
                        marks = marks,
                        startText = startText,
                        endText = endText,
                        onStartChange = { startText = it },
                        onEndChange = { endText = it },
                    )
                }
            }
        },
        confirmButton = {
            Text(
                "PROSES",
                color = if (canConfirm) IdColor.Accent else IdColor.Neutral400,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = canConfirm) {
                        if (onServer) { onConfirmServer(); return@clickable }
                        onConfirm(selectedEngine, if (partial) range?.first else null, if (partial) range?.second else null)
                    }
                    .padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

@Composable
private fun EnginePickRow(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) IdColor.Accent else IdColor.Neutral100)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column {
            Text(label, color = if (selected) IdColor.White else IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(desc, color = if (selected) IdColor.White.copy(alpha = 0.9f) else IdColor.Neutral600, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RangePickRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) IdColor.White else IdColor.Text,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(if (selected) IdColor.Accent else IdColor.Neutral100)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
    )
}
