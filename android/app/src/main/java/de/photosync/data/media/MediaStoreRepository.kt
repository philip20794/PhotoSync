package de.photosync.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.os.bundleOf
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import de.photosync.domain.model.AlbumAccumulator
import de.photosync.domain.model.LocalAlbum
import de.photosync.domain.model.LocalMedia
import de.photosync.domain.model.LocalMediaKind
import de.photosync.domain.model.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException


data class SyncMediaCandidate(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val capturedAtMillis: Long?,
    val dateModifiedSeconds: Long,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
)

data class MediaScanResult(val complete: Boolean)

interface MediaInventory {
    suspend fun loadAlbums(): List<LocalAlbum>
    suspend fun scanAlbum(
        volumeName: String,
        bucketId: String,
        onBatch: suspend (List<SyncMediaCandidate>) -> Unit,
    ): MediaScanResult
}

class MediaStoreRepository(context: Context) : MediaInventory {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun loadAlbums(): List<LocalAlbum> = withContext(Dispatchers.IO) {
        val accumulator = AlbumAccumulator()
        externalVolumes().forEach { volume ->
            resolver.query(
                filesUri(volume),
                albumProjection(),
                queryArgs(selection = visibleMediaSelection(), limit = null, offset = null),
                null,
            )?.use { cursor ->
                val columns = AlbumColumns(cursor)
                while (cursor.moveToNext()) {
                    val kind = cursor.mediaKind(columns.mediaType) ?: continue
                    val id = cursor.getLong(columns.id)
                    accumulator.add(
                        MediaMetadata(
                            volumeName = volume,
                            bucketId = cursor.getString(columns.bucketId) ?: "unknown",
                            bucketName = cursor.getString(columns.bucketName),
                            relativePath = cursor.albumRelativePath(columns),
                            uri = mediaUri(volume, kind, id).toString(),
                            kind = kind,
                        ),
                    )
                }
            }
        }
        accumulator.result()
    }

    fun mediaInAlbum(album: LocalAlbum): Flow<androidx.paging.PagingData<LocalMedia>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = 20, enablePlaceholders = false),
        pagingSourceFactory = { MediaStorePagingSource(resolver, album) },
    ).flow

    override suspend fun scanAlbum(
        volumeName: String,
        bucketId: String,
        onBatch: suspend (List<SyncMediaCandidate>) -> Unit,
    ): MediaScanResult = withContext(Dispatchers.IO) {
        if (volumeName !in externalVolumes()) return@withContext MediaScanResult(complete = false)
        val cursor = resolver.query(
            filesUri(volumeName),
            SYNC_PROJECTION,
            queryArgs(
                selection = "${visibleMediaSelection()} AND ${MediaStore.Images.ImageColumns.BUCKET_ID} = ?",
                selectionArgs = arrayOf(bucketId),
                limit = null,
                offset = null,
            ),
            null,
        ) ?: throw IOException("MediaStore konnte nicht gelesen werden")
        cursor.use {
            val columns = SyncColumns(cursor)
            val batch = ArrayList<SyncMediaCandidate>(SCAN_BATCH_SIZE)
            while (cursor.moveToNext()) {
                val kind = cursor.mediaKind(columns.mediaType) ?: continue
                val id = cursor.getLong(columns.id)
                batch += SyncMediaCandidate(
                    mediaStoreId = id,
                    contentUri = mediaUri(volumeName, kind, id).toString(),
                    displayName = cursor.getString(columns.displayName) ?: "medium-$id",
                    mimeType = cursor.getString(columns.mimeType)
                        ?: if (kind == LocalMediaKind.IMAGE) "image/jpeg" else "video/mp4",
                    capturedAtMillis = cursor.getLong(columns.dateTaken).takeIf { it > 0 }
                        ?: cursor.getLong(columns.dateAdded).takeIf { it > 0 }?.times(1_000),
                    dateModifiedSeconds = cursor.getLong(columns.dateModified),
                    fileSize = cursor.getLong(columns.size),
                    width = cursor.getInt(columns.width),
                    height = cursor.getInt(columns.height),
                    durationMillis = if (kind == LocalMediaKind.VIDEO) cursor.getLongOrNull(columns.duration) else null,
                )
                if (batch.size == SCAN_BATCH_SIZE) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch)
        }
        MediaScanResult(complete = volumeName in externalVolumes())
    }

    private fun externalVolumes(): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.getExternalVolumeNames(appContext)
    } else {
        setOf("external")
    }

    private companion object {
        const val PAGE_SIZE = 60
        const val SCAN_BATCH_SIZE = 250
    }
}

