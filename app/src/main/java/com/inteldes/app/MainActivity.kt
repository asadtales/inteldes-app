package com.inteldes.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import com.inteldes.app.navigation.AppNavGraph
import com.inteldes.app.ui.screens.record.VolumeMarkBridge
import com.inteldes.app.ui.theme.IdColor
import com.inteldes.app.ui.theme.IntelDesTheme

private const val RECORD_SHORTCUT_URI = "inteldes://record"

class MainActivity : FragmentActivity() {
    // A counter, not a boolean: onNewIntent can deliver the same shortcut intent again
    // while the app is already open (singleTop), and a plain boolean staying "true"
    // wouldn't re-trigger navigation the second time. Incrementing always does.
    private val recordShortcutToken = mutableIntStateOf(0)

    // A QR opened through an App Link (the camera app, a message). Parsed with the SAME checks as an in-app scan.
    private val pairLink = androidx.compose.runtime.mutableStateOf<com.inteldes.app.data.server.PairingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isRecordShortcutIntent(intent)) recordShortcutToken.intValue++
        pairLink.value = pairLinkFrom(intent)
        setContent {
            IntelDesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = IdColor.Bg) {
                    RequestNotificationPermissionIfNeeded()
                    AppNavGraph(
                        app = application as IntelDesApp,
                        recordShortcutToken = recordShortcutToken.intValue,
                        pairLink = pairLink.value,
                        onPairLinkHandled = { pairLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isRecordShortcutIntent(intent)) recordShortcutToken.intValue++
        pairLinkFrom(intent)?.let { pairLink.value = it }
    }

    private fun pairLinkFrom(intent: Intent?): com.inteldes.app.data.server.PairingLink? {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return null
        return (application as IntelDesApp).accountService.parseQr(data)
    }

    private fun isRecordShortcutIntent(intent: Intent?) = intent?.data?.toString() == RECORD_SHORTCUT_URI

    // Volume-button-as-mark-shortcut, opt-in via Pengaturan — only reachable while the app
    // is actually open (RecordScreen registers the callback while composed); this doesn't
    // consume the event, so the system still adjusts volume as normal alongside it.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && event?.repeatCount == 0) {
            VolumeMarkBridge.onVolumeKey?.invoke()
        }
        return super.onKeyDown(keyCode, event)
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
