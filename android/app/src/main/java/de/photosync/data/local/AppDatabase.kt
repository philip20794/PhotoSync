package de.photosync.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import de.photosync.data.offline.OfflineAlbumEntity
import de.photosync.data.offline.OfflineAssetEntity
import de.photosync.data.offline.OfflineCleanupEntity
import de.photosync.data.offline.OfflineDao
import de.photosync.data.sync.RemoteCheckpoint
import de.photosync.data.sync.RemoteMetadata
import de.photosync.data.sync.RemoteWork
import de.photosync.data.sync.RemoteDao
import de.photosync.ui.partner.PartnerDisplayDao
import de.photosync.ui.partner.PartnerDisplayEntity

@Entity(tableName = "server_config")
data class ServerConfigEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val baseUrl: String,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Entity(tableName = "device_session")
data class DeviceSessionEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val userId: String,
    val userDisplayName: String,
    val deviceId: String,
    val deviceName: String,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Dao
interface AppStateDao {
    @Query("SELECT * FROM server_config WHERE id = 1")
    fun observeServer(): Flow<ServerConfigEntity?>

    @Query("SELECT * FROM device_session WHERE id = 1")
    fun observeSession(): Flow<DeviceSessionEntity?>

    @Query("SELECT * FROM server_config WHERE id = 1")
    suspend fun getServer(): ServerConfigEntity?

    @Query("SELECT * FROM device_session WHERE id = 1")
    suspend fun getSession(): DeviceSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveServer(config: ServerConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: DeviceSessionEntity)

    @Query("DELETE FROM device_session")
    suspend fun clearSession()
}

