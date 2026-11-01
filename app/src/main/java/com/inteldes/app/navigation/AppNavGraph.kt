package com.inteldes.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.AppViewModelFactory
import com.inteldes.app.ui.detailViewModelFactory
import com.inteldes.app.ui.processingViewModelFactory
import com.inteldes.app.ui.screens.detail.DetailScreen
import com.inteldes.app.ui.screens.home.HomeScreen
import com.inteldes.app.ui.screens.processing.ProcessingScreen
import com.inteldes.app.ui.screens.record.RecordScreen
import com.inteldes.app.ui.screens.settings.SettingsScreen
import com.inteldes.app.data.work.ProcessRecordingWorker
import com.inteldes.app.ui.screens.templates.TemplatesScreen
import com.inteldes.app.ui.screens.account.ApproveScreen
import com.inteldes.app.ui.screens.account.ClaimScreen
import com.inteldes.app.ui.screens.account.LoginScreen
import com.inteldes.app.ui.screens.account.ScanMode
import com.inteldes.app.ui.screens.account.ScanPairScreen
import com.inteldes.app.ui.screens.contexts.ContextDetailScreen
import com.inteldes.app.ui.screens.contexts.ContextsScreen
import com.inteldes.app.ui.contextDetailViewModelFactory

@Composable
fun AppNavGraph(
    app: IntelDesApp,
    navController: NavHostController = rememberNavController(),
    recordShortcutToken: Int = 0,
    pairLink: com.inteldes.app.data.server.PairingLink? = null,
    onPairLinkHandled: () -> Unit = {},
    appLoginUri: String? = null,
    onAppLoginHandled: () -> Unit = {},
) {
    val appFactory = remember { AppViewModelFactory(app) }

    // Launched from the "Rekam" home-screen shortcut — jump straight past the list.
    // Keyed on the token (not a plain boolean) so tapping the shortcut again while the
    // app is already open still navigates, even though the value stays truthy.
    LaunchedEffect(recordShortcutToken) {
        if (recordShortcutToken > 0) navController.navigate(Routes.Record.route) { launchSingleTop = true }
    }

    val account = app.accountService
    val sessionNow by account.session.collectAsStateWithLifecycle()
    val endedByServer by account.endedByServer.collectAsStateWithLifecycle()
    // First launch with no login and no "Lewati" yet: the login screen comes first (and is skippable).
    val startDestination = remember { if (!account.loggedIn && !account.loginSkipped) Routes.Login.route else Routes.Home.route }

    // The server revoked this device (or the refresh was rejected): back to the login screen, with an explanation.
    LaunchedEffect(endedByServer, sessionNow) {
        if (endedByServer && sessionNow == null) {
            navController.navigate(Routes.Login.route) { launchSingleTop = true; popUpTo(Routes.Home.route) }
        }
    }

    // A pairing QR opened from outside the app (camera app / link): same checks as an in-app scan, done by MainActivity.
    LaunchedEffect(pairLink) {
        val link = pairLink ?: return@LaunchedEffect
        when (link.kind) {
            com.inteldes.app.data.server.PairingLink.Kind.WEB_LOGIN ->
                navController.navigate(if (account.loggedIn) Routes.Approve.of(link.code) else Routes.Login.route) { launchSingleTop = true }
            com.inteldes.app.data.server.PairingLink.Kind.ADD_PHONE ->
                navController.navigate(Routes.Claim.of(link.code)) { launchSingleTop = true }
        }
        onPairLinkHandled()
    }

    // The browser handed back a Google / portal login (inteldes://auth/callback). Only the login this phone started counts.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(appLoginUri) {
        val uri = appLoginUri ?: return@LaunchedEffect
        when (val cb = account.readAppLoginCallback(uri)) {
            is com.inteldes.app.data.server.AppLoginCallback.Code ->
                navController.navigate(Routes.Claim.of(com.inteldes.app.ui.screens.account.APP_LOGIN_PREFIX + cb.code)) { launchSingleTop = true }
            is com.inteldes.app.data.server.AppLoginCallback.Error ->
                android.widget.Toast.makeText(appContext, cb.message, android.widget.Toast.LENGTH_LONG).show()
            null -> Unit
        }
        onAppLoginHandled()
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.Login.route) {
            LoginScreen(
                factory = appFactory,
                canSkip = !account.loggedIn,
                onDone = { navController.navigate(Routes.Home.route) { popUpTo(0) } },
                onScanQr = { navController.navigate(Routes.Scan.of(ScanMode.CLAIM.route)) },
                onSkip = { navController.navigate(Routes.Home.route) { popUpTo(0) } },
            )
        }

        composable(
            route = Routes.Scan.route,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mode = ScanMode.entries.firstOrNull { it.route == backStackEntry.arguments?.getString("mode") } ?: ScanMode.CLAIM
            ScanPairScreen(
                mode = mode,
                factory = appFactory,
                onBack = { navController.popBackStack() },
                onLink = { link ->
                    navController.navigate(if (mode == ScanMode.APPROVE) Routes.Approve.of(link.code) else Routes.Claim.of(link.code)) {
                        popUpTo(Routes.Scan.route) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.Approve.route,
            arguments = listOf(navArgument("code") { type = NavType.StringType }),
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString("code") ?: return@composable
            ApproveScreen(code = code, factory = appFactory, onDone = { navController.popBackStack() }, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.Claim.route,
            arguments = listOf(navArgument("code") { type = NavType.StringType }),
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString("code") ?: return@composable
            ClaimScreen(
                code = code,
                factory = appFactory,
                onDone = { navController.navigate(Routes.Home.route) { popUpTo(0) } },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Home.route) {
            val settings by app.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val engineLabel = if (settings?.defaultEngine == WhisperEngine.ON_DEVICE) "Whisper on-device" else "Whisper awan"
            HomeScreen(
                factory = appFactory,
                engineShortLabel = engineLabel,
                onOpenRecording = { id -> navController.navigate(Routes.Detail.of(id)) },
                onRecord = { navController.navigate(Routes.Record.route) { launchSingleTop = true } },
                onSettings = { navController.navigate(Routes.Settings.route) },
                onContexts = { navController.navigate(Routes.Contexts.route) },
                onUploadReady = { id -> navController.navigate(Routes.Processing.of(id)) },
            )
        }

        composable(Routes.Record.route) {
            RecordScreen(
                factory = appFactory,
                onCancel = { navController.popBackStack() },
                onRecordingReady = { id, autoProcessed ->
                    // "Proses otomatis setelah rekam" off: the recording is only saved, not
                    // enqueued — go straight to its (empty) Transkrip tab, where the user can
                    // trigger processing manually whenever they actually want to spend on it.
                    val destination = if (autoProcessed) Routes.Processing.of(id) else Routes.Detail.of(id)
                    navController.navigate(destination) {
                        popUpTo(Routes.Home.route)
                    }
                },
            )
        }

        composable(
            route = Routes.Processing.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType },
                navArgument("tab") { type = NavType.StringType; defaultValue = "script" },
            ),
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            val tab = backStackEntry.arguments?.getString("tab") ?: "script"
            val factory = remember(recordingId) { processingViewModelFactory(app, recordingId) }
            ProcessingScreen(
                factory = factory,
                recordingId = recordingId,
                onDone = {
                    navController.navigate(Routes.Detail.of(recordingId, tab)) {
                        popUpTo(Routes.Home.route)
                    }
                },
                onBackgroundIt = {
                    navController.popBackStack(Routes.Home.route, inclusive = false)
                },
                onCancelled = {
                    navController.popBackStack(Routes.Home.route, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.Detail.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType },
                navArgument("tab") { type = NavType.StringType; defaultValue = "script" },
            ),
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            val tab = backStackEntry.arguments?.getString("tab") ?: "script"
            val factory = remember(recordingId) { detailViewModelFactory(app, recordingId) }
            DetailScreen(
                factory = factory,
                initialTab = tab,
                onBack = { navController.popBackStack() },
                onGoSettings = { navController.navigate(Routes.Settings.route) },
                onOpenProcessing = { id, fromTab, rangeStartSec, rangeEndSec ->
                    // "LIHAT PROGRES"/"COBA LAGI" from the Detail screen used to just navigate
                    // here assuming work was already running — true right after a recording is
                    // first created, but not when reopening a previously failed one, where
                    // nothing was actually enqueued. enqueue() uses KEEP, so this is a no-op
                    // when work is genuinely still active and only starts fresh work otherwise.
                    ProcessRecordingWorker.enqueue(app, id, rangeStartSec, rangeEndSec)
                    navController.navigate(Routes.Processing.of(id, fromTab))
                },
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                factory = appFactory,
                onBack = { navController.popBackStack() },
                onOpenTemplates = { navController.navigate(Routes.Templates.route) },
                onLogin = { navController.navigate(Routes.Login.route) },
                onApproveWebLogin = { navController.navigate(Routes.Scan.of(ScanMode.APPROVE.route)) },
            )
        }

        composable(Routes.Contexts.route) {
            ContextsScreen(
                factory = appFactory,
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.ContextDetail.of(id)) },
            )
        }

        composable(
            route = Routes.ContextDetail.route,
            arguments = listOf(navArgument("contextId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val contextId = backStackEntry.arguments?.getString("contextId") ?: return@composable
            val factory = remember(contextId) { contextDetailViewModelFactory(app, contextId) }
            ContextDetailScreen(factory = factory, onBack = { navController.popBackStack() })
        }

        composable(Routes.Templates.route) {
            TemplatesScreen(factory = appFactory, onBack = { navController.popBackStack() })
        }
    }
}
