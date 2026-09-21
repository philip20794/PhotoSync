package de.photosync.data.sync

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import de.photosync.data.local.AppDatabase
import de.photosync.data.offline.*
import de.photosync.data.remote.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val album = """{"id":"album","owner":{"id":"partner","displayName":"Partner"},"title":"Album","ownedByMe":false,"shared":true,"assetCount":2500,"optimizedBytes":"2500","originalBytes":"15000000000","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
    private fun asset(id: Int) = """{"id":"asset-$id","albumId":"album","ownerId":"partner","originalFileName":"$id.jpg","mimeType":"image/jpeg","fileSize":"6000000","width":10,"height":10,"status":"ready","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    @Test fun offlineServerFailureAndRestartResume2500MetadataWithoutDownloadingMedia() = runBlocking {
        val server = MockWebServer()
        var fail = true
        var mediaRequests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/v1/partner/albums") return MockResponse().setBody("""{"albums":[$album]}""")
                if (url.encodedPath == "/v1/albums/album/assets") {
                    if (fail) return MockResponse().setResponseCode(503)
                    val start = url.queryParameter("cursor")?.toInt() ?: 0
                    val end = minOf(start + 100, 2500)
                    val cursor = if (end == 2500) "null" else "\"$end\""
                    return MockResponse().setBody("""{"assets":[${(start until end).joinToString(",") { asset(it) }}],"nextCursor":$cursor}""")
                }
                if (url.encodedPath == "/v1/sync/changes") return MockResponse().setBody("""{"changes":[],"nextCursor":"complete","hasMore":false}""")
                mediaRequests++
                return MockResponse().setResponseCode(500)
            }
        }
        server.start()
        val name = "remote-" + UUID.randomUUID()
        var db = AppDatabase.build(context, name)
        try {
            val api = RetrofitFactory.create(server.url("/").toString())
            try { RemoteSyncEngine(context, db, api, "scope").run(); fail("Expected unavailable server") } catch (_: retrofit2.HttpException) {}
            assertNull(db.remoteDao().checkpoint("scope")?.cursor)
            assertNotNull(db.remoteDao().nextWork("scope"))
            db.close()
            db = AppDatabase.build(context, name)
            fail = false
            assertTrue(RemoteSyncEngine(context, db, api, "scope").run())
            assertEquals("2000", db.remoteDao().nextWork("scope")?.pageCursor)
            db.close()
            db = AppDatabase.build(context, name)
            RemoteSyncEngine(context, db, api, "scope").run()
            RemoteSyncEngine(context, db, api, "scope").run()
            assertNull(db.remoteDao().nextWork("scope"))
            assertEquals("complete", db.remoteDao().checkpoint("scope")?.cursor)
            val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM remote_metadata WHERE kind='ASSET'").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2500, count)
            assertEquals(0, mediaRequests)
        } finally { db.close(); context.deleteDatabase(name); server.shutdown() }
    }

    @Test fun cursorAndInboxRollbackTogetherAndSurviveRestart() = runBlocking {
        val name = "cursor-" + UUID.randomUUID()
        var db = AppDatabase.build(context, name)
        try {
            db.remoteDao().saveCheckpoint(RemoteCheckpoint("account", cursor = "old"))
            try {
                db.withTransaction {
                    db.remoteDao().saveCheckpoint(RemoteCheckpoint("account", cursor = "bad"))
                    db.remoteDao().saveWork(listOf(RemoteWork("account", "work", "album")))
                    throw java.io.IOException("process boundary")
                }
            } catch (_: java.io.IOException) {}
            db.close()
            db = AppDatabase.build(context, name)
            assertEquals("old", db.remoteDao().checkpoint("account")?.cursor)
            assertNull(db.remoteDao().nextWork("account"))
            assertNull(db.remoteDao().checkpoint("different account"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun staleOfflineCompletionCannotOverwriteNewIntentOrRecoverAnotherAlbum() = runBlocking {
        val name = "offline-race-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            val dao = db.offlineDao()
            dao.saveAlbum(OfflineAlbumEntity("scope", "album", OfflineMode.ORIGINAL, updatedAt = 10))
            dao.invalidateAlbum("scope", "album")
            assertEquals(0, dao.finishAlbum("scope", "album", 10, OfflineStatus.READY, null))
            assertEquals(OfflineStatus.PENDING, dao.album("scope", "album")?.status)
            for (id in listOf("a", "b")) dao.saveAsset(OfflineAssetEntity("scope", id, id, OfflineMode.ORIGINAL,
                status = OfflineStatus.DOWNLOADING, variantVersion = "v", expectedSha256 = "x", fileSize = 1))
            dao.recoverAlbum("scope", "a")
            assertEquals(OfflineStatus.RETRY, dao.asset("scope", "a")?.status)
            assertEquals(OfflineStatus.DOWNLOADING, dao.asset("scope", "b")?.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun offlineScopeAndPagingSurviveRestartWithoutCrossAccountReuse() = runBlocking {
        val name = "offline-scope-" + UUID.randomUUID()
        var db = AppDatabase.build(context, name)
        try {
            val a = "https://server-a|account-a"
            val b = "https://server-a|account-b"
            val c = "https://server-b|account-a"
            for (scope in listOf(a, b, c)) {
                db.offlineDao().saveAlbum(OfflineAlbumEntity(scope, "same-album", OfflineMode.OPTIMIZED, updatedAt = 10))
                db.offlineDao().saveAsset(OfflineAssetEntity(scope, "same-asset", "same-album", OfflineMode.OPTIMIZED,
                    variantVersion = scope, expectedSha256 = "x", fileSize = 1))
            }
            assertEquals(1, db.offlineDao().allAlbums(a).size)
            assertEquals(b, db.offlineDao().asset(b, "same-asset")?.variantVersion)
            assertEquals(1, db.offlineDao().advanceMetadata(a, "same-album", 10, "page-2", false))
            db.close()
            db = AppDatabase.build(context, name)
            assertEquals("page-2", db.offlineDao().album(a, "same-album")?.metadataCursor)
            db.offlineDao().invalidateAlbum(a, "same-album")
            assertNull(db.offlineDao().album(a, "same-album")?.metadataCursor)
            assertEquals(OfflineMode.OPTIMIZED, db.offlineDao().album(b, "same-album")?.desiredMode)
            db.offlineDao().requestScopeRemoval(a)
            assertEquals(OfflineMode.NONE, db.offlineDao().album(a, "same-album")?.desiredMode)
            assertEquals(OfflineMode.OPTIMIZED, db.offlineDao().album(c, "same-album")?.desiredMode)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun changedLegacyQueueEntryIsReinventoriedInsteadOfTrusted() = runBlocking {
        val name = "legacy-queue-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            val albumRow = de.photosync.data.local.SharedAlbumEntity(
                "local", "external:camera", "device", "external", "camera", "Camera",
            )
            db.syncDao().enableAlbum(albumRow)
            val old = de.photosync.data.local.UploadQueueEntity(
                clientAssetId = "legacy", localAlbumId = "local", contentUri = "content://old",
                originalFileName = "old.jpg", mimeType = "image/jpeg", capturedAtMillis = null,
                fileSize = 100, width = 1, height = 1, durationMillis = null, mediaStoreId = -1,
                sha256 = "a".repeat(64), serverAssetId = "server", status = de.photosync.data.local.UploadStatus.COMPLETE,
            )
            db.syncDao().enqueue(listOf(old))
            db.syncDao().enqueueDiscovered(listOf(old.copy(
                contentUri = "content://media/external/images/media/42", mediaStoreId = 42,
                fileSize = 101, dateModifiedSeconds = 11,
            )))
            assertNull(db.syncDao().getUpload("legacy"))
            val replacement = requireNotNull(db.syncDao().getDiscoveredUpload("local", 42))
            assertEquals(de.photosync.data.local.UploadStatus.REPLACEMENT_PENDING, replacement.status)
            assertEquals("a".repeat(64), replacement.sha256)
            assertEquals("server", replacement.serverAssetId)
            assertEquals(101, replacement.fileSize)
            val replacementHash = "b".repeat(64)
            assertEquals(1, db.syncDao().markReplacementChanged(
                replacement.clientAssetId, replacementHash, System.currentTimeMillis(),
            ))
            val delete = db.syncDao().nextDeletes("device", 10).single()
            assertEquals("server", delete.serverAssetId)
            assertEquals(1, db.syncDao().finishReplacementDelete(delete.clientAssetId, System.currentTimeMillis()))
            val pending = requireNotNull(db.syncDao().getUpload(delete.clientAssetId))
            assertEquals(de.photosync.data.local.UploadStatus.PENDING, pending.status)
            assertEquals(replacementHash, pending.sha256)
            assertNull(pending.serverAssetId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun largeUploadQueueDrainsInSuccessfulBoundedBatches() = runBlocking {
        val name = "large-queue-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            db.syncDao().enableAlbum(de.photosync.data.local.SharedAlbumEntity(
                "local", "external:camera", "device", "external", "camera", "Camera",
            ))
            db.syncDao().enqueue((0 until 75).map { id ->
                de.photosync.data.local.UploadQueueEntity(
                    clientAssetId = "asset-" + id, localAlbumId = "local",
                    contentUri = "content://media/" + id, originalFileName = id.toString() + ".jpg",
                    mimeType = "image/jpeg", capturedAtMillis = null, fileSize = 1,
                    width = 1, height = 1, durationMillis = null,
                )
            })
            var processed = 0
            while (db.syncDao().hasRemainingUploads("device")) {
                val batch = db.syncDao().nextUploads("device", 20)
                assertTrue(batch.isNotEmpty())
                assertTrue(batch.size <= 20)
                batch.forEach {
                    db.syncDao().markComplete(it.clientAssetId, "server-" + it.clientAssetId, System.currentTimeMillis())
                }
                processed += batch.size
                assertEquals(
                    if (processed < 75) SyncWorkDisposition.CONTINUE else SyncWorkDisposition.SUCCESS,
                    syncWorkDisposition(false, db.syncDao().hasRemainingUploads("device")),
                )
            }
            assertEquals(75, processed)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun uploadSessionAndCommittedOffsetSurviveProcessRestart() = runBlocking {
        val name = "upload-session-" + UUID.randomUUID()
        var db = AppDatabase.build(context, name)
        try {
            db.syncDao().enableAlbum(de.photosync.data.local.SharedAlbumEntity(
                "local", "external:camera", "device", "external", "camera", "Camera",
            ))
            db.syncDao().enqueue(listOf(de.photosync.data.local.UploadQueueEntity(
                clientAssetId = "asset", localAlbumId = "local", contentUri = "content://media/asset",
                originalFileName = "asset.mp4", mimeType = "video/mp4", capturedAtMillis = null,
                fileSize = 10_000, width = 1, height = 1, durationMillis = 1,
                sha256 = "a".repeat(64), serverAssetId = "server-asset",
            )))
            db.syncDao().saveUploadSession("asset", "session-id", 4096, 1)
            db.close()

            db = AppDatabase.build(context, name)
            assertEquals(1, db.syncDao().recoverInterruptedUploads(2))
            val recovered = requireNotNull(db.syncDao().getUpload("asset"))
            assertEquals(de.photosync.data.local.UploadStatus.RETRY, recovered.status)
            assertEquals("session-id", recovered.uploadSessionId)
            assertEquals(4096, recovered.uploadedBytes)
            assertEquals("asset", db.syncDao().nextUploads("device", 1).single().clientAssetId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun missingCompletedMediaUsesPersistentDeleteQueueAndTombstoneState() = runBlocking {
        val name = "delete-queue-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            db.syncDao().enableAlbum(de.photosync.data.local.SharedAlbumEntity(
                "local", "external:camera", "device", "external", "camera", "Camera",
                serverAlbumId = "server-album", remoteShared = true,
            ))
            db.syncDao().enqueue(listOf(de.photosync.data.local.UploadQueueEntity(
                clientAssetId = "media-id", localAlbumId = "local", contentUri = "content://gone",
                originalFileName = "gone.jpg", mimeType = "image/jpeg", capturedAtMillis = null,
                fileSize = 10, width = 1, height = 1, durationMillis = null,
                mediaStoreId = 42, serverAssetId = "server-asset", status = de.photosync.data.local.UploadStatus.COMPLETE,
                uploadedBytes = 10,
            )))
            assertEquals(1, db.syncDao().markDeletePending("media-id", 2))
            assertEquals("media-id", db.syncDao().nextDeletes("device", 20).single().clientAssetId)
            db.syncDao().markDeleted("media-id", 3)
            assertTrue(!db.syncDao().hasRemainingDeletes("device"))
            assertEquals(de.photosync.data.local.UploadStatus.DELETED, db.syncDao().getUpload("media-id")?.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun remoteDeleteRemovesFileButPreservesOriginalOverrideForRestore() = runBlocking {
        val name = "offline-delete-override-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        val scope = "https://server|account"
        try {
            val album = OfflineAlbumEntity(scope, "album", OfflineMode.OPTIMIZED, updatedAt = 10)
            db.offlineDao().saveAlbum(album)
            val file = File(context.filesDir, "partner-offline-v2/${scopeHash(scope)}/album/asset-original-v")
            file.parentFile!!.mkdirs()
            file.writeText("original")
            db.offlineDao().saveAsset(OfflineAssetEntity(
                scope, "asset", "album", OfflineMode.ORIGINAL, overridesAlbumMode = true,
                actualVariant = OfflineMode.ORIGINAL, status = OfflineStatus.READY,
                variantVersion = "v", expectedSha256 = "hash", fileSize = file.length(),
                localPath = file.absolutePath, bytesDownloaded = file.length(),
            ))

            assertTrue(PartnerOfflineRepository(context, db, scope).removeRemoteAsset("album", "asset"))
            val intent = requireNotNull(db.offlineDao().asset(scope, "asset"))
            assertFalse(file.exists())
            assertEquals(OfflineStatus.REMOTE_DELETED, intent.status)
            assertNull(intent.localPath)
            assertTrue(intent.overridesAlbumMode)
            assertEquals(OfflineMode.ORIGINAL, desiredOfflineVariant(album, intent))
        } finally {
            db.close()
            context.deleteDatabase(name)
            File(context.filesDir, "partner-offline-v2/${scopeHash(scope)}").deleteRecursively()
        }
    }
}
