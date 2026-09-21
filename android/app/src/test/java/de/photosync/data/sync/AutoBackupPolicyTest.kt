package de.photosync.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.offline.offlineWorkConstraints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import androidx.work.NetworkType

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AutoBackupPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun privateBackupThenShareReusesAlbumAndCompletedAsset() = runBlocking {
        val name = "backup-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            val album = SharedAlbumEntity(
                localAlbumId = "device|camera",
                mediaStoreAlbumId = "external:camera",
                sourceDeviceId = "device",
                volumeName = "external",
                bucketId = "camera",
                title = "Camera",
                shareRequested = false,
                backupRequested = true,
            )
            db.syncDao().enableBackup(album)
            db.syncDao().markRemoteAlbum(album.localAlbumId, "server-album", false, true)
            db.syncDao().enqueue(listOf(UploadQueueEntity(
                clientAssetId = "asset", localAlbumId = album.localAlbumId,
                contentUri = "content://media/1", originalFileName = "1.jpg",
                mimeType = "image/jpeg", capturedAtMillis = null, fileSize = 10,
                width = 1, height = 1, durationMillis = null,
            )))
            assertEquals(listOf("asset"), db.syncDao().nextUploads("device", 20).map { it.clientAssetId })
            db.syncDao().markComplete("asset", "server-asset", 1)

            db.syncDao().enableAlbum(album.copy(shareRequested = true, backupRequested = false))
            val shared = db.syncDao().getAlbum(album.localAlbumId)!!
            assertTrue(shared.shareRequested)
            assertTrue(shared.backupRequested)
            assertEquals("server-album", shared.serverAlbumId)
            assertTrue(db.syncDao().nextUploads("device", 20).isEmpty())

            db.syncDao().setShareRequested(album.localAlbumId, false)
            val privateAgain = db.syncDao().getAlbum(album.localAlbumId)!!
            assertFalse(privateAgain.shareRequested)
            assertTrue(privateAgain.backupRequested)
            assertTrue(db.syncDao().getRequestedAlbums("device").isNotEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun disablingBackupStopsOnlyPrivateInventoryWhileSharedAlbumsContinue() = runBlocking {
        val name = "backup-off-" + UUID.randomUUID()
        val db = AppDatabase.build(context, name)
        try {
            val private = SharedAlbumEntity("private", "p", "device", "external", "p", "Private",
                shareRequested = false, backupRequested = true)
            val shared = SharedAlbumEntity("shared", "s", "device", "external", "s", "Shared",
                shareRequested = true, backupRequested = true)
            db.syncDao().enableBackup(private)
            db.syncDao().enableAlbum(shared)
            db.syncDao().enableBackup(shared)
            db.syncDao().disableAutoBackup("device")
            assertFalse(db.syncDao().getAlbum("private")!!.backupRequested)
            assertFalse(db.syncDao().getAlbum("shared")!!.backupRequested)
            assertFalse(db.syncDao().getRequestedAlbums("device").any { it.localAlbumId == "private" })
            assertTrue(db.syncDao().getRequestedAlbums("device").any { it.localAlbumId == "shared" })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun wifiOnlyConstrainsMediaAndOfflineButControlRemainsConnected() {
        assertEquals(NetworkType.UNMETERED, SyncScheduler.transferConstraints(true).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, SyncScheduler.transferConstraints(false).requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, offlineWorkConstraints(true).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, offlineWorkConstraints(false).requiredNetworkType)
    }
}
