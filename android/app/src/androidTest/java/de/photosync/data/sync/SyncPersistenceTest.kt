package de.photosync.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.local.UploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun versionOneDatabaseMigratesWithoutLosingServerOrSession() = runBlocking {
        val name = "sync-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE server_config (id INTEGER NOT NULL PRIMARY KEY, baseUrl TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE device_session (id INTEGER NOT NULL PRIMARY KEY, userId TEXT NOT NULL, userDisplayName TEXT NOT NULL, deviceId TEXT NOT NULL, deviceName TEXT NOT NULL)")
        legacy.execSQL("INSERT INTO server_config VALUES (1, 'https://photosync.test/')")
        legacy.execSQL("INSERT INTO device_session VALUES (1, 'user-1', 'Alice', 'device-1', 'Pixel')")
        legacy.version = 1
        legacy.close()

        val database = AppDatabase.build(context, name)
        try {
            assertEquals("https://photosync.test/", database.appStateDao().getServer()?.baseUrl)
            assertEquals("device-1", database.appStateDao().getSession()?.deviceId)
            assertEquals(emptyList<Any>(), database.syncDao().observeProgress("device-1").first())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun queueSurvivesDatabaseRestartAndRunningUploadIsRecovered() = runBlocking {
        val name = "sync-restart-${UUID.randomUUID()}.db"
        var database = AppDatabase.build(context, name)
        try {
            val album = SharedAlbumEntity(
                localAlbumId = "device-1|external:camera",
                mediaStoreAlbumId = "external:camera",
                sourceDeviceId = "device-1",
                volumeName = "external",
                bucketId = "camera",
                title = "Kamera",
                serverAlbumId = "server-album",
                remoteShared = true,
            )
            database.syncDao().enableAlbum(album)
            database.syncDao().enqueue(listOf(
                UploadQueueEntity(
                    clientAssetId = "asset-1",
                    localAlbumId = album.localAlbumId,
                    contentUri = "content://media/external/images/media/1",
                    originalFileName = "one.jpg",
                    mimeType = "image/jpeg",
                    capturedAtMillis = null,
                    fileSize = 100,
                    width = 10,
                    height = 10,
                    durationMillis = null,
                    status = UploadStatus.UPLOADING,
                    uploadedBytes = 42,
                ),
            ))
            database.close()

            database = AppDatabase.build(context, name)
            assertNotNull(database.syncDao().getUpload("asset-1"))
            assertEquals(1, database.syncDao().recoverInterruptedUploads(System.currentTimeMillis()))
            val recovered = requireNotNull(database.syncDao().getUpload("asset-1"))
            assertEquals(UploadStatus.RETRY, recovered.status)
            assertEquals(0, recovered.uploadedBytes)
            val progress = database.syncDao().observeProgress("device-1").first().single()
            assertEquals(1, progress.totalCount)
            assertEquals(0, progress.completedCount)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
