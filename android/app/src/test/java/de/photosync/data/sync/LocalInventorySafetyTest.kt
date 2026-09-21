package de.photosync.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.DeviceSessionEntity
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.ServerConfigEntity
import de.photosync.data.local.SyncSettingsEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.local.UploadStatus
import de.photosync.data.media.MediaAccess
import de.photosync.data.media.MediaInventory
import de.photosync.data.media.MediaScanResult
import de.photosync.data.media.SyncMediaCandidate
import de.photosync.data.remote.RetrofitFactory
import de.photosync.domain.model.LocalAlbum
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalInventorySafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = DeviceSessionEntity(
        userId = "user", userDisplayName = "User", deviceId = "device", deviceName = "Phone",
    )

    private class FakeInventory(
        var complete: Boolean = true,
        var failure: IOException? = null,
        var candidates: List<SyncMediaCandidate> = emptyList(),
    ) : MediaInventory {
        var scans = 0
        override suspend fun loadAlbums(): List<LocalAlbum> = emptyList()
        override suspend fun scanAlbum(
            volumeName: String,
            bucketId: String,
            onBatch: suspend (List<SyncMediaCandidate>) -> Unit,
        ): MediaScanResult {
            scans += 1
            failure?.let { throw it }
            if (candidates.isNotEmpty()) onBatch(candidates)
            return MediaScanResult(complete)
        }
    }

    private suspend fun fixture(
        access: () -> MediaAccess,
        inventory: FakeInventory,
        privateBackup: Boolean = false,
        verify: suspend (AppDatabase, SyncEngine) -> Unit,
    ) {
        val name = "inventory-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            if (privateBackup) {
                val baseUrl = "https://photosync.invalid/"
                db.appStateDao().saveServer(ServerConfigEntity(baseUrl = baseUrl))
                db.settingsDao().save(SyncSettingsEntity(
                    scope = remoteScope(baseUrl, session.userId),
                    autoBackupEnabled = true,
                ))
            }
            db.syncDao().enableAlbum(SharedAlbumEntity(
                "local", "external:camera", "device", "external", "camera", "Camera",
                serverAlbumId = "server-album",
                shareRequested = !privateBackup,
                backupRequested = privateBackup,
                remoteShared = !privateBackup,
                remoteBackedUp = privateBackup,
            ))
            db.syncDao().enqueue(listOf(UploadQueueEntity(
                clientAssetId = "media-id", localAlbumId = "local", contentUri = "content://gone",
                originalFileName = "gone.jpg", mimeType = "image/jpeg", capturedAtMillis = null,
                fileSize = 10, width = 1, height = 1, durationMillis = null,
                mediaStoreId = 42, serverAssetId = "server-asset", status = UploadStatus.COMPLETE,
                uploadedBytes = 10,
            )))
            val engine = SyncEngine(
                context, db, RetrofitFactory.create("http://127.0.0.1/"), session,
                mediaStore = inventory, mediaAccess = access,
            )
            verify(db, engine)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun fullSuccessfulScanDeletesActuallyMissingMedia() = runBlocking {
        fixture({ MediaAccess.FULL }, FakeInventory()) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.DELETE_PENDING, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun partialAccessNeverDeletesInvisibleMedia() = runBlocking {
        fixture({ MediaAccess.PARTIAL }, FakeInventory()) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun partialAccessNeverDeletesInvisiblePrivateBackup() = runBlocking {
        fixture({ MediaAccess.PARTIAL }, FakeInventory(), privateBackup = true) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
            assertTrue(db.syncDao().getAlbum("local")?.backupRequested == true)
        }
    }

    @Test fun noAccessDoesNotScanOrDelete() = runBlocking {
        val inventory = FakeInventory()
        fixture({ MediaAccess.NONE }, inventory) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(0, inventory.scans)
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun unavailableVolumeNeverDeletes() = runBlocking {
        fixture({ MediaAccess.FULL }, FakeInventory(complete = false)) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun queryFailureNeverDeletes() = runBlocking {
        fixture({ MediaAccess.FULL }, FakeInventory(failure = IOException("query failed"))) { db, engine ->
            assertFalse(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun laterFullSuccessfulScanReconcilesMissingMedia() = runBlocking {
        var access = MediaAccess.PARTIAL
        val inventory = FakeInventory()
        fixture({ access }, inventory) { db, engine ->
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.COMPLETE, db.syncDao().getUpload("media-id")?.status)
            access = MediaAccess.FULL
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.DELETE_PENDING, db.syncDao().getUpload("media-id")?.status)
        }
    }

    @Test fun fullScanTerminatesMissingPendingUploadAndAllowsLaterReconciliation() = runBlocking {
        val inventory = FakeInventory()
        fixture({ MediaAccess.FULL }, inventory) { db, engine ->
            db.syncDao().deleteUpload("media-id")
            val pending = UploadQueueEntity(
                clientAssetId = "pending-local", localAlbumId = "local",
                contentUri = "content://missing", originalFileName = "missing.jpg",
                mimeType = "image/jpeg", capturedAtMillis = null, fileSize = 10,
                width = 1, height = 1, durationMillis = null, mediaStoreId = 42,
                dateModifiedSeconds = 10, status = UploadStatus.RETRY,
            )
            db.syncDao().enqueue(listOf(pending))

            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.ABANDONED, db.syncDao().getUpload("pending-local")?.status)
            assertFalse(db.syncDao().hasRemainingUploads("device"))

            inventory.candidates = listOf(SyncMediaCandidate(
                mediaStoreId = 42,
                contentUri = "content://media/external/images/media/42",
                displayName = "returned.jpg",
                mimeType = "image/jpeg",
                capturedAtMillis = null,
                dateModifiedSeconds = 11,
                fileSize = 10,
                width = 1,
                height = 1,
                durationMillis = null,
            ))
            assertTrue(engine.inventorySharedAlbums())
            val reconciled = requireNotNull(db.syncDao().getDiscoveredUpload("local", 42))
            assertEquals(UploadStatus.PENDING, reconciled.status)
            assertEquals(null, reconciled.serverAssetId)
        }
    }

    @Test fun fullScanQueuesServerCancellationForMissingOpenUpload() = runBlocking {
        fixture({ MediaAccess.FULL }, FakeInventory()) { db, engine ->
            db.syncDao().deleteUpload("media-id")
            db.syncDao().enqueue(listOf(UploadQueueEntity(
                clientAssetId = "open-upload", localAlbumId = "local",
                contentUri = "content://missing", originalFileName = "missing.jpg",
                mimeType = "image/jpeg", capturedAtMillis = null, fileSize = 10,
                width = 1, height = 1, durationMillis = null, mediaStoreId = 42,
                dateModifiedSeconds = 10, serverAssetId = "pending-server-asset",
                status = UploadStatus.RETRY,
            )))
            assertTrue(engine.inventorySharedAlbums())
            assertEquals(UploadStatus.CANCEL_PENDING, db.syncDao().getUpload("open-upload")?.status)
            assertEquals("pending-server-asset", db.syncDao().nextCancellations("device", 10).single().serverAssetId)
        }
    }
}
