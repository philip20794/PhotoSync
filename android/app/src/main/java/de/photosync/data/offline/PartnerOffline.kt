package de.photosync.data.offline

import android.content.Context
import android.os.SystemClock
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PhotoSyncApi
import de.photosync.data.remote.RetrofitFactory
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import androidx.room.withTransaction
import de.photosync.data.sync.WorkerLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun resumeRange(offset: Long): String? = if (offset > 0) "bytes=" + offset + "-" else null

internal fun writeOfflineBody(part: File, append: Boolean, body: okhttp3.ResponseBody) {
    FileOutputStream(part, append).use { output ->
        body.byteStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        output.fd.sync()
    }
}

internal fun deleteTracked(files: Iterable<File>, delete: (File) -> Boolean = { it.delete() }): Boolean {
    var complete = true
    for (file in files) if (file.exists() && !delete(file)) complete = false
    return complete
}

internal fun deleteTreeTracked(root: File, delete: (File) -> Boolean = { it.deleteRecursively() }): Boolean =
    !root.exists() || delete(root)

internal fun completePartIsValid(file: File, size: Long, expectedSha256: String): Boolean =
    file.isFile && file.length() == size && fileSha256(file) == expectedSha256

internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun completedOfflineState(state: OfflineAssetEntity, descriptor: OfflineDescriptor, path: String): OfflineAssetEntity =
    state.copy(actualVariant = descriptor.variant, status = OfflineStatus.READY, localPath = path,
        bytesDownloaded = descriptor.size, lastError = null, updatedAt = System.currentTimeMillis())

internal fun desiredOfflineVariant(album: OfflineAlbumEntity, current: OfflineAssetEntity?): String =
    if (current?.overridesAlbumMode == true) current.desiredVariant else album.desiredMode

class InsufficientStorageException : IOException("Nicht genug freier Speicher")
class PermanentOfflineException(message: String) : IOException(message)

internal enum class PartResponseAction { APPEND, RESTART_WITH_BODY, KEEP_AND_RETRY, KEEP_AND_FAIL, DISCARD_AND_RETRY }

internal fun partResponseAction(offset: Long, expectedSize: Long, code: Int, contentRange: String?): PartResponseAction = when {
    code == 206 && contentRange == "bytes " + offset + "-" + (expectedSize - 1) + "/" + expectedSize -> PartResponseAction.APPEND
    code == 206 -> PartResponseAction.DISCARD_AND_RETRY
    code == 200 -> PartResponseAction.RESTART_WITH_BODY
    code == 401 || code == 403 -> PartResponseAction.KEEP_AND_FAIL
    code == 416 -> PartResponseAction.DISCARD_AND_RETRY
    code == 429 || code in 500..599 -> PartResponseAction.KEEP_AND_RETRY
    else -> PartResponseAction.KEEP_AND_FAIL
}

data class OfflineDescriptor(val variant: String, val version: String, val sha256: String, val size: Long)

fun AssetDto.offlineDescriptor(variant: String): OfflineDescriptor? {
    if (variant == OfflineMode.ORIGINAL) {
        return OfflineDescriptor(variant, updatedAt, sha256 ?: return null, fileSize.toLongOrNull() ?: return null)
    }
    val derivative = derivatives.firstOrNull { it.kind == "optimized" && it.status == "ready" } ?: return null
    return OfflineDescriptor(variant, derivative.updatedAt, derivative.sha256 ?: return null, derivative.fileSize?.toLongOrNull() ?: return null)
}

fun hasEnoughOfflineSpace(available: Long, remaining: Long, reserve: Long = 32L * 1024L * 1024L): Boolean =
    remaining >= 0 && reserve >= 0 && available >= reserve && remaining <= available - reserve

internal fun offlineWorkConstraints(wifiOnly: Boolean = false): Constraints =
    Constraints.Builder().setRequiredNetworkType(
        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
    ).build()

class PartnerOfflineRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: String,
) {
    private val scopedRoot = File(context.filesDir, "partner-offline-v2/" + scopeHash(scope))
    val albums: Flow<List<OfflineAlbumEntity>> = database.offlineDao().observeAlbums(scope)

    fun asset(assetId: String): Flow<OfflineAssetEntity?> = database.offlineDao().observeAsset(scope, assetId)

    /** Removes one remote asset before its change-feed work is acknowledged. */
    suspend fun removeRemoteAsset(albumId: String, assetId: String): Boolean {
        val state = database.offlineDao().asset(scope, assetId) ?: return true
        val files = linkedSetOf<File>()
        if (state.localPath != null) {
            val local = safeScopedFile(state.localPath) ?: return false
            files += local
            files += File(local.absolutePath + ".part")
        }
        File(scopedRoot, albumId).listFiles()?.filter { it.name.startsWith(assetId + "-") }?.let(files::addAll)
        if (!deleteTracked(files)) return false
        return database.withTransaction {
            val current = database.offlineDao().asset(scope, assetId)
            if (current?.localPath != state.localPath || current?.updatedAt != state.updatedAt) false
            else {
                database.offlineDao().saveAsset(state.copy(
                    actualVariant = null,
                    status = OfflineStatus.REMOTE_DELETED,
                    localPath = null,
                    bytesDownloaded = 0,
                    lastError = null,
                    updatedAt = System.currentTimeMillis(),
                ))
                true
            }
        }
    }

    suspend fun removeRemoteAlbum(albumId: String): Boolean {
        val states = database.offlineDao().assets(scope, albumId)
        for (state in states) if (!removeRemoteAsset(albumId, state.assetId)) return false
        if (database.offlineDao().album(scope, albumId) == null) return true
        return database.withTransaction {
            database.offlineDao().deleteAlbumAssets(scope, albumId)
            database.offlineDao().deleteAlbum(scope, albumId)
            true
        }
    }

    private fun safeScopedFile(path: String): File? {
        val root = scopedRoot.canonicalFile
        val file = File(path).canonicalFile
        return if (file.path == root.path || file.path.startsWith(root.path + File.separator)) file else null
    }

    suspend fun restoreOwnBackup(albumId: String, optimizedBytes: Long, originalBytes: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val generation = maxOf(now, (database.offlineDao().album(scope, albumId)?.updatedAt ?: 0) + 1)
            database.offlineDao().saveAlbum(
                OfflineAlbumEntity(
                    scope = scope,
                    albumId = albumId,
                    desiredMode = OfflineMode.ORIGINAL,
                    estimatedOptimizedBytes = optimizedBytes,
                    estimatedOriginalBytes = originalBytes,
                    status = OfflineStatus.PENDING,
                    updatedAt = generation,
                ),
            )
            database.offlineDao().setAlbumVariant(scope, albumId, OfflineMode.ORIGINAL, generation)
        }
        enqueue(albumId)
    }

    suspend fun setAlbumMode(albumId: String, mode: String, optimizedBytes: Long, originalBytes: Long) {
        require(mode in setOf(OfflineMode.NONE, OfflineMode.OPTIMIZED, OfflineMode.ORIGINAL))
        val now = System.currentTimeMillis()
        database.withTransaction {
            require(database.remoteDao().albums(scope).any { it.id == albumId }) { "Kein freigegebenes Partneralbum" }
            val generation = maxOf(now, (database.offlineDao().album(scope, albumId)?.updatedAt ?: 0) + 1)
            database.offlineDao().saveAlbum(OfflineAlbumEntity(scope, albumId, mode, optimizedBytes, originalBytes, OfflineStatus.PENDING, updatedAt = generation))
            if (mode != OfflineMode.NONE) database.offlineDao().setAlbumVariant(scope, albumId, mode, generation)
        }
        enqueue(albumId)
    }

    suspend fun setAssetVariant(asset: AssetDto, variant: String) {
        require(variant == OfflineMode.OPTIMIZED || variant == OfflineMode.ORIGINAL)
        val descriptor = asset.offlineDescriptor(variant) ?: error("Variante ist noch nicht verfügbar")
        val dao = database.offlineDao()
        require(dao.album(scope, asset.albumId)?.desiredMode in setOf(OfflineMode.OPTIMIZED, OfflineMode.ORIGINAL))
        database.withTransaction {
        val existing = dao.asset(scope, asset.id)
        if (existing == null) {
            dao.saveAsset(OfflineAssetEntity(
                scope = scope, assetId = asset.id, albumId = asset.albumId, desiredVariant = variant, overridesAlbumMode = true,
                variantVersion = descriptor.version, expectedSha256 = descriptor.sha256, fileSize = descriptor.size,
            ))
        } else dao.setAssetVariant(scope, asset.id, variant, System.currentTimeMillis())
        dao.invalidateAlbum(scope, asset.albumId)
        }
        enqueue(asset.albumId)
    }

    suspend fun retry(albumId: String, replace: Boolean = false) = enqueue(albumId, replace)

    private suspend fun enqueue(albumId: String, replace: Boolean = false) {
        val wifiOnly = database.settingsDao().get(scope)?.wifiOnly == true
        val request = OneTimeWorkRequestBuilder<PartnerOfflineWorker>()
            .setInputData(Data.Builder().putString(PartnerOfflineWorker.ALBUM_ID, albumId).putString(PartnerOfflineWorker.SCOPE, scope).build())
            .setConstraints(offlineWorkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "partner-offline-" + scopeHash(scope) + "-" + albumId,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class PartnerOfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val database = AppDatabase.get(context)
    private val dao = database.offlineDao()
    private lateinit var root: File

    override suspend fun doWork(): Result {
        val albumId = inputData.getString(ALBUM_ID) ?: return Result.failure()
        val scope = inputData.getString(SCOPE) ?: return Result.failure()
        root = File(applicationContext.filesDir, "partner-offline-v2/" + scopeHash(scope))
        // Different albums may run concurrently; never recover another worker's rows.
        val lock = WorkerLock.tryAcquire(File(applicationContext.filesDir, "offline-locks/" + scopeHash(scope) + "-" + albumId)) ?: return Result.retry()
        return lock.use { runLocked(scope, albumId) }
    }

    private suspend fun runLocked(scope: String, albumId: String): Result {
        root.mkdirs()
        dao.recoverAlbum(scope, albumId)
        val album = dao.album(scope, albumId) ?: return Result.success()
        if (album.desiredMode == OfflineMode.NONE) {
            val failed = dao.assets(scope, albumId).any { !deleteAssetFiles(albumId, it) }
            val albumDir = File(root, albumId)
            val directoryDeleted = deleteTreeTracked(albumDir)
            if (failed || !directoryDeleted) {
                dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.FAILED, "Offline-Dateien konnten nicht vollständig gelöscht werden")
                return Result.retry()
            }
            val finished = database.withTransaction {
                if (dao.album(scope, albumId)?.updatedAt != album.updatedAt) false
                else {
                    dao.deleteAlbumAssets(scope, albumId)
                    dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.READY, null) == 1
                }
            }
            return if (finished) Result.success() else Result.retry()
        }
        val server = database.appStateDao().getServer() ?: return Result.failure()
        val session = database.appStateDao().getSession()
        if (session == null || de.photosync.data.sync.remoteScope(server.baseUrl, session.userId) != scope) {
            dao.requestRemoval(scope, albumId)
            return Result.retry()
        }
        val credentials = SecureCredentialStore(applicationContext)
        val token = credentials.readAccessToken() ?: return Result.failure()
        val api = RetrofitFactory.create(server.baseUrl, fixedToken = token)
        return try {
            val accessible = api.partnerAlbums().albums.any { it.id == albumId } ||
                api.backups().albums.any { it.id == albumId }
            if (!accessible) {
                dao.requestRemoval(scope, albumId)
                return Result.retry()
            }
            setForeground(de.photosync.data.sync.TransferForeground.info(applicationContext, albumId.hashCode(), "Backup wird auf dieses Gerät heruntergeladen"))
            var cursor: String? = album.metadataCursor
            var complete = album.metadataComplete
            while (!complete) {
                if (isStopped) return Result.retry()
                val page = api.albumAssets(albumId, 100, cursor)
                for (asset in page.assets) {
                    currentCoroutineContext().ensureActive()
                    if (dao.album(scope, albumId)?.updatedAt != album.updatedAt) return Result.retry()
                    reconcile(api, album, asset)
                }
                cursor = page.nextCursor
                complete = cursor == null
                if (dao.advanceMetadata(scope, albumId, album.updatedAt, cursor, complete) == 0) return Result.retry()
            }
            for (removed in dao.unseenAssets(scope, albumId, album.updatedAt)) {
                if (removed.status == OfflineStatus.REMOTE_DELETED) {
                    dao.saveAsset(removed.copy(seenGeneration = album.updatedAt))
                    continue
                }
                if (!deleteAssetFiles(albumId, removed)) {
                    dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.FAILED, "Entfernte Offline-Datei konnte nicht gelöscht werden")
                    return Result.retry()
                }
                val deleted = database.withTransaction {
                    if (dao.album(scope, albumId)?.updatedAt != album.updatedAt) false
                    else {
                        if (removed.overridesAlbumMode) {
                            dao.saveAsset(removed.copy(
                                actualVariant = null,
                                status = OfflineStatus.REMOTE_DELETED,
                                localPath = null,
                                bytesDownloaded = 0,
                                lastError = null,
                                seenGeneration = album.updatedAt,
                                updatedAt = System.currentTimeMillis(),
                            ))
                        } else {
                            dao.deleteAsset(scope, removed.assetId)
                        }
                        true
                    }
                }
                if (!deleted) return Result.retry()
            }
            if (dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.READY, null) == 0) return Result.retry()
            Result.success()
        } catch (error: InsufficientStorageException) {
            dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.FAILED, error.message)
            de.photosync.data.sync.SyncErrorNotifier.show(applicationContext, database, scope, "Nicht genug Speicher für Offline-Medien.")
            Result.retry()
        } catch (error: PermanentOfflineException) {
            dao.recoverAlbum(scope, albumId)
            dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.FAILED, error.message)
            de.photosync.data.sync.SyncErrorNotifier.show(applicationContext, database, scope, error.message ?: "Offline-Medium konnte nicht geprüft werden.")
            Result.failure()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            dao.recoverAlbum(scope, albumId)
            dao.finishAlbum(scope, albumId, album.updatedAt, OfflineStatus.RETRY, error.message ?: "Download unterbrochen")
            Result.retry()
        }
    }

    private suspend fun reconcile(api: PhotoSyncApi, album: OfflineAlbumEntity, asset: AssetDto) {
        val current = dao.asset(album.scope, asset.id)
        val desired = desiredOfflineVariant(album, current)
        val descriptor = asset.offlineDescriptor(desired) ?: throw IOException("Variante ist noch nicht verfügbar")
        val target = targetFile(album.albumId, asset.id, descriptor)
        cleanupAsset(album.albumId, asset.id, setOfNotNull(current?.localPath, target.absolutePath))
        if (current?.actualVariant == desired && current.variantVersion == descriptor.version &&
            current.localPath?.let { completePartIsValid(File(it), descriptor.size, descriptor.sha256) } == true &&
            current.status == OfflineStatus.READY) {
            dao.saveAsset(current.copy(seenGeneration = album.updatedAt))
            return
        }

        var state = (current ?: OfflineAssetEntity(
            scope = album.scope, assetId = asset.id, albumId = album.albumId, desiredVariant = desired,
            variantVersion = descriptor.version, expectedSha256 = descriptor.sha256, fileSize = descriptor.size,
        )).copy(
            desiredVariant = desired,
            variantVersion = descriptor.version,
            expectedSha256 = descriptor.sha256,
            fileSize = descriptor.size,
            status = OfflineStatus.DOWNLOADING,
            lastError = null,
            updatedAt = System.currentTimeMillis(),
            seenGeneration = album.updatedAt,
        )
        database.withTransaction {
            if (dao.album(album.scope, album.albumId)?.updatedAt != album.updatedAt) throw IOException("Offline-Ziel geändert")
            dao.saveAsset(state)
        }
        if (completePartIsValid(target, descriptor.size, descriptor.sha256)) {
            commitVariant(album, completedOfflineState(state, descriptor, target.absolutePath))
            return
        }
        deleteChecked(target, "Ungültige Zieldatei")
        val part = File(target.absolutePath + ".part")
        part.parentFile?.mkdirs()
        var offset = part.takeIf { it.isFile }?.length() ?: 0
        if (offset == descriptor.size) {
            if (completePartIsValid(part, descriptor.size, descriptor.sha256)) {
                target.parentFile?.mkdirs()
                if (!part.renameTo(target)) throw IOException("Atomare Übernahme fehlgeschlagen")
                commitVariant(album, completedOfflineState(state, descriptor, target.absolutePath))
                return
            }
            deleteChecked(part, "Beschädigte vollständige Part-Datei")
            offset = 0
        } else if (offset > descriptor.size) {
            deleteChecked(part, "Inkompatible Part-Datei")
            offset = 0
        }
        if (dao.updateDownloadProgress(album.scope, asset.id, descriptor.version, offset, System.currentTimeMillis()) == 0) {
            throw IOException("Offline-Ziel wurde während des Downloads geändert")
        }
        val remaining = descriptor.size - offset
        val available = StatFs(root.absolutePath).availableBytes
        if (!hasEnoughOfflineSpace(available, remaining)) {
            dao.saveAsset(state.copy(status = OfflineStatus.FAILED, lastError = "Nicht genug freier Speicher"))
            throw InsufficientStorageException()
        }

        val path = if (desired == OfflineMode.ORIGINAL) "original" else "optimized"
        val response = api.downloadVariant(asset.id, path, resumeRange(offset))
        val action = partResponseAction(offset, descriptor.size, response.code(), response.headers()["Content-Range"])
        if (action !in setOf(PartResponseAction.APPEND, PartResponseAction.RESTART_WITH_BODY)) {
            response.body()?.close()
            response.errorBody()?.close()
            when (action) {
                PartResponseAction.DISCARD_AND_RETRY -> {
                    deleteChecked(part, "Nicht mehr fortsetzbare Part-Datei")
                    throw IOException("Range nicht mehr fortsetzbar")
                }
                PartResponseAction.KEEP_AND_RETRY -> throw IOException("Temporärer Downloadfehler: " + response.code())
                PartResponseAction.KEEP_AND_FAIL -> throw PermanentOfflineException("Download abgelehnt: " + response.code())
                else -> error("unreachable")
            }
        }
        if (!response.isSuccessful || response.body() == null) throw IOException("Download fehlgeschlagen: " + response.code())
        val append = action == PartResponseAction.APPEND && offset > 0
        if (!append) {
            offset = 0
            if (dao.updateDownloadProgress(album.scope, asset.id, descriptor.version, 0, System.currentTimeMillis()) == 0) {
                response.body()?.close()
                throw IOException("Offline-Ziel wurde während des Downloads geändert")
            }
        }
        response.body()!!.use { body ->
            val expectedBodySize = descriptor.size - offset
            val responseSize = body.contentLength()
            if (responseSize >= 0 && responseSize != expectedBodySize) {
                throw IOException("Unerwartete HTTP-Dateigröße")
            }
            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = offset
                    var persisted = offset
                    var lastPersistedAt = SystemClock.elapsedRealtime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > descriptor.size) throw IOException("Unerwartete Dateigröße")
                        output.write(buffer, 0, read)
                        val nowElapsed = SystemClock.elapsedRealtime()
                        if (total - persisted >= DOWNLOAD_PROGRESS_BYTES || nowElapsed - lastPersistedAt >= DOWNLOAD_PROGRESS_INTERVAL_MS) {
                            if (dao.updateDownloadProgress(album.scope, asset.id, descriptor.version, total, System.currentTimeMillis()) == 0) {
                                throw IOException("Offline-Ziel wurde während des Downloads geändert")
                            }
                            persisted = total
                            lastPersistedAt = nowElapsed
                        }
                    }
                    if (dao.updateDownloadProgress(album.scope, asset.id, descriptor.version, total, System.currentTimeMillis()) == 0) {
                        throw IOException("Offline-Ziel wurde während des Downloads geändert")
                    }
                }
                output.fd.sync()
            }
        }
        if (!completePartIsValid(part, descriptor.size, descriptor.sha256)) {
            deleteChecked(part, "Beschädigte Part-Datei")
            dao.saveAsset(state.copy(status = OfflineStatus.RETRY, attempts = state.attempts + 1, bytesDownloaded = 0, lastError = "Integritätsprüfung fehlgeschlagen"))
            throw IOException("Integritätsprüfung fehlgeschlagen")
        }

        target.parentFile?.mkdirs()
        if (!part.renameTo(target)) throw IOException("Atomare Übernahme fehlgeschlagen")
        state = completedOfflineState(state, descriptor, target.absolutePath)
        commitVariant(album, state)
    }

    private suspend fun commitVariant(album: OfflineAlbumEntity, state: OfflineAssetEntity) {
        database.withTransaction {
            if (dao.album(album.scope, album.albumId)?.updatedAt != album.updatedAt) throw IOException("Offline-Ziel geändert")
            dao.saveAsset(state)
        }
        cleanupAsset(album.albumId, state.assetId, setOfNotNull(state.localPath))
    }

    private fun cleanupAsset(albumId: String, assetId: String, keep: Set<String>) {
        File(root, albumId).listFiles()?.filter {
            it.name.startsWith(assetId + "-") && it.absolutePath !in keep && it.absolutePath.removeSuffix(".part") !in keep
        }?.forEach {
            if (!it.delete()) throw IOException("Alte Offline-Variante konnte nicht entfernt werden")
        }
    }

    private fun deleteChecked(file: File, label: String) {
        if (file.exists() && !file.delete()) throw IOException(label + " konnte nicht gelöscht werden")
    }

    private fun deleteAssetFiles(albumId: String, state: OfflineAssetEntity): Boolean {
        val candidates = mutableSetOf<File>()
        if (state.localPath != null) {
            val local = safeScopedFile(state.localPath) ?: return false
            candidates.add(local)
            candidates.add(File(local.absolutePath + ".part"))
        }
        File(root, albumId).listFiles()?.filter { it.name.startsWith(state.assetId + "-") }?.let(candidates::addAll)
        return deleteTracked(candidates)
    }

    private fun safeScopedFile(path: String): File? {
        val scoped = root.canonicalFile
        val file = File(path).canonicalFile
        return if (file.path == scoped.path || file.path.startsWith(scoped.path + File.separator)) file else null
    }

    private fun targetFile(albumId: String, assetId: String, descriptor: OfflineDescriptor): File {
        val version = MessageDigest.getInstance("SHA-256").digest((descriptor.version + descriptor.sha256).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(root, albumId + "/" + assetId + "-" + version + "." + descriptor.variant.lowercase())
    }

    companion object {
        const val ALBUM_ID = "album_id"
        const val SCOPE = "scope"
        private const val DOWNLOAD_PROGRESS_BYTES = 1024L * 1024L
        private const val DOWNLOAD_PROGRESS_INTERVAL_MS = 750L
    }
}

