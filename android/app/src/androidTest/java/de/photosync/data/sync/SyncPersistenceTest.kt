package de.photosync.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SharedAlbumEntity
import de.photosync.data.local.UploadQueueEntity
import de.photosync.data.local.UploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

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

    @Test
    fun changedMediaStoreVersionReplacesQueueEntryButUnchangedVersionDoesNot() = runBlocking {
        val name = "sync-media-version-" + UUID.randomUUID() + ".db"
        val database = AppDatabase.build(context, name)
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
            val completed = UploadQueueEntity(
                clientAssetId = "ms_stable",
                localAlbumId = album.localAlbumId,
                contentUri = "content://media/external/images/media/42",
                originalFileName = "one.jpg",
                mimeType = "image/jpeg",
                capturedAtMillis = null,
                fileSize = 100,
                width = 10,
                height = 10,
                durationMillis = null,
                mediaStoreId = 42,
                dateModifiedSeconds = 10,
                sha256 = "a".repeat(64),
                serverAssetId = "server-asset",
                status = UploadStatus.COMPLETE,
                uploadedBytes = 100,
            )
            database.syncDao().enqueueDiscovered(listOf(completed))
            database.syncDao().enqueueDiscovered(listOf(completed.copy(contentUri = completed.contentUri + "?same")))
            val unchanged = requireNotNull(database.syncDao().getDiscoveredUpload(album.localAlbumId, 42))
            assertEquals("ms_stable", unchanged.clientAssetId)
            assertEquals(UploadStatus.COMPLETE, unchanged.status)
            assertEquals("server-asset", unchanged.serverAssetId)
            assertEquals("a".repeat(64), unchanged.sha256)

            val changed = completed.copy(
                contentUri = completed.contentUri + "?changed",
                fileSize = 101,
                dateModifiedSeconds = 11,
                sha256 = null,
                serverAssetId = null,
                status = UploadStatus.PENDING,
                uploadedBytes = 0,
            )
            database.syncDao().enqueueDiscovered(listOf(changed))
            assertEquals(null, database.syncDao().getUpload("ms_stable"))
            val replacement = requireNotNull(database.syncDao().getDiscoveredUpload(album.localAlbumId, 42))
            assertEquals(true, replacement.clientAssetId.startsWith("ms_stable_v"))
            assertEquals(101, replacement.fileSize)
            assertEquals(11, replacement.dateModifiedSeconds)
            assertEquals(UploadStatus.REPLACEMENT_PENDING, replacement.status)
            assertEquals("a".repeat(64), replacement.sha256)
            assertEquals("server-asset", replacement.serverAssetId)

            val replacementHash = "b".repeat(64)
            assertEquals(
                1,
                database.syncDao().markReplacementChanged(
                    replacement.clientAssetId, replacementHash, System.currentTimeMillis(),
                ),
            )
            val delete = database.syncDao().nextDeletes("device-1", 10).single()
            assertEquals(UploadStatus.REPLACEMENT_DELETE_PENDING, delete.status)
            assertEquals("server-asset", delete.serverAssetId)
            assertEquals(1, database.syncDao().finishReplacementDelete(delete.clientAssetId, System.currentTimeMillis()))
            val readyForNewUpload = requireNotNull(database.syncDao().getUpload(delete.clientAssetId))
            assertEquals(UploadStatus.PENDING, readyForNewUpload.status)
            assertEquals(replacementHash, readyForNewUpload.sha256)
            assertEquals(null, readyForNewUpload.serverAssetId)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun legacyQueueRowIsSafelyReinventoriedOnFirstScan() = runBlocking {
        val name = "legacy-queue-" + UUID.randomUUID() + ".db"
        val database = AppDatabase.build(context, name)
        try {
            val album = SharedAlbumEntity("local", "external:camera", "device", "external", "camera", "Camera")
            database.syncDao().enableAlbum(album)
            val legacy = UploadQueueEntity(
                clientAssetId = "legacy-id", localAlbumId = "local",
                contentUri = "content://old", originalFileName = "old.jpg", mimeType = "image/jpeg",
                capturedAtMillis = null, fileSize = 100, width = 10, height = 10, durationMillis = null,
                mediaStoreId = -1, dateModifiedSeconds = 0, sha256 = "a".repeat(64),
                serverAssetId = "old-server-id", status = UploadStatus.COMPLETE, uploadedBytes = 100,
            )
            database.syncDao().enqueue(listOf(legacy))
            database.syncDao().enqueueDiscovered(listOf(legacy.copy(
                contentUri = "content://media/external/images/media/42",
                fileSize = 101, mediaStoreId = 42, dateModifiedSeconds = 11,
            )))
            assertEquals(null, database.syncDao().getUpload("legacy-id"))
            val rescanned = requireNotNull(database.syncDao().getDiscoveredUpload("local", 42))
            assertEquals(UploadStatus.REPLACEMENT_PENDING, rescanned.status)
            assertEquals(101, rescanned.fileSize)
            assertEquals("a".repeat(64), rescanned.sha256)
            assertEquals("old-server-id", rescanned.serverAssetId)
            assertEquals(1, database.syncDao().markReplacementChanged(
                rescanned.clientAssetId, "b".repeat(64), System.currentTimeMillis(),
            ))
            val delete = database.syncDao().nextDeletes("device", 10).single()
            assertEquals("old-server-id", delete.serverAssetId)
            assertEquals(1, database.syncDao().finishReplacementDelete(delete.clientAssetId, System.currentTimeMillis()))
            assertEquals(UploadStatus.PENDING, database.syncDao().getUpload(delete.clientAssetId)?.status)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionFiveOfflineRowsBecomeUnusableLegacyScopeAndCleanupIntent() {
        val name = "offline-v5-" + UUID.randomUUID() + ".db"
        migration.createDatabase(name, 5).apply {
            execSQL("INSERT INTO offline_albums VALUES ('album', 'OPTIMIZED', 10, 20, 'READY', NULL, 7)")
            execSQL("INSERT INTO offline_assets VALUES ('asset', 'album', 'OPTIMIZED', 0, 'OPTIMIZED', 'READY', 'v1', 'hash', 10, '/old/file', 10, 0, NULL, 7)")
            close()
        }
        val migrated = migration.runMigrationsAndValidate(name, 6, true, AppDatabase.MIGRATION_5_6)
        migrated.query("SELECT scope, status FROM offline_albums WHERE albumId = 'album'").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("__legacy_unscoped__", it.getString(0))
            assertEquals("PENDING", it.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM offline_albums WHERE scope = 'active-account'").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT kind FROM offline_cleanup").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("LEGACY_ROOT", it.getString(0))
        }
        migrated.close()
    }


    @Test
    fun offlineDownloadSurvivesDatabaseRestartAndIsRecoveredForRetry() = runBlocking {
        val name = "offline-restart-" + UUID.randomUUID() + ".db"
        var database = AppDatabase.build(context, name)
        try {
            database.offlineDao().saveAlbum(de.photosync.data.offline.OfflineAlbumEntity(
                scope = "scope", albumId = "partner-album", desiredMode = de.photosync.data.offline.OfflineMode.OPTIMIZED,
                status = de.photosync.data.offline.OfflineStatus.PENDING,
            ))
            database.offlineDao().saveAsset(de.photosync.data.offline.OfflineAssetEntity(
                scope = "scope", assetId = "partner-asset", albumId = "partner-album",
                desiredVariant = de.photosync.data.offline.OfflineMode.OPTIMIZED,
                status = de.photosync.data.offline.OfflineStatus.DOWNLOADING,
                variantVersion = "v1", expectedSha256 = "a".repeat(64), fileSize = 100,
                bytesDownloaded = 42,
            ))
            database.close()

            database = AppDatabase.build(context, name)
            assertEquals(1, database.offlineDao().recoverInterrupted("scope", System.currentTimeMillis()))
            val recovered = requireNotNull(database.offlineDao().asset("scope", "partner-asset"))
            assertEquals(de.photosync.data.offline.OfflineStatus.RETRY, recovered.status)
            assertEquals(42, recovered.bytesDownloaded)
            assertEquals(de.photosync.data.offline.OfflineMode.OPTIMIZED, database.offlineDao().album("scope", "partner-album")?.desiredMode)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

}
