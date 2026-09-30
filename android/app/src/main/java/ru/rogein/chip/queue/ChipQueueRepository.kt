package ru.rogein.chip.queue

import android.content.Context
import androidx.room.Room
import ru.rogein.chip.parse.ChipDump
import java.util.UUID

class ChipQueueRepository(private val dao: ChipQueueDao) {
    fun observeAll() = dao.observeAll()

    suspend fun enqueue(dump: ChipDump, rawHex: String = "") {
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
    }

    suspend fun clear() = dao.clear()

    companion object {
        fun create(context: Context): ChipQueueRepository {
            val db = Room.databaseBuilder(
                context.applicationContext,
                ChipQueueDatabase::class.java,
                "chip-queue.db",
            ).build()
            return ChipQueueRepository(db.dao())
        }
    }
}
