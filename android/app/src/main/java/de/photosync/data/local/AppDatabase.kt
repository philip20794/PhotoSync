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
    entities = [ServerConfigEntity::class, DeviceSessionEntity::class, SharedAlbumEntity::class, UploadQueueEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
    abstract fun syncDao(): SyncDao

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

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context, DATABASE_NAME).also { instance = it }
        }

        internal fun build(context: Context, name: String): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            name,
        ).addMigrations(MIGRATION_1_2).build()
    }
}
