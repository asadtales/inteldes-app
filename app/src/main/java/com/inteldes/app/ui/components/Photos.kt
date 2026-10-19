package com.inteldes.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.inteldes.app.data.photo.PhotoStorage
import com.inteldes.app.ui.theme.IdColor
import java.io.File

private const val MAX_GALLERY_PICK = 10

class PhotoPickerActions(val launchCamera: () -> Unit, val launchGallery: () -> Unit)

/**
 * Camera (system camera app, no CAMERA permission needed) and gallery (system photo picker, no
 * storage permission needed). [onPicked] gets each chosen image's Uri; [temporary] is true for camera
 * captures, whose cache file the receiver should delete once imported.
 */
@Composable
fun rememberPhotoPicker(onPicked: (uri: Uri, temporary: Boolean) -> Unit): PhotoPickerActions {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) {
            if (saved) {
                latest(uri, true)
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_PICK),
    ) { uris -> uris.forEach { latest(it, false) } }

    return remember(cameraLauncher, galleryLauncher) {
        PhotoPickerActions(
            launchCamera = {
                val uri = PhotoStorage.newCameraTarget(context)
                cameraUri = uri
                cameraLauncher.launch(uri)
            },
            launchGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

@Composable
fun PhotoPickerButtons(actions: PhotoPickerActions, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val content = if (onDark) IdColor.Neutral100 else IdColor.Text
    val line = if (onDark) IdColor.Neutral600 else IdColor.Divider
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerButton("KAMERA", Icons.Filled.PhotoCamera, content, line, Modifier.weight(1f), actions.launchCamera)
        PickerButton("GALERI", Icons.Filled.PhotoLibrary, content, line, Modifier.weight(1f), actions.launchGallery)
    }
}

@Composable
private fun PickerButton(label: String, icon: ImageVector, content: Color, line: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .border(BorderStroke(1.dp, line))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = content, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.8.sp)
    }
}

@Composable
fun PhotoImage(path: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    AsyncImage(
        model = File(path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}
