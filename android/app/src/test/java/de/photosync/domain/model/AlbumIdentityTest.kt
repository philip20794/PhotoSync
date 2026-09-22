package de.photosync.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumIdentityTest {
    @Test
    fun volumeAndNormalizedRelativePathAreStableAcrossReinstall() {
        assertEquals(
            albumSourceKey("external", "/storage/emulated/0/Pictures/Wir/"),
            albumSourceKey("external_primary", "pictures\\wir"),
        )
    }

    @Test
    fun accumulatorUsesPathInsteadOfInstallationSpecificBucketId() {
        val albums = AlbumAccumulator().apply {
            add(MediaMetadata("external_primary", "old-bucket", "Pictures/Wir/", "Wir", "content://old", LocalMediaKind.IMAGE))
            add(MediaMetadata("external_primary", "new-bucket", "pictures/wir", "Wir", "content://new", LocalMediaKind.IMAGE))
        }.result()

        assertEquals(1, albums.size)
        assertEquals("pictures/wir", albums.single().relativePath)
        assertEquals(2, albums.single().imageCount)
    }
}
