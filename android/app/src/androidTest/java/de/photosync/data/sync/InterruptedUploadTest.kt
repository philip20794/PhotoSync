package de.photosync.data.sync

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.local.UploadStatus
import de.photosync.data.media.MediaStoreRepository
import de.photosync.data.remote.RetrofitFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InterruptedUploadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    } else {
        "external"
    }

    @Test
    fun disconnectedRequestReturnsQueueToRetryAndKeepsItAcrossRestart() = runBlocking {
        val databaseName = "sync-interrupt-${UUID.randomUUID()}.db"
        val mediaUri = insertImage()
        val server = MockWebServer()
        server.start()
        var database = AppDatabase.build(context, databaseName)
        try {
            val album = SharedAlbumEntity(
                localAlbumId = "device-1|external:camera",
                mediaStoreAlbumId = "external:camera",
                sourceDeviceId = "device-1",
                volumeName = volume,
                bucketId = "bucket-not-used-by-test",
                title = "Kamera",
                serverAlbumId = SERVER_ALBUM_ID,
                remoteShared = true,
            )
            database.syncDao().enableAlbum(album)
            database.syncDao().enqueue(listOf(
                UploadQueueEntity(
                    clientAssetId = "test-client-asset",
                    localAlbumId = album.localAlbumId,
                    contentUri = mediaUri.toString(),
                    originalFileName = "interrupt.png",
                    mimeType = "image/png",
                    capturedAtMillis = null,
                    fileSize = requireNotNull(resolver.openAssetFileDescriptor(mediaUri, "r")).use { it.length },
                    width = 1024,
                    height = 1024,
                    durationMillis = null,
                ),
            ))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(assetJson("pending", null)))
            val fileSize = requireNotNull(database.syncDao().getUpload("test-client-asset")).fileSize
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(sessionJson(0, fileSize, false)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))

            val api = RetrofitFactory.create(server.url("/").toString())
            val result = SyncEngine(
                context,
                database,
                api,
                DeviceSessionEntity(userId = "user-1", userDisplayName = "Alice", deviceId = "device-1", deviceName = "Pixel"),
                maxUploads = 1,
            ).run()
            assertEquals(true, result.retry)
            val interrupted = requireNotNull(database.syncDao().getUpload("test-client-asset"))
            assertEquals(UploadStatus.RETRY, interrupted.status)
            assertNotNull(interrupted.sha256)
            assertEquals(SERVER_ASSET_ID, interrupted.serverAssetId)
            assertEquals(UPLOAD_SESSION_ID, interrupted.uploadSessionId)
            assertEquals(0, interrupted.uploadedBytes)

            database.close()
            database = AppDatabase.build(context, databaseName)
            assertEquals(UploadStatus.RETRY, requireNotNull(database.syncDao().getUpload("test-client-asset")).status)
            assertEquals(3, server.requestCount)
        } finally {
            database.close()
            server.shutdown()
            resolver.delete(mediaUri, null, null)
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun sharingInventoriesAlbumUploadsOriginalAndDoesNotRepeatAfterRestart() = runBlocking {
        val databaseName = "sync-complete-${UUID.randomUUID()}.db"
        val mediaUri = insertImage()
        val originalBytes = resolver.openInputStream(mediaUri)!!.use { it.readBytes() }
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(originalBytes).joinToString("") { byte -> "%02x".format(byte) }
        val localAlbum = MediaStoreRepository(context).loadAlbums()
            .single { it.coverUri == mediaUri.toString() }
        val server = MockWebServer()
        server.start()
        var database = AppDatabase.build(context, databaseName)
        try {
            val album = SharedAlbumEntity(
                localAlbumId = "device-1|${localAlbum.id}",
                mediaStoreAlbumId = localAlbum.id,
                sourceDeviceId = "device-1",
                volumeName = localAlbum.volumeName,
                bucketId = localAlbum.bucketId,
                title = localAlbum.name,
            )
            database.syncDao().enableAlbum(album)
            server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(albumJson()))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(assetJson("pending", null)))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(sessionJson(0, originalBytes.size.toLong(), false)))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(sessionJson(originalBytes.size.toLong(), originalBytes.size.toLong(), true,
                    assetJson("ready", expectedHash))))
            val session = DeviceSessionEntity(userId = "user-1", userDisplayName = "Alice", deviceId = "device-1", deviceName = "Pixel")
            val api = RetrofitFactory.create(server.url("/").toString())

            val first = SyncEngine(context, database, api, session, maxUploads = 1).run()
            assertEquals(false, first.retry)
            val progress = database.syncDao().observeProgress("device-1").first().single()
            assertEquals(1, progress.totalCount)
            assertEquals(1, progress.completedCount)
            assertEquals(4, server.requestCount)
            assertEquals("/v1/albums", server.takeRequest().path)
            assertEquals("/v1/albums/$SERVER_ALBUM_ID/assets", server.takeRequest().path)
            assertEquals("/v1/assets/$SERVER_ASSET_ID/upload-session", server.takeRequest().path)
            val uploadRequest = server.takeRequest()
            assertEquals("/v1/upload-sessions/$UPLOAD_SESSION_ID", uploadRequest.path)
            assertEquals("0", uploadRequest.getHeader("Upload-Offset"))
            assertEquals(originalBytes.toList(), uploadRequest.body.readByteArray().toList())

            database.close()
            database = AppDatabase.build(context, databaseName)
            val second = SyncEngine(context, database, api, session, maxUploads = 1).run()
            assertEquals(false, second.retry)
            assertEquals(false, second.moreWork)
            assertEquals(4, server.requestCount)
        } finally {
            database.close()
            server.shutdown()
            resolver.delete(mediaUri, null, null)
            context.deleteDatabase(databaseName)
        }
    }

    private fun insertImage(): Uri {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "interrupt-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/PhotoSyncInterruptedTest/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = requireNotNull(resolver.insert(collection, values))
        resolver.openOutputStream(uri, "w")!!.use { output ->
            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(45, 125, 210))
            }
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally {
                bitmap.recycle()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private fun albumJson() = """
        {
          "id":"$SERVER_ALBUM_ID","owner":{"id":"00000000-0000-0000-0000-000000000001","displayName":"Alice"},
          "title":"PhotoSyncInterruptedTest","ownedByMe":true,"shared":true,
          "sourceDeviceId":"device-1","clientAlbumId":"external:test",
          "createdAt":"2026-09-14T00:00:00.000Z","updatedAt":"2026-09-14T00:00:00.000Z"
        }
    """.trimIndent()

    private fun assetJson(status: String, sha256: String?) = """
        {
          "id":"$SERVER_ASSET_ID","ownerId":"00000000-0000-0000-0000-000000000001",
          "albumId":"$SERVER_ALBUM_ID","originalFileName":"interrupt.png","mimeType":"image/png",
          "capturedAt":null,"fileSize":"100","width":1024,"height":1024,"durationMillis":null,
          "sha256":${sha256?.let { "\"$it\"" } ?: "null"},"status":"$status",
          "createdAt":"2026-09-14T00:00:00.000Z","updatedAt":"2026-09-14T00:00:00.000Z"
        }
    """.trimIndent()

    private fun sessionJson(offset: Long, size: Long, completed: Boolean, asset: String? = null) = """
        {
          "id":"$UPLOAD_SESSION_ID","assetId":"$SERVER_ASSET_ID","offset":"$offset","size":"$size",
          "expiresAt":"2026-09-21T00:00:00.000Z","completed":$completed,"asset":${asset ?: "null"}
        }
    """.trimIndent()

    private companion object {
        const val SERVER_ALBUM_ID = "00000000-0000-0000-0000-000000000010"
        const val SERVER_ASSET_ID = "00000000-0000-0000-0000-000000000020"
        const val UPLOAD_SESSION_ID = "00000000-0000-0000-0000-000000000030"
    }
}
