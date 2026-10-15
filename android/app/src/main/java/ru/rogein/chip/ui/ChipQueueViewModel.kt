package ru.rogein.chip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.rogein.chip.event.buildEventSlug
import ru.rogein.chip.parse.BLOCK_COUNT
import ru.rogein.chip.parse.BlockOrder
import ru.rogein.chip.parse.CP_CLEAR
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.Punch
import ru.rogein.chip.parse.StationCommand
import ru.rogein.chip.parse.SfrChipParser
import ru.rogein.chip.parse.TestCoursePlan
import ru.rogein.chip.parse.courseKey
import ru.rogein.chip.queue.ChipQueueRepository
import ru.rogein.chip.queue.CorrectionEntity
import ru.rogein.chip.queue.EnqueueResult
import ru.rogein.chip.queue.EventSettings
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.ExclusionEntity
import ru.rogein.chip.queue.QueuedChipEntity
import ru.rogein.chip.queue.ScoredResult
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import java.time.LocalTime

enum class ChipMode { Issue, Finish, Station, Tests }

enum class StationProgram(val command: StationCommand, val title: String) {
    Control(StationCommand.Control, "КП"),
    Start(StationCommand.Start, "старт"),
    Finish(StationCommand.Finish, "финиш"),
    Clear(StationCommand.Clear, "очистка"),
    Active(StationCommand.Active, "активный режим"),
    Passive(StationCommand.Passive, "пассивный режим"),
    Battery(StationCommand.Battery, "проверка батареи"),
}

enum class AppSection { Chip, Protocol, Results, More }

enum class MorePage { Menu, Removed, Exclusions, Corrections, Publish, Settings }

enum class CsvImportKind { Protocol, Courses }

data class FinishReplacePrompt(
    val logicalId: Int?,
    val courseName: String,
    val dump: ChipDump,
    val rawHex: String,
    val personName: String,
)

data class IssueOverwritePrompt(
    val id: Int,
    val name: String,
    val issuedCourse: String,
    val newCourse: String,
)

data class StationWritePrompt(
    val program: StationProgram,
    val controlNumber: Int?,
    val order: BlockOrder,
    val oldPointerEnd: Int?,
    val blockCount: Int,
)

interface CsvPicker {
    fun pick(kind: CsvImportKind)
}