internal fun scopeHash(scope: String): String = MessageDigest.getInstance("SHA-256")
    .digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

class OfflineLegacyCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.get(applicationContext)
        val dao = database.offlineDao()
        // Repairs a kill between the durable logout intent and WorkManager enqueue.
        // NONE cleanup needs no token and remains safe after another account logs in.
        dao.allAlbumsAcrossScopes()
            .filter { it.desiredMode == OfflineMode.NONE && it.status != OfflineStatus.READY }
            .forEach { PartnerOfflineRepository(applicationContext, database, it.scope).retry(it.albumId) }
        for (cleanup in dao.cleanups()) {
            if (cleanup.kind == OfflineCleanupKind.PARTNER_CACHE) {
                try {
                    de.photosync.ui.partner.PartnerMediaCache.get(
                        applicationContext,
                        SecureCredentialStore(applicationContext),
                    ).clear()
                    dao.deleteCleanup(cleanup.id)
                } catch (error: IOException) {
                    dao.failCleanup(cleanup.id, error.message ?: "Partnercache konnte nicht gelöscht werden", System.currentTimeMillis())
                    return Result.retry()
                }
                continue
            }
            if (cleanup.kind != OfflineCleanupKind.LEGACY_ROOT) continue
            val legacyRoot = File(applicationContext.filesDir, "partner-offline")
            if (legacyRoot.exists() && !legacyRoot.deleteRecursively()) {
                dao.failCleanup(cleanup.id, "Legacy-Offline-Verzeichnis konnte nicht vollständig gelöscht werden", System.currentTimeMillis())
                return Result.retry()
            }
            dao.allAlbums(LEGACY_OFFLINE_SCOPE).forEach {
                dao.deleteAlbumAssets(LEGACY_OFFLINE_SCOPE, it.albumId)
                dao.deleteAlbum(LEGACY_OFFLINE_SCOPE, it.albumId)
            }
            dao.deleteCleanup(cleanup.id)
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline-legacy-cleanup",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OfflineLegacyCleanupWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
