package ru.rogein.chip.queue

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Полная копия очереди в Download/rogein/chip-queue.json.
 * Сама очередь в Room не очищается: десктоп забирает файл по adb и удаляет только его.
 */
class ChipQueueSnapshotWriter(private val context: Context) {
    fun write(items: List<QueuedChipEntity>) {
        val json = toJson(items)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(json)
        } else {
            writeLegacy(json)
        }
    }

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
                    .put("clearedAt", item.clearedAt ?: JSONObject.NULL)
                    .put("punches", punches),
            )
        }
        return JSONObject().put("items", array).toString()
    }

    private fun writeMediaStore(json: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FILE_NAME, relative),
        )
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw SnapshotWriteException("не удалось создать файл в Загрузках")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw SnapshotWriteException("не удалось открыть файл в Загрузках")
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, published, null, null)
        } catch (e: SnapshotWriteException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw SnapshotWriteException(e.message ?: "ошибка записи снимка")
        }
    }

    private fun writeLegacy(json: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME,
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw SnapshotWriteException("не удалось создать папку Загрузки/rogein")
        }
        try {
            File(dir, FILE_NAME).writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            throw SnapshotWriteException(e.message ?: "ошибка записи снимка")
        }
    }

    companion object {
        const val DIR_NAME = "rogein"
        const val FILE_NAME = "chip-queue.json"
    }
}

class SnapshotWriteException(message: String) : Exception(message)
