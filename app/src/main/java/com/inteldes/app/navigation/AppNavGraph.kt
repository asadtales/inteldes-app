package com.inteldes.app.navigation

import androidx.compose.runtime.Composable
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

@Composable
fun AppNavGraph(app: IntelDesApp, navController: NavHostController = rememberNavController()) {
    val appFactory = remember { AppViewModelFactory(app) }

    NavHost(navController = navController, startDestination = Routes.Home.route) {
        composable(Routes.Home.route) {
            val settings by app.settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val engineLabel = if (settings?.defaultEngine == WhisperEngine.ON_DEVICE) "Whisper on-device" else "Whisper awan"
            HomeScreen(
                factory = appFactory,
                engineShortLabel = engineLabel,
                onOpenRecording = { id -> navController.navigate(Routes.Detail.of(id)) },
                onRecord = { navController.navigate(Routes.Record.route) },
                onSettings = { navController.navigate(Routes.Settings.route) },
            )
        }

        composable(Routes.Record.route) {
            RecordScreen(
                factory = appFactory,
                onCancel = { navController.popBackStack() },
                onRecordingReady = { id ->
                    navController.navigate(Routes.Processing.of(id)) {
                        popUpTo(Routes.Home.route)
                    }
                },
            )
        }

        composable(
            route = Routes.Processing.route,
            arguments = listOf(navArgument("recordingId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            val factory = remember(recordingId) { processingViewModelFactory(app, recordingId) }
            ProcessingScreen(
                factory = factory,
                recordingId = recordingId,
                onDone = {
                    navController.navigate(Routes.Detail.of(recordingId)) {
                        popUpTo(Routes.Home.route)
                    }
                },
                onBackgroundIt = {
                    navController.popBackStack(Routes.Home.route, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.Detail.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType },
                navArgument("tab") { type = NavType.StringType; defaultValue = "notes" },
            ),
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            val tab = backStackEntry.arguments?.getString("tab") ?: "notes"
            val factory = remember(recordingId) { detailViewModelFactory(app, recordingId) }
            DetailScreen(
                factory = factory,
                initialTab = tab,
                onBack = { navController.popBackStack() },
                onGoSettings = { navController.navigate(Routes.Settings.route) },
                onOpenProcessing = { id -> navController.navigate(Routes.Processing.of(id)) },
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(factory = appFactory, onBack = { navController.popBackStack() })
        }
    }
}
