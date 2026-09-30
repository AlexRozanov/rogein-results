package ru.rogein.chip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.rogein.chip.parse.SfrChipParser
import ru.rogein.chip.queue.ChipQueueRepository

class ChipQueueViewModel(
    private val repository: ChipQueueRepository,
) : ViewModel() {
    val items = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _status = MutableStateFlow("Приложите чип к телефону")
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private var lastUid: String? = null
    private var lastAt: Long = 0

    fun markReading() {
        _busy.value = true
        _status.value = "Читаю чип…"
    }

    fun onReadFailed(message: String) {
        _busy.value = false
        _status.value = message
    }

    fun onChipBlocks(uid: String, blocks: List<ByteArray>) {
        val now = System.currentTimeMillis()
        if (uid == lastUid && now - lastAt < 2_000) {
            _busy.value = false
            return
        }
        lastUid = uid
        lastAt = now
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val dump = SfrChipParser.parse(uid, blocks)
                repository.enqueue(dump, rawBlocksToHex(blocks))
                val label = dump.logicalId?.let { "№ $it" } ?: uid
                _status.value = "$label · ${dump.punches.size} отметок"
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось прочитать чип"
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clear()
            _status.value = "Очередь очищена. Приложите чип"
        }
    }

    fun setNfcUnavailable() {
        _status.value = "NFC недоступен на этом устройстве"
    }

    companion object {
        fun factory(repository: ChipQueueRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChipQueueViewModel(repository) as T
                }
            }
    }
}

internal fun rawBlocksToHex(blocks: List<ByteArray>): String =
    blocks.joinToString(";") { block ->
        block.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
