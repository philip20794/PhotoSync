package de.photosync.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "shared_albums",
    indices = [Index("shareRequested"), Index("sourceDeviceId")],
)
data class SharedAlbumEntity(
    @PrimaryKey val localAlbumId: String,
    val mediaStoreAlbumId: String,
    val sourceDeviceId: String,
    val volumeName: String,
    val bucketId: String,
    val title: String,
    val serverAlbumId: String? = null,
    val shareRequested: Boolean = true,
    val remoteShared: Boolean = false,
    val lastScanAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "upload_queue",
    foreignKeys = [ForeignKey(
        entity = SharedAlbumEntity::class,
        parentColumns = ["localAlbumId"],
        childColumns = ["localAlbumId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("localAlbumId"), Index("status")],
)
data class UploadQueueEntity(
    @PrimaryKey val clientAssetId: String,
    val localAlbumId: String,
    val contentUri: String,
    val originalFileName: String,
    val mimeType: String,
    val capturedAtMillis: Long?,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val sha256: String? = null,
    val serverAssetId: String? = null,
    val status: String = UploadStatus.PENDING,
    val uploadedBytes: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

object UploadStatus {
    const val PENDING = "pending"
    const val HASHING = "hashing"
    const val UPLOADING = "uploading"
    const val RETRY = "retry"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
}

data class AlbumSyncProgress(
    val localAlbumId: String,
    val mediaStoreAlbumId: String,
    val shareRequested: Boolean,
    val remoteShared: Boolean,
    val lastScanAt: Long?,
    val lastError: String?,
    val totalCount: Long,
    val completedCount: Long,
    val failedCount: Long,
    val uploadedBytes: Long,
    val totalBytes: Long,
)

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: SharedAlbumEntity): Long

    @Query("UPDATE shared_albums SET title = :title, shareRequested = 1, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun enableExistingAlbum(localAlbumId: String, title: String)

    @Transaction
    suspend fun enableAlbum(album: SharedAlbumEntity) {
        insertAlbum(album)
        enableExistingAlbum(album.localAlbumId, album.title)
    }

    @Query("UPDATE shared_albums SET shareRequested = :requested, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun setShareRequested(localAlbumId: String, requested: Boolean)

    @Query("SELECT * FROM shared_albums WHERE localAlbumId = :localAlbumId")
    suspend fun getAlbum(localAlbumId: String): SharedAlbumEntity?

    @Query("SELECT * FROM shared_albums WHERE sourceDeviceId = :deviceId AND shareRequested = 1 ORDER BY localAlbumId")
    suspend fun getRequestedAlbums(deviceId: String): List<SharedAlbumEntity>

    @Query("SELECT * FROM shared_albums WHERE sourceDeviceId = :deviceId AND ((shareRequested = 1 AND (serverAlbumId IS NULL OR remoteShared = 0)) OR (shareRequested = 0 AND remoteShared = 1)) ORDER BY localAlbumId")
    suspend fun getAlbumsNeedingRemoteUpdate(deviceId: String): List<SharedAlbumEntity>

    @Query("UPDATE shared_albums SET serverAlbumId = :serverAlbumId, remoteShared = :remoteShared, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markRemoteAlbum(localAlbumId: String, serverAlbumId: String, remoteShared: Boolean)

    @Query("UPDATE shared_albums SET remoteShared = :remoteShared, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markRemoteSharing(localAlbumId: String, remoteShared: Boolean)

    @Query("UPDATE shared_albums SET lastScanAt = :scannedAt, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markScanned(localAlbumId: String, scannedAt: Long)

    @Query("UPDATE shared_albums SET lastError = :message WHERE localAlbumId = :localAlbumId")
    suspend fun markAlbumError(localAlbumId: String, message: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(items: List<UploadQueueEntity>): List<Long>

    @Query("SELECT q.* FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND a.shareRequested = 1 AND q.status IN ('pending', 'retry') ORDER BY q.updatedAt, q.clientAssetId LIMIT :limit")
    suspend fun nextUploads(deviceId: String, limit: Int): List<UploadQueueEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND a.shareRequested = 1 AND q.status IN ('pending', 'retry', 'hashing', 'uploading'))")
    suspend fun hasRemainingUploads(deviceId: String): Boolean

    @Query("UPDATE upload_queue SET status = 'retry', uploadedBytes = 0, lastError = 'Upload wurde unterbrochen', updatedAt = :now WHERE status IN ('hashing', 'uploading')")
    suspend fun recoverInterruptedUploads(now: Long): Int

    @Query("UPDATE upload_queue SET status = 'hashing', uploadedBytes = 0, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markHashing(clientAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET sha256 = :sha256, status = 'pending', uploadedBytes = 0, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun saveHash(clientAssetId: String, sha256: String, now: Long)

    @Query("UPDATE upload_queue SET fileSize = :fileSize, width = :width, height = :height, durationMillis = :durationMillis, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun updateResolvedMetadata(
        clientAssetId: String,
        fileSize: Long,
        width: Int,
        height: Int,
        durationMillis: Long?,
        now: Long,
    )

    @Query("UPDATE upload_queue SET serverAssetId = :serverAssetId, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun saveServerAsset(clientAssetId: String, serverAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'uploading', uploadedBytes = :uploadedBytes, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markUploading(clientAssetId: String, uploadedBytes: Long, now: Long)

    @Query("UPDATE upload_queue SET uploadedBytes = :uploadedBytes, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'uploading'")
    suspend fun updateUploadProgress(clientAssetId: String, uploadedBytes: Long, now: Long)

    @Query("UPDATE upload_queue SET status = 'complete', uploadedBytes = fileSize, serverAssetId = :serverAssetId, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markComplete(clientAssetId: String, serverAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'retry', uploadedBytes = 0, attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markRetry(clientAssetId: String, message: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'failed', uploadedBytes = 0, attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markFailed(clientAssetId: String, message: String, now: Long)

    @Query("SELECT * FROM upload_queue WHERE clientAssetId = :clientAssetId")
    suspend fun getUpload(clientAssetId: String): UploadQueueEntity?

    @Query("""
        SELECT a.localAlbumId AS localAlbumId,
               a.mediaStoreAlbumId AS mediaStoreAlbumId,
               a.shareRequested AS shareRequested,
               a.remoteShared AS remoteShared,
               a.lastScanAt AS lastScanAt,
               a.lastError AS lastError,
               COUNT(q.clientAssetId) AS totalCount,
               COALESCE(SUM(CASE WHEN q.status = 'complete' THEN 1 ELSE 0 END), 0) AS completedCount,
               COALESCE(SUM(CASE WHEN q.status = 'failed' THEN 1 ELSE 0 END), 0) AS failedCount,
               COALESCE(SUM(CASE WHEN q.status = 'complete' THEN q.fileSize ELSE q.uploadedBytes END), 0) AS uploadedBytes,
               COALESCE(SUM(q.fileSize), 0) AS totalBytes
        FROM shared_albums a
        LEFT JOIN upload_queue q ON q.localAlbumId = a.localAlbumId
        WHERE a.sourceDeviceId = :deviceId
        GROUP BY a.localAlbumId, a.mediaStoreAlbumId, a.shareRequested, a.remoteShared, a.lastScanAt, a.lastError
        ORDER BY a.localAlbumId
    """)
    fun observeProgress(deviceId: String): Flow<List<AlbumSyncProgress>>
}
