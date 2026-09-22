package de.photosync.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.SyncSettingsEntity
import de.photosync.data.local.BackupTransferProgress
import de.photosync.data.offline.PartnerOfflineRepository
import de.photosync.data.remote.RetrofitFactory
import de.photosync.data.remote.PartnerAlbumDto
import de.photosync.data.remote.UpdateProfileRequest
import de.photosync.data.sync.SyncScheduler
import de.photosync.data.sync.remoteScope
import de.photosync.ui.partner.PartnerMediaCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose

data class BackupProgressUi(val indeterminate: Boolean, val percent: Int? = null, val status: String, val isError: Boolean = false)

data class SettingsSnapshot(
    val preferences: SyncSettingsEntity,
    val offlineBytes: Long,
    val backupProgress: BackupProgressUi? = null,
)

internal fun backupProgressUi(raw: BackupTransferProgress, wifiOnly: Boolean, wifiAvailable: Boolean): BackupProgressUi {
    if (raw.albumCount == 0L || raw.scannedAlbumCount < raw.albumCount) return BackupProgressUi(true, status = "Backup wird vorbereitet")
    raw.failureMessage?.let { return BackupProgressUi(false, status = "Backup braucht Aufmerksamkeit", isError = true) }
    if (raw.totalBytes == 0L || raw.securedBytes >= raw.totalBytes) return BackupProgressUi(false, percent = 100, status = "Backup vollständig")
    if (wifiOnly && !wifiAvailable) return BackupProgressUi(false, status = "Wartet auf WLAN")
    val visibleBytes = raw.transferBytes.coerceIn(raw.securedBytes, raw.totalBytes)
    val percent = ((visibleBytes * 100) / raw.totalBytes).toInt().coerceIn(0, 99)
    return BackupProgressUi(false, percent = percent, status = "$percent % gesichert")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    private val backupTransfers = database.appStateDao().observeSession().flatMapLatest { session ->
        if (session == null) flowOf(BackupTransferProgress(0, 0, 0, 0, 0, null))
        else database.syncDao().observeBackupProgress(session.deviceId)
    }

    val settings: Flow<SettingsSnapshot> = combine(
        database.settingsDao().observe(scope),
        database.offlineDao().observeStoredBytes(scope),
        backupTransfers,
        wifiAvailability(appContext),
    ) { value, bytes, transfers, wifiAvailable ->
        val preferences = value ?: SyncSettingsEntity(scope)
        SettingsSnapshot(preferences, bytes, if (preferences.autoBackupEnabled) backupProgressUi(transfers, preferences.wifiOnly, wifiAvailable) else null)
    }

    val cacheMaxBytes: Long get() = cache.maxBytes

    suspend fun initialize() {
        if (database.settingsDao().get(scope) == null) database.settingsDao().save(SyncSettingsEntity(scope))
    }

    suspend fun backups(): List<PartnerAlbumDto> = api.backups().albums

    suspend fun restoreBackup(album: PartnerAlbumDto) {
        PartnerOfflineRepository(appContext, database, scope).restoreOwnBackup(
            album.id, album.optimizedBytes.toLongOrNull() ?: 0, album.originalBytes.toLongOrNull() ?: 0,
        )
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

private fun wifiAvailability(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun available(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    trySend(available())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(available()) }
        override fun onLost(network: Network) { trySend(available()) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(available()) }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
