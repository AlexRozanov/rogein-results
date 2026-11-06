package ru.rogein.chip.queue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Полная копия очереди рядом со start-pack.json: каталог приложения и Download/rogein.
 * Очередь в Room не очищается.
 */
class ChipQueueSnapshotWriter(private val context: Context) {
    fun write(items: List<QueuedChipEntity>): List<String> =
        RogeinDownloadJson.write(context, FILE_NAME, toJson(items))

    private fun toJson(items: List<QueuedChipEntity>): String {
        val array = JSONArray()
        for (item in items) {
            val punches = try {
                JSONArray(item.punchesJson)
            } catch (_: Exception) {
                JSONArray()
            }
            if (punches.length() == 0) continue
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("uid", item.uid)
                    .put("logicalId", item.logicalId ?: JSONObject.NULL)
                    .put("name", item.personName)
                    .put("courseName", item.courseName)
                    .put("clearedAt", item.clearedAt ?: JSONObject.NULL)
                    .put("punches", punches),
            )
        }
        return JSONObject().put("items", array).toString()
    }

    companion object {
        const val FILE_NAME = "chip-queue.json"
    }
}

class SnapshotWriteException(message: String) : Exception(message)