@Database(
    entities = [ServerConfigEntity::class, DeviceSessionEntity::class, SharedAlbumEntity::class, UploadQueueEntity::class, SyncSettingsEntity::class, OfflineAlbumEntity::class, OfflineAssetEntity::class, OfflineCleanupEntity::class, RemoteCheckpoint::class, RemoteMetadata::class, RemoteWork::class, PartnerDisplayEntity::class],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
    abstract fun syncDao(): SyncDao
    abstract fun settingsDao(): SettingsDao
    abstract fun offlineDao(): OfflineDao
    abstract fun remoteDao(): RemoteDao
    abstract fun partnerDisplayDao(): PartnerDisplayDao

    companion object {
        private const val DATABASE_NAME = "photosync.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `shared_albums` (
                        `localAlbumId` TEXT NOT NULL,
                        `mediaStoreAlbumId` TEXT NOT NULL,
                        `sourceDeviceId` TEXT NOT NULL,
                        `volumeName` TEXT NOT NULL,
                        `bucketId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `serverAlbumId` TEXT,
                        `shareRequested` INTEGER NOT NULL,
                        `remoteShared` INTEGER NOT NULL,
                        `lastScanAt` INTEGER,
                        `lastError` TEXT,
                        PRIMARY KEY(`localAlbumId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_albums_shareRequested` ON `shared_albums` (`shareRequested`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_albums_sourceDeviceId` ON `shared_albums` (`sourceDeviceId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `upload_queue` (
                        `clientAssetId` TEXT NOT NULL,
                        `localAlbumId` TEXT NOT NULL,
                        `contentUri` TEXT NOT NULL,
                        `originalFileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `capturedAtMillis` INTEGER,
                        `fileSize` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `durationMillis` INTEGER,
                        `sha256` TEXT,
                        `serverAssetId` TEXT,
                        `status` TEXT NOT NULL,
                        `uploadedBytes` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`clientAssetId`),
                        FOREIGN KEY(`localAlbumId`) REFERENCES `shared_albums`(`localAlbumId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_localAlbumId` ON `upload_queue` (`localAlbumId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `upload_queue` ADD COLUMN `mediaStoreId` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `upload_queue` ADD COLUMN `dateModifiedSeconds` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_localAlbumId_mediaStoreId` ON `upload_queue` (`localAlbumId`, `mediaStoreId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `offline_albums` (
                    `albumId` TEXT NOT NULL, `desiredMode` TEXT NOT NULL, `estimatedOptimizedBytes` INTEGER NOT NULL,
                    `estimatedOriginalBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastError` TEXT,
                    `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`albumId`))""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `offline_assets` (
                    `assetId` TEXT NOT NULL, `albumId` TEXT NOT NULL, `desiredVariant` TEXT NOT NULL,
                    `overridesAlbumMode` INTEGER NOT NULL, `actualVariant` TEXT, `status` TEXT NOT NULL, `variantVersion` TEXT NOT NULL,
                    `expectedSha256` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `localPath` TEXT,
                    `bytesDownloaded` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT,
                    `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`assetId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_assets_albumId` ON `offline_assets` (`albumId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_assets_status` ON `offline_assets` (`status`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE remote_checkpoint (scope TEXT NOT NULL PRIMARY KEY, cursor TEXT, pendingCursor TEXT, hasMore INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE remote_metadata (scope TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, albumId TEXT NOT NULL, sortKey TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(scope, id))")
                db.execSQL("CREATE INDEX index_remote_metadata_scope_kind_albumId_sortKey ON remote_metadata(scope, kind, albumId, sortKey)")
                db.execSQL("CREATE TABLE remote_work (scope TEXT NOT NULL, id TEXT NOT NULL, albumId TEXT NOT NULL, assetId TEXT, pageCursor TEXT, PRIMARY KEY(scope, id))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_albums RENAME TO offline_albums_legacy")
                db.execSQL("ALTER TABLE offline_assets RENAME TO offline_assets_legacy")
                db.execSQL("""CREATE TABLE offline_albums (
                    scope TEXT NOT NULL, albumId TEXT NOT NULL, desiredMode TEXT NOT NULL,
                    estimatedOptimizedBytes INTEGER NOT NULL, estimatedOriginalBytes INTEGER NOT NULL,
                    status TEXT NOT NULL, lastError TEXT, updatedAt INTEGER NOT NULL,
                    metadataCursor TEXT, metadataComplete INTEGER NOT NULL,
                    PRIMARY KEY(scope, albumId))""")
                db.execSQL("CREATE INDEX index_offline_albums_scope ON offline_albums (scope)")
                db.execSQL("CREATE INDEX index_offline_albums_scope_status ON offline_albums (scope, status)")
                db.execSQL("""INSERT INTO offline_albums
                    SELECT '__legacy_unscoped__', albumId, desiredMode, estimatedOptimizedBytes,
                    estimatedOriginalBytes, 'PENDING', 'Nicht zuordenbare Daten aus älterer Version werden entfernt',
                    updatedAt, NULL, 0 FROM offline_albums_legacy""")
                db.execSQL("""CREATE TABLE offline_assets (
                    scope TEXT NOT NULL, assetId TEXT NOT NULL, albumId TEXT NOT NULL,
                    desiredVariant TEXT NOT NULL, overridesAlbumMode INTEGER NOT NULL,
                    actualVariant TEXT, status TEXT NOT NULL, variantVersion TEXT NOT NULL,
                    expectedSha256 TEXT NOT NULL, fileSize INTEGER NOT NULL, localPath TEXT,
                    bytesDownloaded INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT,
                    updatedAt INTEGER NOT NULL, seenGeneration INTEGER NOT NULL,
                    PRIMARY KEY(scope, assetId))""")
                db.execSQL("CREATE INDEX index_offline_assets_scope_albumId ON offline_assets (scope, albumId)")
                db.execSQL("CREATE INDEX index_offline_assets_scope_status ON offline_assets (scope, status)")
                db.execSQL("""INSERT INTO offline_assets
                    SELECT '__legacy_unscoped__', assetId, albumId, desiredVariant, overridesAlbumMode,
                    actualVariant, 'RETRY', variantVersion, expectedSha256, fileSize, localPath,
                    bytesDownloaded, attempts, 'Nicht zuordenbare Daten aus älterer Version werden entfernt',
                    updatedAt, 0 FROM offline_assets_legacy""")
                db.execSQL("""CREATE TABLE offline_cleanup (
                    id TEXT NOT NULL, kind TEXT NOT NULL, scope TEXT NOT NULL, albumId TEXT,
                    attempts INTEGER NOT NULL, lastError TEXT, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id))""")
                db.execSQL("""INSERT INTO offline_cleanup
                    (id, kind, scope, albumId, attempts, lastError, updatedAt)
                    VALUES ('legacy-root-v6', 'LEGACY_ROOT', '__legacy_unscoped__', NULL, 0, NULL, 0)""")
                db.execSQL("DROP TABLE offline_assets_legacy")
                db.execSQL("DROP TABLE offline_albums_legacy")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_albums ADD COLUMN backupRequested INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shared_albums ADD COLUMN remoteBackedUp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_albums_backupRequested ON shared_albums (backupRequested)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS sync_settings (
                    scope TEXT NOT NULL, autoBackupEnabled INTEGER NOT NULL, wifiOnly INTEGER NOT NULL,
                    notifySyncErrors INTEGER NOT NULL, lastSuccessfulSyncAt INTEGER,
                    serverReachable INTEGER, lastServerCheckAt INTEGER, lastSyncError TEXT,
                    PRIMARY KEY(scope))""")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN uploadSessionId TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_albums ADD COLUMN relativePath TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS partner_display_overrides (scope TEXT NOT NULL, assetId TEXT NOT NULL, rotationDegrees INTEGER NOT NULL, PRIMARY KEY(scope, assetId))")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context, DATABASE_NAME).also { instance = it }
        }

        internal fun build(context: Context, name: String): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            name,
        ).addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
        ).build()
    }
}
