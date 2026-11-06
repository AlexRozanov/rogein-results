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
import ru.rogein.chip.parse.ChipCellLog
import ru.rogein.chip.parse.ChipDump
import ru.rogein.chip.parse.ChipMemoryLog
import ru.rogein.chip.parse.CsvExport
import ru.rogein.chip.parse.RogaineResultCsvRow
import ru.rogein.chip.parse.MAX_LOGICAL_ID
import ru.rogein.chip.parse.ROGAINE_REMOVE_POINTS
import ru.rogein.chip.parse.isRogaine
import ru.rogein.chip.parse.Punch
import ru.rogein.chip.parse.StationCommand
import ru.rogein.chip.parse.SfrChipParser
import ru.rogein.chip.parse.StationCheckReport
import ru.rogein.chip.parse.StationCourseCheck
import ru.rogein.chip.parse.TestCoursePlan
import ru.rogein.chip.parse.ReceiptMark
import ru.rogein.chip.parse.ReceiptText
import ru.rogein.chip.parse.SplitReceipt
import ru.rogein.chip.parse.applyAddedCps
import ru.rogein.chip.parse.courseKey
import ru.rogein.chip.parse.displayBirthDate
import ru.rogein.chip.parse.filterCourseCps
import ru.rogein.chip.parse.personGenderChoice
import ru.rogein.chip.parse.storedBirthDate
import ru.rogein.chip.parse.storedPersonGender
import ru.rogein.chip.parse.formatElapsed
import ru.rogein.chip.parse.parseDaySeconds
import ru.rogein.chip.parse.resultStatusLine
import ru.rogein.chip.parse.orientPunchWindow
import ru.rogein.chip.printer.BondedPrinter
import ru.rogein.chip.queue.ChipQueueRepository
import ru.rogein.chip.queue.CorrectionEntity
import ru.rogein.chip.queue.EnqueueResult
import ru.rogein.chip.queue.FinishCsvImportPreview
import ru.rogein.chip.queue.EventSettings
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.ExclusionEntity
import ru.rogein.chip.queue.QueuedChipEntity
import ru.rogein.chip.queue.ScoredResult
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class ChipMode { Issue, Finish, Station, Tests }

enum class StationProgram(val command: StationCommand?, val title: String) {
    Control(StationCommand.Control, "КП"),
    Start(StationCommand.Start, "старт"),
    Finish(StationCommand.Finish, "финиш"),
    Clear(StationCommand.Clear, "очистка"),
    Active(StationCommand.Active, "активный режим"),
    Passive(StationCommand.Passive, "пассивный режим"),
    Battery(StationCommand.Battery, "проверка батареи"),
    Inspect(null, "проверка"),
}

enum class AppSection { Chip, Protocol, Results, More }

enum class MorePage { Menu, Starts, Printer, Removed, Exclusions, Corrections, Publish, Settings }

data class PrintPrompt(
    val summary: String,
    val text: String,
)

enum class CsvImportKind { Protocol, Courses, Finish, EventPack, Formats, AwardGroups, Legends }

data class FinishCsvPrompt(
    val addedCount: Int,
    val conflictCount: Int,
    val conflictLabels: List<String>,
)

data class FinishReplacePrompt(
    val logicalId: Int?,
    val courseName: String,
    val dump: ChipDump,
    val rawHex: String,
    val personName: String,
)

data class FinishMissingPrompt(
    val dump: ChipDump,
    val rawHex: String,
    val personName: String,
    val courseName: String,
    val finishCp: Int,
    val finish: Punch,
)

data class IssueOverwritePrompt(
    val id: Int,
    val name: String,
    val issuedCourse: String,
    val newCourse: String,
)

