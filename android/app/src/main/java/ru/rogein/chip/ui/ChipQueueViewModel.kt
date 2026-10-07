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
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.Punch
import ru.rogein.chip.parse.SfrChipParser
import ru.rogein.chip.parse.TestCoursePlan
import ru.rogein.chip.queue.ChipQueueRepository
import ru.rogein.chip.queue.EnqueueResult
import ru.rogein.chip.startlist.StartPerson
import java.time.LocalTime

enum class ChipMode { Issue, Finish, Tests }

interface ChipIo {
    fun clearMarks(order: BlockOrder, pointerEnd: Int?, blockCount: Int)
    fun writeLogicalId(order: BlockOrder, id: Int)
    fun issue(order: BlockOrder, id: Int, pointerEnd: Int?, blockCount: Int)
    fun writeTestCourse(order: BlockOrder, id: Int, punches: List<Punch>, pointerEnd: Int?, blockCount: Int)
}

interface ChipFeedback {
    fun signalSuccess()
    fun signalError()
}

class ChipQueueViewModel(
    private val repository: ChipQueueRepository,
) : ViewModel() {
    val items = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val people = repository.startList.people.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val courses = repository.startList.courses.stateIn(
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

    private val _mode = MutableStateFlow(ChipMode.Issue)
    val mode = _mode.asStateFlow()

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _selectedId = MutableStateFlow<Int?>(null)
    val selectedId = _selectedId.asStateFlow()

    private val _addingNew = MutableStateFlow(false)
    val addingNew = _addingNew.asStateFlow()

    private val _newNumber = MutableStateFlow("")
    val newNumber = _newNumber.asStateFlow()

    private val _newName = MutableStateFlow("")
    val newName = _newName.asStateFlow()

    private val _newCourse = MutableStateFlow("")
    val newCourse = _newCourse.asStateFlow()

    private val _extraCourse = MutableStateFlow("")
    val extraCourse = _extraCourse.asStateFlow()

    private val _testCourse = MutableStateFlow("")
    val testCourse = _testCourse.asStateFlow()

    private val _sessionSummary = MutableStateFlow<String?>(null)
    val sessionSummary = _sessionSummary.asStateFlow()

    @Volatile
    private var chipIo: ChipIo? = null

    @Volatile
    private var feedback: ChipFeedback? = null

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

    fun attachFeedback(next: ChipFeedback) {
        feedback = next
    }

    fun setMode(mode: ChipMode) {
        _mode.value = mode
        _addingNew.value = false
        if (mode == ChipMode.Tests && _testCourse.value.isBlank()) {
            val person = _selectedId.value?.let { id -> people.value.find { it.id == id } }
            _testCourse.value = person?.currentCourse
                ?.takeIf { it.isNotBlank() }
                ?: person?.courses?.firstOrNull().orEmpty()
        }
        promptStatus()
    }

    fun onQuery(value: String) {
        _query.value = value
        _addingNew.value = false
    }

    fun selectPerson(id: Int) {
        if (_selectedId.value == id) {
            clearSelection()
            return
        }
        _selectedId.value = id
        _query.value = ""
        _addingNew.value = false
        val person = people.value.find { it.id == id }
        _testCourse.value = person?.currentCourse
            ?.takeIf { it.isNotBlank() }
            ?: person?.courses?.firstOrNull().orEmpty()
        promptStatus()
    }

    fun clearSelection() {
        _selectedId.value = null
        _addingNew.value = false
        _query.value = ""
        _extraCourse.value = ""
        _testCourse.value = ""
        promptStatus()
    }

    fun startNewPerson() {
        _addingNew.value = true
        _selectedId.value = null
        _newNumber.value = _query.value.filter { it.isDigit() }.take(5)
        _newName.value = if (_query.value.any { it.isLetter() }) _query.value else ""
        _newCourse.value = courses.value.firstOrNull()?.name.orEmpty()
        _status.value = "Заполните номер, ФИО и дистанцию, затем приложите чип"
    }

    fun onNewNumber(value: String) {
        _newNumber.value = value.filter { it.isDigit() }.take(5)
    }

    fun onNewName(value: String) {
        _newName.value = value
    }

    fun onNewCourse(value: String) {
        _newCourse.value = value
    }

    fun onExtraCourse(value: String) {
        _extraCourse.value = value
    }

    fun onTestCourse(value: String) {
        _testCourse.value = value
    }

    fun writeCorrectCourse() {
        writeTestCourse(correct = true)
    }

    fun writeWrongCourse() {
        writeTestCourse(correct = false)
    }

    private fun writeTestCourse(correct: Boolean) {
        val person = _selectedId.value?.let { id -> people.value.find { it.id == id } }
        if (person == null) {
            _status.value = "Выберите участника из протокола."
            return
        }
        val courseName = _testCourse.value.trim().ifBlank { person.currentCourse }
        if (courseName.isEmpty()) {
            _status.value = "Выберите дистанцию."
            return
        }
        val course = courses.value.find { it.name.equals(courseName, ignoreCase = true) }
        val controls = course?.controls.orEmpty()
        val inner = controls.filter { it != CP_START && it != CP_FINISH }
        if (inner.isEmpty()) {
            _status.value = "У дистанции $courseName нет порядка КП. Импортируйте CSV на компьютере и отправьте протокол."
            return
        }
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) {
            _status.value = "Приложите чип и не убирайте его."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val cps = if (correct) {
                    TestCoursePlan.fullSequence(controls)
                } else {
                    TestCoursePlan.wrongSequence(controls)
                }
                val now = LocalTime.now()
                val punches = TestCoursePlan.punches(cps, now.hour, now.minute, now.second)
                io.writeTestCourse(order, person.id, punches, pointerEnd, chipBlockCount)
                pointerEnd = 4 + punches.size
                punchCount = punches.size
                _sessionSummary.value = "№ ${person.id} · ${punches.size} отметок"
                val kind = if (correct) "правильное" else "ошибочное"
                _status.value = "${person.name} · $courseName · записано $kind прохождение (${punches.size} КП)."
                signalSuccess()
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось записать прохождение"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun promptStatus() {
        _status.value = when (_mode.value) {
            ChipMode.Issue -> when {
                _chipReady.value && (_selectedId.value != null || _addingNew.value) ->
                    "Чип на месте. Не убирайте — номер запишется."
                _selectedId.value != null || _addingNew.value ->
                    "Приложите чип — номер запишется, отметки очистятся"
                else -> "Выберите участника или приложите чип"
            }
            ChipMode.Finish -> "Приложите чип — отметки сохранятся"
            ChipMode.Tests -> when {
                _selectedId.value == null -> "Выберите участника из протокола"
                _chipReady.value -> "Чип на месте. Нажмите «Правильное» или «Ошибочное»."
                else -> "Приложите чип, затем запишите прохождение"
            }
        }
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
        signalError()
    }

    fun onChipGone() {
        _chipReady.value = false
        blockOrder = null
        pointerEnd = null
        _sessionSummary.value = null
        if (!_busy.value) {
            promptStatus()
        }
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
                blockOrder = dump.blockOrder
                pointerEnd = dump.pointerEnd
                punchCount = dump.punches.size
                chipBlockCount = dump.blockCount
                _chipReady.value = true
                val label = dump.logicalId?.let { "№ $it" } ?: "без номера"
                _sessionSummary.value = "$label · $punchCount отметок"
                when (_mode.value) {
                    ChipMode.Issue -> handleIssueRead(dump.logicalId)
                    ChipMode.Finish -> handleFinishRead(dump, uid, blocks)
                    ChipMode.Tests -> {
                        _status.value = if (_selectedId.value != null) {
                            "Чип на месте. Нажмите «Правильное» или «Ошибочное»."
                        } else {
                            "Чип на месте. Выберите участника, затем запишите прохождение."
                        }
                    }
                }
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось прочитать чип"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun handleIssueRead(logicalId: Int?) {
        val draft = issueTarget(logicalId)
        if (draft == null) {
            _status.value = if (logicalId != null) {
                "№ $logicalId нет в протоколе. Выберите участника или добавьте нового."
            } else {
                "Выберите участника из списка, затем приложите чип."
            }
            return
        }
        issueToChip(draft.first, draft.second, draft.third)
    }

    private suspend fun handleFinishRead(
        dump: ru.rogein.chip.parse.ChipDump,
        uid: String,
        blocks: List<ByteArray>,
    ) {
        val person = dump.logicalId?.let { repository.startList.person(it) }
        val course = person?.currentCourse.orEmpty()
        val who = person?.let { "${it.name} · ${it.currentCourse.ifBlank { "без дистанции" }}" }
            ?: dump.logicalId?.let { "№ $it" }
            ?: "без номера"
        when (val result = repository.enqueue(dump, rawBlocksToHex(blocks), person?.name.orEmpty(), course)) {
            is EnqueueResult.Duplicate -> {
                val dist = result.courseName.ifBlank { "без дистанции" }
                _status.value = "$who уже считан ($dist). Повтор не записан."
                signalError()
            }
            is EnqueueResult.Saved -> {
                _status.value = if (result.snapshotError == null) {
                    "$who · ${dump.punches.size} отметок сохранено."
                } else {
                    "$who · в очереди, снимок в Загрузки не записан: ${result.snapshotError}"
                }
                signalSuccess()
            }
        }
    }

    private fun issueTarget(logicalId: Int?): Triple<Int, String, String>? {
        if (_addingNew.value) {
            val id = _newNumber.value.toIntOrNull()
            val name = _newName.value.trim()
            val course = _newCourse.value.trim()
            if (id == null || id !in 1..MAX_LOGICAL_ID || name.isEmpty() || course.isEmpty()) {
                return null
            }
            return Triple(id, name, course)
        }
        val selected = _selectedId.value?.let { id -> people.value.find { it.id == id } }
        if (selected != null) {
            return Triple(selected.id, selected.name, selected.currentCourse)
        }
        if (logicalId != null) {
            val known = people.value.find { it.id == logicalId }
            if (known != null) {
                _selectedId.value = known.id
                return Triple(known.id, known.name, known.currentCourse)
            }
        }
        return null
    }

    private suspend fun issueToChip(id: Int, name: String, course: String) {
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) {
            _status.value = "Приложите чип и не убирайте его."
            return
        }
        try {
            io.issue(order, id, pointerEnd, chipBlockCount)
            repository.startList.upsertLocal(id, name, course)
            repository.startList.markIssued(id)
            _selectedId.value = null
            _addingNew.value = false
            _query.value = ""
            _extraCourse.value = ""
            pointerEnd = 5
            punchCount = 0
            _sessionSummary.value = "№ $id · 0 отметок"
            _status.value = "$name · $course. Чип выдан."
            signalSuccess()
        } catch (e: Exception) {
            forgetChip()
            _status.value = e.message ?: "Не удалось выдать чип"
            signalError()
        }
    }

    fun setCurrentCourse(course: String) {
        val id = _selectedId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.startList.setCurrentCourse(id, course)
            _status.value = "Текущая дистанция: $course. Приложите чип."
        }
    }

    fun addSelectedCourse() {
        val id = _selectedId.value ?: return
        val course = _extraCourse.value.trim()
        if (course.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val person = repository.startList.person(id) ?: return@launch
            repository.startList.upsertLocal(id, person.name, course, person.courses)
            _extraCourse.value = ""
            _status.value = "Добавлена $course. Приложите чип."
        }
    }

    fun reloadStartList() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = repository.startList.reloadFromUsb(quiet = false)
                ?: "Файл протокола не найден. Отправьте его с компьютера по USB."
        }
    }

    fun pollStartList() {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val note = repository.startList.reloadFromUsb(quiet = true) ?: return@launch
            if (!_busy.value) {
                _status.value = note
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
                signalSuccess()
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось очистить отметки"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    fun writeLogicalId() {
        val id = selectedIssueId() ?: return
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                io.writeLogicalId(order, id)
                _sessionSummary.value = "№ $id · $punchCount отметок"
                _status.value = "Номер $id записан."
                signalSuccess()
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось записать номер"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun selectedIssueId(): Int? {
        if (_addingNew.value) return _newNumber.value.toIntOrNull()
        return _selectedId.value
    }

    private fun sessionLabel(): String {
        val id = selectedIssueId() ?: return "без номера"
        return "№ $id"
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

    fun resetForNewStart() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshotError = repository.resetForNewStart()
            lastUid = null
            lastAt = 0
            _selectedId.value = null
            _addingNew.value = false
            _query.value = ""
            _extraCourse.value = ""
            _testCourse.value = ""
            _sessionSummary.value = null
            _status.value = if (snapshotError == null) {
                "Стартовый протокол, дистанции и очередь удалены. Отправьте новый протокол с компьютера."
            } else {
                "Данные старта удалены, снимок в Загрузках не обновлён: $snapshotError"
            }
        }
    }

    private fun signalSuccess() {
        feedback?.signalSuccess()
    }

    private fun signalError() {
        feedback?.signalError()
    }

    fun setNfcUnavailable() {
        _status.value = "NFC недоступен на этом устройстве"
    }

    fun suggestions(): List<StartPerson> {
        val q = _query.value.trim().lowercase().replace('ё', 'е')
        return people.value.filter { person ->
            if (q.isEmpty()) return@filter true
            person.name.lowercase().replace('ё', 'е').contains(q) ||
                person.id.toString().contains(q)
        }
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
