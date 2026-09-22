package de.photosync.ui.partner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.local.AppDatabase
import de.photosync.data.offline.OfflineAlbumEntity
import de.photosync.data.offline.OfflineAssetEntity
import de.photosync.data.offline.PartnerOfflineRepository
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PartnerAlbumDto
import de.photosync.data.remote.RetrofitFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class PartnerGalleryState(
    val albums: List<PartnerAlbumDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val cacheBytes: Long = 0,
    val cacheMaxBytes: Long = PartnerMediaCache.DEFAULT_MAX_BYTES,
    val offlineAlbums: Map<String, OfflineAlbumEntity> = emptyMap(),
)

class PartnerGalleryViewModel(
    private val repository: PartnerMediaRepository,
    private val offline: PartnerOfflineRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PartnerGalleryState(cacheMaxBytes = repository.cacheMaxBytes),
    )
    val state: StateFlow<PartnerGalleryState> = mutableState.asStateFlow()
    private val mutableShare = MutableSharedFlow<PartnerShareEvent>()
    val share = mutableShare.asSharedFlow()

    init {
        refresh()
        refreshCacheSize()
        viewModelScope.launch {
            repository.albumUpdates.collect { albums ->
                mutableState.update { it.copy(albums = albums, loading = false) }
            }
        }
        viewModelScope.launch { offline.albums.collect { albums ->
            mutableState.update { it.copy(offlineAlbums = albums.associateBy(OfflineAlbumEntity::albumId)) }
        } }
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                mutableState.update { it.copy(albums = repository.albums(), loading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = "Partneralben konnten nicht geladen werden.") }
            }
        }
    }

    fun assets(album: PartnerAlbumDto): Flow<PagingData<AssetDto>> =
        repository.assets(album.id).cachedIn(viewModelScope)

    suspend fun thumbnail(asset: AssetDto): File = repository.thumbnail(asset)

    suspend fun thumbnail(assetId: String, version: String, sha256: String?): File =
        repository.thumbnail(assetId, version, sha256)

    suspend fun optimizedImage(asset: AssetDto): File = repository.optimizedImage(asset)

    fun videoPlayer(asset: AssetDto) = repository.videoPlayer(asset)

    fun localVideoPlayer(path: String) = repository.localVideoPlayer(path)

    fun setAlbumOffline(album: PartnerAlbumDto, mode: String) {
        viewModelScope.launch { offline.setAlbumMode(album.id, mode, album.optimizedBytes.toLongOrNull() ?: 0, album.originalBytes.toLongOrNull() ?: 0) }
    }

    fun setAssetOffline(asset: AssetDto, variant: String) {
        viewModelScope.launch { offline.setAssetVariant(asset, variant) }
    }

    fun retryOffline(albumId: String) {
        viewModelScope.launch { offline.retry(albumId) }
    }

    fun rotate(asset: AssetDto) { viewModelScope.launch { repository.rotate(asset.id) } }
    fun rotation(assetId: String): Flow<Int?> = repository.rotation(assetId)
    fun share(asset: AssetDto) { viewModelScope.launch {
        runCatching { repository.originalForShare(asset) }.onSuccess { mutableShare.emit(PartnerShareEvent.Ready(it, asset.mimeType)) }
            .onFailure { mutableShare.emit(PartnerShareEvent.Error("Original konnte nicht geteilt werden.")) }
    } }

    fun offlineAsset(assetId: String): Flow<OfflineAssetEntity?> = offline.asset(assetId)

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refreshCacheSize()
        }
    }

    fun cycleCacheLimit() {
        viewModelScope.launch {
            val next = when (repository.cacheMaxBytes) {
                PartnerMediaCache.MIN_MAX_BYTES -> PartnerMediaCache.DEFAULT_MAX_BYTES
                PartnerMediaCache.DEFAULT_MAX_BYTES -> 1024L * 1024L * 1024L
                else -> PartnerMediaCache.MIN_MAX_BYTES
            }
            repository.setCacheMaxBytes(next)
            mutableState.update { it.copy(cacheMaxBytes = next, cacheBytes = 0) }
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            mutableState.update { it.copy(cacheBytes = repository.cachedBytes()) }
        }
    }

    companion object {
        fun factory(context: Context, baseUrl: String, userId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val credentials = SecureCredentialStore(context)
                val database = AppDatabase.get(context)
                return PartnerGalleryViewModel(
                    PartnerMediaRepository(RetrofitFactory.create(baseUrl, credentials), baseUrl, userId, PartnerMediaCache.get(context, credentials), database, context.applicationContext),
                    PartnerOfflineRepository(context.applicationContext, database, de.photosync.data.sync.remoteScope(baseUrl, userId)),
                ) as T
            }
        }
    }
}

sealed interface PartnerShareEvent {
    data class Ready(val file: File, val mimeType: String) : PartnerShareEvent
    data class Error(val message: String) : PartnerShareEvent
}
