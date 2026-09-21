package de.photosync.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaVersionTest {
    private fun item(size: Long, modified: Long) = UploadQueueEntity(
        clientAssetId = "ms_stable",
        localAlbumId = "device|external:camera",
        contentUri = "content://media/external/images/media/42",
        originalFileName = "photo.jpg",
        mimeType = "image/jpeg",
        capturedAtMillis = null,
        fileSize = size,
        width = 100,
        height = 80,
        durationMillis = null,
        mediaStoreId = 42,
        dateModifiedSeconds = modified,
    )

    @Test
    fun unchangedMediaVersionKeepsStableClientIdentity() {
        assertEquals(versionedClientAssetId(item(100, 10)), versionedClientAssetId(item(100, 10)))
    }

    @Test
    fun sizeOrModificationTimeCreatesANewClientIdentity() {
        val original = versionedClientAssetId(item(100, 10))
        assertNotEquals(original, versionedClientAssetId(item(101, 10)))
        assertNotEquals(original, versionedClientAssetId(item(100, 11)))
    }
}
