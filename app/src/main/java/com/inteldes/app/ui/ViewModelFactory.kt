package com.inteldes.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.inteldes.app.IntelDesApp
import com.inteldes.app.ui.screens.detail.DetailViewModel
import com.inteldes.app.ui.screens.home.HomeViewModel
import com.inteldes.app.ui.screens.processing.ProcessingViewModel
import com.inteldes.app.ui.screens.record.RecordViewModel
import com.inteldes.app.ui.screens.settings.SettingsViewModel

/** Factory for the ViewModels that take no extra navigation arguments. */
class AppViewModelFactory(private val app: IntelDesApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(app, app.repository, app.settingsStore) as T
        modelClass.isAssignableFrom(RecordViewModel::class.java) ->
            RecordViewModel(app, app.audioRecorder, app.repository, app.settingsStore) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(app.settingsStore, app.securePrefs) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class $modelClass")
    }
}

/** Processing and Detail need the navigated-to recording id, so they get their own small factories. */
fun processingViewModelFactory(app: IntelDesApp, recordingId: String): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            ProcessingViewModel(app, app.repository, recordingId) as T
    }

fun detailViewModelFactory(app: IntelDesApp, recordingId: String): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            DetailViewModel(app.repository, app.settingsStore, app.securePrefs, recordingId) as T
    }
