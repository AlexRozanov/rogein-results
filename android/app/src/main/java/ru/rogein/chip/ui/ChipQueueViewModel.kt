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
import ru.rogein.chip.parse.BLOCK_COUNT
import ru.rogein.chip.parse.BlockOrder
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.SfrChipParser
import ru.rogein.chip.queue.ChipQueueRepository

interface ChipIo {
    fun clearMarks(order: BlockOrder, pointerEnd: Int?, blockCount: Int)
    fun writeLogicalId(order: BlockOrder, id: Int)
}

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

    private val _chipReady = MutableStateFlow(false)
    val chipReady = _chipReady.asStateFlow()

    private val _numberText = MutableStateFlow("")
    val numberText = _numberText.asStateFlow()

    private val _sessionSummary = MutableStateFlow<String?>(null)
    val sessionSummary = _sessionSummary.asStateFlow()

    @Volatile
    private var chipIo: ChipIo? = null

    @Volatile
    private var blockOrder: BlockOrder? = null

    @Volatile
    private var pointerEnd: Int? = null

    @Volatile
    private var punchCount: Int = 0

    @Volatile
    private var chipBlockCount: Int = BLOCK_COUNT

    private var lastUid: String? = null
    private var lastAt: Long = 0

    fun attach(io: ChipIo) {
        chipIo = io
    }

    fun markReading() {
        _busy.value = true
        _chipReady.value = false
        _sessionSummary.value = null
        _status.value = "Читаю чип…"
    }

    fun onReadFailed(message: String) {
        _busy.value = false
        _chipReady.value = false
        _status.value = message
    }

    fun onChipGone() {
        _chipReady.value = false
        blockOrder = null
        pointerEnd = null
        _sessionSummary.value = null
        if (!_busy.value) {
            _status.value = "Чип убран. Приложите его снова, чтобы очистить отметки или записать номер."
        }
    }

    fun onNumberText(value: String) {
        _numberText.value = value.filter { it.isDigit() }.take(5)
    }

    fun reportProgress(text: String) {
        _status.value = text
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
                val snapshotError = repository.enqueue(dump, rawBlocksToHex(blocks))
                blockOrder = dump.blockOrder
                pointerEnd = dump.pointerEnd
                punchCount = dump.punches.size
                chipBlockCount = dump.blockCount
                _numberText.value = dump.logicalId?.toString().orEmpty()
                val label = dump.logicalId?.let { "№ $it" } ?: "без номера"
                _sessionSummary.value = "$label · $punchCount отметок"
                _chipReady.value = true
                _status.value = if (snapshotError == null) {
                    "$label · ${dump.punches.size} отметок. Чип на связи."
                } else {
                    "$label · в очереди, снимок в Загрузки не записан: $snapshotError"
                }
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось прочитать чип"
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMarks() {
        val order = blockOrder
        val end = pointerEnd
        val count = chipBlockCount
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                io.clearMarks(order, end, count)
                pointerEnd = 5
                punchCount = 0
                _sessionSummary.value = "${sessionLabel()} · 0 отметок"
                _status.value = "Отметки очищены."
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось очистить отметки"
            } finally {
                _busy.value = false
            }
        }
    }

    fun writeLogicalId() {
        val id = _numberText.value.toIntOrNull()
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) return
        if (id == null || id !in 1..MAX_LOGICAL_ID) {
            _status.value = "Номер от 1 до $MAX_LOGICAL_ID"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                io.writeLogicalId(order, id)
                _numberText.value = id.toString()
                _sessionSummary.value = "№ $id · $punchCount отметок"
                _status.value = "Номер $id записан."
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось записать номер"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun sessionLabel(): String {
        val id = _numberText.value.toIntOrNull()
        return if (id != null) "№ $id" else "без номера"
    }

    private fun forgetChip() {
        _chipReady.value = false
        blockOrder = null
        pointerEnd = null
        _sessionSummary.value = null
    }

    fun clearQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshotError = repository.clear()
            _status.value = if (snapshotError == null) {
                "Очередь очищена. Приложите чип"
            } else {
                "Очередь очищена, снимок в Загрузках не обновлён: $snapshotError"
            }
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
