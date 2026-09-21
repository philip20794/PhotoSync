package de.photosync.data.sync

import androidx.room.*
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

/** Server URL and authenticated user are part of every remote-state key. */
fun remoteScope(baseUrl: String, userId: String) = baseUrl.trimEnd('/') + "|" + userId

@Entity(tableName = "remote_checkpoint")
data class RemoteCheckpoint(
    @PrimaryKey val scope: String,
    val cursor: String? = null,
    val pendingCursor: String? = null,
    val hasMore: Boolean = false,
)

@Entity(tableName = "remote_metadata", primaryKeys = ["scope", "id"], indices = [Index("scope", "kind", "albumId", "sortKey")])
data class RemoteMetadata(
    val scope: String, val id: String, val kind: String, val albumId: String,
    val sortKey: String, val json: String,
)

@Entity(tableName = "remote_work", primaryKeys = ["scope", "id"])
data class RemoteWork(val scope: String, val id: String, val albumId: String, val assetId: String? = null, val pageCursor: String? = null)

@Dao
interface RemoteDao {
    @Query("SELECT * FROM remote_checkpoint WHERE scope = :scope")
    suspend fun checkpoint(scope: String): RemoteCheckpoint?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(value: RemoteCheckpoint)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMetadata(values: List<RemoteMetadata>)
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND kind = 'ALBUM' ORDER BY sortKey, id")
    fun observeAlbums(scope: String): Flow<List<RemoteMetadata>>
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND kind = 'ALBUM'")
    suspend fun albums(scope: String): List<RemoteMetadata>
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND kind = 'ASSET' AND albumId = :albumId ORDER BY sortKey DESC, id DESC")
    fun assets(scope: String, albumId: String): PagingSource<Int, RemoteMetadata>
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND id = :id LIMIT 1")
    suspend fun metadata(scope: String, id: String): RemoteMetadata?
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND kind = 'ASSET' AND albumId = :albumId")
    suspend fun assetMetadata(scope: String, albumId: String): List<RemoteMetadata>
    @Query("SELECT * FROM remote_metadata WHERE scope = :scope AND kind = 'ASSET'")
    suspend fun allAssetMetadata(scope: String): List<RemoteMetadata>
    @Query("DELETE FROM remote_metadata WHERE scope = :scope AND albumId = :albumId")
    suspend fun removeAlbum(scope: String, albumId: String)
    @Query("DELETE FROM remote_metadata WHERE scope = :scope AND id = :id")
    suspend fun removeAsset(scope: String, id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWork(values: List<RemoteWork>)
    @Query("SELECT * FROM remote_work WHERE scope = :scope ORDER BY id LIMIT 1")
    suspend fun nextWork(scope: String): RemoteWork?
    @Query("DELETE FROM remote_work WHERE scope = :scope AND id = :id")
    suspend fun completeWork(scope: String, id: String)
    @Query("DELETE FROM remote_work WHERE scope = :scope")
    suspend fun clearWork(scope: String)
    @Query("DELETE FROM remote_metadata WHERE scope = :scope AND kind = 'ASSET'")
    suspend fun clearAssets(scope: String)
}
