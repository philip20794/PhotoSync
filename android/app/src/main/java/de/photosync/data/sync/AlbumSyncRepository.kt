package de.photosync.data.sync

import android.content.Context
import de.photosync.data.local.AlbumSyncProgress
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.domain.model.LocalAlbum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AlbumSyncRepository(
    context: Context,
    private val database: AppDatabase,
    private val deviceId: String,
) {
    private val appContext = context.applicationContext

    val progress: Flow<Map<String, AlbumSyncProgress>> = database.syncDao()
        .observeProgress(deviceId)
        .map { rows -> rows.associateBy(AlbumSyncProgress::mediaStoreAlbumId) }

    init {
        SyncScheduler.schedulePeriodic(appContext)
    }

    suspend fun setSharing(album: LocalAlbum, shared: Boolean) {
        val key = "$deviceId|${album.id}"
        if (shared) {
            database.syncDao().enableAlbum(
                SharedAlbumEntity(
                    localAlbumId = key,
                    mediaStoreAlbumId = album.id,
                    sourceDeviceId = deviceId,
                    volumeName = album.volumeName,
                    relativePath = album.relativePath,
                    bucketId = album.bucketId,
                    title = album.name,
                ),
            )
        } else {
            database.syncDao().setShareRequested(key, false)
        }
        SyncScheduler.runNow(appContext)
    }

    fun requestSync() = SyncScheduler.runNow(appContext)
}
