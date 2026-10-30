package com.inteldes.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.inteldes.app.IntelDesApp
import com.inteldes.app.ui.screens.contexts.ContextsViewModel
import com.inteldes.app.ui.screens.detail.DetailViewModel
import com.inteldes.app.ui.screens.home.HomeViewModel
import com.inteldes.app.ui.screens.processing.ProcessingViewModel
import com.inteldes.app.ui.screens.record.RecordViewModel
import com.inteldes.app.ui.screens.settings.SettingsViewModel
import com.inteldes.app.ui.screens.templates.TemplatesViewModel

/** Factory for the ViewModels that take no extra navigation arguments. */
class AppViewModelFactory(private val app: IntelDesApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(app, app.repository, app.settingsStore, app.audioRecorder, app.templateRepository) as T
        modelClass.isAssignableFrom(RecordViewModel::class.java) ->
            RecordViewModel(app, app.audioRecorder, app.repository, app.settingsStore, app.templateRepository) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(app, app.settingsStore, app.securePrefs, app.templateRepository, app.syncStore, app.driveAuth, app.accountService) as T
        modelClass.isAssignableFrom(com.inteldes.app.ui.screens.account.AccountViewModel::class.java) ->
            com.inteldes.app.ui.screens.account.AccountViewModel(app.accountService) as T
        modelClass.isAssignableFrom(ContextsViewModel::class.java) ->
            ContextsViewModel(app.contextRepository, app.repository) as T
        modelClass.isAssignableFrom(TemplatesViewModel::class.java) ->
            TemplatesViewModel(app.templateRepository, app.settingsStore) as T
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
            DetailViewModel(app, app.repository, app.templateRepository, app.contextRepository, app.settingsStore, app.securePrefs, recordingId) as T
    }

fun contextDetailViewModelFactory(app: IntelDesApp, contextId: String): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            com.inteldes.app.ui.screens.contexts.ContextDetailViewModel(app.contextRepository, app.repository, app.settingsStore, app.securePrefs, contextId) as T
    }
