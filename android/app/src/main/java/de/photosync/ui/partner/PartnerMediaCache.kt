package de.photosync.ui.partner

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.DerivativeDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

enum class PartnerVariant(val path: String) {
    THUMBNAIL("thumbnail"),
    OPTIMIZED("optimized"),
}

data class PartnerCacheKey(
    val scope: String,
    val assetId: String,
    val variant: PartnerVariant,
    val version: String,
    val sha256: String?,
) {
    val value: String = listOf(scope, assetId, variant.name.lowercase(), version, sha256.orEmpty()).joinToString("|")
    val fileName: String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun AssetDto.partnerCacheKey(scope: String, variant: PartnerVariant): PartnerCacheKey {
    val derivative: DerivativeDto? = derivatives.firstOrNull { it.kind == variant.name.lowercase() }
    return PartnerCacheKey(scope, id, variant, derivative?.updatedAt ?: updatedAt, derivative?.sha256)
}

/**
 * A volatile partner-only cache. It is intentionally under cacheDir, never Room or filesDir:
 * it is not an offline download and the operating system may reclaim it at any time.
 */
class PartnerMediaCache internal constructor(
    private val context: Context,
    private val credentials: SecureCredentialStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val imageDir = File(context.cacheDir, "partner-variant-images")
    private val videoDir = File(context.cacheDir, "partner-variant-video")
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private var videoCache = newVideoCache()
    private val imageFlights = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val lifecycleMutex = Mutex()

    val maxBytes: Long
        get() = preferences.getLong(MAX_BYTES, DEFAULT_MAX_BYTES).coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES)

    private fun newVideoCache(): SimpleCache {
        videoDir.mkdirs()
        return SimpleCache(videoDir, LeastRecentlyUsedCacheEvictor(videoBudget()), databaseProvider)
    }

    private fun imageBudget(): Long = maxBytes / 2
    private fun videoBudget(): Long = maxBytes - imageBudget()

    private fun validImageFile(file: File, key: PartnerCacheKey): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val expected = key.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    private fun finalizeImageCache(temporary: File, target: File) {
        if (target.exists() && !target.delete()) throw IOException("Ungültige Cache-Datei konnte nicht entfernt werden")
        if (temporary.renameTo(target)) return
        val fallback = File(target.parentFile, target.name + "." + UUID.randomUUID() + ".part")
        try {
            FileInputStream(temporary).use { input ->
                FileOutputStream(fallback).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!fallback.renameTo(target)) throw IOException("Atomare Cache-Übernahme fehlgeschlagen")
            if (temporary.exists() && !temporary.delete()) throw IOException("Temporäre Cache-Datei konnte nicht gelöscht werden")
        } finally {
            if (fallback.exists()) fallback.delete()
        }
    }

    suspend fun imageFile(
        key: PartnerCacheKey,
        download: suspend () -> Response<ResponseBody>,
    ): File {
        val mine = CompletableDeferred<File>()
        val running = lifecycleMutex.withLock { imageFlights.putIfAbsent(key.value, mine) }
        if (running != null) return running.await()
        try {
            val result = withContext(Dispatchers.IO) {
                imageDir.mkdirs()
                val target = File(imageDir, key.fileName)
                imageDir.listFiles()?.filter {
                    it.name.startsWith(key.fileName + ".") && it.name.endsWith(".part")
                }?.forEach { it.delete() }
                if (validImageFile(target, key)) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withContext target
                }
                if (target.exists() && !target.delete()) throw IOException("Beschädigter Cache konnte nicht entfernt werden")
                val response = download()
                if (!response.isSuccessful || response.body() == null) {
                    response.errorBody()?.close()
                    throw HttpException(response)
                }
                val temporary = File(imageDir, key.fileName + "." + UUID.randomUUID() + ".part")
                try {
                    response.body()!!.byteStream().use { input ->
                        FileOutputStream(temporary).use { raw ->
                            val output = raw.buffered()
                            input.copyTo(output)
                            output.flush()
                            raw.fd.sync()
                        }
                    }
                    if (!validImageFile(temporary, key)) throw IOException("Cache-Prüfsumme stimmt nicht")
                    finalizeImageCache(temporary, target)
                    if (!validImageFile(target, key)) throw IOException("Finaler Cache ist beschädigt")
                    target.setLastModified(System.currentTimeMillis())
                    trimImages()
                    target
                } catch (error: Throwable) {
                    if (temporary.exists() && !temporary.delete()) {
                        error.addSuppressed(IOException("Temporäre Cache-Datei konnte nicht gelöscht werden"))
                    }
                    if (target.exists() && !validImageFile(target, key)) target.delete()
                    throw error
                }
            }
            mine.complete(result)
            return result
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            imageFlights.remove(key.value, mine)
        }
    }

    fun localPlayer(file: File): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        prepare()
    }

    fun player(url: String, key: PartnerCacheKey): ExoPlayer {
        val upstream = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(authInterceptor())
                .build(),
        )
        val source = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
            .apply {
                setMediaItem(MediaItem.Builder().setUri(url).setCustomCacheKey(key.value).build())
                prepare()
            }
    }

    suspend fun clear() {
        lifecycleMutex.withLock {
            imageFlights.values.toList().forEach { runCatching { it.await() } }
            withContext(Dispatchers.IO) {
                synchronized(this@PartnerMediaCache) {
                    if (imageDir.exists() && !imageDir.deleteRecursively()) throw IOException("Bildcache konnte nicht vollständig gelöscht werden")
                    imageDir.mkdirs()
                    videoCache.keys.toList().forEach(videoCache::removeResource)
                }
            }
        }
    }

    /** Invalidates every cached representation of one scoped remote asset. */
    suspend fun invalidateAsset(scope: String, asset: AssetDto) {
        val keys = listOf(PartnerVariant.THUMBNAIL, PartnerVariant.OPTIMIZED).map { asset.partnerCacheKey(scope, it) }
        lifecycleMutex.withLock {
            imageFlights.values.toList().forEach { runCatching { it.await() } }
            withContext(Dispatchers.IO) {
                for (key in keys) {
                    val file = File(imageDir, key.fileName)
                    if (file.exists() && !file.delete()) throw IOException("Asset-Cache konnte nicht invalidiert werden")
                    videoCache.removeResource(key.value)
                }
                videoCache.keys.filter { it.contains("|${asset.id}|") }.forEach(videoCache::removeResource)
            }
        }
    }

    suspend fun setMaxBytes(bytes: Long) = withContext(Dispatchers.IO) {
        val safe = bytes.coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES)
        synchronized(this@PartnerMediaCache) {
            preferences.edit().putLong(MAX_BYTES, safe).apply()
            videoCache.release()
            if (videoDir.exists() && !videoDir.deleteRecursively()) throw IOException("Videocache konnte nicht vollständig gelöscht werden")
            videoCache = newVideoCache()
            trimImages()
        }
    }

    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) {
        imageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + videoCache.cacheSpace
    }

    private fun trimImages() {
        var total = imageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        imageDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                if (total > imageBudget()) {
                    val length = file.length()
                    if (!file.delete()) throw IOException("Cache-Datei konnte nicht bereinigt werden")
                    total -= length
                }
            }
    }

    private fun authInterceptor() = Interceptor { chain ->
        val request = chain.request().newBuilder().apply {
            if (chain.request().header("Authorization") == null) {
                credentials.readAccessToken()?.let { header("Authorization", "Bearer $it") }
            }
        }.build()
        chain.proceed(request)
    }

    companion object {
        private const val PREFERENCES = "partner_media_cache"
        private const val MAX_BYTES = "max_bytes"
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        const val MIN_MAX_BYTES = 500L * 1024L * 1024L
        const val MAX_MAX_BYTES = 10L * 1024L * 1024L * 1024L

        @Volatile private var instance: PartnerMediaCache? = null

        fun get(context: Context, credentials: SecureCredentialStore): PartnerMediaCache =
            instance ?: synchronized(this) {
                instance ?: PartnerMediaCache(context.applicationContext, credentials).also { instance = it }
            }
    }
}
