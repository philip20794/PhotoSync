package de.photosync.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import de.photosync.domain.model.LocalMediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreLoadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var sampleVideoUri: Uri
    private val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    } else {
        "external"
    }

    @Before
    fun seedLargeLibrary() {
        clearTestMedia()
        repeat(500) { insertMedia(LocalMediaKind.IMAGE, "image-%04d.png".format(it)) }
        repeat(100) { sampleVideoUri = insertMedia(LocalMediaKind.VIDEO, "video-%04d.mp4".format(it)) }
    }

    @After
    fun clearTestMedia() {
        resolver.delete(
            MediaStore.Files.getContentUri(volume),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(TEST_PATH),
        )
    }

    @Test
    fun groupsAndPagesSixHundredRealMediaStoreRows() = runBlocking {
        val repository = MediaStoreRepository(context)
        val album = repository.loadAlbums().single { it.name == "PhotoSyncLoadTest" }
        assertEquals(500, album.imageCount)
        assertEquals(100, album.videoCount)
        assertEquals(600, album.totalCount)
        val imageLoader = ImageLoader.Builder(context).build()
        val imageThumbnail = imageLoader.execute(
            ImageRequest.Builder(context).data(album.coverUri).size(128, 128).build(),
        )
        assertTrue((imageThumbnail as? ErrorResult)?.throwable?.stackTraceToString(), imageThumbnail is SuccessResult)
        val videoThumbnail = imageLoader.execute(
            ImageRequest.Builder(context).data(sampleVideoUri).size(128, 128).build(),
        )
        assertTrue((videoThumbnail as? ErrorResult)?.throwable?.stackTraceToString(), videoThumbnail is SuccessResult)

        val page = MediaStorePagingSource(resolver, album).load(
            PagingSource.LoadParams.Refresh(key = 0, loadSize = 60, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(60, page.data.size)
        assertEquals(60, page.nextKey)
        assertTrue(page.data.all { it.uri.startsWith("content://") })
    }

    private fun insertMedia(kind: LocalMediaKind, name: String): Uri {
        val collection = when (kind) {
            LocalMediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            LocalMediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (kind == LocalMediaKind.IMAGE) "image/png" else "video/mp4")
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, if (kind == LocalMediaKind.IMAGE) 2_000_000_000_000L else 1_000_000_000_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, TEST_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = requireNotNull(resolver.insert(collection, values))
        resolver.openOutputStream(uri, "w")!!.use { output ->
            when (kind) {
                LocalMediaKind.IMAGE -> {
                    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.rgb(45, 125, 210))
                    }
                    try {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    } finally {
                        bitmap.recycle()
                    }
                }
                LocalMediaKind.VIDEO -> testAssets.open(TEST_VIDEO_ASSET).use { it.copyTo(output) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private companion object {
        const val TEST_PATH = "DCIM/PhotoSyncLoadTest/"
        const val TEST_VIDEO_ASSET = "media_store_test.mp4"
    }
}
