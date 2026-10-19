package com.inteldes.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatMmSs
import com.inteldes.app.ui.util.validRange

/**
 * "Potong & simpan" editor. The chosen range becomes a NEW recording (its own audio file);
 * the original recording and its file are never changed.
 *
 * @param onSave Runs the cut; reports success/failure through the callback so this dialog
 * can show progress and any error (e.g. unsupported format) without closing.
 */
@Composable
fun TrimAudioDialog(
    durationSec: Int,
    marks: List<RecordingMark>,
    currentPositionSec: Int,
    onDismiss: () -> Unit,
    onSave: (startSec: Int, endSec: Int, onResult: (Result<String>) -> Unit) -> Unit,
    onSaved: () -> Unit,
) {
    var startText by remember { mutableStateOf(formatMmSs(0)) }
    var endText by remember { mutableStateOf(formatMmSs(durationSec)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val range = validRange(startText, endText, durationSec)
    val canSave = range != null && !saving

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Potong & simpan", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Bagian yang dipilih disimpan sebagai rekaman BARU. Rekaman asli tidak berubah.",
                    color = IdColor.Neutral700,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(14.dp))
                RangeSelector(
                    durationSec = durationSec,
                    marks = marks,
                    startText = startText,
                    endText = endText,
                    onStartChange = { startText = it; error = null },
                    onEndChange = { endText = it; error = null },
                    currentPositionSec = currentPositionSec,
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = IdColor.Accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Text(
                if (saving) "MEMOTONG…" else "SIMPAN",
                color = if (canSave) IdColor.Accent else IdColor.Neutral400,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = canSave) {
                        val (s, e) = range ?: return@clickable
                        saving = true
                        error = null
                        onSave(s, e) { result ->
                            saving = false
                            result.fold(
                                onSuccess = { onSaved() },
                                onFailure = { error = it.message ?: "Gagal memotong audio." },
                            )
                        }
                    }
                    .padding(10.dp),
            )
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(enabled = !saving, onClick = onDismiss).padding(10.dp))
        },
    )
}
