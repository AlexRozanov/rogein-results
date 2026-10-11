package ru.rogein.chip.queue

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "event_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "event_corrections")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val logicalId: Int?,
    val courseName: String,
    val payloadJson: String,
)

@Entity(tableName = "event_exclusions")
data class ExclusionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val fromCp: Int,
    val toCp: Int,
    val maxLegSeconds: Int?,
)

@Dao
interface EventDao {
    @Query("SELECT * FROM event_settings")
    fun observeSettings(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM event_settings")
    suspend fun listSettings(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(row: SettingEntity)

    @Query("SELECT * FROM event_corrections ORDER BY id ASC")
    fun observeCorrections(): Flow<List<CorrectionEntity>>

    @Query("SELECT * FROM event_corrections ORDER BY id ASC")
    suspend fun listCorrections(): List<CorrectionEntity>

    @Insert
    suspend fun insertCorrection(row: CorrectionEntity): Long

    @Query("DELETE FROM event_corrections WHERE id = :id")
    suspend fun deleteCorrection(id: Long)

    @Query("DELETE FROM event_corrections")
    suspend fun clearCorrections()

    @Query("SELECT * FROM event_exclusions ORDER BY id ASC")
    fun observeExclusions(): Flow<List<ExclusionEntity>>

    @Query("SELECT * FROM event_exclusions ORDER BY id ASC")
    suspend fun listExclusions(): List<ExclusionEntity>

    @Insert
    suspend fun insertExclusion(row: ExclusionEntity): Long

    @Query("DELETE FROM event_exclusions WHERE id = :id")
    suspend fun deleteExclusion(id: Long)

    @Query("DELETE FROM event_exclusions")
    suspend fun clearExclusions()
}
