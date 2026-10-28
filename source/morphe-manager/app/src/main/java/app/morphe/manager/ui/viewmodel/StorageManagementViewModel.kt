/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.StorageStats
import app.morphe.manager.domain.repository.StorageStatsRepository
import app.morphe.manager.domain.worker.WorkerRepository
import app.morphe.manager.patcher.worker.PatcherWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StorageManagementViewModel(
    private val storageStatsRepository: StorageStatsRepository,
    val prefs: PreferencesManager,
    workerRepository: WorkerRepository
) : ViewModel() {

    val stats: StateFlow<StorageStats> = storageStatsRepository.stats

    /**
     * Whether a patch, single or batch, is running on the files the patcher workspace and the
     * temporary clears would wipe, which the page warns about before clearing them.
     */
    val patchInProgress: StateFlow<Boolean> = workerRepository.activeFlow<PatcherWorker>()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun refresh() = storageStatsRepository.refresh()

    fun setSaveOriginalApks(enabled: Boolean) = viewModelScope.launch {
        prefs.saveOriginalApks.update(enabled)
    }

    fun setSavePatchedApks(enabled: Boolean) = viewModelScope.launch {
        prefs.savePatchedApks.update(enabled)
    }

    fun clearHttpCache(onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        onDone(storageStatsRepository.clearHttpCache())
    }

    fun clearInstallerShareCache(onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        onDone(storageStatsRepository.clearInstallerShareCache())
    }

    fun clearPatcherWorkspace(onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        onDone(storageStatsRepository.clearPatcherWorkspace())
    }

    fun clearTemporary(onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        onDone(storageStatsRepository.clearTemporary())
    }

    fun clearAllCaches(onDone: (Long) -> Unit = {}) = viewModelScope.launch {
        onDone(storageStatsRepository.clearAllCaches())
    }
}
