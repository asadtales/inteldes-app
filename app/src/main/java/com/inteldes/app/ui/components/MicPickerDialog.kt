package com.inteldes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.audio.MicOption
import com.inteldes.app.ui.theme.IdColor

/** Pick which microphone records: automatic, the phone's own, a Bluetooth earbud, USB, or a wired headset. */
@Composable
fun MicPickerDialog(
    options: List<MicOption>,
    selectedKey: String,
    activeName: String?,
    onSelect: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sumber mikrofon", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                activeName?.let {
                    Text("Sedang dipakai: $it", color = IdColor.Neutral700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                }
                MicRow("Otomatis", "Ikuti pilihan Android. Earbud Bluetooth yang tersambung biasanya langsung dipakai.", selectedKey.isEmpty()) { onSelect("") }
                options.forEach { o -> MicRow(o.label, o.hint, selectedKey == o.key) { onSelect(o.key) } }
                if (options.none { it.kind != com.inteldes.app.data.audio.MicKind.PHONE }) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sambungkan earbud Bluetooth, mic USB, atau headset — pilihannya muncul di sini otomatis.",
                        color = IdColor.Neutral600, fontSize = 11.5.sp, lineHeight = 16.sp,
                    )
                }
            }
        },
        confirmButton = {
            Text("TUTUP", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

@Composable
private fun MicRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(if (selected) IdColor.Neutral900 else IdColor.Neutral100)
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (selected) IdColor.Bg else IdColor.Text)
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, lineHeight = 15.sp, color = if (selected) IdColor.Bg.copy(alpha = 0.8f) else IdColor.Neutral700)
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(IdColor.Accent))
        }
    }
}
