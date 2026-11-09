package com.inteldes.app.ui.screens.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inteldes.app.data.location.LocationHelper
import com.inteldes.app.ui.components.PhotoImage
import com.inteldes.app.ui.components.rememberPhotoPicker
import com.inteldes.app.ui.components.rememberVideoPicker
import com.inteldes.app.ui.theme.IdColor

/** Photo / video capture on its own, outside any audio recording, each with the place it was taken. */
@Composable
fun CaptureScreen(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenRecording: (String) -> Unit,
) {
    val vm: CaptureViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    // The position needs the location permission; ask when the screen opens with geotag on.
    LaunchedEffect(state.geotag) {
        if (state.geotag && !LocationHelper.hasPermission(context)) permission.launch(LocationHelper.permissions)
    }
    val photo = rememberPhotoPicker { uri, temporary -> vm.addPhoto(uri, temporary) }
    val video = rememberVideoPicker { uri, temporary -> vm.addVideo(uri, temporary) }

    Column(modifier = Modifier.fillMaxSize().background(IdColor.Bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = IdColor.Text) }
            Column(Modifier.weight(1f)) {
                Text("DOKUMENTASI LAPANGAN", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 1.sp)
                Text("Foto dan video tanpa merekam suara", color = IdColor.Neutral600, fontSize = 11.sp)
            }
        }
        androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = IdColor.Divider)

        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("FOTO", Icons.Filled.PhotoCamera, Modifier.weight(1f), photo.launchCamera)
                BigButton("VIDEO", Icons.Filled.Videocam, Modifier.weight(1f), video.record)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("PILIH FOTO", Modifier.weight(1f), photo.launchGallery)
                SmallButton("PILIH VIDEO", Modifier.weight(1f), video.pick)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).clickable { vm.setGeotag(!state.geotag) }.padding(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Catat lokasi (geotag)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        if (state.geotag) "Koordinat, waktu WIB dan nama tempat ditempel di foto dan ikut tersimpan pada video."
                        else "Foto dan video disimpan tanpa lokasi.",
                        color = IdColor.Neutral600, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
                Text(if (state.geotag) "AKTIF" else "MATI", color = if (state.geotag) IdColor.Accent else IdColor.Neutral600, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
            if (state.busy) Text("Menyimpan dan mencari lokasi…", color = IdColor.Neutral700, fontSize = 12.sp)
            state.notice?.let { Text(it, color = IdColor.Accent, fontSize = 12.sp, lineHeight = 16.sp) }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items) { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(IdColor.Neutral100).padding(8.dp)) {
                    Box(Modifier.size(56.dp).background(IdColor.Neutral300), contentAlignment = Alignment.Center) {
                        if (item.isVideo) Text("▶", fontSize = 20.sp, color = IdColor.Accent) else PhotoImage(item.path, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(if (item.isVideo) "Video" else "Foto", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Text(
                            item.geo?.let { g -> g.placeName?.takeIf { it.isNotBlank() } ?: "%.5f, %.5f".format(g.latitude, g.longitude) } ?: "Tanpa lokasi",
                            color = IdColor.Neutral600, fontSize = 11.sp, maxLines = 2,
                        )
                    }
                }
            }
        }

        state.recordingId?.let { id ->
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    "${state.photoCount} foto, ${state.videoCount} video masuk ke arsip sebagai \"${state.title}\". Dikirim ke web lewat sinkron.",
                    color = IdColor.Neutral700, fontSize = 11.5.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().background(IdColor.Accent).clickable { onOpenRecording(id) }.padding(vertical = 14.dp),
                ) { Text("SELESAI & BUKA", color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.6.sp) }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.background(IdColor.Accent).clickable(onClick = onClick).padding(vertical = 22.dp),
    ) {
        Icon(icon, contentDescription = null, tint = IdColor.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = IdColor.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun SmallButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label, color = IdColor.Text, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 0.8.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.border(1.dp, IdColor.Divider).clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}
