package de.photosync.data.remote

import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Path

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String>)

@Serializable
data class UserDto(val id: String, val displayName: String, val username: String = "")

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
data class MeResponse(val user: UserDto, val device: DeviceDto, val partner: UserDto? = null)

@Serializable
data class UpdateProfileRequest(val displayName: String, val deviceName: String)

@Serializable
data class LoginRequest(val username: String, val password: String, val deviceName: String)

@Serializable
data class CreateAlbumRequest(
    val clientAlbumId: String,
    val sourceVolume: String,
    val sourceRelativePath: String,
    val title: String,
    val shared: Boolean = true,
    val backedUp: Boolean = false,
)

@Serializable
data class SetAlbumSharingRequest(val shared: Boolean)

@Serializable
data class AlbumDto(
    val id: String,
    val owner: UserDto,
    val title: String,
    val ownedByMe: Boolean,
    val shared: Boolean,
    val backedUp: Boolean = false,
    val sourceDeviceId: String? = null,
    val clientAlbumId: String? = null,
    val sourceVolume: String? = null,
    val sourceRelativePath: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PartnerCoverDto(val assetId: String, val version: String, val sha256: String? = null)

@Serializable
data class PartnerAlbumDto(
    val id: String,
    val owner: UserDto,
    val title: String,
    val ownedByMe: Boolean,
    val shared: Boolean,
    val assetCount: Int,
    val optimizedBytes: String,
    val originalBytes: String,
    val cover: PartnerCoverDto? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PartnerAlbumsResponse(val albums: List<PartnerAlbumDto>)

@Serializable
data class AlbumsResponse(val albums: List<AlbumDto>)

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
data class DerivativeDto(
    val kind: String,
    val status: String,
    val mimeType: String? = null,
    val fileSize: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: String? = null,
    val sha256: String? = null,
    val updatedAt: String,
)

@Serializable
data class AssetPageDto(val assets: List<AssetDto>, val nextCursor: String? = null)

@Serializable
data class ServerChange(val revision: String, val albumId: String, val assetId: String? = null, val kind: String, val operation: String)

@Serializable
data class ChangePage(val changes: List<ServerChange>, val nextCursor: String, val hasMore: Boolean)

@Serializable
data class PushTokenRequest(val token: String)

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
    val integrityStatus: String = "healthy",
    val integrityError: String? = null,
    val status: String,
    val derivatives: List<DerivativeDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UploadSessionDto(
    val id: String,
    val assetId: String,
    val offset: String,
    val size: String,
    val maxChunkBytes: String = "4194304",
    val expiresAt: String,
    val completed: Boolean = false,
    val asset: AssetDto? = null,
)

@Serializable
data class TrashAssetDto(
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
    val derivatives: List<DerivativeDto> = emptyList(),
    val deletedAt: String,
    val purgeAfter: String,
    val remainingRetentionSeconds: Long,
    val originalAlbum: TrashAlbumDto,
    val availableVariants: List<String> = emptyList(),
    val cleanupLastError: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TrashAlbumDto(val id: String, val title: String)

@Serializable
data class TrashResponse(val assets: List<TrashAssetDto>)

@Serializable
data class AssetIdsRequest(val assetIds: List<String>)

@Serializable
data class PurgeResponse(val purged: List<String> = emptyList(), val failed: List<PurgeFailureDto> = emptyList())

@Serializable
data class PurgeFailureDto(val id: String, val message: String)

interface PhotoSyncApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("v1/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @GET("v1/me")
    suspend fun me(): MeResponse

    @PATCH("v1/me")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): MeResponse

    @GET("v1/sync/changes")
    suspend fun changes(@Query("cursor") cursor: String? = null): ChangePage

    @PUT("v1/sync/push-token")
    suspend fun registerPushToken(@Body request: PushTokenRequest)

    @POST("v1/albums")
    suspend fun createAlbum(@Body request: CreateAlbumRequest): AlbumDto

    @PATCH("v1/albums/{id}")
    suspend fun setAlbumSharing(@Path("id") id: String, @Body request: SetAlbumSharingRequest): AlbumDto

    @POST("v1/albums/{albumId}/assets")
    suspend fun createAsset(@Path("albumId") albumId: String, @Body request: CreateAssetRequest): AssetDto

    @GET("v1/assets/{id}")
    suspend fun getAsset(@Path("id") id: String): AssetDto

    @HTTP(method = "DELETE", path = "v1/assets/{id}/upload", hasBody = false)
    suspend fun cancelUpload(@Path("id") id: String): CancelUploadResponse

    @HTTP(method = "DELETE", path = "v1/assets/{id}", hasBody = false)
    suspend fun trashAsset(@Path("id") id: String): TrashAssetDto

    @GET("v1/trash")
    suspend fun trash(): TrashResponse
    @Streaming
    @GET("v1/trash/assets/{id}/thumbnail")
    suspend fun downloadTrashThumbnail(@Path("id") id: String): Response<ResponseBody>


    @POST("v1/trash/assets/{id}/restore")
    suspend fun restoreTrashAsset(@Path("id") id: String): AssetDto

    @POST("v1/trash/restore")
    suspend fun restoreTrashAssets(@Body request: AssetIdsRequest): RestoreResponse

    @HTTP(method = "DELETE", path = "v1/trash/assets/{id}", hasBody = false)
    suspend fun purgeTrashAsset(@Path("id") id: String): PurgeSingleResponse

    @HTTP(method = "DELETE", path = "v1/trash/assets", hasBody = true)
    suspend fun purgeTrashAssets(@Body request: AssetIdsRequest): PurgeResponse

    @HTTP(method = "DELETE", path = "v1/trash", hasBody = true)
    suspend fun emptyTrash(@Body request: AssetIdsRequest = AssetIdsRequest(emptyList())): PurgeResponse

    @GET("v1/albums")
    suspend fun albums(): AlbumsResponse

    @GET("v1/backups")
    suspend fun backups(): PartnerAlbumsResponse

    @GET("v1/partner/albums")
    suspend fun partnerAlbums(): PartnerAlbumsResponse

    @GET("v1/albums/{albumId}/assets")
    suspend fun albumAssets(
        @Path("albumId") albumId: String,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String? = null,
    ): AssetPageDto

    @Streaming
    @GET("v1/assets/{id}/{variant}")
    suspend fun downloadVariant(
        @Path("id") id: String,
        @Path("variant") variant: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @PUT("v1/assets/{id}/original")
    suspend fun uploadOriginal(@Path("id") id: String, @Body body: RequestBody): AssetDto

    @POST("v1/assets/{id}/upload-session")
    suspend fun createUploadSession(@Path("id") id: String): UploadSessionDto

    @PATCH("v1/upload-sessions/{id}")
    suspend fun uploadChunk(
        @Path("id") id: String,
        @Header("Upload-Offset") offset: String,
        @Body body: RequestBody,
    ): UploadSessionDto
}

@Serializable
data class RestoreResponse(val restored: List<AssetDto> = emptyList(), val skipped: List<String> = emptyList())

@Serializable
data class PurgeSingleResponse(val id: String, val purged: Boolean)

@Serializable
data class CancelUploadResponse(val id: String, val cancelled: Boolean)
