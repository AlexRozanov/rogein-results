package ru.rogein.chip.queue

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chip_queue")
data class QueuedChipEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val logicalId: Int?,
    val clearedAt: String?,
    val punchCount: Int,
    val firstTime: String?,
    val lastTime: String?,
    val punchesJson: String,
    val rawHex: String,
    val createdAt: Long,
    val ackedAt: Long?,
)

@Dao
interface ChipQueueDao {
    @Query("SELECT * FROM chip_queue ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QueuedChipEntity>>

    @Query("SELECT * FROM chip_queue WHERE ackedAt IS NULL ORDER BY createdAt ASC")
    fun observePending(): Flow<List<QueuedChipEntity>>

    @Insert
    suspend fun insert(item: QueuedChipEntity)

    @Query("UPDATE chip_queue SET ackedAt = :ackedAt WHERE id = :id")
    suspend fun ack(id: String, ackedAt: Long)

    @Query("DELETE FROM chip_queue")
    suspend fun clear()
}

@Database(entities = [QueuedChipEntity::class], version = 1)
abstract class ChipQueueDatabase : RoomDatabase() {
    abstract fun dao(): ChipQueueDao
}
