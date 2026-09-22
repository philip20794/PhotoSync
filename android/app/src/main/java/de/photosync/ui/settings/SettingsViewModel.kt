package de.photosync.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.photosync.data.SettingsRepository
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.SyncSettingsEntity
import de.photosync.data.remote.PartnerAlbumDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: SyncSettingsEntity? = null,
    val displayName: String = "",
    val deviceName: String = "",
    val partnerName: String? = null,
    val backups: List<PartnerAlbumDto> = emptyList(),
    val offlineBytes: Long = 0,
    val cacheBytes: Long = 0,
    val cacheMaxBytes: Long = 0,
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val transient = MutableStateFlow(SettingsUiState(cacheMaxBytes = repository.cacheMaxBytes))
    val state = combine(repository.settings, transient) { saved, ui ->
        ui.copy(preferences = saved.preferences, offlineBytes = saved.offlineBytes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), transient.value)

    init {
        viewModelScope.launch {
            repository.initialize()
            refresh()
        }
    }

    fun refresh() = action {
        val account = repository.refreshAccount()
        transient.value = transient.value.copy(
            displayName = account.user.displayName,
            deviceName = account.device.name,
            partnerName = account.partner?.displayName,
            backups = repository.backups(),
            cacheBytes = repository.cacheBytes(),
        )
    }

    fun saveProfile(displayName: String, deviceName: String) = action {
        val account = repository.updateProfile(displayName, deviceName)
        transient.value = transient.value.copy(
            displayName = account.user.displayName,
            deviceName = account.device.name,
            partnerName = account.partner?.displayName,
            message = "Kontodaten gespeichert.",
        )
    }

    fun restoreBackup(album: PartnerAlbumDto) = action {
        repository.restoreBackup(album)
        transient.value = transient.value.copy(message = "Backup-Download gestartet.")
    }

    fun setAutoBackup(value: Boolean) = action { repository.setAutoBackup(value) }
    fun setWifiOnly(value: Boolean) = action { repository.setWifiOnly(value) }
    fun setNotifyErrors(value: Boolean) = action { repository.setNotifySyncErrors(value) }

    fun setCacheMax(bytes: Long) = action {
        repository.setCacheMaxBytes(bytes)
        transient.value = transient.value.copy(cacheMaxBytes = repository.cacheMaxBytes, cacheBytes = repository.cacheBytes())
    }

    fun clearCache() = action {
        repository.clearCache()
        transient.value = transient.value.copy(cacheBytes = repository.cacheBytes(), message = "Cache geleert.")
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            transient.value = transient.value.copy(working = true, error = null, message = null)
            try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                transient.value = transient.value.copy(error = error.message ?: "Aktion fehlgeschlagen.")
            } finally {
                transient.value = transient.value.copy(working = false)
            }
        }
    }

    companion object {
        fun factory(context: Context, baseUrl: String, userId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    SettingsRepository(
                        context.applicationContext,
                        AppDatabase.get(context),
                        SecureCredentialStore(context),
                        baseUrl,
                        userId,
                    ),
                ) as T
            }
    }
}
