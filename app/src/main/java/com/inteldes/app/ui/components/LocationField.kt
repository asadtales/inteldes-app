package com.inteldes.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.location.LocationHelper
import com.inteldes.app.data.model.LocationInfo
import com.inteldes.app.ui.theme.IdColor
import kotlinx.coroutines.launch

/**
 * Editable meeting-location text with a "use current location" button. Asks for the location
 * permission the first time it's needed; [onDetected] receives the GPS fix and its place name.
 */
@Composable
fun LocationField(
    name: String,
    onNameChange: (String) -> Unit,
    onDetected: (LocationInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }

    fun detect() {
        scope.launch {
            detecting = true
            val found = LocationHelper.detect(context)
            detecting = false
            if (found != null) onDetected(found)
            else Toast.makeText(context, "Lokasi belum bisa didapat. Aktifkan GPS atau isi manual.", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) detect()
        else Toast.makeText(context, "Izin lokasi ditolak. Lokasi bisa diisi manual.", Toast.LENGTH_SHORT).show()
    }

    Column(modifier = modifier) {
        IdTextField(value = name, onValueChange = onNameChange, placeholder = "Lokasi rapat, mis. Balai Desa Tales")
        Spacer(Modifier.height(6.dp))
        Text(
            if (detecting) "MENCARI LOKASI…" else "GUNAKAN LOKASI SAAT INI",
            color = if (detecting) IdColor.Neutral600 else IdColor.Accent,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .clickable(enabled = !detecting) {
                    if (LocationHelper.hasPermission(context)) detect() else permissionLauncher.launch(LocationHelper.permissions)
                }
                .padding(vertical = 6.dp),
        )
    }
}

/** Dialog around [LocationField] for changing the location of an existing recording. */
@Composable
fun LocationEditDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lokasi rapat", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Lokasi dicantumkan ke dokumen saat disusun ulang dan di ekspor. Kosongkan untuk menghapus.",
                    color = IdColor.Neutral700, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                LocationField(name = name, onNameChange = { name = it }, onDetected = { name = it.name })
            }
        },
        confirmButton = {
            Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(name) }.padding(10.dp))
        },
        dismissButton = {
            Text("Batal", color = IdColor.Neutral700, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
        },
    )
}

/** One-line "Lokasi: … UBAH" row. */
@Composable
fun LocationRow(name: String?, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = modifier.fillMaxWidth()) {
        Text(
            if (name.isNullOrBlank()) "Lokasi: belum diisi" else "Lokasi: $name",
            color = if (name.isNullOrBlank()) IdColor.Neutral600 else IdColor.Neutral800,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (name.isNullOrBlank()) "ISI" else "UBAH",
            color = IdColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            modifier = Modifier.clickable(onClick = onEdit).padding(8.dp),
        )
    }
}
