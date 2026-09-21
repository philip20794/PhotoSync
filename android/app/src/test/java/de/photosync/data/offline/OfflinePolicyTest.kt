package de.photosync.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class OfflinePolicyTest {
    private fun state(actual: String?) = OfflineAssetEntity(
        scope = "server|account", assetId = "asset", albumId = "album", desiredVariant = actual ?: OfflineMode.OPTIMIZED,
        actualVariant = actual, status = if (actual == null) OfflineStatus.PENDING else OfflineStatus.READY,
        variantVersion = "v1", expectedSha256 = "a".repeat(64), fileSize = 100,
        localPath = actual?.let { "/offline/" + it.lowercase() },
    )

    @Test fun noneToOptimizedCompletesWithOnlyOptimizedState() {
        val result = completedOfflineState(state(null), OfflineDescriptor(OfflineMode.OPTIMIZED, "v1", "a".repeat(64), 100), "/new/optimized")
        assertEquals(OfflineMode.OPTIMIZED, result.actualVariant)
        assertEquals("/new/optimized", result.localPath)
        assertEquals(OfflineStatus.READY, result.status)
    }

    @Test fun optimizedToOriginalReplacesTheDurableVariant() {
        val result = completedOfflineState(state(OfflineMode.OPTIMIZED), OfflineDescriptor(OfflineMode.ORIGINAL, "v2", "b".repeat(64), 200), "/new/original")
        assertEquals(OfflineMode.ORIGINAL, result.actualVariant)
        assertEquals("/new/original", result.localPath)
        assertFalse(result.localPath!!.contains("optimized"))
    }

    @Test fun originalToOptimizedReplacesTheDurableVariant() {
        val result = completedOfflineState(state(OfflineMode.ORIGINAL), OfflineDescriptor(OfflineMode.OPTIMIZED, "v3", "c".repeat(64), 80), "/new/optimized")
        assertEquals(OfflineMode.OPTIMIZED, result.actualVariant)
        assertEquals(80, result.bytesDownloaded)
    }

    @Test fun rangeResumeAndStoragePolicyCoverInterruptionAndDiskFull() {
        assertNull(resumeRange(0))
        assertEquals("bytes=4096-", resumeRange(4096))
        assertTrue(hasEnoughOfflineSpace(200, 100, 50))
        assertFalse(hasEnoughOfflineSpace(149, 100, 50))
        assertFalse(offlineWorkConstraints().requiresStorageNotLow())
    }

    @Test fun partRecoveryDistinguishesHttpOutcomes() {
        val range = "bytes 4-9/10"
        assertEquals(PartResponseAction.APPEND, partResponseAction(4, 10, 206, range))
        assertEquals(PartResponseAction.RESTART_WITH_BODY, partResponseAction(4, 10, 200, null))
        assertEquals(PartResponseAction.KEEP_AND_FAIL, partResponseAction(4, 10, 401, null))
        assertEquals(PartResponseAction.KEEP_AND_FAIL, partResponseAction(4, 10, 403, null))
        assertEquals(PartResponseAction.DISCARD_AND_RETRY, partResponseAction(4, 10, 416, null))
        assertEquals(PartResponseAction.KEEP_AND_RETRY, partResponseAction(4, 10, 429, null))
        assertEquals(PartResponseAction.KEEP_AND_RETRY, partResponseAction(4, 10, 503, null))
        assertEquals(PartResponseAction.DISCARD_AND_RETRY, partResponseAction(4, 10, 206, "bytes 3-9/10"))
    }

    @Test fun completePartCanBeValidatedBeforeHandling416() {
        val bytes = "already complete".toByteArray()
        val part = File.createTempFile("photosync-complete-", ".part")
        try {
            part.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertTrue(completePartIsValid(part, bytes.size.toLong(), hash))
            assertFalse(completePartIsValid(part, bytes.size.toLong(), "0".repeat(64)))
        } finally {
            part.delete()
        }
    }

    @Test fun failedDeletionRemainsDetectableAndRetryable() {
        val first = File.createTempFile("photosync-delete-", ".tmp")
        val second = File.createTempFile("photosync-delete-", ".tmp")
        try {
            assertFalse(deleteTracked(listOf(first, second)) { file ->
                if (file == first) false else file.delete()
            })
            assertTrue(first.exists())
            assertFalse(second.exists())
            assertTrue(deleteTracked(listOf(first), File::delete))
            assertFalse(first.exists())
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test fun individualOverrideRemainsDistinctFromAlbumMode() {
        val item = state(OfflineMode.OPTIMIZED).copy(desiredVariant = OfflineMode.ORIGINAL, overridesAlbumMode = true)
        assertTrue(item.overridesAlbumMode)
        assertEquals(OfflineMode.ORIGINAL, item.desiredVariant)
        assertEquals(OfflineMode.OPTIMIZED, item.actualVariant)
    }
    @Test fun networkAbortKeepsPartAndRangeResumeCompletesIt() {
        val expected = "0123456789".toByteArray()
        val part = File.createTempFile("photosync-offline-", ".part")
        part.delete()
        val broken = object : ResponseBody() {
            private var position = 0
            private val input = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= 4) throw IOException("network lost")
                    val count = minOf(length, 4 - position)
                    expected.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
            }
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = expected.size.toLong()
            override fun source(): BufferedSource = input.source().buffer()
        }
        try {
            try { writeOfflineBody(part, false, broken) } catch (_: IOException) {}
            assertEquals(4, part.length())
            assertEquals("bytes=4-", resumeRange(part.length()))
            writeOfflineBody(part, true, expected.copyOfRange(4, expected.size).toResponseBody())
            assertTrue(part.readBytes().contentEquals(expected))
        } finally { part.delete() }
    }

}
