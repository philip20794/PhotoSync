package de.photosync.data.offline

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

object OfflineMode {
    const val NONE = "NONE"
    const val OPTIMIZED = "OPTIMIZED"
    const val ORIGINAL = "ORIGINAL"
}

object OfflineStatus {
    const val PENDING = "PENDING"
    const val DOWNLOADING = "DOWNLOADING"
    const val READY = "READY"
    const val RETRY = "RETRY"
    const val FAILED = "FAILED"
    const val REMOTE_DELETED = "REMOTE_DELETED"
}

/** A deliberately unusable scope assigned to pre-v6 rows until they are purged. */
const val LEGACY_OFFLINE_SCOPE = "__legacy_unscoped__"

@Entity(
    tableName = "offline_albums",
    primaryKeys = ["scope", "albumId"],
    indices = [Index("scope"), Index(value = ["scope", "status"])],
)
data class OfflineAlbumEntity(
    val scope: String,
    val albumId: String,
    val desiredMode: String = OfflineMode.NONE,
    val estimatedOptimizedBytes: Long = 0,
    val estimatedOriginalBytes: Long = 0,
    val status: String = OfflineStatus.READY,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val metadataCursor: String? = null,
    val metadataComplete: Boolean = false,
)

@Entity(
    tableName = "offline_assets",
    primaryKeys = ["scope", "assetId"],
    indices = [Index(value = ["scope", "albumId"]), Index(value = ["scope", "status"])],
)
data class OfflineAssetEntity(
    val scope: String,
    val assetId: String,
    val albumId: String,
    val desiredVariant: String,
    val overridesAlbumMode: Boolean = false,
    val actualVariant: String? = null,
    val status: String = OfflineStatus.PENDING,
    val variantVersion: String,
    val expectedSha256: String,
    val fileSize: Long,
    val localPath: String? = null,
    val bytesDownloaded: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val seenGeneration: Long = 0,
)

