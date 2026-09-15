package de.photosync.data

import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.ServerConfigEntity
import de.photosync.data.remote.AuthResponse
import de.photosync.data.remote.PairRequest
import de.photosync.data.remote.RetrofitFactory
import de.photosync.data.remote.SetupRequest
import de.photosync.domain.model.DeviceSession
import de.photosync.domain.model.ServerConfig
import de.photosync.domain.model.SessionState
import kotlinx.coroutines.flow.combine
import retrofit2.HttpException

class PhotoSyncRepository(
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
        credentials.clear()
        database.appStateDao().clearSession()
        database.appStateDao().saveServer(ServerConfigEntity(baseUrl = baseUrl))
    }

    suspend fun setup(baseUrl: String, setupToken: String, displayName: String, deviceName: String) {
        val response = RetrofitFactory.create(baseUrl).setup(
            "Bearer ${setupToken.trim()}",
            SetupRequest(displayName.trim(), deviceName.trim()),
        )
        persistAuth(response)
    }

    suspend fun pair(baseUrl: String, code: String, displayName: String?, deviceName: String) {
        val response = RetrofitFactory.create(baseUrl).pair(
            PairRequest(code.trim(), displayName?.trim()?.takeIf { it.isNotEmpty() }, deviceName.trim()),
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
        credentials.clear()
        database.appStateDao().clearSession()
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
