package de.photosync.data.local

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import de.photosync.data.offline.LEGACY_OFFLINE_SCOPE
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionEightAlbumsGainStableRelativePathWithoutLosingState() {
        val name = "album-source-migration-" + UUID.randomUUID()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE shared_albums (
                            localAlbumId TEXT NOT NULL PRIMARY KEY, mediaStoreAlbumId TEXT NOT NULL,
                            sourceDeviceId TEXT NOT NULL, volumeName TEXT NOT NULL, bucketId TEXT NOT NULL,
                            title TEXT NOT NULL, serverAlbumId TEXT, shareRequested INTEGER NOT NULL,
                            backupRequested INTEGER NOT NULL, remoteShared INTEGER NOT NULL,
                            remoteBackedUp INTEGER NOT NULL, lastScanAt INTEGER, lastError TEXT)""")
                        db.execSQL("INSERT INTO shared_albums VALUES ('local', 'camera', 'device', 'external', 'camera', 'Camera', 'server', 1, 1, 1, 1, NULL, NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        try {
            AppDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT relativePath, serverAlbumId, shareRequested, backupRequested FROM shared_albums").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("", it.getString(0))
                assertEquals("server", it.getString(1))
                assertEquals(1, it.getInt(2))
                assertEquals(1, it.getInt(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSixAlbumsGainPrivateBackupFlagsWithoutLosingSharingState() {
        val name = "settings-migration-" + UUID.randomUUID()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE shared_albums (
                            localAlbumId TEXT NOT NULL PRIMARY KEY, mediaStoreAlbumId TEXT NOT NULL,
                            sourceDeviceId TEXT NOT NULL, volumeName TEXT NOT NULL, bucketId TEXT NOT NULL,
                            title TEXT NOT NULL, serverAlbumId TEXT, shareRequested INTEGER NOT NULL,
                            remoteShared INTEGER NOT NULL, lastScanAt INTEGER, lastError TEXT)""")
                        db.execSQL("INSERT INTO shared_albums VALUES ('local', 'camera', 'device', 'external', 'camera', 'Camera', NULL, 1, 1, NULL, NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        try {
            AppDatabase.MIGRATION_6_7.migrate(db)
            db.query("SELECT shareRequested, backupRequested, remoteBackedUp FROM shared_albums WHERE localAlbumId = 'local'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals(0, it.getInt(1))
                assertEquals(0, it.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM sync_settings").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionFiveRowsAreQuarantinedAndScheduledForCleanup() {
        val name = "offline-migration-" + UUID.randomUUID()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE offline_albums (
                            albumId TEXT NOT NULL PRIMARY KEY, desiredMode TEXT NOT NULL,
                            estimatedOptimizedBytes INTEGER NOT NULL, estimatedOriginalBytes INTEGER NOT NULL,
                            status TEXT NOT NULL, lastError TEXT, updatedAt INTEGER NOT NULL)""")
                        db.execSQL("""CREATE TABLE offline_assets (
                            assetId TEXT NOT NULL PRIMARY KEY, albumId TEXT NOT NULL, desiredVariant TEXT NOT NULL,
                            overridesAlbumMode INTEGER NOT NULL, actualVariant TEXT, status TEXT NOT NULL,
                            variantVersion TEXT NOT NULL, expectedSha256 TEXT NOT NULL, fileSize INTEGER NOT NULL,
                            localPath TEXT, bytesDownloaded INTEGER NOT NULL, attempts INTEGER NOT NULL,
                            lastError TEXT, updatedAt INTEGER NOT NULL)""")
                        db.execSQL("CREATE INDEX index_offline_assets_albumId ON offline_assets(albumId)")
                        db.execSQL("CREATE INDEX index_offline_assets_status ON offline_assets(status)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("INSERT INTO offline_albums VALUES ('album', 'OPTIMIZED', 10, 20, 'READY', NULL, 7)")
            db.execSQL("INSERT INTO offline_assets VALUES ('asset', 'album', 'OPTIMIZED', 0, 'OPTIMIZED', 'READY', 'v1', 'hash', 10, '/old/file', 10, 0, NULL, 7)")
            AppDatabase.MIGRATION_5_6.migrate(db)
            db.query("SELECT scope, status FROM offline_albums WHERE albumId = 'album'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(LEGACY_OFFLINE_SCOPE, it.getString(0))
                assertEquals("PENDING", it.getString(1))
            }
            db.query("SELECT COUNT(*) FROM offline_albums WHERE scope = 'new-session'").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT kind FROM offline_cleanup").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("LEGACY_ROOT", it.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
