package ru.rogein.chip.queue

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Полная копия очереди рядом со start-pack.json: каталог приложения и Download/rogein.
 * Очередь в Room не очищается.
 */
class ChipQueueSnapshotWriter(private val context: Context) {
    fun write(items: List<QueuedChipEntity>): List<String> {
        val json = toJson(items)
        val written = LinkedHashSet<String>()
        val errors = mutableListOf<String>()
        for (file in fileTargets()) {
            try {
                writeFile(file, json)
                written += file.absolutePath
            } catch (e: Exception) {
                errors += "${file.absolutePath}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                writeMediaStore(json)
                written += "Downloads/rogein/$FILE_NAME"
            } catch (e: Exception) {
                errors += "MediaStore: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (written.isEmpty()) {
            throw SnapshotWriteException(
                errors.joinToString("; ").ifBlank { "не удалось записать $FILE_NAME" },
            )
        }
        Log.i(TAG, "wrote $FILE_NAME to ${written.joinToString()}")
        if (errors.isNotEmpty()) {
            Log.w(TAG, "snapshot extra errors: ${errors.joinToString()}")
        }
        return written.toList()
    }

    private fun fileTargets(): List<File> {
        val files = LinkedHashSet<File>()
        files += File(context.filesDir, FILE_NAME)
        context.getExternalFilesDir(null)?.let { files += File(it, FILE_NAME) }
        files += File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${context.packageName}/files/$FILE_NAME",
        )
        files += File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$DIR_NAME/$FILE_NAME",
        )
        return files.distinctBy { it.absoluteFile.normalize().path }
    }

    private fun writeFile(file: File, json: String) {
        val parent = file.parentFile
            ?: throw SnapshotWriteException("нет папки для ${file.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw SnapshotWriteException("не удалось создать ${parent.path}")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val tmp = File(parent, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        } else if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            throw SnapshotWriteException("файл не записался: ${file.path}")
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
                    .put("name", item.personName)
                    .put("courseName", item.courseName)
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
        }
        val uri = resolver.insert(collection, values)
            ?: throw SnapshotWriteException("не удалось создать файл в Загрузках")
        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw SnapshotWriteException("не удалось открыть файл в Загрузках")
        } catch (e: SnapshotWriteException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw SnapshotWriteException(e.message ?: "ошибка записи снимка")
        }
    }

    companion object {
        const val DIR_NAME = "rogein"
        const val FILE_NAME = "chip-queue.json"
        private const val TAG = "ChipQueueSnapshot"
    }
}

class SnapshotWriteException(message: String) : Exception(message)
