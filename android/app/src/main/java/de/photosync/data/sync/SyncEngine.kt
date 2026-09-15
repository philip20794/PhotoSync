package de.photosync.data.sync

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.media.MediaStoreRepository
import de.photosync.data.remote.CreateAlbumRequest
import de.photosync.data.remote.CreateAssetRequest
import de.photosync.data.remote.PhotoSyncApi
import de.photosync.data.remote.SetAlbumSharingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

internal data class SyncRunResult(val retry: Boolean, val moreWork: Boolean)

internal class SyncEngine(
    context: Context,
    private val database: AppDatabase,
    private val api: PhotoSyncApi,
    private val session: DeviceSessionEntity,
    private val maxUploads: Int = 20,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val mediaStore = MediaStoreRepository(context)
    private val dao = database.syncDao()

    suspend fun run(): SyncRunResult {
        dao.recoverInterruptedUploads(System.currentTimeMillis())
        if (!syncAlbumSharing()) return SyncRunResult(retry = true, moreWork = true)
        if (!inventorySharedAlbums()) return SyncRunResult(retry = true, moreWork = true)

        for (item in dao.nextUploads(session.deviceId, maxUploads)) {
            val album = dao.getAlbum(item.localAlbumId) ?: continue
            if (!album.shareRequested || !album.remoteShared || album.serverAlbumId == null) continue
            try {
                upload(item, album)
            } catch (cancelled: CancellationException) {
                dao.markRetry(item.clientAssetId, "Upload wurde unterbrochen", System.currentTimeMillis())
                throw cancelled
            } catch (error: Throwable) {
                val message = error.syncMessage()
                if (error.isRetryable()) {
                    dao.markRetry(item.clientAssetId, message, System.currentTimeMillis())
                    return SyncRunResult(retry = true, moreWork = true)
                }
                dao.markFailed(item.clientAssetId, message, System.currentTimeMillis())
            }
        }
        return SyncRunResult(retry = false, moreWork = dao.hasRemainingUploads(session.deviceId))
    }

    private suspend fun syncAlbumSharing(): Boolean {
        for (album in dao.getAlbumsNeedingRemoteUpdate(session.deviceId)) {
            try {
                if (album.shareRequested) {
                    val remote = api.createAlbum(CreateAlbumRequest(album.mediaStoreAlbumId, album.title))
                    dao.markRemoteAlbum(album.localAlbumId, remote.id, remote.shared)
                } else {
                    val serverId = album.serverAlbumId ?: continue
                    api.setAlbumSharing(serverId, SetAlbumSharingRequest(false))
                    dao.markRemoteSharing(album.localAlbumId, false)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                dao.markAlbumError(album.localAlbumId, error.syncMessage())
                if (error.isRetryable()) return false
            }
        }
        return true
    }

    private suspend fun inventorySharedAlbums(): Boolean {
        for (album in dao.getRequestedAlbums(session.deviceId)) {
            if (!album.remoteShared || album.serverAlbumId == null) continue
            try {
                mediaStore.scanAlbum(album.volumeName, album.bucketId) { candidates ->
                    val now = System.currentTimeMillis()
                    dao.enqueue(candidates.map { media ->
                        val stableSource = "${session.deviceId}:${album.volumeName}:${media.mediaStoreId}"
                        UploadQueueEntity(
                            clientAssetId = "ms_${stableSource.sha256()}",
                            localAlbumId = album.localAlbumId,
                            contentUri = media.contentUri,
                            originalFileName = media.displayName.sanitizedFileName(),
                            mimeType = media.mimeType,
                            capturedAtMillis = media.capturedAtMillis,
                            fileSize = media.fileSize,
                            width = media.width,
                            height = media.height,
                            durationMillis = media.durationMillis,
                            updatedAt = now,
                        )
                    })
                }
                dao.markScanned(album.localAlbumId, System.currentTimeMillis())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                dao.markAlbumError(album.localAlbumId, error.syncMessage())
                if (error.isRetryable()) return false
            }
        }
        return true
    }

    private suspend fun upload(original: UploadQueueEntity, album: SharedAlbumEntity) {
        val resolved = resolveMetadata(original)
        require(resolved.fileSize > 0) { "Dateigröße ist unbekannt" }
        require(resolved.width > 0 && resolved.height > 0) { "Medienabmessungen sind unbekannt" }
        if (resolved.mimeType.startsWith("video/")) require(resolved.durationMillis != null) {
            "Videodauer ist unbekannt"
        }
        if (resolved != original) {
            dao.updateResolvedMetadata(
                resolved.clientAssetId,
                resolved.fileSize,
                resolved.width,
                resolved.height,
                resolved.durationMillis,
                System.currentTimeMillis(),
            )
        }

        val hash = resolved.sha256 ?: run {
            dao.markHashing(original.clientAssetId, System.currentTimeMillis())
            computeSha256(resolved)
                .also { dao.saveHash(original.clientAssetId, it, System.currentTimeMillis()) }
        }
        val remote = api.createAsset(
            requireNotNull(album.serverAlbumId),
            CreateAssetRequest(
                originalFileName = resolved.originalFileName,
                mimeType = resolved.mimeType,
                capturedAt = resolved.capturedAtMillis?.let { Instant.ofEpochMilli(it).toString() },
                fileSize = resolved.fileSize.toString(),
                width = resolved.width,
                height = resolved.height,
                durationMillis = resolved.durationMillis?.toString(),
                clientAssetId = resolved.clientAssetId,
                expectedSha256 = hash,
            ),
        )
        dao.saveServerAsset(resolved.clientAssetId, remote.id, System.currentTimeMillis())
        if (remote.status == "ready") {
            check(remote.sha256 == hash) { "Server-Prüfsumme stimmt nicht überein" }
            dao.markComplete(resolved.clientAssetId, remote.id, System.currentTimeMillis())
            return
        }
        check(remote.status == "pending") { "Server verarbeitet diesen Upload noch" }

        dao.markUploading(resolved.clientAssetId, 0, System.currentTimeMillis())
        var lastPersistedBytes = 0L
        var lastPersistedAt = SystemClock.elapsedRealtime()
        val body = ContentUriRequestBody(resolver, Uri.parse(resolved.contentUri), resolved.fileSize) { bytes ->
            val now = SystemClock.elapsedRealtime()
            if (bytes == resolved.fileSize || bytes - lastPersistedBytes >= PROGRESS_BYTES || now - lastPersistedAt >= PROGRESS_MILLIS) {
                runBlocking(Dispatchers.IO) {
                    dao.updateUploadProgress(resolved.clientAssetId, bytes, System.currentTimeMillis())
                }
                lastPersistedBytes = bytes
                lastPersistedAt = now
            }
        }
        val uploaded = api.uploadOriginal(remote.id, body)
        check(uploaded.status == "ready" && uploaded.sha256 == hash) { "Server hat das Original nicht bestätigt" }
        dao.markComplete(resolved.clientAssetId, remote.id, System.currentTimeMillis())
    }

    private suspend fun resolveMetadata(item: UploadQueueEntity): UploadQueueEntity = withContext(Dispatchers.IO) {
        val uri = Uri.parse(item.contentUri)
        var fileSize = item.fileSize
        var width = item.width
        var height = item.height
        var duration = item.durationMillis
        if (fileSize <= 0) {
            fileSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: fileSize
        }
        if (item.mimeType.startsWith("image/") && (width <= 0 || height <= 0)) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (width <= 0) width = options.outWidth
            if (height <= 0) height = options.outHeight
        }
        if (item.mimeType.startsWith("video/") && (width <= 0 || height <= 0 || duration == null)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                if (width <= 0) width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: width
                if (height <= 0) height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
                if (duration == null) duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }
        item.copy(fileSize = fileSize, width = width, height = height, durationMillis = duration)
    }

    private suspend fun computeSha256(item: UploadQueueEntity): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = resolver.openInputStream(Uri.parse(item.contentUri)) ?: throw IOException("Medium ist nicht mehr lesbar")
        var bytes = 0L
        stream.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        if (bytes != item.fileSize) throw IllegalStateException("Medium wurde seit der Inventarisierung verändert")
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val PROGRESS_BYTES = 1024L * 1024L
        const val PROGRESS_MILLIS = 750L
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun String.sanitizedFileName(): String = replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
    .trim()
    .ifBlank { "medium" }
    .take(255)

private fun Throwable.isRetryable(): Boolean = when (this) {
    is IOException, is SecurityException -> true
    is HttpException -> code() == 401 || code() == 408 || code() == 409 || code() == 429 || code() >= 500
    else -> false
}

private fun Throwable.syncMessage(): String = when (this) {
    is SecurityException -> "Medienberechtigung fehlt"
    is IOException -> "Verbindung oder Mediendatei vorübergehend nicht verfügbar"
    is HttpException -> when (code()) {
        401 -> "Geräteanmeldung ist nicht mehr gültig"
        413 -> "Original ist größer als das Serverlimit"
        422 -> "Original stimmt nicht mit seinen Metadaten überein"
        else -> "Serverfehler (${code()})"
    }
    is IllegalArgumentException, is IllegalStateException -> message ?: "Medium kann nicht synchronisiert werden"
    else -> "Synchronisierung fehlgeschlagen"
}