data class IssueFillPrompt(
    val id: Int,
    val name: String,
    val course: String,
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

interface CsvShare {
    fun share(files: List<Pair<String, String>>)
}

interface ChipIo {
    fun clearMarks(order: BlockOrder, pointerEnd: Int?, blockCount: Int)
    fun writeLogicalId(order: BlockOrder, id: Int)
    fun issue(order: BlockOrder, id: Int, pointerEnd: Int?, blockCount: Int)
    fun writeTestCourse(order: BlockOrder, id: Int, punches: List<Punch>, pointerEnd: Int?, blockCount: Int)
    fun readAllBlocks(): Pair<String, List<ByteArray>>
    fun writeStationProgram(
        order: BlockOrder,
        command: StationCommand,
        controlNumber: Int?,
        oldPointerEnd: Int?,
        blockCount: Int,
        clock: LocalTime,
    )
}

/** Отсчёт до момента, записанного на чип как часы станции. */
data class StationClockCountdown(
    val deadlineEpochMs: Long,
    val label: String,
    val writeFinished: Boolean = false,
)

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
    val savedStarts = repository.observeStarts().stateIn(
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

    private val _finishMissingPrompt = MutableStateFlow<FinishMissingPrompt?>(null)
    val finishMissingPrompt = _finishMissingPrompt.asStateFlow()

    private val _finishCsvPrompt = MutableStateFlow<FinishCsvPrompt?>(null)
    val finishCsvPrompt = _finishCsvPrompt.asStateFlow()
    private var pendingFinishCsv: FinishCsvImportPreview? = null

    private val _issueOverwritePrompt = MutableStateFlow<IssueOverwritePrompt?>(null)
    val issueOverwritePrompt = _issueOverwritePrompt.asStateFlow()

    private val _issueFillPrompt = MutableStateFlow<IssueFillPrompt?>(null)
    val issueFillPrompt = _issueFillPrompt.asStateFlow()

    private val _stationWritePrompt = MutableStateFlow<StationWritePrompt?>(null)
    val stationWritePrompt = _stationWritePrompt.asStateFlow()

    private val _stationProgram = MutableStateFlow(StationProgram.Control)
    val stationProgram = _stationProgram.asStateFlow()

    private val _stationInspect = MutableStateFlow<String?>(null)
    val stationInspect = _stationInspect.asStateFlow()

    private val _stationCountdown = MutableStateFlow<StationClockCountdown?>(null)
    val stationCountdown = _stationCountdown.asStateFlow()

    private val _courseCheckOn = MutableStateFlow(false)
    val courseCheckOn = _courseCheckOn.asStateFlow()

    private val _courseCheckName = MutableStateFlow("")
    val courseCheckName = _courseCheckName.asStateFlow()

    private val _courseCheckReport = MutableStateFlow<StationCheckReport?>(null)
    val courseCheckReport = _courseCheckReport.asStateFlow()

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

    private val _chipStationLabel = MutableStateFlow<String?>(null)
    val chipStationLabel = _chipStationLabel.asStateFlow()

    private val _chipMemoryLog = MutableStateFlow<ChipMemoryLog?>(null)
    val chipMemoryLog = _chipMemoryLog.asStateFlow()

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

    private val _newGender = MutableStateFlow("")
    val newGender = _newGender.asStateFlow()

    private val _newBirth = MutableStateFlow("")
    val newBirth = _newBirth.asStateFlow()

    private val _extraCourse = MutableStateFlow("")
    val extraCourse = _extraCourse.asStateFlow()

    private val _testCourse = MutableStateFlow("")
    val testCourse = _testCourse.asStateFlow()

    private val _sessionSummary = MutableStateFlow<String?>(null)
    val sessionSummary = _sessionSummary.asStateFlow()

    private val _csvExport = MutableStateFlow<List<Pair<String, String>>?>(null)
    val csvExport = _csvExport.asStateFlow()

    private val _printPrompt = MutableStateFlow<PrintPrompt?>(null)
    val printPrompt = _printPrompt.asStateFlow()

    private val _printerName = MutableStateFlow("")
    val printerName = _printerName.asStateFlow()

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
            runCatching { repository.ensureCurrentStart() }
            _printerName.value = repository.printer.selectedName()
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
        val prev = _section.value
        _section.value = next
        if (next == AppSection.Protocol || prev == AppSection.Protocol) {
            _selectedId.value = null
            _addingNew.value = false
        }
        if (next != AppSection.Protocol) {
            _courseCheckOn.value = false
        }
        if (next != AppSection.Chip) {
            _stationWritePrompt.value = null
        }
        if (next != AppSection.More) {
            _morePage.value = MorePage.Menu
        }
    }

    fun setCourseCheck(on: Boolean) {
        _courseCheckOn.value = on
        if (on) _stationWritePrompt.value = null
        if (!on) _courseCheckReport.value = null
    }

    fun setCourseCheckCourse(name: String) {
        if (name == _courseCheckName.value) return
        _courseCheckName.value = name
        _courseCheckReport.value = null
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
                _status.value = if (isRogaine(settings.value.sportKind)) {
                    importUsbEventPack(replace) ?: "Файл пакета не найден"
                } else {
                    repository.startList.loadProtocolFromUsb(replace, settings.value.sportKind)
                }
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось загрузить протокол"
            } finally {
                _busy.value = false
            }
        }
    }

    fun downloadEventPack() {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val sport = settings.value.sportKind
                if (!isRogaine(sport)) {
                    _status.value = "Пакет с сайта — для старта «Рогейн»."
                    return@launch
                }
                val downloaded = repository.event.fetchEventPack(settings.value)
                repository.startList.sportMismatch(downloaded.pack.sport, sport)?.let {
                    _status.value = it
                    return@launch
                }
                val note = repository.applyRogaineEventPack(downloaded.pack, replace = true)
                val mapBytes = downloaded.mapBytes
                if (mapBytes != null && mapBytes.isNotEmpty()) {
                    repository.event.saveCourseMap(mapBytes, downloaded.mapFileName)
                }
                val mapNote = if (mapBytes != null && mapBytes.isNotEmpty()) ", карта загружена" else ""
                _status.value = note.replace("Пакет старта:", "Пакет с сайта:") + mapNote
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось скачать пакет"
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
                    CsvImportKind.Protocol -> repository.startList.importProtocolCsv(
                        text,
                        pendingCsvReset,
                        settings.value.sportKind,
                    )
                    CsvImportKind.Courses -> repository.startList.importCoursesCsv(text, pendingCsvReset)
                    CsvImportKind.Finish -> importFinishCsv(text)
                    CsvImportKind.EventPack -> importEventPackJson(text)
                    CsvImportKind.Formats -> {
                        val (note, rows) = repository.startList.importFormatsCsv(text, pendingCsvReset)
                        repository.event.mergeFormatSettings(rows)
                        note
                    }
                    CsvImportKind.AwardGroups -> {
                        val rows = repository.startList.parseAwardGroupsCsv(text)
                        repository.event.mergeAwardGroups(rows)
                        "Группы: ${rows.size}."
                    }
                    CsvImportKind.Legends -> {
                        val rows = repository.startList.parseLegendsCsv(text)
                        repository.event.mergeLegends(rows)
                        repository.startList.fillEmptyFormatControls(rows.map { it.cp })
                        "Легенды: ${rows.size} КП."
                    }
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
        if (id == null || id !in 1..MAX_LOGICAL_ID) {
            _status.value = "Укажите номер чипа (1…$MAX_LOGICAL_ID)"
            return
        }
        if (_addingNew.value && people.value.any { it.id == id }) {
            _status.value = "№ $id уже в протоколе. Выберите его, чтобы добавить ФИО."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.startList.person(id)
            val savedName = name.ifBlank { existing?.name.orEmpty() }
            val savedCourse = course.ifBlank { existing?.currentCourse.orEmpty() }
            repository.startList.upsertLocal(
                id,
                savedName,
                savedCourse,
                gender = storedPersonGender(_newGender.value),
                birthDate = storedBirthDate(_newBirth.value),
            )
            _addingNew.value = false
            _selectedId.value = null
            _status.value = when {
                name.isNotEmpty() && existing?.name.isNullOrBlank() ->
                    "№ $id: ФИО сохранено"
                savedCourse.isEmpty() ->
                    "Участник № $id сохранён. Внимание: дистанция не указана."
                else ->
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
            _status.value = if (isRogaine(settings.value.sportKind)) "Укажите номер КП" else "Выберите КП дистанции"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isRogaine(settings.value.sportKind)) {
                    repository.event.addRogainePersonalCp(logicalId, cp)
                } else {
                    repository.event.addMissingCp(logicalId, courseName, cp)
                }
                repository.requestLiveSend()
                _status.value = "№ $logicalId: добавлен КП $cp, результат пересчитан"
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось добавить КП"
            }
        }
    }

    fun addRogaineRemoveCp(logicalId: Int?, cp: Int, removeMode: String) {
        if (cp <= 0) {
            _status.value = "Укажите номер КП"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.event.addRogaineRemoveCp(logicalId, cp, removeMode)
                repository.requestLiveSend()
                val action = if (removeMode == ROGAINE_REMOVE_POINTS) "только очки" else "очки и перегоны"
                _status.value = if (logicalId == null) {
                    "Общая корректировка: КП $cp ($action), результат пересчитан"
                } else {
                    "№ $logicalId: удалён КП $cp ($action), результат пересчитан"
                }
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось удалить КП"
            }
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
        _stationInspect.value = null
        promptStatus()
    }

    fun onStationControlNumber(value: String) {
        _stationControlNumber.value = value.filter { it.isDigit() }.take(3)
        _stationWritePrompt.value = null
        promptStatus()
    }

    fun onQuery(value: String) {
        _query.value = value
        if (_section.value != AppSection.Chip) {
            _addingNew.value = false
        }
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
        _newGender.value = personGenderChoice(person?.gender.orEmpty())
        _newBirth.value = displayBirthDate(person?.birthDate.orEmpty())
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
        val q = _query.value
        _addingNew.value = true
        _selectedId.value = null
        _newNumber.value = q.filter { it.isDigit() }.take(5)
        _newName.value = if (q.any { it.isLetter() }) q else ""
        _newCourse.value = courses.value.firstOrNull()?.name.orEmpty()
        _newGender.value = ""
        _newBirth.value = ""
        if (_section.value != AppSection.Chip) {
            _query.value = ""
        }
        promptStatus()
    }

    fun onNewNumber(value: String) {
        val digits = value.filter { it.isDigit() }.take(5)
        _newNumber.value = digits
        if (_section.value == AppSection.Chip && _mode.value == ChipMode.Issue) {
            syncIssueNumber(digits)
        }
    }

    private fun syncIssueNumber(digits: String) {
        val id = digits.toIntOrNull()
        if (id == null || id !in 1..MAX_LOGICAL_ID) {
            _selectedId.value = null
            _addingNew.value = digits.isNotEmpty()
            if (digits.isEmpty()) {
                _newName.value = ""
                _newCourse.value = ""
                _newGender.value = ""
                _newBirth.value = ""
            }
            promptStatus()
            return
        }
        val person = people.value.find { it.id == id }
        if (person != null) {
            _selectedId.value = id
            _addingNew.value = false
            _newName.value = person.name
            _newCourse.value = person.currentCourse
            _newGender.value = personGenderChoice(person.gender)
            _newBirth.value = displayBirthDate(person.birthDate)
        } else {
            _selectedId.value = null
            _addingNew.value = true
            _newName.value = ""
            _newCourse.value = ""
            _newGender.value = ""
            _newBirth.value = ""
        }
        promptStatus()
    }

    fun onNewName(value: String) {
        _newName.value = value
    }

    fun onNewGender(value: String) {
        _newGender.value = value
    }

    fun onNewBirth(value: String) {
        _newBirth.value = value
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
            if (id != null && id in 1..MAX_LOGICAL_ID && protocolPerson(id) == null) {
                viewModelScope.launch(Dispatchers.IO) {
                    issueToChip(id, _newName.value.trim(), value)
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

    fun writeRandomRogaineCourse() {
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
        val order = blockOrder
        val io = chipIo
        if (!_chipReady.value || order == null || io == null) {
            _status.value = "Приложите чип и не убирайте его."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val current = settings.value
                val pool = EventStore.legendCps(current).ifEmpty { TestCoursePlan.defaultRogainePool }
                val cps = TestCoursePlan.randomRogaineSequence(current.startCp, current.finishCp, pool)
                val now = LocalTime.now()
                val punches = TestCoursePlan.punches(cps, now.hour, now.minute, now.second)
                io.writeTestCourse(order, logicalId, punches, pointerEnd, chipBlockCount)
                pointerEnd = 4 + punches.size
                punchCount = punches.size
                _chipMemoryLog.value = null
                _sessionSummary.value = "№ ${person.id} · ${punches.size} отметок"
                val inner = cps.filter { it != current.startCp && it != current.finishCp }
                _status.value = "${person.name} · случайные КП: ${inner.joinToString()} (${punches.size} отметок)."
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
                _chipMemoryLog.value = null
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
        if (_section.value != AppSection.Chip) return
        val id = _newNumber.value.toIntOrNull()
        val person = protocolPerson(id)
        _status.value = when (_mode.value) {
            ChipMode.Issue -> when {
                id == null || id !in 1..MAX_LOGICAL_ID ->
                    "Введите номер и приложите чип. Фамилия — если нужно найти в списке."
                person != null -> {
                    val who = person.name.ifBlank { "без ФИО" }
                    val dist = person.currentCourse.ifBlank {
                        if (isRogaine(settings.value.sportKind)) "без формата" else "без дистанции"
                    }
                    if (_chipReady.value) {
                        "Чип на месте. Запишу № $id · $who · $dist."
                    } else {
                        "№ $id · $who · $dist. Приложите чип."
                    }
                }
                _chipReady.value ->
                    "Чип на месте. Запишу № $id и добавлю в протокол."
                else ->
                    "№ $id нет в протоколе — выдадим и добавим. Приложите чип."
            }
            ChipMode.Finish -> "Приложите чип — отметки сохранятся"
            ChipMode.Station -> stationPrompt()
            ChipMode.Tests -> testsPrompt()
        }
    }

    private fun stationPrompt(): String {
        val program = _stationProgram.value
        if (program == StationProgram.Inspect) {
            return "Проверка. Приложите чип — будет показан его режим, запись не выполняется."
        }
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
        _chipStationLabel.value = null
        _status.value = message
        signalError()
    }

    fun onChipGone() {
        _chipReady.value = false
        _chipLogicalId.value = null
        _chipStationLabel.value = null
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
        if (uid == lastUid && now - lastAt < 2_000 && _mode.value != ChipMode.Station && !_courseCheckOn.value) {
            _busy.value = false
            return
        }
        lastUid = uid
        lastAt = now
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val stationWrite = _mode.value == ChipMode.Station &&
                    _section.value == AppSection.Chip &&
                    !_courseCheckOn.value
                if (stationWrite) {
                    handleStationRead(blocks, declaredBlockCount)
                    return@launch
                }
                if (_courseCheckOn.value) {
                    _stationWritePrompt.value = null
                }
                val dump = SfrChipParser.parse(uid, blocks, declaredBlockCount)
                blockOrder = dump.blockOrder
                pointerEnd = dump.pointerEnd
                punchCount = dump.punches.size
                chipBlockCount = dump.blockCount
                _chipReady.value = true
                _chipLogicalId.value = dump.logicalId
                _chipStationLabel.value = SfrChipParser.stationRole(blocks)?.let {
                    SfrChipParser.stationCommandLabel(it.command, it.cp)
                }
                val station = _chipStationLabel.value
                val label = when {
                    station != null -> "служебный · $station"
                    dump.logicalId != null -> "№ ${dump.logicalId}"
                    else -> "без номера"
                }
                _sessionSummary.value = if (station != null) label else "$label · $punchCount отметок"
                if (_courseCheckOn.value) {
                    handleCourseStationCheck(blocks, dump)
                    return@launch
                }
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
        if (draft != null) {
            issueToChip(draft.first, draft.second, draft.third)
            return
        }
        if (logicalId != null) {
            val known = people.value.find { it.id == logicalId }
            if (known != null && personNeedsFill(known.name, known.currentCourse)) {
                _selectedId.value = known.id
                _newNumber.value = known.id.toString()
                _newName.value = known.name
                _newCourse.value = known.currentCourse
                openIssueFill(known.id, known.name, known.currentCourse)
                _status.value = if (isRogaine(settings.value.sportKind)) {
                    "№ ${known.id}: заполните ФИО и формат."
                } else {
                    "№ ${known.id}: заполните ФИО и дистанцию."
                }
                return
            }
        }
        _status.value = issueTargetError(logicalId)
        if (_addingNew.value) signalError()
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
            val readyFor = if (isRogaine(settings.value.sportKind)) "формата" else "дистанции"
            "Чип не перезаписан. Готов для $readyFor $course."
        } else {
            "Чип не перезаписан."
        }
    }

    fun confirmIssueFill(name: String, course: String) {
        val prompt = _issueFillPrompt.value ?: return
        _issueFillPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.startList.person(prompt.id)
            val savedName = name.trim().ifBlank { existing?.name.orEmpty() }
            val savedCourse = course.trim().ifBlank { existing?.currentCourse.orEmpty() }
            repository.startList.upsertLocal(prompt.id, savedName, savedCourse)
            val rogaine = isRogaine(settings.value.sportKind)
            _status.value = when {
                savedName.isBlank() && savedCourse.isBlank() ->
                    if (rogaine) {
                        "№ ${prompt.id} в протоколе. ФИО и формат можно указать позже."
                    } else {
                        "№ ${prompt.id} в протоколе. ФИО и дистанцию можно указать позже."
                    }
                savedCourse.isBlank() ->
                    if (rogaine) {
                        "№ ${prompt.id}: ФИО сохранено. Формат можно указать позже."
                    } else {
                        "№ ${prompt.id}: ФИО сохранено. Дистанцию можно указать позже."
                    }
                savedName.isBlank() ->
                    if (rogaine) {
                        "№ ${prompt.id}: формат $savedCourse. ФИО можно указать позже."
                    } else {
                        "№ ${prompt.id}: дистанция $savedCourse. ФИО можно указать позже."
                    }
                else ->
                    "№ ${prompt.id}: $savedName · $savedCourse."
            }
        }
    }

    fun skipIssueFill() {
        val id = _issueFillPrompt.value?.id
        _issueFillPrompt.value = null
        val later = if (isRogaine(settings.value.sportKind)) {
            "ФИО и формат можно указать позже"
        } else {
            "ФИО и дистанцию можно указать позже"
        }
        _status.value = if (id != null) {
            "№ $id в протоколе. $later."
        } else {
            "$later в протоколе."
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
        val rawHex = rawBlocksToHex(blocks)
        val personName = person?.name.orEmpty()
        val finishCp = settings.value.finishCp.takeIf { it > 0 } ?: CP_FINISH
        if (!dump.hasPunch(finishCp)) {
            val now = LocalTime.now()
            _finishMissingPrompt.value = FinishMissingPrompt(
                dump = dump,
                rawHex = rawHex,
                personName = personName,
                courseName = course,
                finishCp = finishCp,
                finish = Punch(finishCp, now.hour, now.minute, now.second),
            )
            _status.value = "№ $logicalId: нет отметки финиша (КП $finishCp)."
            signalError()
            return
        }
        saveFinishDump(dump, rawHex, personName, course, replace = false)
    }

    fun confirmMissingFinish() {
        val pending = _finishMissingPrompt.value ?: return
        _finishMissingPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            saveFinishDump(
                pending.dump.withPunch(pending.finish),
                pending.rawHex,
                pending.personName,
                pending.courseName,
                replace = false,
            )
        }
    }

    fun skipMissingFinish() {
        val pending = _finishMissingPrompt.value ?: return
        _finishMissingPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            saveFinishDump(
                pending.dump,
                pending.rawHex,
                pending.personName,
                pending.courseName,
                replace = false,
            )
        }
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

    fun confirmFinishCsvOverwrite() {
        applyPendingFinishCsv(overwrite = true)
    }

    fun confirmFinishCsvNewOnly() {
        applyPendingFinishCsv(overwrite = false)
    }

    fun cancelFinishCsvImport() {
        pendingFinishCsv = null
        _finishCsvPrompt.value = null
        _status.value = "CSV не загружен."
    }

    private suspend fun importFinishCsv(text: String): String {
        val preview = repository.previewFinishCsv(text)
        if (preview.fresh.isEmpty() && preview.conflicts.isEmpty()) {
            error("В файле нет финишных отметок")
        }
        if (preview.conflicts.isEmpty()) {
            return applyFinishCsv(preview, overwrite = false)
        }
        pendingFinishCsv = preview
        _finishCsvPrompt.value = FinishCsvPrompt(
            addedCount = preview.fresh.size,
            conflictCount = preview.conflicts.size,
            conflictLabels = preview.conflicts.take(8).map { row ->
                val id = row.dump.logicalId?.toString() ?: "—"
                val course = row.courseName.ifBlank { "без дистанции" }
                "№ $id $course"
            },
        )
        return "В CSV ${preview.conflicts.size} уже есть в результатах."
    }

    private fun applyPendingFinishCsv(overwrite: Boolean) {
        val preview = pendingFinishCsv ?: return
        pendingFinishCsv = null
        _finishCsvPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                _status.value = applyFinishCsv(preview, overwrite)
            } catch (e: Exception) {
                _status.value = e.message ?: "Ошибка CSV"
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun applyFinishCsv(preview: FinishCsvImportPreview, overwrite: Boolean): String {
        val stats = repository.importFinishCsv(preview, overwrite)
        repository.requestLiveSend()
        val parts = ArrayList<String>()
        parts += "добавлено ${stats.added}"
        if (overwrite) {
            parts += "перезаписано ${stats.overwritten}"
        } else if (preview.conflicts.isNotEmpty()) {
            parts += "${preview.conflicts.size} уже были, не менялись"
        }
        return "CSV: ${parts.joinToString(", ")}."
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
                val saved = if (result.snapshotError == null) {
                    "$who · ${dump.punches.size} отметок сохранено, chip-queue.json записан."
                } else {
                    "$who · в очереди, chip-queue.json не записан: ${result.snapshotError}"
                }
                val hint = ChipCellLog.hintFromRawHex(rawHex)
                _status.value = if (hint == null) {
                    saved
                } else {
                    "$saved Возможна ошибка считки: $hint. Полный лог — вкладка «Тесты»."
                }
                signalSuccess()
                maybeAskPrint(dump.logicalId, course)
            }
        }
    }

    private fun issueTarget(@Suppress("UNUSED_PARAMETER") logicalId: Int?): Triple<Int, String, String>? {
        val typed = _newNumber.value.toIntOrNull()
        val selected = _selectedId.value?.let { id -> people.value.find { it.id == id } }
        val id = typed ?: selected?.id
        if (id == null || id !in 1..MAX_LOGICAL_ID) return null
        val person = protocolPerson(id)
        val name = person?.name?.ifBlank { _newName.value.trim() } ?: _newName.value.trim()
        val course = person?.currentCourse?.ifBlank { _newCourse.value.trim() } ?: _newCourse.value.trim()
        return Triple(id, name, course)
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
                _status.value = if (isRogaine(settings.value.sportKind)) {
                    "Чип уже готов для формата $issued."
                } else {
                    "Чип уже готов для дистанции $issued."
                }
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
            _newNumber.value = ""
            _newName.value = ""
            _newCourse.value = ""
            _newGender.value = ""
            _newBirth.value = ""
            _extraCourse.value = ""
            pointerEnd = 5
            punchCount = 0
            _sessionSummary.value = "№ $id · 0 отметок"
            val who = name.ifBlank { "№ $id" }
            val dist = course.ifBlank {
                if (isRogaine(settings.value.sportKind)) "без формата" else "без дистанции"
            }
            _status.value = "$who · $dist. Чип выдан."
            signalSuccess()
            if (personNeedsFill(name, course)) {
                openIssueFill(id, name, course)
            }
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
                _status.value = if (isRogaine(settings.value.sportKind)) {
                    "Текущий формат: $course. Приложите чип."
                } else {
                    "Текущая дистанция: $course. Приложите чип."
                }
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
            val note = importUsbPack(quiet = !force) 
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
            val note = importUsbPack(quiet = true) ?: return@launch
            if (!_busy.value) {
                _status.value = note
            }
        }
    }

    private suspend fun importUsbPack(quiet: Boolean): String? {
        val sport = settings.value.sportKind
        if (isRogaine(sport)) {
            val hasPeople = repository.startList.peopleNow().isNotEmpty()
            val hasCourses = repository.startList.coursesNow().isNotEmpty()
            if (quiet && (hasPeople || hasCourses)) return null
            return importUsbEventPack(replace = true, required = !quiet)
        }
        return repository.startList.reloadFromUsb(quiet = quiet, sport = sport)
    }

    private suspend fun importUsbEventPack(replace: Boolean, required: Boolean = true): String? {
        val (pack, error) = repository.startList.readUsbEventPack()
        val loaded = pack ?: return if (required) error ?: "Файл пакета не найден" else null
        repository.startList.sportMismatch(loaded.sport, settings.value.sportKind)?.let {
            return if (required) it else null
        }
        return repository.applyRogaineEventPack(loaded, replace)
    }

    private suspend fun importEventPackJson(text: String): String {
        if (!isRogaine(settings.value.sportKind)) {
            error("Пакет JSON — для старта «Рогейн».")
        }
        val pack = repository.startList.readEventPackJson(text)
        repository.startList.sportMismatch(pack.sport, settings.value.sportKind)?.let { error(it) }
        return repository.applyRogaineEventPack(pack, pendingCsvReset)
            .replace("Пакет старта:", "Пакет JSON:")
    }

    private fun handleCourseStationCheck(blocks: List<ByteArray>, dump: ChipDump) {
        val name = _courseCheckName.value.trim()
        val course = courses.value.find { it.name.equals(name, true) }
        if (course == null) {
            _courseCheckReport.value = null
            _status.value = "Выберите дистанцию для проверки станций."
            signalError()
            return
        }
        if (SfrChipParser.stationRole(blocks) != null && dump.punches.isEmpty()) {
            _courseCheckReport.value = null
            _status.value = "Это карта станции, а не чип с отметками. Очистите чип и отметьтесь на станциях."
            signalError()
            return
        }
        val now = java.util.Calendar.getInstance()
        val nowSeconds = now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
            now.get(java.util.Calendar.MINUTE) * 60 +
            now.get(java.util.Calendar.SECOND)
        val report = StationCourseCheck.check(
            course.controls,
            dump.punches,
            nowSeconds = nowSeconds,
            clearedAt = dump.clearedAt,
        )
        _courseCheckReport.value = report
        _status.value = when {
            !report.ok -> "«${course.name}»: проверка станций не сошлась."
            report.attention -> "«${course.name}»: есть повтор номера, посмотрите замечание."
            else -> "«${course.name}»: старт, финиш и все КП есть."
        }
        if (report.ok) signalSuccess() else signalError()
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
                if (_courseCheckOn.value) {
                    _courseCheckReport.value = null
                    _status.value = "Отметки очищены. Отметьтесь на всех станциях и приложите чип."
                } else {
                    _status.value = "Отметки очищены."
                }
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

    private fun protocolPerson(id: Int?): StartPerson? =
        id?.let { n -> people.value.find { it.id == n } }

    private fun issueTargetError(logicalId: Int?): String {
        val id = _newNumber.value.toIntOrNull()
        return when {
            id == null || id !in 1..MAX_LOGICAL_ID ->
                "Введите номер и приложите чип."
            logicalId != null ->
                "№ $logicalId на чипе. Введите номер, чтобы выдать, или заполните карточку участника."
            else ->
                "Введите номер и приложите чип."
        }
    }

    private fun personNeedsFill(name: String, course: String): Boolean =
        name.isBlank() || course.isBlank()

    private fun openIssueFill(id: Int, name: String, course: String) {
        _issueFillPrompt.value = IssueFillPrompt(id = id, name = name, course = course)
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
        if (id == null) {
            val station = _chipStationLabel.value
            return if (station != null) {
                "Служебный чип: $station. «Считать дамп» покажет полный лог."
            } else {
                "На чипе нет номера. Сначала выдайте чип на вкладке «Выдача»."
            }
        }
        val person = personFromChip()
            ?: return "№ $id нет в стартовом протоколе."
        if (isRogaine(settings.value.sportKind)) {
            return "№ ${person.id} · ${person.name}. «Случайные КП» запишет прохождение, «Считать дамп» — полный лог."
        }
        val course = _testCourse.value.trim().ifBlank { person.currentCourse }
        return if (course.isBlank()) {
            "№ ${person.id} · ${person.name}. Выберите дистанцию."
        } else {
            "№ ${person.id} · ${person.name}. «Правильное» / «Ошибочное» запишет прохождение, «Считать дамп» — полный лог."
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

    fun readChipDump() {
        val io = chipIo
        if (!_chipReady.value || io == null) {
            _status.value = "Приложите чип и не убирайте его. Затем нажмите «Считать дамп»."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                _status.value = "Читаю полный дамп…"
                val (uid, blocks) = io.readAllBlocks()
                val log = ChipCellLog.fromRaw(uid, blocks, blocks.size)
                _chipMemoryLog.value = log
                pointerEnd = log.pointerEnd
                chipBlockCount = log.blockCount
                val file = repository.event.saveChipLog(log.format(), log.logicalId, uid)
                val kind = log.kindLine?.let { "$it. " }.orEmpty()
                _status.value = if (log.hint != null) {
                    "${kind}Внимание: ${log.hint}. Полный лог: ${file.name}."
                } else {
                    "${kind}Полный лог сохранён: ${file.name}."
                }
                signalSuccess()
            } catch (e: Exception) {
                forgetChip()
                _status.value = e.message ?: "Не удалось считать дамп"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    fun shareChipLog() {
        val log = _chipMemoryLog.value
        if (log == null) {
            _status.value = "Сначала нажмите «Считать дамп», не убирая чип."
            return
        }
        _csvExport.value = listOf(log.fileName() to log.format())
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
        if (program == StationProgram.Inspect) {
            _stationWritePrompt.value = null
            val text = SfrChipParser.describeChipMode(blocks)
            _stationInspect.value = text
            _status.value = text
            return
        }
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
        if (_mode.value != ChipMode.Station || _section.value != AppSection.Chip || _courseCheckOn.value) {
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
        val command = pending.program.command ?: return
        val label = stationProgramLabel(pending.program, pending.controlNumber)
        val setsClock = stationSetsClock(command)
        val deadline = if (setsClock) System.currentTimeMillis() + STATION_CLOCK_LEAD_MS else 0L
        val clock = if (setsClock) {
            Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()).toLocalTime()
        } else {
            LocalTime.now()
        }
        if (setsClock) beginStationCountdown(deadline, label)
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                io.writeStationProgram(
                    order,
                    command,
                    pending.controlNumber.takeIf { pending.program == StationProgram.Control },
                    pending.oldPointerEnd,
                    pending.blockCount,
                    clock,
                )
                if (setsClock) {
                    val current = _stationCountdown.value
                    if (current != null && current.deadlineEpochMs == deadline) {
                        _stationCountdown.value = current.copy(writeFinished = true)
                    }
                    _status.value = "На чип записана станция «$label». На нуле приложите этот чип к станции."
                } else {
                    _status.value = "На чип записана станция «$label». Приложите этот чип к станции."
                    signalSuccess()
                }
            } catch (e: Exception) {
                if (setsClock) _stationCountdown.value = null
                forgetChip()
                _status.value = e.message ?: "Не удалось записать программу станции"
                signalError()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun stationSetsClock(command: StationCommand): Boolean = when (command) {
        StationCommand.Control, StationCommand.Start, StationCommand.Finish, StationCommand.Clear -> true
        StationCommand.Active, StationCommand.Passive, StationCommand.Battery -> false
    }

    /** Цифры с начала записи. Ноль — момент, который уже лежит на чипе как время станции. */
    private fun beginStationCountdown(deadline: Long, label: String) {
        _stationCountdown.value = StationClockCountdown(deadline, label)
        viewModelScope.launch {
            var signaledAt = 0L
            while (isActive) {
                val state = _stationCountdown.value
                if (state == null || state.deadlineEpochMs != deadline) return@launch
                val now = System.currentTimeMillis()
                if (signaledAt == 0L && state.writeFinished && now >= deadline) {
                    signaledAt = now
                    signalSuccess()
                }
                if (signaledAt != 0L && now >= signaledAt + STATION_CLOCK_ZERO_HOLD_MS) {
                    if (_stationCountdown.value?.deadlineEpochMs == deadline) {
                        _stationCountdown.value = null
                    }
                    return@launch
                }
                delay(40)
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

    fun resetForNewStart(sport: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = repository.resetForNewStart(sport)
                resetSessionUi()
                _status.value = message
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось начать новый старт"
            }
        }
    }

    fun openStart(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = repository.openStart(id)
                resetSessionUi()
                _status.value = message
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось открыть старт"
            }
        }
    }

    fun deleteStart(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = repository.deleteStart(id)
                resetSessionUi()
                _status.value = message
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось удалить старт"
            }
        }
    }

    fun exportCsv(startId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = repository.exportCsv(startId).toMutableList()
                if (isRogaine(settings.value.sportKind)) {
                    val scored = EventStore.score(
                        items.value,
                        people.value,
                        courses.value,
                        settings.value,
                        corrections.value,
                        exclusions.value,
                    )
                    if (scored.isNotEmpty()) {
                        files += "rogaine-results.csv" to CsvExport.rogaineResults(
                            scored.map { row ->
                                RogaineResultCsvRow(
                                    place = row.place,
                                    id = row.logicalId?.toString().orEmpty(),
                                    name = row.name,
                                    format = row.course,
                                    pointsRaw = row.calc.pointsRaw,
                                    penaltyPoints = row.calc.penaltyPoints,
                                    pointsFinal = row.calc.pointsFinal,
                                    elapsedSeconds = row.calc.elapsedSeconds,
                                    status = row.calc.status,
                                )
                            },
                        )
                    }
                }
                _csvExport.value = files
            } catch (e: Exception) {
                _status.value = e.message ?: "Не удалось экспортировать CSV"
            }
        }
    }

    fun consumeCsvExport() {
        _csvExport.value = null
    }

    private fun resetSessionUi() {
        lastUid = null
        lastAt = 0
        _selectedId.value = null
        _addingNew.value = false
        _query.value = ""
        _extraCourse.value = ""
        _testCourse.value = ""
        _chipLogicalId.value = null
        _chipStationLabel.value = null
        _chipMemoryLog.value = null
        _sessionSummary.value = null
        _finishReplacePrompt.value = null
        _finishMissingPrompt.value = null
        _issueOverwritePrompt.value = null
        _issueFillPrompt.value = null
        _overwritePrompt.value = false
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
        val num = _newNumber.value
        return people.value.filter { person ->
            val nameHit = q.isEmpty() ||
                person.name.lowercase().replace('ё', 'е').contains(q) ||
                person.id.toString().contains(q)
            val numberHit = num.isEmpty() || person.id.toString().startsWith(num)
            if (q.isNotEmpty()) nameHit else numberHit
        }
    }

    fun bondedPrinters(): List<BondedPrinter> = repository.printer.bonded()

    fun hasPrinterPermission(): Boolean = repository.printer.hasConnectPermission()

    fun selectPrinter(device: BondedPrinter) {
        repository.printer.save(device.address, device.name)
        _printerName.value = device.name
        _status.value = "Принтер: ${device.name}"
    }

    fun clearPrinter() {
        repository.printer.clear()
        _printerName.value = ""
        _status.value = "Принтер отключён"
    }

    fun testPrinter() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.printer.printSelfTest()
                _status.value = "Тест отправлен на принтер"
            } catch (e: Exception) {
                _status.value = "Тест не удался: ${e.message ?: "ошибка принтера"}"
            }
        }
    }

    fun confirmPrint() {
        val prompt = _printPrompt.value ?: return
        _printPrompt.value = null
        printText(prompt.text, saved = true)
    }

    fun skipPrint() {
        _printPrompt.value = null
    }

    fun printScored(row: ScoredResult) {
        printText(receiptText(row), saved = false)
    }

    private fun printText(text: String, saved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.printer.print(text)
                _status.value = "Чек напечатан"
            } catch (e: Exception) {
                val fail = e.message ?: "ошибка принтера"
                _status.value = if (saved) {
                    "Результат сохранён, печать не удалась: $fail"
                } else {
                    "Печать не удалась: $fail"
                }
            }
        }
    }

    private suspend fun maybeAskPrint(logicalId: Int?, course: String) {
        if (!repository.printer.isReady()) return
        val scored = EventStore.score(
            repository.itemsNow(),
            people.value,
            courses.value,
            settings.value,
            corrections.value,
            exclusions.value,
        )
        val row = scored.find { item ->
            item.logicalId == logicalId && courseKey(item.course) == courseKey(course)
        } ?: return
        val who = row.logicalId?.let { "№ $it" } ?: row.name
        val courseLabel = if (isRogaine(settings.value.sportKind)) {
            row.course.ifBlank { "без формата" }
        } else {
            row.course.ifBlank { "без дистанции" }
        }
        val head = listOf(who, courseLabel, formatElapsed(row.calc.elapsedSeconds))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val lines = mutableListOf(head, resultStatusLine(row.calc.status, row.calc.diagnostics))
        if (isRogaine(settings.value.sportKind)) {
            lines += "Все очки ${row.calc.pointsRaw}"
            if (row.calc.penaltyPoints > 0) {
                lines += "Штрафные очки ${row.calc.penaltyPoints}"
            }
            lines += "Очки с учётом штрафных ${row.calc.pointsFinal}"
        }
        _printPrompt.value = PrintPrompt(summary = lines.joinToString("\n"), text = receiptText(row))
    }

    private fun receiptText(row: ScoredResult): String {
        val settings = settings.value
        val rogaine = isRogaine(settings.sportKind)
        val startClock = parseDaySeconds(settings.competitionStartTime)
            .takeIf { rogaine && settings.startMode == "time" }
        return ReceiptText.format(
            SplitReceipt(
                date = settings.competitionDate,
                title = settings.title,
                course = row.course,
                bib = row.logicalId?.toString().orEmpty(),
                name = row.name,
                marks = receiptMarks(row, settings),
                startCp = settings.startCp,
                elapsedSeconds = row.calc.elapsedSeconds,
                status = row.calc.status,
                finishCp = settings.finishCp,
                startClockSeconds = startClock,
                pointsRaw = row.calc.pointsRaw,
                penaltyPoints = row.calc.penaltyPoints,
                pointsFinal = row.calc.pointsFinal,
                rogaine = rogaine,
            ),
        )
    }

    private fun receiptMarks(row: ScoredResult, settings: EventSettings): List<ReceiptMark> {
        val required = filterCourseCps(
            courses.value.find { it.name.equals(row.course, true) }?.controls.orEmpty(),
            EventStore.removedCps(corrections.value),
        )
        val window = orientPunchWindow(row.punches, settings.startCp, settings.finishCp)
        val expanded = applyAddedCps(
            window,
            required,
            EventStore.addCpCounts(row.logicalId, row.course, corrections.value),
            settings.startCp,
            settings.finishCp,
        )
        return expanded.map { ReceiptMark(it.cp, it.seconds) }
    }

    companion object {
        private const val STATION_CLOCK_LEAD_MS = 3_000L
        private const val STATION_CLOCK_ZERO_HOLD_MS = 700L

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
