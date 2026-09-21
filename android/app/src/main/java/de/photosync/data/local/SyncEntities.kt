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
    indices = [Index("shareRequested"), Index("backupRequested"), Index("sourceDeviceId")],
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
    val backupRequested: Boolean = false,
    val remoteShared: Boolean = false,
    val remoteBackedUp: Boolean = false,
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
    indices = [Index("localAlbumId"), Index("status"), Index(value = ["localAlbumId", "mediaStoreId"])],
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
    val mediaStoreId: Long = -1,
    val dateModifiedSeconds: Long = 0,
    val sha256: String? = null,
    val serverAssetId: String? = null,
    val status: String = UploadStatus.PENDING,
    val uploadedBytes: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val uploadSessionId: String? = null,
)

object UploadStatus {
    const val PENDING = "pending"
    const val HASHING = "hashing"
    const val UPLOADING = "uploading"
    const val RETRY = "retry"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
    const val REPLACEMENT_PENDING = "replacement_pending"
    const val REPLACEMENT_DELETE_PENDING = "replacement_delete_pending"
    const val CANCEL_PENDING = "cancel_pending"
    const val REPLACEMENT_CANCEL_PENDING = "replacement_cancel_pending"
    const val ABANDONED = "abandoned"
    const val DELETE_PENDING = "delete_pending"
    const val DELETED = "deleted"
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

