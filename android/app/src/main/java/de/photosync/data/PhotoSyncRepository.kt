package de.photosync.data

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.ServerConfigEntity
import de.photosync.data.remote.AuthResponse
import de.photosync.data.remote.RetrofitFactory
import de.photosync.data.remote.LoginRequest
import de.photosync.domain.model.DeviceSession
import de.photosync.domain.model.ServerConfig
import de.photosync.domain.model.SessionState
import kotlinx.coroutines.flow.combine
import retrofit2.HttpException
import de.photosync.data.offline.PartnerOfflineRepository
import de.photosync.data.offline.OfflineCleanupEntity
import de.photosync.data.offline.OfflineCleanupKind
import de.photosync.data.sync.remoteScope
import de.photosync.ui.partner.PartnerMediaCache

class PhotoSyncRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val credentials: SecureCredentialStore,
) {
    val sessionState = combine(database.appStateDao().observeServer(), database.appStateDao().observeSession()) { server, session ->
        when {
            server == null -> SessionState.NeedsServer
            session == null || credentials.readAccessToken() == null -> SessionState.NeedsAuthentication(ServerConfig(server.baseUrl))
            else -> SessionState.Authenticated(
                ServerConfig(server.baseUrl),
                DeviceSession(session.userId, session.userDisplayName, session.deviceId, session.deviceName),
            )
        }
    }

    suspend fun testConnection(baseUrl: String) {
        val health = RetrofitFactory.create(baseUrl).health()
        check(health.status == "ok" && health.checks["database"] == "ok" && health.checks["media"] == "ok") {
            "Server is not ready"
        }
    }

    suspend fun saveServer(baseUrl: String) {
        try {
            prepareSessionRemoval()
        } finally {
            credentials.clear()
            database.appStateDao().clearSession()
        }
        database.appStateDao().saveServer(ServerConfigEntity(baseUrl = baseUrl))
    }

    suspend fun login(baseUrl: String, username: String, password: String) {
        val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter(String::isNotBlank).joinToString(" ").ifBlank { "Android-Gerät" }
        val response = RetrofitFactory.create(baseUrl).login(
            LoginRequest(username.trim(), password, deviceName),
        )
        persistAuth(response)
    }

    suspend fun verifySession(baseUrl: String): DeviceSession {
        val response = RetrofitFactory.create(baseUrl, credentials).me()
        val session = DeviceSession(response.user.id, response.user.displayName, response.device.id, response.device.name)
        database.appStateDao().saveSession(session.toEntity())
        return session
    }

    suspend fun signOut() {
        try {
            prepareSessionRemoval()
        } finally {
            credentials.clear()
            database.appStateDao().clearSession()
            de.photosync.data.offline.OfflineLegacyCleanupWorker.enqueue(context)
        }
    }

    /**
     * Privacy policy: volatile partner cache is synchronously erased before logout.
     * Durable offline files are first marked NONE in Room, remain bound to their
     * old scope, and are then removed by idempotent scoped workers.
     */
    private suspend fun prepareSessionRemoval() {
        val server = database.appStateDao().getServer() ?: return
        val session = database.appStateDao().getSession() ?: return
        val scope = remoteScope(server.baseUrl, session.userId)
        val albums = database.offlineDao().allAlbums(scope)
        database.withTransaction { database.offlineDao().requestScopeRemoval(scope) }
        val offline = PartnerOfflineRepository(context, database, scope)
        albums.forEach { offline.retry(it.albumId) }
        val cacheCleanup = OfflineCleanupEntity("partner-cache-logout", OfflineCleanupKind.PARTNER_CACHE, scope)
        database.offlineDao().saveCleanup(cacheCleanup)
        PartnerMediaCache.get(context, credentials).clear()
        database.offlineDao().deleteCleanup(cacheCleanup.id)
    }

    suspend fun clearInvalidSessionIfUnauthorized(error: Throwable) {
        if (error is HttpException && error.code() == 401) signOut()
    }

    private suspend fun persistAuth(response: AuthResponse) {
        require(response.tokenType.equals("Bearer", ignoreCase = true)) { "Unsupported token type" }
        credentials.writeAccessToken(response.accessToken)
        database.appStateDao().saveSession(
            DeviceSession(response.user.id, response.user.displayName, response.device.id, response.device.name).toEntity(),
        )
    }
}

private fun DeviceSession.toEntity() = DeviceSessionEntity(
    userId = userId,
    userDisplayName = userDisplayName,
    deviceId = deviceId,
    deviceName = deviceName,
)
