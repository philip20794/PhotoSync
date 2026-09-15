package de.photosync.data.remote

import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String>)

@Serializable
data class UserDto(val id: String, val displayName: String)

@Serializable
data class DeviceDto(val id: String, val name: String, val createdAt: String, val revokedAt: String? = null)

@Serializable
data class AuthResponse(
    val user: UserDto,
    val device: DeviceDto,
    val accessToken: String,
    val tokenType: String,
)

@Serializable
data class MeResponse(val user: UserDto, val device: DeviceDto)

@Serializable
data class SetupRequest(val displayName: String, val deviceName: String)

@Serializable
data class PairRequest(val code: String, val displayName: String? = null, val deviceName: String)

@Serializable
data class CreateAlbumRequest(val clientAlbumId: String, val title: String)

@Serializable
data class SetAlbumSharingRequest(val shared: Boolean)

@Serializable
data class AlbumDto(
    val id: String,
    val owner: UserDto,
    val title: String,
    val ownedByMe: Boolean,
    val shared: Boolean,
    val sourceDeviceId: String? = null,
    val clientAlbumId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateAssetRequest(
    val originalFileName: String,
    val mimeType: String,
    val capturedAt: String? = null,
    val fileSize: String,
    val width: Int,
    val height: Int,
    val durationMillis: String? = null,
    val clientAssetId: String,
    val expectedSha256: String,
)

@Serializable
data class AssetDto(
    val id: String,
    val ownerId: String,
    val albumId: String,
    val originalFileName: String,
    val mimeType: String,
    val capturedAt: String? = null,
    val fileSize: String,
    val width: Int,
    val height: Int,
    val durationMillis: String? = null,
    val sha256: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

interface PhotoSyncApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("v1/auth/setup")
    suspend fun setup(
        @Header("Authorization") setupAuthorization: String,
        @Body request: SetupRequest,
    ): AuthResponse

    @POST("v1/auth/pair")
    suspend fun pair(@Body request: PairRequest): AuthResponse

    @GET("v1/me")
    suspend fun me(): MeResponse

    @POST("v1/albums")
    suspend fun createAlbum(@Body request: CreateAlbumRequest): AlbumDto

    @PATCH("v1/albums/{id}")
    suspend fun setAlbumSharing(@Path("id") id: String, @Body request: SetAlbumSharingRequest): AlbumDto

    @POST("v1/albums/{albumId}/assets")
    suspend fun createAsset(@Path("albumId") albumId: String, @Body request: CreateAssetRequest): AssetDto

    @GET("v1/assets/{id}")
    suspend fun getAsset(@Path("id") id: String): AssetDto

    @PUT("v1/assets/{id}/original")
    suspend fun uploadOriginal(@Path("id") id: String, @Body body: RequestBody): AssetDto
}