    @Query("UPDATE shared_albums SET title = :title, volumeName = :volumeName, bucketId = :bucketId, backupRequested = 1, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun enableExistingBackup(localAlbumId: String, title: String, volumeName: String, bucketId: String)

    @Transaction
    suspend fun enableBackup(album: SharedAlbumEntity) {
        insertAlbum(album)
        enableExistingBackup(album.localAlbumId, album.title, album.volumeName, album.bucketId)
    }

    @Query("UPDATE shared_albums SET backupRequested = 0 WHERE sourceDeviceId = :deviceId")
    suspend fun disableAutoBackup(deviceId: String)

    @Query("UPDATE shared_albums SET shareRequested = :requested, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun setShareRequested(localAlbumId: String, requested: Boolean)

    @Query("SELECT * FROM shared_albums WHERE localAlbumId = :localAlbumId")
    suspend fun getAlbum(localAlbumId: String): SharedAlbumEntity?

    @Query("SELECT * FROM shared_albums WHERE sourceDeviceId = :deviceId AND (shareRequested = 1 OR backupRequested = 1) ORDER BY localAlbumId")
    suspend fun getRequestedAlbums(deviceId: String): List<SharedAlbumEntity>

    @Query("SELECT * FROM shared_albums WHERE sourceDeviceId = :deviceId AND (((shareRequested = 1 OR backupRequested = 1) AND serverAlbumId IS NULL) OR shareRequested != remoteShared OR (backupRequested = 1 AND remoteBackedUp = 0)) ORDER BY localAlbumId")
    suspend fun getAlbumsNeedingRemoteUpdate(deviceId: String): List<SharedAlbumEntity>

    @Query("UPDATE shared_albums SET serverAlbumId = :serverAlbumId, remoteShared = :remoteShared, remoteBackedUp = :remoteBackedUp, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markRemoteAlbum(localAlbumId: String, serverAlbumId: String, remoteShared: Boolean, remoteBackedUp: Boolean)

    @Query("UPDATE shared_albums SET remoteShared = :remoteShared, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markRemoteSharing(localAlbumId: String, remoteShared: Boolean)

    @Query("UPDATE shared_albums SET lastScanAt = :scannedAt, lastError = NULL WHERE localAlbumId = :localAlbumId")
    suspend fun markScanned(localAlbumId: String, scannedAt: Long)

    @Query("UPDATE shared_albums SET lastError = :message WHERE localAlbumId = :localAlbumId")
    suspend fun markAlbumError(localAlbumId: String, message: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(items: List<UploadQueueEntity>): List<Long>

    @Query("SELECT * FROM upload_queue WHERE localAlbumId = :localAlbumId AND mediaStoreId = :mediaStoreId LIMIT 1")
    suspend fun getDiscoveredUpload(localAlbumId: String, mediaStoreId: Long): UploadQueueEntity?

    @Query("SELECT * FROM upload_queue WHERE localAlbumId = :localAlbumId AND mediaStoreId >= 0")
    suspend fun knownUploads(localAlbumId: String): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET status = 'delete_pending', lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'complete' AND serverAssetId IS NOT NULL")
    suspend fun markDeletePending(clientAssetId: String, now: Long): Int

    @Query("UPDATE upload_queue SET status = CASE WHEN serverAssetId IS NULL THEN 'abandoned' ELSE 'cancel_pending' END, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status IN ('pending', 'retry', 'hashing', 'uploading', 'failed', 'replacement_pending')")
    suspend fun markMissingBeforeComplete(clientAssetId: String, now: Long): Int

    @Query("SELECT q.* FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND q.status = 'replacement_pending' ORDER BY q.updatedAt, q.clientAssetId LIMIT :limit")
    suspend fun nextReplacements(deviceId: String, limit: Int): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET sha256 = :sha256, status = 'complete', uploadedBytes = fileSize, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'replacement_pending' AND serverAssetId IS NOT NULL")
    suspend fun markReplacementUnchanged(clientAssetId: String, sha256: String, now: Long): Int

    @Query("UPDATE upload_queue SET sha256 = :sha256, status = 'replacement_delete_pending', uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'replacement_pending' AND serverAssetId IS NOT NULL")
    suspend fun markReplacementChanged(clientAssetId: String, sha256: String, now: Long): Int

    @Query("UPDATE upload_queue SET attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'replacement_pending'")
    suspend fun markReplacementRetry(clientAssetId: String, message: String, now: Long): Int

    @Query("SELECT q.* FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND q.status IN ('cancel_pending', 'replacement_cancel_pending') AND q.serverAssetId IS NOT NULL ORDER BY q.updatedAt, q.clientAssetId LIMIT :limit")
    suspend fun nextCancellations(deviceId: String, limit: Int): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET status = CASE WHEN status = 'replacement_cancel_pending' THEN 'pending' ELSE 'abandoned' END, serverAssetId = NULL, sha256 = CASE WHEN status = 'replacement_cancel_pending' THEN NULL ELSE sha256 END, uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status IN ('cancel_pending', 'replacement_cancel_pending')")
    suspend fun finishCancellation(clientAssetId: String, now: Long): Int

    @Query("SELECT q.* FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND q.status IN ('delete_pending', 'replacement_delete_pending') AND q.serverAssetId IS NOT NULL ORDER BY q.updatedAt, q.clientAssetId LIMIT :limit")
    suspend fun nextDeletes(deviceId: String, limit: Int): List<UploadQueueEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND q.status IN ('delete_pending', 'replacement_delete_pending') AND q.serverAssetId IS NOT NULL)")
    suspend fun hasRemainingDeletes(deviceId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND q.status IN ('replacement_pending', 'cancel_pending', 'replacement_cancel_pending'))")
    suspend fun hasRemainingReconciliation(deviceId: String): Boolean

    @Query("UPDATE upload_queue SET status = 'deleted', uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markDeleted(clientAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'pending', serverAssetId = NULL, uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status = 'replacement_delete_pending'")
    suspend fun finishReplacementDelete(clientAssetId: String, now: Long): Int

    @Query("UPDATE upload_queue SET attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId AND status IN ('delete_pending', 'replacement_delete_pending')")
    suspend fun markDeleteRetry(clientAssetId: String, message: String, now: Long)

    @Query("DELETE FROM upload_queue WHERE clientAssetId = :clientAssetId")
    suspend fun deleteUpload(clientAssetId: String)

    @Query("""
        UPDATE upload_queue
        SET contentUri = :contentUri,
            originalFileName = :originalFileName,
            mimeType = :mimeType,
            capturedAtMillis = :capturedAtMillis,
            fileSize = :fileSize,
            width = :width,
            height = :height,
            durationMillis = :durationMillis,
            mediaStoreId = :mediaStoreId,
            dateModifiedSeconds = :dateModifiedSeconds,
            updatedAt = :now
        WHERE clientAssetId = :clientAssetId
    """)
    suspend fun updateDiscovery(
        clientAssetId: String,
        contentUri: String,
        originalFileName: String,
        mimeType: String,
        capturedAtMillis: Long?,
        fileSize: Long,
        width: Int,
        height: Int,
        durationMillis: Long?,
        mediaStoreId: Long,
        dateModifiedSeconds: Long,
        now: Long,
    )

    @Transaction
    suspend fun enqueueDiscovered(items: List<UploadQueueEntity>) {
        for (item in items) {
            val existing = getDiscoveredUpload(item.localAlbumId, item.mediaStoreId)
                ?: getUpload(item.clientAssetId)
            if (existing == null) {
                enqueue(listOf(item))
                continue
            }
            val legacy = existing.mediaStoreId < 0
            if (legacy) {
                // Adopt the newly discovered MediaStore identity, but retain any
                // remote version until it has been compared and deleted/cancelled.
                val nextStatus = when {
                    existing.status == UploadStatus.COMPLETE && existing.serverAssetId != null ->
                        UploadStatus.REPLACEMENT_PENDING
                    existing.serverAssetId != null -> UploadStatus.REPLACEMENT_CANCEL_PENDING
                    else -> UploadStatus.PENDING
                }
                deleteUpload(existing.clientAssetId)
                enqueue(listOf(item.copy(
                    clientAssetId = versionedClientAssetId(item),
                    sha256 = if (nextStatus == UploadStatus.REPLACEMENT_PENDING) existing.sha256 else null,
                    serverAssetId = existing.serverAssetId,
                    status = nextStatus,
                    uploadedBytes = 0,
                    uploadSessionId = null,
                    attempts = 0,
                    lastError = null,
                )))
                continue
            }
            val unchanged = (
                existing.mediaStoreId == item.mediaStoreId &&
                    existing.fileSize == item.fileSize &&
                    existing.dateModifiedSeconds == item.dateModifiedSeconds
                )
            if (existing.status == UploadStatus.DELETED || existing.status == UploadStatus.ABANDONED) {
                deleteUpload(existing.clientAssetId)
                enqueue(listOf(item.copy(
                    clientAssetId = reintroducedClientAssetId(item),
                    sha256 = null, serverAssetId = null, status = UploadStatus.PENDING,
                    uploadedBytes = 0, attempts = 0, lastError = null,
                )))
                continue
            }
            if (unchanged) {
                updateDiscovery(
                    existing.clientAssetId,
                    item.contentUri,
                    item.originalFileName,
                    item.mimeType,
                    item.capturedAtMillis,
                    item.fileSize,
                    item.width,
                    item.height,
                    item.durationMillis,
                    item.mediaStoreId,
                    item.dateModifiedSeconds,
                    item.updatedAt,
                )
            } else {
                val nextStatus = when {
                    existing.status == UploadStatus.COMPLETE && existing.serverAssetId != null -> UploadStatus.REPLACEMENT_PENDING
                    existing.serverAssetId != null -> UploadStatus.REPLACEMENT_CANCEL_PENDING
                    else -> UploadStatus.PENDING
                }
                deleteUpload(existing.clientAssetId)
                enqueue(listOf(item.copy(
                    clientAssetId = versionedClientAssetId(item),
                    sha256 = if (nextStatus == UploadStatus.REPLACEMENT_PENDING) existing.sha256 else null,
                    serverAssetId = existing.serverAssetId,
                    status = nextStatus,
                    uploadedBytes = 0,
                    uploadSessionId = null,
                    attempts = 0,
                    lastError = null,
                )))
            }
        }
    }

    @Query("SELECT q.* FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND (a.shareRequested = 1 OR a.backupRequested = 1) AND q.status IN ('pending', 'retry') ORDER BY q.updatedAt, q.clientAssetId LIMIT :limit")
    suspend fun nextUploads(deviceId: String, limit: Int): List<UploadQueueEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM upload_queue q JOIN shared_albums a ON a.localAlbumId = q.localAlbumId WHERE a.sourceDeviceId = :deviceId AND (a.shareRequested = 1 OR a.backupRequested = 1) AND q.status IN ('pending', 'retry', 'hashing', 'uploading'))")
    suspend fun hasRemainingUploads(deviceId: String): Boolean

    @Query("UPDATE upload_queue SET status = 'retry', lastError = 'Upload wurde unterbrochen', updatedAt = :now WHERE status IN ('hashing', 'uploading')")
    suspend fun recoverInterruptedUploads(now: Long): Int

    @Query("UPDATE upload_queue SET status = 'hashing', uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markHashing(clientAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET sha256 = :sha256, status = 'pending', uploadedBytes = 0, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
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

    @Query("UPDATE upload_queue SET status = 'complete', uploadedBytes = fileSize, serverAssetId = :serverAssetId, uploadSessionId = NULL, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markComplete(clientAssetId: String, serverAssetId: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'retry', attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markRetry(clientAssetId: String, message: String, now: Long)

    @Query("UPDATE upload_queue SET status = 'failed', uploadedBytes = 0, uploadSessionId = NULL, attempts = attempts + 1, lastError = :message, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun markFailed(clientAssetId: String, message: String, now: Long)

    @Query("UPDATE upload_queue SET uploadSessionId = :sessionId, status = 'uploading', uploadedBytes = :offset, lastError = NULL, updatedAt = :now WHERE clientAssetId = :clientAssetId")
    suspend fun saveUploadSession(clientAssetId: String, sessionId: String, offset: Long, now: Long)

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


internal fun versionedClientAssetId(item: UploadQueueEntity): String {
    val fingerprint = item.mediaStoreId.toString() + ":" + item.fileSize + ":" + item.dateModifiedSeconds
    val version = java.security.MessageDigest.getInstance("SHA-256")
        .digest(fingerprint.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(20)
    return item.clientAssetId + "_v" + version
}

internal fun reintroducedClientAssetId(item: UploadQueueEntity): String =
    item.clientAssetId + "_reintroduced_" + System.currentTimeMillis().toString(36)
