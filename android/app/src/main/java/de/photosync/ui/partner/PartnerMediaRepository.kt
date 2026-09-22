package de.photosync.ui.partner

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.map
import android.content.Context
import de.photosync.data.local.AppDatabase
import de.photosync.data.sync.remoteScope
import de.photosync.data.sync.SyncScheduler
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PartnerAlbumDto
import de.photosync.data.remote.PhotoSyncApi
import kotlinx.coroutines.flow.Flow
import java.io.File

class PartnerMediaRepository(
    private val api: PhotoSyncApi,
    private val baseUrl: String,
    private val userId: String,
    private val cache: PartnerMediaCache,
    private val database: AppDatabase,
    private val context: Context,
) {
    private val scope = remoteScope(baseUrl, userId)
    private val json = Json { ignoreUnknownKeys = true }
    val albumUpdates: Flow<List<PartnerAlbumDto>> = flow {
        emitAll(database.remoteDao().observeAlbums(scope).map { rows ->
            rows.map { json.decodeFromString<PartnerAlbumDto>(it.json) }
        })
    }

    suspend fun albums(): List<PartnerAlbumDto> {
        SyncScheduler.runNow(context)
        // Check availability for the UI's error state; background sync owns Room writes.
        return api.partnerAlbums().albums
    }

    fun assets(albumId: String): Flow<PagingData<AssetDto>> = flow {
        SyncScheduler.runNow(context)
        emitAll(Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PREFETCH_DISTANCE, enablePlaceholders = false, maxSize = 240),
            // Room is filled by the durable background reconciliation, but it must
            // not be the gallery's paging boundary: a newly opened large album can
            // contain only its first reconciled batch. The grid's append requests
            // therefore follow the server cursor directly.
            pagingSourceFactory = { PartnerAssetPagingSource(api, albumId) },
        ).flow)
    }

    suspend fun thumbnail(asset: AssetDto): File = cache.imageFile(asset.partnerCacheKey(scope, PartnerVariant.THUMBNAIL)) {
        api.downloadVariant(asset.id, PartnerVariant.THUMBNAIL.path)
    }

    suspend fun thumbnail(assetId: String, version: String, sha256: String?): File =
        cache.imageFile(PartnerCacheKey(scope, assetId, PartnerVariant.THUMBNAIL, version, sha256)) {
            api.downloadVariant(assetId, PartnerVariant.THUMBNAIL.path)
        }

    suspend fun optimizedImage(asset: AssetDto): File = cache.imageFile(asset.partnerCacheKey(scope, PartnerVariant.OPTIMIZED)) {
        api.downloadVariant(asset.id, PartnerVariant.OPTIMIZED.path)
    }

    fun optimizedVideoUrl(asset: AssetDto): String =
        baseUrl.trimEnd('/') + "/v1/assets/" + asset.id + "/" + PartnerVariant.OPTIMIZED.path

    fun videoPlayer(asset: AssetDto) = cache.player(optimizedVideoUrl(asset), asset.partnerCacheKey(scope, PartnerVariant.OPTIMIZED))

    fun localVideoPlayer(path: String) = cache.localPlayer(File(path))

    suspend fun clearCache() = cache.clear()

    suspend fun setCacheMaxBytes(bytes: Long) = cache.setMaxBytes(bytes)

    suspend fun cachedBytes(): Long = cache.cachedBytes()

    val cacheMaxBytes: Long get() = cache.maxBytes

    private class PartnerAssetPagingSource(
        private val api: PhotoSyncApi,
        private val albumId: String,
    ) : PagingSource<String, AssetDto>() {
        override fun getRefreshKey(state: PagingState<String, AssetDto>): String? = null

        override suspend fun load(params: LoadParams<String>): LoadResult<String, AssetDto> = try {
            val page = api.albumAssets(albumId, params.loadSize.coerceAtMost(MAX_PAGE_SIZE), params.key)
            LoadResult.Page(
                data = page.assets,
                prevKey = null,
                nextKey = page.nextCursor,
            )
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val PREFETCH_DISTANCE = 18
        private const val MAX_PAGE_SIZE = 100
    }
}
