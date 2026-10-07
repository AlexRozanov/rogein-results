package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.startlist.StartListStore
import java.util.UUID

sealed class EnqueueResult {
    data class Saved(val snapshotError: String?) : EnqueueResult()
    data class Duplicate(val logicalId: Int?, val courseName: String) : EnqueueResult()
}

class ChipQueueRepository(
    private val dao: ChipQueueDao,
    private val snapshot: ChipQueueSnapshotWriter,
    val startList: StartListStore,
) {
    fun observeAll() = dao.observeAll()

    /** Saved — чип в очереди. Duplicate — этот номер уже считан на этой дистанции. */
    suspend fun enqueue(
        dump: ChipDump,
        rawHex: String = "",
        personName: String = "",
        courseName: String = "",
    ): EnqueueResult {
        val course = courseName.trim()
        val chipId = dump.logicalId
        if (chipId != null && dao.existsByChipAndCourse(chipId, course)) {
            return EnqueueResult.Duplicate(chipId, course)
        }
        dao.insert(
            QueuedChipEntity(
                id = UUID.randomUUID().toString(),
                uid = dump.uid,
                logicalId = dump.logicalId,
                clearedAt = dump.clearedAt,
                punchCount = dump.punches.size,
                firstTime = dump.punches.firstOrNull()?.time,
                lastTime = dump.punches.lastOrNull()?.time,
                punchesJson = dump.punches.joinToString(prefix = "[", postfix = "]") { punch ->
                    """{"cp":${punch.cp},"time":"${punch.time}"}"""
                },
                rawHex = rawHex,
                createdAt = System.currentTimeMillis(),
                ackedAt = null,
                personName = personName,
                courseName = course,
            ),
        )
        return EnqueueResult.Saved(publishSnapshot())
    }

    suspend fun clear(): String? {
        dao.clear()
        return publishSnapshot()
    }

    suspend fun resetForNewStart(): String? {
        dao.clear()
        startList.resetAll()
        return publishSnapshot()
    }

    private suspend fun publishSnapshot(): String? {
        return try {
            snapshot.write(dao.listAll())
            null
        } catch (e: Exception) {
            e.message ?: "не удалось записать снимок"
        }
    }

    companion object {
        fun create(context: Context): ChipQueueRepository {
            val appContext = context.applicationContext
            val db = Room.databaseBuilder(
                appContext,
                ChipQueueDatabase::class.java,
                "chip-queue.db",
            )
                .addMigrations(ChipQueueDatabase.MIGRATION_1_2, ChipQueueDatabase.MIGRATION_2_3)
                .build()
            return ChipQueueRepository(
                db.dao(),
                ChipQueueSnapshotWriter(appContext),
                StartListStore(appContext, db.startListDao()),
            )
        }
    }
}