interface ChipIo {
    fun clearMarks(order: BlockOrder, pointerEnd: Int?, blockCount: Int)
    fun writeLogicalId(order: BlockOrder, id: Int)
    fun issue(order: BlockOrder, id: Int, pointerEnd: Int?, blockCount: Int)
    fun writeTestCourse(order: BlockOrder, id: Int, punches: List<Punch>, pointerEnd: Int?, blockCount: Int)
    fun writeStationProgram(
        order: BlockOrder,
        command: StationCommand,
        controlNumber: Int?,
        oldPointerEnd: Int?,
        blockCount: Int,
    )
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
    val settings = repository.event.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EventSettings(),
    )
    val corrections = repository.event.corrections.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val exclusions = repository.event.exclusions.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _section = MutableStateFlow(AppSection.Chip)
    val section = _section.asStateFlow()

    private val _morePage = MutableStateFlow(MorePage.Menu)
    val morePage = _morePage.asStateFlow()

    private val _overwritePrompt = MutableStateFlow(false)
    val overwritePrompt = _overwritePrompt.asStateFlow()

    private val _finishReplacePrompt = MutableStateFlow<FinishReplacePrompt?>(null)
    val finishReplacePrompt = _finishReplacePrompt.asStateFlow()

    private val _issueOverwritePrompt = MutableStateFlow<IssueOverwritePrompt?>(null)
    val issueOverwritePrompt = _issueOverwritePrompt.asStateFlow()

    private val _stationWritePrompt = MutableStateFlow<StationWritePrompt?>(null)
    val stationWritePrompt = _stationWritePrompt.asStateFlow()

    private val _stationProgram = MutableStateFlow(StationProgram.Control)
    val stationProgram = _stationProgram.asStateFlow()

    private val _stationControlNumber = MutableStateFlow("")
    val stationControlNumber = _stationControlNumber.asStateFlow()

    private val _status = MutableStateFlow("Приложите чип к телефону")
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _chipReady = MutableStateFlow(false)
    val chipReady = _chipReady.asStateFlow()

    private val _chipLogicalId = MutableStateFlow<Int?>(null)
    val chipLogicalId = _chipLogicalId.asStateFlow()

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
    private var pendingCsvReset: Boolean = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(4_000)
                runCatching { repository.republishSnapshot() }
                runCatching { repository.flushLiveIfPending() }
            }
        }
    }

    fun attach(io: ChipIo) {
        chipIo = io
    }

    fun attachFeedback(next: ChipFeedback) {
        feedback = next
    }

    fun setSection(next: AppSection) {
        _section.value = next
        if (next != AppSection.More) {
            _morePage.value = MorePage.Menu
        }
    }

    fun setMorePage(page: MorePage) {
        _section.value = AppSection.More
        _morePage.value = page
    }

    fun prepareCsvImport(reset: Boolean) {
        pendingCsvReset = reset
    }

    fun loadUsbProtocol(replace: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                _status.value = repository.startList.loadProtocolFromUsb(replace)
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось загрузить протокол"
            } finally {
                _busy.value = false
            }
        }
    }

    fun loadUsbCourses(replace: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                _status.value = repository.startList.loadCoursesFromUsb(replace)
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось загрузить дистанции"
            } finally {
                _busy.value = false
            }
        }
    }

    fun importCsvText(kind: CsvImportKind, text: String?) {
        if (text.isNullOrBlank()) {
            _status.value = "Не удалось прочитать файл"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                _status.value = when (kind) {
                    CsvImportKind.Protocol -> repository.startList.importProtocolCsv(text, pendingCsvReset)
                    CsvImportKind.Courses -> repository.startList.importCoursesCsv(text, pendingCsvReset)
                }
            } catch (e: Exception) {
                _status.value = e.message ?: "Ошибка CSV"
            } finally {
                _busy.value = false
            }
        }
    }

    fun saveManualPerson() {
        val id = _newNumber.value.toIntOrNull()
        val name = _newName.value.trim()
        val course = _newCourse.value.trim()
        if (id == null || id !in 1..MAX_LOGICAL_ID || name.isEmpty()) {
            _status.value = "Укажите номер (1…$MAX_LOGICAL_ID) и ФИО"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.startList.upsertLocal(id, name, course)
            _addingNew.value = false
            _selectedId.value = id
            _status.value = if (course.isEmpty()) {
                "Участник № $id сохранён. Внимание: дистанция не указана."
            } else {
                "Участник № $id сохранён в протоколе"
            }
        }
    }

    fun deletePerson(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.startList.deletePerson(id)
            if (_selectedId.value == id) {
                _selectedId.value = null
            }
            _status.value = "Участник № $id удалён из протокола"
        }
    }

    fun saveCourse(name: String, controls: List<Int>, previousName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.startList.saveCourse(name, controls, previousName)
                _status.value = "Дистанция «${name.trim()}» сохранена."
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось сохранить дистанцию"
            }
        }
    }

    fun deleteCourse(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.startList.deleteCourse(name)
            _status.value = "Дистанция «$name» удалена."
        }
    }

    fun addRemovedCp(cp: Int) {
        if (cp <= 0) {
            _status.value = "Выберите КП, который есть на дистанции"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.addRemovedCp(cp)
            repository.requestLiveSend()
            _status.value = "КП $cp снят с дистанций для всех участников"
        }
    }

    fun addMissingCp(logicalId: Int, courseName: String, cp: Int) {
        if (cp <= 0) {
            _status.value = "Выберите КП дистанции"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.addMissingCp(logicalId, courseName, cp)
            repository.requestLiveSend()
            _status.value = "№ $logicalId: добавлен КП $cp, результат пересчитан"
        }
    }

    fun assignCourse(logicalId: Int, fromCourse: String, toCourse: String) {
        val next = toCourse.trim()
        if (next.isEmpty()) {
            _status.value = "Выберите дистанцию"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.assignCourse(logicalId, fromCourse, next)
            repository.requestLiveSend()
            _status.value = "№ $logicalId: дистанция «$next», результат пересчитан"
        }
    }

    fun deleteCorrection(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.deleteCorrection(id)
            repository.requestLiveSend()
            _status.value = "Корректировка отменена, результат пересчитан"
        }
    }

    fun addExclusion(courseName: String, fromCp: Int, toCp: Int, maxMinutes: Int?) {
        if (courseName.isBlank() || fromCp <= 0 || toCp <= 0) {
            _status.value = "Выберите дистанцию и перегон"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.event.addExclusion(
                    courseName,
                    fromCp,
                    toCp,
                    maxMinutes?.takeIf { it > 0 }?.times(60),
                )
                repository.requestLiveSend()
                _status.value = "Перегон $fromCp → $toCp исключён, результат пересчитан"
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось исключить перегон"
            }
        }
    }

    fun deleteExclusion(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.deleteExclusion(id)
            repository.requestLiveSend()
            _status.value = "Исключение перегона удалено, результат пересчитан"
        }
    }

    fun saveSettings(next: EventSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.event.saveSettings(next)
            _status.value = "Настройки сохранены"
        }
    }

    fun saveAndGenerateSlug(next: EventSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            val slug = buildEventSlug(next.competitionDate, next.title)
            if (slug == null) {
                repository.event.saveSettings(next)
                _status.value = "Для slug нужны название старта и дата соревнования"
                return@launch
            }
            repository.event.saveSettings(next.copy(slug = slug))
            _status.value = "Slug: $slug"
        }
    }

    fun testSite(baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val base = repository.event.testSite(baseUrl)
                _status.value = "Сайт отвечает: $base"
            } catch (e: Exception) {
                _status.value = "Нет связи с сайтом: ${e.message ?: "ошибка"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun requestPublish(next: EventSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                repository.event.saveSettings(next)
                if (next.slug.isBlank() || next.title.isBlank()) {
                    _status.value = "Укажите slug и название старта"
                    return@launch
                }
                if (repository.event.eventExists(next.apiBaseUrl, next.slug)) {
                    _overwritePrompt.value = true
                    return@launch
                }
                publishNow(next)
            } catch (e: Exception) {
                _status.value = "Ошибка публикации: ${e.message ?: "ошибка"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun confirmOverwrite() {
        _overwritePrompt.value = false
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                publishNow(repository.event.settingsNow())
            } catch (e: Exception) {
                _status.value = "Ошибка публикации: ${e.message ?: "ошибка"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelOverwrite() {
        _overwritePrompt.value = false
    }

    fun sendLiveNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.flushLiveNow()
                val note = repository.event.settingsNow().liveNote
                if (note.isNotBlank()) _status.value = note
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось отправить online"
            }
        }
    }

    private suspend fun publishNow(current: EventSettings) {
        _status.value = repository.event.publish(
            current,
            people.value,
            courses.value,
            items.value,
            corrections.value,
            exclusions.value,
        )
    }

    fun scoredResults(
        items: List<QueuedChipEntity>,
        people: List<StartPerson>,
        courses: List<StartCourse>,
        settings: EventSettings,
        corrections: List<CorrectionEntity>,
        exclusions: List<ExclusionEntity>,
    ): List<ScoredResult> = EventStore.score(items, people, courses, settings, corrections, exclusions)

    fun setMode(mode: ChipMode) {
        _mode.value = mode
        _addingNew.value = false
        if (mode != ChipMode.Station) {
            _stationWritePrompt.value = null
        }
        if (mode == ChipMode.Tests) {
            applyTestCourseFromChip()
        }
        promptStatus()
    }

    fun setStationProgram(program: StationProgram) {
        _stationProgram.value = program
        _stationWritePrompt.value = null
        promptStatus()
    }

    fun onStationControlNumber(value: String) {
        _stationControlNumber.value = value.filter { it.isDigit() }.take(3)
        _stationWritePrompt.value = null
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
        _newNumber.value = person?.id?.toString().orEmpty()
        _newName.value = person?.name.orEmpty()
        _newCourse.value = person?.currentCourse.orEmpty()
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
        if (
            _section.value == AppSection.Chip &&
            _mode.value == ChipMode.Issue &&
            _addingNew.value &&
            _chipReady.value &&
            value.isNotBlank()
        ) {
            val id = _newNumber.value.toIntOrNull()
            val name = _newName.value.trim()
            if (id != null && id in 1..MAX_LOGICAL_ID && name.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    issueToChip(id, name, value)
                }
            }
        }
    }

    fun onExtraCourse(value: String) {
        _extraCourse.value = value
    }

    fun onTestCourse(value: String) {
        _testCourse.value = value
        if (_mode.value == ChipMode.Tests) {
            promptStatus()
        }
    }

    fun writeCorrectCourse() {
        writeTestCourse(correct = true)
    }

    fun writeWrongCourse() {
        writeTestCourse(correct = false)
    }

    private fun writeTestCourse(correct: Boolean) {
        val logicalId = _chipLogicalId.value
        if (!_chipReady.value) {
            _status.value = "Приложите чип — участник определится по номеру из протокола."
            return
        }
        if (logicalId == null) {
            _status.value = "На чипе нет номера. Сначала выдайте чип на вкладке «Выдача»."
            return
        }
        val person = personFromChip()
        if (person == null) {
            _status.value = "№ $logicalId нет в стартовом протоколе."
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
            _status.value = "У дистанции $courseName нет порядка КП. Загрузите CSV дистанций на вкладке «Протокол»."
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
                io.writeTestCourse(order, logicalId, punches, pointerEnd, chipBlockCount)
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

    private fun issueCourseMissing(): Boolean {
        if (_addingNew.value) return _newCourse.value.isBlank()
        val selected = _selectedId.value?.let { id -> people.value.find { it.id == id } }
        return selected != null && selected.currentCourse.isBlank()
    }

    private fun promptStatus() {
        _status.value = when (_mode.value) {
            ChipMode.Issue -> when {
                issueCourseMissing() ->
                    if (_chipReady.value) {
                        "Укажите дистанцию — без неё номер не запишется."
                    } else {
                        "Укажите дистанцию, затем приложите чип"
                    }
                _chipReady.value && (_selectedId.value != null || _addingNew.value) ->
                    "Чип на месте. Не убирайте — номер запишется."
                _selectedId.value != null || _addingNew.value ->
                    "Приложите чип — номер запишется, отметки очистятся"
                else -> "Выберите участника или приложите чип"
            }
            ChipMode.Finish -> "Приложите чип — отметки сохранятся"
            ChipMode.Station -> stationPrompt()
            ChipMode.Tests -> testsPrompt()
        }
    }

    private fun stationPrompt(): String {
        val program = _stationProgram.value
        if (program == StationProgram.Control && parsedStationControlNumber() == null) {
            return "Укажите номер КП (1…255, кроме 240, 241 и 243), затем приложите чип."
        }
        val label = stationProgramLabel(program, parsedStationControlNumber())
        return "Станция «$label». Приложите чип — запись только после подтверждения."
    }

    private fun parsedStationControlNumber(): Int? {
        val n = _stationControlNumber.value.toIntOrNull() ?: return null
        return n.takeIf { it in 1..255 && it != CP_START && it != CP_FINISH && it != CP_CLEAR }
    }

    private fun stationProgramLabel(program: StationProgram, controlNumber: Int?): String =
        if (program == StationProgram.Control) {
            controlNumber?.let { "КП $it" } ?: "КП"
        } else {
            program.title
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
        _chipLogicalId.value = null
        _status.value = message
        signalError()
    }

    fun onChipGone() {
        _chipReady.value = false
        _chipLogicalId.value = null
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

    fun onChipBlocks(uid: String, blocks: List<ByteArray>, declaredBlockCount: Int? = null) {
        val now = System.currentTimeMillis()
        if (uid == lastUid && now - lastAt < 2_000 && _mode.value != ChipMode.Station) {
            _busy.value = false
            return
        }
        lastUid = uid
        lastAt = now
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                if (_mode.value == ChipMode.Station) {
                    handleStationRead(blocks, declaredBlockCount)
                    return@launch
                }
                val dump = SfrChipParser.parse(uid, blocks, declaredBlockCount)
                blockOrder = dump.blockOrder
                pointerEnd = dump.pointerEnd
                punchCount = dump.punches.size
                chipBlockCount = dump.blockCount
                _chipReady.value = true
                _chipLogicalId.value = dump.logicalId
                val label = dump.logicalId?.let { "№ $it" } ?: "без номера"
                _sessionSummary.value = "$label · $punchCount отметок"
                when (_mode.value) {
                    ChipMode.Issue -> handleIssueRead(dump.logicalId)
                    ChipMode.Finish -> handleFinishRead(dump, uid, blocks)
                    ChipMode.Station -> Unit
                    ChipMode.Tests -> handleTestsRead()
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
        if (draft.third.isBlank()) {
            if (!_addingNew.value) {
                _selectedId.value = draft.first
                _newNumber.value = draft.first.toString()
                _newName.value = draft.second
                _newCourse.value = ""
            }
            _status.value = "Укажите дистанцию для № ${draft.first}, затем приложите чип."
            signalError()
            return
        }
        issueToChip(draft.first, draft.second, draft.third)
    }

    fun confirmIssueOverwrite() {
        val pending = _issueOverwritePrompt.value ?: return
        _issueOverwritePrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            issueToChip(pending.id, pending.name, pending.newCourse, replace = true)
        }
    }

    fun cancelIssueOverwrite() {
        val pending = _issueOverwritePrompt.value
        _issueOverwritePrompt.value = null
        val course = pending?.issuedCourse.orEmpty()
        _status.value = if (course.isNotBlank()) {
            "Чип не перезаписан. Готов для дистанции $course."
        } else {
            "Чип не перезаписан."
        }
    }

    private suspend fun handleFinishRead(
        dump: ChipDump,
        uid: String,
        blocks: List<ByteArray>,
    ) {
        val logicalId = dump.logicalId
        if (logicalId == null) {
            _status.value = finishChipError(blocks)
            signalError()
            return
        }
        val person = repository.startList.person(logicalId)
        val course = person?.currentCourse.orEmpty()
        saveFinishDump(dump, rawBlocksToHex(blocks), person?.name.orEmpty(), course, replace = false)
    }

    fun confirmFinishReplace() {
        val pending = _finishReplacePrompt.value ?: return
        _finishReplacePrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            saveFinishDump(pending.dump, pending.rawHex, pending.personName, pending.courseName, replace = true)
        }
    }

    fun cancelFinishReplace() {
        val pending = _finishReplacePrompt.value
        _finishReplacePrompt.value = null
        val id = pending?.logicalId?.toString() ?: "без номера"
        _status.value = "№ $id не перезаписан."
    }

    private suspend fun saveFinishDump(
        dump: ChipDump,
        rawHex: String,
        personName: String,
        course: String,
        replace: Boolean,
    ) {
        val who = personName.ifBlank { dump.logicalId?.let { "№ $it" } ?: "без номера" }
        val dist = course.ifBlank { "без дистанции" }
        when (val result = repository.enqueue(dump, rawHex, personName, course, replace)) {
            is EnqueueResult.Duplicate -> {
                _finishReplacePrompt.value = FinishReplacePrompt(
                    logicalId = result.logicalId,
                    courseName = result.courseName,
                    dump = dump,
                    rawHex = rawHex,
                    personName = personName,
                )
                _status.value = "Данные с чипа ${result.logicalId ?: "—"} уже есть ($dist)."
            }
            is EnqueueResult.Saved -> {
                repository.requestLiveSend()
                _status.value = if (result.snapshotError == null) {
                    "$who · ${dump.punches.size} отметок сохранено, chip-queue.json записан."
                } else {
                    "$who · в очереди, chip-queue.json не записан: ${result.snapshotError}"
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
            if (id == null || id !in 1..MAX_LOGICAL_ID || name.isEmpty()) {
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

    private fun unfinishedIssuedCourse(id: Int): String? {
        val person = people.value.find { it.id == id } ?: return null
        if (!person.issued) return null
        val issued = person.issuedCourse.ifBlank { person.currentCourse }.trim()
        if (issued.isBlank()) return null
        val finished = items.value.any { item ->
            item.logicalId == id &&
                courseKey(item.courseName.ifBlank { issued }) == courseKey(issued)
        }
        return if (finished) null else issued
    }

    private suspend fun issueToChip(id: Int, name: String, course: String, replace: Boolean = false) {
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) {
            _status.value = "Приложите чип и не убирайте его."
            return
        }
        if (!replace) {
            val issued = unfinishedIssuedCourse(id)
            if (issued != null) {
                _issueOverwritePrompt.value = IssueOverwritePrompt(
                    id = id,
                    name = name,
                    issuedCourse = issued,
                    newCourse = course,
                )
                _status.value = "Чип уже готов для дистанции $issued."
                return
            }
        }
        try {
            io.issue(order, id, pointerEnd, chipBlockCount)
            repository.startList.upsertLocal(id, name, course)
            repository.startList.markIssued(id, course)
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
        if (course.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.startList.setCurrentCourse(id, course)
            val person = repository.startList.person(id)
            if (_mode.value == ChipMode.Issue && _chipReady.value && person != null) {
                issueToChip(person.id, person.name, course)
            } else {
                _status.value = "Текущая дистанция: $course. Приложите чип."
            }
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

    fun dismissStatus() {
        promptStatus()
    }

    fun reloadStartList(force: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.republishSnapshot() }
            val note = repository.startList.reloadFromUsb(quiet = !force)
            if (force) {
                _status.value = note ?: "Файл протокола не найден"
            } else if (note != null && !_busy.value) {
                _status.value = note
            }
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

    private fun personFromChip(): StartPerson? {
        val id = _chipLogicalId.value ?: return null
        return people.value.find { it.id == id }
    }

    private fun applyTestCourseFromChip() {
        val person = personFromChip()
        _testCourse.value = person?.currentCourse
            ?.takeIf { it.isNotBlank() }
            ?: person?.courses?.firstOrNull().orEmpty()
    }

    private fun testsPrompt(): String {
        if (!_chipReady.value) {
            return "Приложите чип — участник определится по номеру из протокола"
        }
        val id = _chipLogicalId.value
            ?: return "На чипе нет номера. Сначала выдайте чип на вкладке «Выдача»."
        val person = personFromChip()
            ?: return "№ $id нет в стартовом протоколе."
        val course = _testCourse.value.trim().ifBlank { person.currentCourse }
        return if (course.isBlank()) {
            "№ ${person.id} · ${person.name}. Выберите дистанцию."
        } else {
            "№ ${person.id} · ${person.name}. Нажмите «Правильное» или «Ошибочное»."
        }
    }

    private fun finishChipError(blocks: List<ByteArray>): String {
        val what = SfrChipParser.stationRole(blocks)?.let {
            "Это ${SfrChipParser.stationCommandLabel(it.command, it.cp)}."
        } ?: "На чипе нет логического номера участника. Сначала выдайте его на вкладке «Выдача»."
        return "Этот чип нельзя принять на финише. $what"
    }

    private fun handleTestsRead() {
        applyTestCourseFromChip()
        _status.value = testsPrompt()
    }

    private fun handleStationRead(blocks: List<ByteArray>, declaredBlockCount: Int?) {
        if (_mode.value != ChipMode.Station) return
        val count = declaredBlockCount ?: chipBlockCount
        val b3 = blocks.getOrNull(3) ?: ByteArray(4)
        val b4 = blocks.getOrNull(4) ?: ByteArray(4)
        val order = SfrChipParser.wireOrder(b3, b4, count)
        val oldEnd = SfrChipParser.maxPointerCandidate(b4, count)
        blockOrder = order
        pointerEnd = oldEnd
        chipBlockCount = count
        _chipReady.value = true
        _chipLogicalId.value = null
        val program = _stationProgram.value
        val controlNumber = parsedStationControlNumber()
        if (program == StationProgram.Control && controlNumber == null) {
            _status.value = "Укажите номер КП (1…255, кроме 240, 241 и 243)."
            signalError()
            return
        }
        _stationWritePrompt.value = StationWritePrompt(
            program = program,
            controlNumber = controlNumber,
            order = order,
            oldPointerEnd = oldEnd.takeIf { it >= 5 },
            blockCount = count,
        )
        _status.value = "Чип на месте. Подтвердите запись станции «${stationProgramLabel(program, controlNumber)}»."
    }

    fun confirmStationWrite() {
        if (_mode.value != ChipMode.Station) {
            _stationWritePrompt.value = null
            return
        }
        val pending = _stationWritePrompt.value ?: return
        _stationWritePrompt.value = null
        val io = chipIo
        val order = pending.order
        if (!_chipReady.value || io == null) {
            _status.value = "Чип убран. Приложите его снова и подтвердите."
            signalError()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                io.writeStationProgram(
                    order,
                    pending.program.command,
                    pending.controlNumber,
                    pending.oldPointerEnd,
                    pending.blockCount,
                )
                val label = stationProgramLabel(pending.program, pending.controlNumber)
                _status.value = "На чип записана станция «$label». Приложите этот чип к станции."
                signalSuccess()
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось записать программу станции"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelStationWrite() {
        _stationWritePrompt.value = null
        _status.value = "Запись станции отменена. Чип не изменён."
    }

    private fun forgetChip() {
        _chipReady.value = false
        _chipLogicalId.value = null
        blockOrder = null
        pointerEnd = null
        _sessionSummary.value = null
    }

    fun deleteQueued(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshotError = repository.deleteQueued(id)
            _status.value = if (snapshotError == null) {
                "Участник убран из финишного протокола."
            } else {
                "Убран из протокола, снимок в Загрузках не обновлён: $snapshotError"
            }
        }
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
            _chipLogicalId.value = null
            _sessionSummary.value = null
            _status.value = if (snapshotError == null) {
                "Стартовый протокол, дистанции, очередь и корректировки удалены."
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
