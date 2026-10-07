package ru.rogein.chip.queue

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val personName: String = "",
    val courseName: String = "",
)

@Entity(tableName = "start_people")
data class StartPersonEntity(
    @PrimaryKey val logicalId: Int,
    val name: String,
    val currentCourse: String,
    val coursesJson: String,
    val origin: String,
    val localCourseTouchedAt: Long,
    val issuedAt: Long = 0L,
)

@Entity(tableName = "start_courses")
data class StartCourseEntity(
    @PrimaryKey val name: String,
    val controlsJson: String,
)

@Entity(tableName = "start_meta")
data class StartMetaEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long,
    val appliedAt: Long,
)

@Dao
interface ChipQueueDao {
    @Query("SELECT * FROM chip_queue ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QueuedChipEntity>>

    @Query("SELECT * FROM chip_queue WHERE ackedAt IS NULL ORDER BY createdAt ASC")
    fun observePending(): Flow<List<QueuedChipEntity>>

    @Query("SELECT * FROM chip_queue ORDER BY createdAt ASC")
    suspend fun listAll(): List<QueuedChipEntity>

    @Insert
    suspend fun insert(item: QueuedChipEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM chip_queue
            WHERE logicalId = :logicalId
              AND courseName = :courseName COLLATE NOCASE
        )
        """,
    )
    suspend fun existsByChipAndCourse(logicalId: Int, courseName: String): Boolean

    @Query("UPDATE chip_queue SET ackedAt = :ackedAt WHERE id = :id")
    suspend fun ack(id: String, ackedAt: Long)

    @Query("DELETE FROM chip_queue")
    suspend fun clear()
}

@Dao
interface StartListDao {
    @Query("SELECT * FROM start_people ORDER BY logicalId ASC")
    fun observePeople(): Flow<List<StartPersonEntity>>

    @Query("SELECT * FROM start_people ORDER BY logicalId ASC")
    suspend fun listPeople(): List<StartPersonEntity>

    @Query("SELECT * FROM start_people WHERE logicalId = :id LIMIT 1")
    suspend fun person(id: Int): StartPersonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: StartPersonEntity)

    @Query("DELETE FROM start_people WHERE logicalId = :id")
    suspend fun deletePerson(id: Int)

    @Query("SELECT * FROM start_courses ORDER BY name COLLATE NOCASE ASC")
    fun observeCourses(): Flow<List<StartCourseEntity>>

    @Query("SELECT * FROM start_courses ORDER BY name COLLATE NOCASE ASC")
    suspend fun listCourses(): List<StartCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourse(course: StartCourseEntity)

    @Query("DELETE FROM start_courses")
    suspend fun clearCourses()

    @Query("DELETE FROM start_people")
    suspend fun clearPeople()

    @Query("DELETE FROM start_meta")
    suspend fun clearMeta()

    @Query("SELECT * FROM start_meta WHERE id = 1 LIMIT 1")
    suspend fun meta(): StartMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: StartMetaEntity)
}

@Database(
    entities = [
        QueuedChipEntity::class,
        StartPersonEntity::class,
        StartCourseEntity::class,
        StartMetaEntity::class,
    ],
    version = 3,
)
abstract class ChipQueueDatabase : RoomDatabase() {
    abstract fun dao(): ChipQueueDao
    abstract fun startListDao(): StartListDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chip_queue ADD COLUMN personName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chip_queue ADD COLUMN courseName TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS start_people (
                        logicalId INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        currentCourse TEXT NOT NULL,
                        coursesJson TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        localCourseTouchedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS start_courses (
                        name TEXT NOT NULL PRIMARY KEY,
                        controlsJson TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS start_meta (
                        id INTEGER NOT NULL PRIMARY KEY,
                        revision INTEGER NOT NULL,
                        appliedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE start_people ADD COLUMN issuedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
