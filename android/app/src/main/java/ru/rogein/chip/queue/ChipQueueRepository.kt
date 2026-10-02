package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.parse.ChipDump
import java.util.UUID

class ChipQueueRepository(
    private val dao: ChipQueueDao,
    private val snapshot: ChipQueueSnapshotWriter,
) {
    fun observeAll() = dao.observeAll()

    /** null — снимок записан. Иначе чип уже в очереди, а текст — почему снимок не лег в Загрузки. */
    suspend fun enqueue(dump: ChipDump, rawHex: String = ""): String? {
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
            ),
        )
        return publishSnapshot()
    }

    suspend fun clear(): String? {
        dao.clear()
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
            ).build()
            return ChipQueueRepository(db.dao(), ChipQueueSnapshotWriter(appContext))
        }
    }
}
