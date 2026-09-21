package de.photosync.ui.partner

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.photosync.data.local.SecureCredentialStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PartnerMediaCacheConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun identicalCacheRequestsUseOnePhysicalDownload() = runBlocking {
        val cache = PartnerMediaCache(context, SecureCredentialStore(context))
        cache.clear()
        val calls = AtomicInteger()
        val bytes = "thumbnail".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val key = PartnerCacheKey("server|account", "asset", PartnerVariant.THUMBNAIL, "v1", hash)
        val files = coroutineScope {
            List(20) {
                async {
                    cache.imageFile(key) {
                        calls.incrementAndGet()
                        delay(50)
                        Response.success(bytes.toResponseBody())
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, calls.get())
        assertEquals(1, files.map { it.absolutePath }.distinct().size)
        assertTrue(files.first().readText() == "thumbnail")

        val failures = AtomicInteger()
        val failedKey = key.copy(version = "v2")
        val outcomes = coroutineScope {
            List(10) {
                async { runCatching {
                    cache.imageFile(failedKey) {
                        failures.incrementAndGet()
                        delay(50)
                        throw IOException("network")
                    }
                } }
            }.awaitAll()
        }
        assertEquals(1, failures.get())
        assertTrue(outcomes.all { it.exceptionOrNull() is IOException })
        cache.clear()
    }

    @Test fun corruptFinalAndCrashPartAreDiscardedBeforeCacheReuse() = runBlocking {
        val cache = PartnerMediaCache(context, SecureCredentialStore(context))
        cache.clear()
        val expected = "complete-cache-payload".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(expected).joinToString("") { "%02x".format(it) }
        val key = PartnerCacheKey("server|account", "asset-crash", PartnerVariant.OPTIMIZED, "v1", hash)
        var calls = 0
        val first = cache.imageFile(key) {
            calls += 1
            Response.success(expected.toResponseBody())
        }
        assertEquals(expected.toList(), first.readBytes().toList())

        // Models a process kill during a copy: a corrupt final and an
        // uncommitted .part may coexist. Neither may be presented as valid.
        first.writeText("partial")
        val strandedPart = java.io.File(first.parentFile, first.name + ".crashed.part")
        strandedPart.writeText("partial-copy")
        val recovered = cache.imageFile(key) {
            calls += 1
            Response.success(expected.toResponseBody())
        }
        assertEquals(2, calls)
        assertEquals(expected.toList(), recovered.readBytes().toList())
        assertTrue(!strandedPart.exists())
        assertTrue(recovered.name.endsWith(key.fileName))
        cache.clear()
    }
}
