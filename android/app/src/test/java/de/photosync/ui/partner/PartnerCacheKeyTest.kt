package de.photosync.ui.partner

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerCacheKeyTest {
    @Test
    fun cacheKeySeparatesVariantAndAssetVersion() {
        val thumbnail = PartnerCacheKey("server-a|user-a", "asset", PartnerVariant.THUMBNAIL, "2026-09-15T10:00:00Z", "a")
        val optimized = PartnerCacheKey("server-a|user-a", "asset", PartnerVariant.OPTIMIZED, "2026-09-15T10:00:00Z", "a")
        val changed = PartnerCacheKey("server-a|user-a", "asset", PartnerVariant.THUMBNAIL, "2026-09-15T10:01:00Z", "b")
        val otherAccount = thumbnail.copy(scope = "server-a|user-b")
        val otherServer = thumbnail.copy(scope = "server-b|user-a")

        assertNotEquals(thumbnail.fileName, optimized.fileName)
        assertNotEquals(thumbnail.fileName, changed.fileName)
        assertNotEquals(thumbnail.fileName, otherAccount.fileName)
        assertNotEquals(thumbnail.fileName, otherServer.fileName)
        assertTrue(thumbnail.value.contains("thumbnail"))
    }
}
