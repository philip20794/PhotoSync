package de.photosync.data

import android.content.Context
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.SyncSettingsEntity
import de.photosync.data.offline.PartnerOfflineRepository
import de.photosync.data.remote.RetrofitFactory
import de.photosync.data.remote.UpdateProfileRequest
import de.photosync.data.sync.SyncScheduler
import de.photosync.data.sync.remoteScope
import de.photosync.ui.partner.PartnerMediaCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SettingsSnapshot(
    val preferences: SyncSettingsEntity,
    val offlineBytes: Long,
)

class SettingsRepository(
    context: Context,
    private val database: AppDatabase,
    private val credentials: SecureCredentialStore,
    private val baseUrl: String,
    private val userId: String,
) {
    private val appContext = context.applicationContext
    private val scope = remoteScope(baseUrl, userId)
    private val cache = PartnerMediaCache.get(appContext, credentials)
    private val api = RetrofitFactory.create(baseUrl, credentials)

    val settings: Flow<SettingsSnapshot> = combine(
        database.settingsDao().observe(scope),
        database.offlineDao().observeStoredBytes(scope),
    ) { value, bytes -> SettingsSnapshot(value ?: SyncSettingsEntity(scope), bytes) }

    val cacheMaxBytes: Long get() = cache.maxBytes

    suspend fun initialize() {
        if (database.settingsDao().get(scope) == null) database.settingsDao().save(SyncSettingsEntity(scope))
    }

    suspend fun refreshAccount(): de.photosync.data.remote.MeResponse {
        val response = api.me()
        saveSession(response)
        database.settingsDao().markSuccess(scope, System.currentTimeMillis())
        return response
    }

    suspend fun updateProfile(displayName: String, deviceName: String): de.photosync.data.remote.MeResponse {
        require(displayName.isNotBlank() && deviceName.isNotBlank()) { "Name und Gerätename dürfen nicht leer sein." }
        val response = api.updateProfile(UpdateProfileRequest(displayName.trim(), deviceName.trim()))
        saveSession(response)
        database.settingsDao().markSuccess(scope, System.currentTimeMillis())
        return response
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        database.settingsDao().save(current().copy(autoBackupEnabled = enabled))
        val session = database.appStateDao().getSession()
        if (!enabled && session != null) database.syncDao().disableAutoBackup(session.deviceId)
        SyncScheduler.runNow(appContext)
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        database.settingsDao().save(current().copy(wifiOnly = enabled))
        SyncScheduler.networkPolicyChanged(appContext)
        SyncScheduler.transferNow(appContext, enabled, replace = true)
        database.offlineDao().allAlbums(scope)
            .filter { it.desiredMode != "NONE" || it.status != "READY" }
            .forEach { PartnerOfflineRepository(appContext, database, scope).retry(it.albumId, replace = true) }
        SyncScheduler.runNow(appContext)
    }

    suspend fun setNotifySyncErrors(enabled: Boolean) {
        database.settingsDao().save(current().copy(notifySyncErrors = enabled))
    }

    suspend fun setCacheMaxBytes(bytes: Long) = cache.setMaxBytes(bytes)
    suspend fun cacheBytes(): Long = cache.cachedBytes()
    suspend fun clearCache() = cache.clear()

    private suspend fun current(): SyncSettingsEntity =
        database.settingsDao().get(scope) ?: SyncSettingsEntity(scope)

    private suspend fun saveSession(response: de.photosync.data.remote.MeResponse) {
        database.appStateDao().saveSession(DeviceSessionEntity(
            userId = response.user.id,
            userDisplayName = response.user.displayName,
            deviceId = response.device.id,
            deviceName = response.device.name,
        ))
    }
}
