package com.inteldes.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inteldes.app.data.model.Video
import com.inteldes.app.data.video.VideoRules
import com.inteldes.app.data.video.VideoStorage
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.util.formatMmSs
import java.io.File

class VideoPickerActions(val record: () -> Unit, val pick: () -> Unit)

/** Asks the camera app for a video that stops by itself at the server's limits (2 minutes, just under 100 MB). */
private class CaptureLimitedVideo : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, VideoRules.MAX_SEC.toInt())
            .putExtra(MediaStore.EXTRA_SIZE_LIMIT, VideoRules.MAX_BYTES - 5L * 1024 * 1024)
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

/** Camera (system camera app) and gallery (system picker) for one video; [onPicked] gets its Uri and whether it is a temporary camera file. */
@Composable
fun rememberVideoPicker(onPicked: (uri: Uri, temporary: Boolean) -> Unit): VideoPickerActions {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(CaptureLimitedVideo()) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) if (saved) latest(uri, true) else runCatching { context.contentResolver.delete(uri, null, null) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { latest(it, false) } }
    return remember(camera, gallery) {
        VideoPickerActions(
            record = { val uri = VideoStorage.newCameraTarget(context); cameraUri = uri; camera.launch(uri) },
            pick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        )
    }
}

/** The "video singkat" block of a recording: add by recording or picking, see what is attached and what the server knows of it. */
@Composable
fun VideoDocumentation(
    videos: List<Video>,
    onAdd: (Uri, Boolean) -> Unit,
    onCaption: (Video, String) -> Unit,
    onDelete: (Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberVideoPicker(onAdd)
    var open by remember { mutableStateOf<Video?>(null) }
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().background(IdColor.Neutral100).padding(14.dp)) {
        Text("VIDEO SINGKAT (${videos.size})", color = IdColor.Neutral600, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoButton("REKAM VIDEO", Modifier.weight(1f), picker.record)
            VideoButton("PILIH VIDEO", Modifier.weight(1f), picker.pick)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Maksimal ${VideoRules.MAX_SEC.toInt() / 60} menit dan ${VideoRules.MAX_BYTES / 1_048_576} MB per video, ${VideoRules.MAX_PER_RECORDING} video per rekaman. Video dikirim ke web lewat sinkron.",
            color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp,
        )
        videos.forEach { v ->
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(IdColor.Bg).clickable { open = v }.padding(10.dp),
            ) {
                Text("▶", fontSize = 18.sp, color = IdColor.Accent, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        v.caption.ifBlank { "Video" } + (v.durationSec?.let { " · ${formatMmSs(it.toInt())}" } ?: "") + " · ${"%.1f".format(v.sizeBytes / 1_048_576.0)} MB",
                        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Text(
                        v.syncError?.let { "Ditolak server: $it" } ?: if (v.synced) "Terkirim ke web" else "Menunggu sinkron ke web",
                        fontSize = 11.sp, color = if (v.syncError != null) IdColor.Accent else IdColor.Neutral600, maxLines = 2,
                    )
                }
            }
        }
    }
    open?.let { v ->
        var caption by remember(v.id) { mutableStateOf(v.caption) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("Video", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "${v.durationSec?.let { formatMmSs(it.toInt()) + " · " } ?: ""}${"%.1f".format(v.sizeBytes / 1_048_576.0)} MB" +
                            (v.takenAtSec?.let { " · menit ${formatMmSs(it)}" } ?: ""),
                        color = IdColor.Neutral700, fontSize = 12.sp,
                    )
                    if (v.latitude != null && v.longitude != null) {
                        Text("📍 " + (v.placeName ?: "%.5f, %.5f".format(v.latitude, v.longitude)), color = IdColor.Neutral700, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    IdTextField(caption, { caption = it.take(1000) }, "Tambah keterangan video…", singleLine = false, minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "PUTAR", color = IdColor.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().border(1.dp, IdColor.Divider).clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(VideoStorage.playUri(context, File(v.filePath)), "video/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        }.padding(vertical = 11.dp),
                    )
                }
            },
            confirmButton = { Text("SIMPAN", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if (caption.trim() != v.caption) onCaption(v, caption); open = null }.padding(10.dp)) },
            dismissButton = {
                Row {
                    Text("HAPUS", color = IdColor.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onDelete(v); open = null }.padding(10.dp))
                    Text("Tutup", color = IdColor.Neutral700, modifier = Modifier.clickable { open = null }.padding(10.dp))
                }
            },
        )
    }
}

@Composable
private fun VideoButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label, color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.8.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.border(1.dp, IdColor.Divider).clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}
