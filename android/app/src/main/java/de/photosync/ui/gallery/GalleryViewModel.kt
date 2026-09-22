package de.photosync.ui.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import de.photosync.data.local.AlbumSyncProgress
import de.photosync.data.local.AppDatabase
import de.photosync.data.media.MediaStoreRepository
import de.photosync.data.sync.AlbumSyncRepository
import de.photosync.domain.model.LocalAlbum
import de.photosync.domain.model.LocalMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class GalleryUiState(
    val albums: List<LocalAlbum> = emptyList(),
    val syncProgress: Map<String, AlbumSyncProgress> = emptyMap(),
    val albumGridColumns: Int = 2,
    val mediaGridColumns: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val error: Boolean = false,
)

class GalleryViewModel(
    private val mediaRepository: MediaStoreRepository,
    private val syncRepository: AlbumSyncRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            syncRepository.progress.collect { progress ->
                mutableState.update { it.copy(syncProgress = progress) }
            }
        }
    }

    fun refresh(hasAccess: Boolean) {
        if (!hasAccess) {
            mutableState.update { it.copy(albums = emptyList(), loading = false, error = false) }
            return
        }
        syncRepository.requestSync()
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = false) }
            try {
                val albums = mediaRepository.loadAlbums()
                mutableState.update { it.copy(albums = albums, loading = false, error = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun setSharing(album: LocalAlbum, shared: Boolean) {
        viewModelScope.launch { syncRepository.setSharing(album, shared) }
    }

    fun setAlbumGridColumns(columns: Int) {
        mutableState.update { it.copy(albumGridColumns = columns.coerceIn(1, 4)) }
    }

    fun setMediaGridColumns(albumId: String, columns: Int) {
        mutableState.update { it.copy(mediaGridColumns = it.mediaGridColumns + (albumId to columns.coerceIn(2, 7))) }
    }

    fun media(album: LocalAlbum): Flow<PagingData<LocalMedia>> = mediaRepository.mediaInAlbum(album).cachedIn(viewModelScope)

    companion object {
        fun factory(context: Context, deviceId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(
                MediaStoreRepository(context),
                AlbumSyncRepository(context, AppDatabase.get(context), deviceId),
            ) as T
        }
    }
}
