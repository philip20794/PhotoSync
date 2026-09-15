package de.photosync.domain.model

data class ServerConfig(val baseUrl: String)

data class DeviceSession(
    val userId: String,
    val userDisplayName: String,
    val deviceId: String,
    val deviceName: String,
)

sealed interface SessionState {
    data object Loading : SessionState
    data object NeedsServer : SessionState
    data class NeedsAuthentication(val server: ServerConfig) : SessionState
    data class Authenticated(val server: ServerConfig, val session: DeviceSession) : SessionState
}
