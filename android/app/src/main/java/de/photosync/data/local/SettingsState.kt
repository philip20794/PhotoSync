package de.photosync.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sync_settings")
data class SyncSettingsEntity(
    @PrimaryKey val scope: String,
    val autoBackupEnabled: Boolean = false,
    val wifiOnly: Boolean = false,
    val notifySyncErrors: Boolean = true,
    val lastSuccessfulSyncAt: Long? = null,
    val serverReachable: Boolean? = null,
    val lastServerCheckAt: Long? = null,
    val lastSyncError: String? = null,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM sync_settings WHERE scope = :scope")
    fun observe(scope: String): Flow<SyncSettingsEntity?>

    @Query("SELECT * FROM sync_settings WHERE scope = :scope")
    suspend fun get(scope: String): SyncSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: SyncSettingsEntity)

    @Query("UPDATE sync_settings SET lastSuccessfulSyncAt = :now, serverReachable = 1, lastServerCheckAt = :now, lastSyncError = NULL WHERE scope = :scope")
    suspend fun markSuccess(scope: String, now: Long)

    @Query("UPDATE sync_settings SET serverReachable = 0, lastServerCheckAt = :now, lastSyncError = :message WHERE scope = :scope")
    suspend fun markFailure(scope: String, now: Long, message: String)
}
