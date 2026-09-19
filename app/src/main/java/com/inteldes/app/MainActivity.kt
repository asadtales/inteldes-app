package com.inteldes.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.inteldes.app.navigation.AppNavGraph
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.theme.IntelDesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntelDesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = IdColor.Bg) {
                    RequestNotificationPermissionIfNeeded()
                    AppNavGraph(app = application as IntelDesApp)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
