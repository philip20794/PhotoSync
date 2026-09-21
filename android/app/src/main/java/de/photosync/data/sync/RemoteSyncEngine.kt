package de.photosync.data.sync

import android.content.Context
import androidx.room.withTransaction
import de.photosync.data.local.AppDatabase
import de.photosync.data.offline.PartnerOfflineRepository
import de.photosync.data.local.SecureCredentialStore
import de.photosync.ui.partner.PartnerMediaCache
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PhotoSyncApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** The caller holds the sync file lock. No transaction spans a network call. */
class RemoteSyncEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val api: PhotoSyncApi,
    private val scope: String,
) {
    private val dao = db.remoteDao()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(): Boolean {
        val albums = api.partnerAlbums().albums
        val visible = albums.map { it.id }.toSet()
        val offline = PartnerOfflineRepository(context, db, scope)
        val cache = PartnerMediaCache.get(context, SecureCredentialStore(context))
        val previous = dao.albums(scope).map { it.id }.toSet()
        val removedAlbums = previous - visible
        val removedAssetMetadata = removedAlbums.flatMap { dao.assetMetadata(scope, it) }
        for (row in removedAssetMetadata) {
            cache.invalidateAsset(scope, json.decodeFromString<AssetDto>(row.json))
        }
        db.withTransaction {
            for (removed in removedAlbums) {
                dao.removeAlbum(scope, removed)
                db.offlineDao().requestRemoval(scope, removed)
            }
            dao.saveMetadata(albums.map { RemoteMetadata(scope, it.id, "ALBUM", it.id, it.title, json.encodeToString(it)) })
            dao.saveWork(albums.filter { it.id !in previous }.map { RemoteWork(scope, "album:" + it.id, it.id) })
        }
        for (removed in removedAlbums) {
            if (!offline.removeRemoteAlbum(removed)) throw IOException("Offline-Dateien des entfernten Partneralbums konnten nicht gelöscht werden")
        }
        var checkpoint = dao.checkpoint(scope) ?: RemoteCheckpoint(scope)
        if (checkpoint.pendingCursor == null && dao.nextWork(scope) == null) {
            val page = try { api.changes(checkpoint.cursor) } catch (error: HttpException) {
                if (error.code() != 410) throw error
                // The retained journal is the reset baseline. Reconcile all visible
                // albums as well; the old applied cursor is never reused.
                // Cache invalidation happens before metadata is discarded so an
                // asset deleted while this device was offline cannot survive reset.
                for (stale in dao.allAssetMetadata(scope)) {
                    cache.invalidateAsset(scope, json.decodeFromString<AssetDto>(stale.json))
                }
                db.withTransaction {
                    dao.saveCheckpoint(RemoteCheckpoint(scope))
                    dao.clearWork(scope)
                    dao.clearAssets(scope)
                    dao.saveWork(albums.map { RemoteWork(scope, "album:" + it.id, it.id) })
                }
                return true
            }
            require(page.changes.all { it.kind in setOf("ALBUM", "ASSET") && it.operation in setOf("UPSERT", "DELETE", "RESTORE") })
            checkpoint = checkpoint.copy(pendingCursor = page.nextCursor, hasMore = page.hasMore)
            db.withTransaction {
                dao.saveWork(page.changes.filter { it.albumId in visible }.map {
                    RemoteWork(scope, if (it.kind == "ALBUM") "album:" + it.albumId else "asset:" + it.assetId,
                        it.albumId, if (it.kind == "ASSET") requireNotNull(it.assetId) else null)
                }.distinctBy { it.id })
                dao.saveCheckpoint(checkpoint)
            }
        }
        // Bound each execution; album scan checkpoints survive the worker deadline.
        repeat(20) {
            val work = dao.nextWork(scope) ?: return@repeat
            if (work.albumId !in visible) {
                dao.completeWork(scope, work.id)
            } else if (work.assetId != null) {
                val asset = try { api.getAsset(work.assetId) } catch (error: HttpException) {
                    if (error.code() != 404) throw error
                    null
                }
                if (asset == null) {
                    val previous = dao.metadata(scope, work.assetId)?.let { json.decodeFromString<AssetDto>(it.json) }
                    if (previous != null) {
                        cache.invalidateAsset(scope, previous)
                        if (!offline.removeRemoteAsset(work.albumId, work.assetId)) {
                            throw IOException("Offline-Datei konnte für gelöschtes Partnerasset nicht gelöscht werden")
                        }
                    }
                }
                db.withTransaction {
                    if (asset?.status == "ready") dao.saveMetadata(listOf(metadata(asset)))
                    else dao.removeAsset(scope, work.assetId)
                    db.offlineDao().invalidateAlbum(scope, work.albumId)
                    dao.completeWork(scope, work.id)
                }
            } else {
                val page = api.albumAssets(work.albumId, 100, work.pageCursor)
                db.withTransaction {
                    dao.saveMetadata(page.assets.map(::metadata))
                    db.offlineDao().invalidateAlbum(scope, work.albumId)
                }
                if (page.nextCursor == null) dao.completeWork(scope, work.id)
                else dao.saveWork(listOf(work.copy(pageCursor = page.nextCursor)))
            }
        }
        val pending = dao.nextWork(scope) != null
        if (!pending && checkpoint.pendingCursor != null) {
            db.withTransaction {
                dao.saveCheckpoint(checkpoint.copy(cursor = checkpoint.pendingCursor, pendingCursor = null))
            }
        }
        // DB intent precedes enqueue. A process kill in between is repaired by the
        // periodic run, including FAILED storage jobs and interrupted downloads.
        db.offlineDao().allAlbums(scope).filter { it.desiredMode != "NONE" || it.status != "READY" }.forEach { offline.retry(it.albumId) }
        return pending || checkpoint.hasMore || (checkpoint.cursor == null && checkpoint.pendingCursor == null)
    }

    private fun metadata(asset: AssetDto) = RemoteMetadata(
        scope, asset.id, "ASSET", asset.albumId, asset.capturedAt ?: asset.createdAt, json.encodeToString(asset),
    )
}