internal class MediaStorePagingSource(
    private val resolver: ContentResolver,
    private val album: LocalAlbum,
) : PagingSource<Int, LocalMedia>() {
    override fun getRefreshKey(state: PagingState<Int, LocalMedia>): Int? {
        val anchor = state.anchorPosition ?: return null
        return (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LocalMedia> = withContext(Dispatchers.IO) {
        val offset = params.key ?: 0
        try {
            val items = resolver.query(
                filesUri(album.volumeName),
                MEDIA_PROJECTION,
                queryArgs(
                    selection = "${visibleMediaSelection()} AND ${MediaStore.Images.ImageColumns.BUCKET_ID} = ?",
                    selectionArgs = arrayOf(album.bucketId),
                    limit = params.loadSize,
                    offset = offset,
                ),
                null,
            )?.use(::readMedia) ?: emptyList()
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (items.size < params.loadSize) null else offset + items.size,
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    private fun readMedia(cursor: Cursor): List<LocalMedia> {
        val columns = MediaColumns(cursor)
        return buildList(cursor.count.coerceAtMost(100)) {
            while (cursor.moveToNext()) {
                val kind = cursor.mediaKind(columns.mediaType) ?: continue
                val id = cursor.getLong(columns.id)
                add(
                    LocalMedia(
                        id = id,
                        uri = mediaUri(album.volumeName, kind, id).toString(),
                        displayName = cursor.getString(columns.displayName) ?: "Medium",
                        kind = kind,
                        width = cursor.getInt(columns.width),
                        height = cursor.getInt(columns.height),
                        durationMillis = cursor.getLongOrNull(columns.duration),
                        takenAtMillis = cursor.getLong(columns.dateTaken).takeIf { it > 0 }
                            ?: cursor.getLong(columns.dateAdded) * 1_000,
                    ),
                )
            }
        }
    }
}

private val ALBUM_PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.Files.FileColumns.MEDIA_TYPE,
    MediaStore.Images.ImageColumns.BUCKET_ID,
    MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
)

private fun albumProjection(): Array<String> = ALBUM_PROJECTION + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
} else {
    arrayOf(MediaStore.MediaColumns.DATA)
}

private val SYNC_PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.Files.FileColumns.MEDIA_TYPE,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.MIME_TYPE,
    MediaStore.Images.ImageColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_ADDED,
    MediaStore.MediaColumns.DATE_MODIFIED,
    MediaStore.MediaColumns.SIZE,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.Video.VideoColumns.DURATION,
)

private val MEDIA_PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.Files.FileColumns.MEDIA_TYPE,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    MediaStore.Video.VideoColumns.DURATION,
    MediaStore.Images.ImageColumns.DATE_TAKEN,
    MediaStore.MediaColumns.DATE_ADDED,
)

private class AlbumColumns(cursor: Cursor) {
    val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
    val bucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_ID)
    val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
    val relativePath = cursor.getColumnIndexOrThrow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA,
    )
}

private class SyncColumns(cursor: Cursor) {
    val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
    val displayName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
    val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
    val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
    val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
    val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
    val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
    val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
    val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
    val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
}

private class MediaColumns(cursor: Cursor) {
    val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
    val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
    val displayName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
    val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
    val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
    val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
    val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
    val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
}

private fun Cursor.mediaKind(column: Int): LocalMediaKind? = when (getInt(column)) {
    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> LocalMediaKind.IMAGE
    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> LocalMediaKind.VIDEO
    else -> null
}

private fun Cursor.getLongOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

private fun filesUri(volume: String): Uri = MediaStore.Files.getContentUri(volume)

private fun mediaUri(volume: String, kind: LocalMediaKind, id: Long): Uri {
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        when (kind) {
            LocalMediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            LocalMediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        }
    } else {
        when (kind) {
            LocalMediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            LocalMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }
    return ContentUris.withAppendedId(base, id)
}

private fun visibleMediaSelection(): String = buildList {
    add("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("${MediaStore.MediaColumns.IS_PENDING} = 0")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
}.joinToString(" AND ")

private fun queryArgs(
    selection: String,
    selectionArgs: Array<String>? = null,
    limit: Int?,
    offset: Int?,
): Bundle = bundleOf(
    ContentResolver.QUERY_ARG_SQL_SELECTION to selection,
    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS to selectionArgs,
    ContentResolver.QUERY_ARG_SORT_COLUMNS to arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED),
    ContentResolver.QUERY_ARG_SORT_DIRECTION to ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
).apply {
    if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    if (offset != null) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
}

private fun Cursor.albumRelativePath(columns: AlbumColumns): String {
    val raw = getString(columns.relativePath).orEmpty()
    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        raw
    } else {
        raw.substringBeforeLast('/', missingDelimiterValue = raw)
    }
    return path.ifBlank { getString(columns.bucketName) ?: getString(columns.bucketId) }
}