@Entity(tableName = "offline_cleanup")
data class OfflineCleanupEntity(
    @androidx.room.PrimaryKey val id: String,
    val kind: String,
    val scope: String,
    val albumId: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

object OfflineCleanupKind {
    const val LEGACY_ROOT = "LEGACY_ROOT"
    const val PARTNER_CACHE = "PARTNER_CACHE"
}

@Dao
interface OfflineDao {
    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM offline_assets WHERE scope = :scope AND status = 'READY' AND localPath IS NOT NULL")
    fun observeStoredBytes(scope: String): Flow<Long>

    @Query("SELECT * FROM offline_albums WHERE scope = :scope")
    suspend fun allAlbums(scope: String): List<OfflineAlbumEntity>

    @Query("SELECT * FROM offline_albums")
    suspend fun allAlbumsAcrossScopes(): List<OfflineAlbumEntity>

    @Query("UPDATE offline_albums SET desiredMode = 'NONE', status = 'PENDING', lastError = NULL, metadataCursor = NULL, metadataComplete = 0, updatedAt = updatedAt + 1 WHERE scope = :scope AND albumId = :albumId")
    suspend fun requestRemoval(scope: String, albumId: String)

    @Query("UPDATE offline_albums SET desiredMode = 'NONE', status = 'PENDING', lastError = NULL, metadataCursor = NULL, metadataComplete = 0, updatedAt = updatedAt + 1 WHERE scope = :scope")
    suspend fun requestScopeRemoval(scope: String): Int

    @Query("UPDATE offline_albums SET status = 'PENDING', lastError = NULL, metadataCursor = NULL, metadataComplete = 0, updatedAt = updatedAt + 1 WHERE scope = :scope AND albumId = :albumId AND desiredMode != 'NONE'")
    suspend fun invalidateAlbum(scope: String, albumId: String)

    @Query("UPDATE offline_albums SET status = :status, lastError = :error WHERE scope = :scope AND albumId = :albumId AND updatedAt = :generation")
    suspend fun finishAlbum(scope: String, albumId: String, generation: Long, status: String, error: String?): Int

    @Query("UPDATE offline_albums SET metadataCursor = :cursor, metadataComplete = :complete WHERE scope = :scope AND albumId = :albumId AND updatedAt = :generation")
    suspend fun advanceMetadata(scope: String, albumId: String, generation: Long, cursor: String?, complete: Boolean): Int

    @Query("UPDATE offline_assets SET status = 'RETRY' WHERE scope = :scope AND albumId = :albumId AND status = 'DOWNLOADING'")
    suspend fun recoverAlbum(scope: String, albumId: String)

    @Query("SELECT * FROM offline_albums WHERE scope = :scope")
    fun observeAlbums(scope: String): Flow<List<OfflineAlbumEntity>>

    @Query("SELECT * FROM offline_albums WHERE scope = :scope AND albumId = :albumId")
    suspend fun album(scope: String, albumId: String): OfflineAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAlbum(album: OfflineAlbumEntity)

    @Query("SELECT * FROM offline_assets WHERE scope = :scope AND assetId = :assetId")
    fun observeAsset(scope: String, assetId: String): Flow<OfflineAssetEntity?>

    @Query("SELECT * FROM offline_assets WHERE scope = :scope AND assetId = :assetId")
    suspend fun asset(scope: String, assetId: String): OfflineAssetEntity?

    @Query("SELECT * FROM offline_assets WHERE scope = :scope AND albumId = :albumId")
    suspend fun assets(scope: String, albumId: String): List<OfflineAssetEntity>

    @Query("SELECT * FROM offline_assets WHERE scope = :scope AND albumId = :albumId AND seenGeneration != :generation")
    suspend fun unseenAssets(scope: String, albumId: String, generation: Long): List<OfflineAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAsset(asset: OfflineAssetEntity)

    @Query("UPDATE offline_assets SET bytesDownloaded = :bytes, updatedAt = :now WHERE scope = :scope AND assetId = :assetId AND variantVersion = :variantVersion AND status = 'DOWNLOADING'")
    suspend fun updateDownloadProgress(scope: String, assetId: String, variantVersion: String, bytes: Long, now: Long): Int

    @Query("UPDATE offline_assets SET desiredVariant = :variant, overridesAlbumMode = 1, status = CASE WHEN actualVariant = :variant THEN 'READY' ELSE 'PENDING' END, lastError = NULL, updatedAt = :now WHERE scope = :scope AND assetId = :assetId")
    suspend fun setAssetVariant(scope: String, assetId: String, variant: String, now: Long)

    @Query("UPDATE offline_assets SET desiredVariant = :variant, overridesAlbumMode = 0, status = CASE WHEN actualVariant = :variant THEN 'READY' ELSE 'PENDING' END, lastError = NULL, updatedAt = :now WHERE scope = :scope AND albumId = :albumId")
    suspend fun setAlbumVariant(scope: String, albumId: String, variant: String, now: Long)

    @Query("UPDATE offline_assets SET status = 'RETRY', lastError = 'Download wurde unterbrochen', updatedAt = :now WHERE scope = :scope AND status = 'DOWNLOADING'")
    suspend fun recoverInterrupted(scope: String, now: Long): Int

    @Query("DELETE FROM offline_assets WHERE scope = :scope AND assetId = :assetId")
    suspend fun deleteAsset(scope: String, assetId: String)

    @Query("DELETE FROM offline_assets WHERE scope = :scope AND albumId = :albumId")
    suspend fun deleteAlbumAssets(scope: String, albumId: String)

    @Query("DELETE FROM offline_albums WHERE scope = :scope AND albumId = :albumId")
    suspend fun deleteAlbum(scope: String, albumId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCleanup(cleanup: OfflineCleanupEntity)

    @Query("SELECT * FROM offline_cleanup")
    suspend fun cleanups(): List<OfflineCleanupEntity>

    @Query("UPDATE offline_cleanup SET attempts = attempts + 1, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun failCleanup(id: String, error: String, now: Long)

    @Query("DELETE FROM offline_cleanup WHERE id = :id")
    suspend fun deleteCleanup(id: String)
}
