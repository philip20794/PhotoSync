package de.photosync.ui.partner

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Device-local presentation choice; it never changes the partner's original. */
@Entity(tableName = "partner_display_overrides", primaryKeys = ["scope", "assetId"])
data class PartnerDisplayEntity(val scope: String, val assetId: String, val rotationDegrees: Int)

@Dao
interface PartnerDisplayDao {
    @Query("SELECT rotationDegrees FROM partner_display_overrides WHERE scope = :scope AND assetId = :assetId")
    fun observeRotation(scope: String, assetId: String): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PartnerDisplayEntity)
}
