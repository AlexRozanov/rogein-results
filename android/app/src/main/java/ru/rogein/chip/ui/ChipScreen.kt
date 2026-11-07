package ru.rogein.chip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.QueuedChipEntity
import ru.rogein.chip.queue.StartArchiveStore
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.ChipCellLog
import ru.rogein.chip.parse.ChipMemoryLog
import ru.rogein.chip.parse.SPORT_ORIENT
import ru.rogein.chip.parse.isRogaine
import ru.rogein.chip.parse.nextFreeLogicalId
import ru.rogein.chip.parse.nextFreeTeamId
import ru.rogein.chip.parse.parseDaySeconds
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Green = Color(0xFF1B4D3E)
private val Cream = Color(0xFFF4F1EA)
private val Warn = Color(0xFF8A5A00)
private val Danger = Color(0xFF8B3A3A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipScreen(viewModel: ChipQueueViewModel, csvPicker: CsvPicker, csvShare: CsvShare) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val corrections by viewModel.corrections.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val savedStarts by viewModel.savedStarts.collectAsStateWithLifecycle()
    val csvExport by viewModel.csvExport.collectAsStateWithLifecycle()
    val printPrompt by viewModel.printPrompt.collectAsStateWithLifecycle()
    val printerName by viewModel.printerName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val morePage by viewModel.morePage.collectAsStateWithLifecycle()
    val overwritePrompt by viewModel.overwritePrompt.collectAsStateWithLifecycle()
    val finishReplacePrompt by viewModel.finishReplacePrompt.collectAsStateWithLifecycle()
    val finishMissingPrompt by viewModel.finishMissingPrompt.collectAsStateWithLifecycle()
    val finishCsvPrompt by viewModel.finishCsvPrompt.collectAsStateWithLifecycle()
    val issueOverwritePrompt by viewModel.issueOverwritePrompt.collectAsStateWithLifecycle()
    val finishNumberConflictPrompt by viewModel.finishNumberConflictPrompt.collectAsStateWithLifecycle()
    val issueFillPrompt by viewModel.issueFillPrompt.collectAsStateWithLifecycle()
    val stationWritePrompt by viewModel.stationWritePrompt.collectAsStateWithLifecycle()
    val stationProgram by viewModel.stationProgram.collectAsStateWithLifecycle()
    val stationInspect by viewModel.stationInspect.collectAsStateWithLifecycle()
    val stationCountdown by viewModel.stationCountdown.collectAsStateWithLifecycle()
    val courseCheckOn by viewModel.courseCheckOn.collectAsStateWithLifecycle()
    val courseCheckName by viewModel.courseCheckName.collectAsStateWithLifecycle()
    val courseCheckReport by viewModel.courseCheckReport.collectAsStateWithLifecycle()
    val stationControlNumber by viewModel.stationControlNumber.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val addingNew by viewModel.addingNew.collectAsStateWithLifecycle()
    val newNumber by viewModel.newNumber.collectAsStateWithLifecycle()
    val newName by viewModel.newName.collectAsStateWithLifecycle()
    val newCourse by viewModel.newCourse.collectAsStateWithLifecycle()
    val newGender by viewModel.newGender.collectAsStateWithLifecycle()
    val newBirth by viewModel.newBirth.collectAsStateWithLifecycle()
    val newTeamWith by viewModel.newTeamWith.collectAsStateWithLifecycle()
    val extraCourse by viewModel.extraCourse.collectAsStateWithLifecycle()
    val testCourse by viewModel.testCourse.collectAsStateWithLifecycle()
    val chipReady by viewModel.chipReady.collectAsStateWithLifecycle()
    val chipLogicalId by viewModel.chipLogicalId.collectAsStateWithLifecycle()
    val chipStationLabel by viewModel.chipStationLabel.collectAsStateWithLifecycle()
    val chipMemoryLog by viewModel.chipMemoryLog.collectAsStateWithLifecycle()
    val selected = people.find { it.id == selectedId }
    val results = remember(items, people, courses, settings, corrections, exclusions) {
        viewModel.scoredResults(items, people, courses, settings, corrections, exclusions)
    }
    var confirmNewStart by remember { mutableStateOf(false) }
    val visibleStarts = remember(savedStarts, settings, items, people) {
        savedStarts.map { start ->
            if (!start.current) start
            else start.copy(
                label = StartArchiveStore.label(
                    settings.title,
                    settings.slug,
                    settings.competitionDate,
                ),
                date = settings.competitionDate,
                slug = settings.slug,
                chipCount = items.size,
                personCount = people.size,
            )
        }
    }
    LaunchedEffect(csvExport) {
        val files = csvExport ?: return@LaunchedEffect
        csvShare.share(files)
        viewModel.consumeCsvExport()
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val protocolPersonEditor = section == AppSection.Protocol &&
        query.isBlank() &&
        (addingNew || selected != null)
    val hideKeyboardOnSelect = rememberHideKeyboardOnSelect(viewModel::selectPerson)
    val showStatus = !imeVisible && !protocolPersonEditor && shouldShowStatus(status, section)

    Box {
    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        topTitle(section, morePage, settings.sportKind),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (section == AppSection.More && morePage != MorePage.Menu) {
                        IconButton(onClick = { viewModel.setMorePage(MorePage.Menu) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                        }
                    }
                },
                actions = {
                    if (section == AppSection.Chip) {
                        CompactBarAction("Новый старт", onClick = { confirmNewStart = true })
                    }
                },
                expandedHeight = 48.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (!imeVisible && !protocolPersonEditor) {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = section == AppSection.Chip,
                        onClick = { viewModel.setSection(AppSection.Chip) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("Чип") },
                    )
                    NavigationBarItem(
                        selected = section == AppSection.Protocol,
                        onClick = { viewModel.setSection(AppSection.Protocol) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Протокол") },
                    )
                    NavigationBarItem(
                        selected = section == AppSection.Results,
                        onClick = { viewModel.setSection(AppSection.Results) },
                        icon = { Icon(Icons.Default.Star, contentDescription = null) },
                        label = { Text("Итоги") },
                    )
                    NavigationBarItem(
                        selected = section == AppSection.More,
                        onClick = { viewModel.setSection(AppSection.More) },
                        icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                        label = { Text("Ещё") },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (showStatus) {
                StatusStrip(status, onDismiss = viewModel::dismissStatus)
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (section) {
                AppSection.Chip -> ChipSection(
                    viewModel = viewModel,
                    items = items,
                    courses = courses,
                    query = query,
                    selected = selected,
                    selectedId = selectedId,
                    addingNew = addingNew,
                    newNumber = newNumber,
                    newName = newName,
                    newCourse = newCourse,
                    extraCourse = extraCourse,
                    fillOpen = issueFillPrompt != null,
                    testCourse = testCourse,
                    chipReady = chipReady,
                    chipLogicalId = chipLogicalId,
                    people = people,
                    busy = busy,
                    mode = mode,
                    stationProgram = stationProgram,
                    stationInspect = stationInspect,
                    stationControlNumber = stationControlNumber,
                    onStationProgram = viewModel::setStationProgram,
                    onStationControlNumber = viewModel::onStationControlNumber,
                    onDeleteQueued = viewModel::deleteQueued,
                    liveReady = EventStore.liveReady(settings),
                    sportKind = settings.sportKind,
                    chipLog = chipMemoryLog,
                    chipStationLabel = chipStationLabel,
                    onShareChipLog = viewModel::shareChipLog,
                    onReadChipDump = viewModel::readChipDump,
                    modifier = Modifier.weight(1f),
                )
                AppSection.Protocol -> ProtocolScreen(
                    people = people,
                    courses = courses,
                    query = query,
                    selected = selected,
                    addingNew = addingNew,
                    newNumber = newNumber,
                    newName = newName,
                    newCourse = newCourse,
                    newGender = newGender,
                    newBirth = newBirth,
                    newTeamWith = newTeamWith,
                    onQuery = viewModel::onQuery,
                    onSelect = hideKeyboardOnSelect,
                    onNew = viewModel::startNewPerson,
                    onClearSelection = viewModel::clearSelection,
                    onNewNumber = viewModel::onNewNumber,
                    onNewName = viewModel::onNewName,
                    onNewCourse = viewModel::onNewCourse,
                    onNewGender = viewModel::onNewGender,
                    onNewBirth = viewModel::onNewBirth,
                    onNewTeamWith = viewModel::onNewTeamWith,
                    onSavePerson = viewModel::saveManualPerson,
                    onDeletePerson = viewModel::deletePerson,
                    onLeaveTeam = viewModel::leavePersonTeam,
                    onUsbProtocol = viewModel::loadUsbProtocol,
                    onUsbCourses = viewModel::loadUsbCourses,
                    onPickProtocol = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.Protocol)
                    },
                    onPickCourses = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.Courses)
                    },
                    sportKind = settings.sportKind,
                    onLoadSitePack = viewModel::downloadEventPack,
                    onPickEventPack = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.EventPack)
                    },
                    onPickFormats = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.Formats)
                    },
                    onPickGroups = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.AwardGroups)
                    },
                    onPickLegends = { replace ->
                        viewModel.prepareCsvImport(replace)
                        csvPicker.pick(CsvImportKind.Legends)
                    },
                    onSaveCourse = viewModel::saveCourse,
                    onDeleteCourse = viewModel::deleteCourse,
                    legendCps = EventStore.legendCps(settings),
                    legends = EventStore.legends(settings),
                    formatTypeRules = EventStore.formatTypeRules(settings),
                    awardGroups = EventStore.awardGroups(settings),
                    competitionDate = settings.competitionDate,
                    items = items,
                    courseCheckOn = courseCheckOn,
                    courseCheckName = courseCheckName,
                    courseCheckReport = courseCheckReport,
                    chipReady = chipReady,
                    busy = busy,
                    onCourseCheck = viewModel::setCourseCheck,
                    onCourseCheckCourse = viewModel::setCourseCheckCourse,
                    onClearChip = viewModel::clearMarks,
                    modifier = Modifier.weight(1f),
                )
                AppSection.Results -> ResultsScreen(
                    results = results,
                    courses = courses,
                    corrections = corrections,
                    startCp = settings.startCp,
                    finishCp = settings.finishCp,
                    liveNote = settings.liveNote,
                    livePending = settings.livePending,
                    liveUrl = EventStore.liveViewerUrl(settings),
                    liveShareText = EventStore.liveShareText(settings),
                    onSendLive = viewModel::sendLiveNow,
                    onAddCp = viewModel::addMissingCp,
                    onRemoveCp = viewModel::addRogaineRemoveCp,
                    onAssignCourse = viewModel::assignCourse,
                    onDeleteCorrection = viewModel::deleteCorrection,
                    canPrint = printerName.isNotBlank(),
                    onPrint = viewModel::printScored,
                    onPickFinishCsv = { csvPicker.pick(CsvImportKind.Finish) },
                    onDeleteFinish = viewModel::deleteQueued,
                    onReassignFinish = viewModel::reassignFinishNumber,
                    sportKind = settings.sportKind,
                    startClockSeconds = parseDaySeconds(settings.competitionStartTime)
                        .takeIf { isRogaine(settings.sportKind) && settings.startMode == "time" },
                    legendCps = EventStore.legendCps(settings),
                    people = people,
                    awardGroups = EventStore.awardGroups(settings),
                    competitionDate = settings.competitionDate,
                )
                AppSection.More -> when (morePage) {
                    MorePage.Menu -> MoreMenu(onOpen = viewModel::setMorePage, sportKind = settings.sportKind)
                    MorePage.Printer -> PrinterScreen(
                        selectedName = printerName,
                        onSelect = viewModel::selectPrinter,
                        onClear = viewModel::clearPrinter,
                        onTest = viewModel::testPrinter,
                        loadBonded = viewModel::bondedPrinters,
                    )
                    MorePage.Starts -> StartsScreen(
                        starts = visibleStarts,
                        onOpen = viewModel::openStart,
                        onDelete = viewModel::deleteStart,
                        onExport = { viewModel.exportCsv(it) },
                        onNewStart = { confirmNewStart = true },
                    )
                    MorePage.Removed -> RemovedCpsScreen(
                        courses = courses,
                        corrections = corrections,
                        onAdd = viewModel::addRemovedCp,
                        onUndo = viewModel::deleteCorrection,
                        sportKind = settings.sportKind,
                        onAddRogaine = { cp, mode -> viewModel.addRogaineRemoveCp(null, cp, mode) },
                        startCp = settings.startCp,
                        finishCp = settings.finishCp,
                        legendCps = EventStore.legendCps(settings),
                    )
                    MorePage.Exclusions -> ExclusionsScreen(
                        courses = courses,
                        settings = settings,
                        corrections = corrections,
                        exclusions = exclusions,
                        onAdd = viewModel::addExclusion,
                        onDelete = viewModel::deleteExclusion,
                    )
                    MorePage.Corrections -> CorrectionsScreen(
                        results = results,
                        people = people,
                        courses = courses,
                        corrections = corrections,
                        startCp = settings.startCp,
                        finishCp = settings.finishCp,
                        onAddCp = viewModel::addMissingCp,
                        onDeleteCorrection = viewModel::deleteCorrection,
                        sportKind = settings.sportKind,
                        onRemoveCp = viewModel::addRogaineRemoveCp,
                        legendCps = EventStore.legendCps(settings),
                    )
                    MorePage.Publish -> PublishScreen(
                        settings = settings,
                        busy = busy,
                        onSave = viewModel::saveSettings,
                        onTest = viewModel::testSite,
                        onGenerateSlug = viewModel::saveAndGenerateSlug,
                        onPublish = viewModel::requestPublish,
                    )
                    MorePage.Settings -> EventSettingsScreen(
                        settings = settings,
                        busy = busy,
                        onSave = viewModel::saveSettings,
                    )
                }
            }
        }
    }
    if (confirmNewStart) {
        AlertDialog(
            onDismissRequest = { confirmNewStart = false },
            title = { Text("Новый старт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Текущий сохранится в списке стартов. Выберите тип.")
                    Button(
                        onClick = {
                            confirmNewStart = false
                            viewModel.resetForNewStart("orient")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ориентирование") }
                    Button(
                        onClick = {
                            confirmNewStart = false
                            viewModel.resetForNewStart("rogaine")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Рогейн") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { confirmNewStart = false }) { Text("Отмена") }
            },
        )
    }
    if (section == AppSection.Chip) stationWritePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::cancelStationWrite,
            title = { Text("Записать станцию?") },
            text = {
                Text(stationWriteConfirmText(prompt.program, prompt.controlNumber))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmStationWrite) {
                    Text("Записать", color = Color(0xFF8B3A3A))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelStationWrite) { Text("Отмена") }
            },
        )
    }
    issueOverwritePrompt?.let { prompt ->
        val title = when (prompt.kind) {
            IssueOverwriteKind.CourseAlreadyIssued -> "Перезаписать чип?"
            IssueOverwriteKind.NumberBoundOtherChip -> "Номер уже на другом чипе"
            IssueOverwriteKind.ChipBoundOtherNumber -> "Этот чип уже выдан"
            IssueOverwriteKind.NumberIssuedUnknownChip -> "Номер уже выдан"
        }
        val body = when (prompt.kind) {
            IssueOverwriteKind.CourseAlreadyIssued -> {
                val what = if (isRogaine(settings.sportKind)) "формата" else "дистанции"
                "Чип уже готов для $what ${prompt.issuedCourse}.\nПерезаписать чип?"
            }
            IssueOverwriteKind.NumberBoundOtherChip ->
                "№ ${prompt.id} уже привязан к другому чипу (${prompt.boundUid}).\n" +
                    "Записать этот номер на текущий чип?"
            IssueOverwriteKind.ChipBoundOtherNumber ->
                "Этот чип уже выдан как № ${prompt.otherId}.\nЗаписать на него № ${prompt.id}?"
            IssueOverwriteKind.NumberIssuedUnknownChip ->
                "№ ${prompt.id} уже отмечен как выданный, UID неизвестен (возможно, другой телефон).\n" +
                    "Записать на этот чип?"
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelIssueOverwrite,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmIssueOverwrite) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelIssueOverwrite) { Text("Нет") }
            },
        )
    }
    finishNumberConflictPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFinishNumberConflict,
            title = { Text("Два чипа с № ${prompt.logicalId}") },
            text = {
                Text(
                    "Логический номер ${prompt.logicalId} уже есть в финише с другого чипа " +
                        "(${prompt.otherUid.ifBlank { "UID неизвестен" }}). " +
                        "Текущий чип (${prompt.newUid.ifBlank { "без UID" }}) тоже сохранён.\n\n" +
                        "Оба результата помечены как конфликт. В протоколе смените номер у одного из них.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissFinishNumberConflict) { Text("Понятно") }
            },
        )
    }
    issueFillPrompt?.let { prompt ->
        var fillName by remember(prompt.id, prompt.name) { mutableStateOf(prompt.name) }
        var fillCourse by remember(prompt.id, prompt.course) { mutableStateOf(prompt.course) }
        AlertDialog(
            onDismissRequest = viewModel::skipIssueFill,
            title = { Text("№ ${prompt.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val courseTitle = if (isRogaine(settings.sportKind)) "Формат" else "Дистанция"
                    val courseHint = if (isRogaine(settings.sportKind)) {
                        "Чип выдан. ФИО и формат можно указать сейчас или позже."
                    } else {
                        "Чип выдан. ФИО и дистанцию можно указать сейчас или позже."
                    }
                    Text(courseHint)
                    OutlinedTextField(
                        value = fillName,
                        onValueChange = { fillName = it },
                        singleLine = true,
                        label = { Text("ФИО") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (courses.isNotEmpty()) {
                        CourseChips(courseTitle, courses.map { it.name }, fillCourse) { fillCourse = it }
                    } else {
                        OutlinedTextField(
                            value = fillCourse,
                            onValueChange = { fillCourse = it },
                            singleLine = true,
                            label = { Text(courseTitle) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmIssueFill(fillName, fillCourse) }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipIssueFill) { Text("Позже") }
            },
        )
    }
    finishMissingPrompt?.let { prompt ->
        val who = prompt.dump.logicalId?.let { "№ $it" } ?: "без номера"
        AlertDialog(
            onDismissRequest = viewModel::skipMissingFinish,
            title = { Text("Нет отметки финиша") },
            text = {
                Text(
                    "На чипе $who нет КП ${prompt.finishCp} (финиш).\n" +
                        "Поставить время считывания ${prompt.finish.time} как финишную отметку?",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMissingFinish) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipMissingFinish) { Text("Нет") }
            },
        )
    }
    finishReplacePrompt?.let { prompt ->
        val chipLabel = prompt.logicalId?.toString() ?: "—"
        val courseLabel = prompt.courseName.ifBlank { "без дистанции" }
        AlertDialog(
            onDismissRequest = viewModel::cancelFinishReplace,
            title = { Text("Перезаписать данные?") },
            text = {
                Text(
                    "Данные с чипа $chipLabel уже загружены для дистанции $courseLabel.\nПерезаписать?",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFinishReplace) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFinishReplace) { Text("Нет") }
            },
        )
    }
    finishCsvPrompt?.let { prompt ->
        val names = prompt.conflictLabels.joinToString(", ")
        val extra = (prompt.conflictCount - prompt.conflictLabels.size).coerceAtLeast(0)
        val who = if (extra > 0) "$names и ещё $extra" else names
        val addPart = if (prompt.addedCount > 0) {
            "Новых будет добавлено ${prompt.addedCount}. "
        } else {
            ""
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelFinishCsvImport,
            title = { Text("Эти результаты уже есть") },
            text = {
                Text(
                    "В CSV уже считаны на этом телефоне: $who.\n" +
                        addPart +
                        "Перезаписать их или добавить только новых? Результаты, которых нет в файле, не изменятся.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFinishCsvOverwrite) { Text("Перезаписать") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = viewModel::confirmFinishCsvNewOnly) { Text("Только новые") }
                    TextButton(onClick = viewModel::cancelFinishCsvImport) { Text("Отмена") }
                }
            },
        )
    }
    printPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::skipPrint,
            title = { Text("Распечатать результат?") },
            text = { Text(prompt.summary) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPrint) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::skipPrint) { Text("Нет") }
            },
        )
    }
    if (overwritePrompt) {
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwrite,
            title = { Text("Заменить публикацию?") },
            text = { Text("На сайте уже есть старт с этим slug. Повторная отправка заменит результаты.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmOverwrite) { Text("Заменить") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelOverwrite) { Text("Отмена") }
            },
        )
    }
    stationCountdown?.let { StationCountdownOverlay(it) }
    }
}

@Composable
private fun ChipSection(
    viewModel: ChipQueueViewModel,
    items: List<QueuedChipEntity>,
    courses: List<StartCourse>,
    query: String,
    selected: StartPerson?,
    selectedId: Int?,
    addingNew: Boolean,
    newNumber: String,
    newName: String,
    newCourse: String,
    extraCourse: String,
    fillOpen: Boolean,
    testCourse: String,
    chipReady: Boolean,
    chipLogicalId: Int?,
    people: List<StartPerson>,
    busy: Boolean,
    mode: ChipMode,
    stationProgram: StationProgram,
    stationInspect: String?,
    stationControlNumber: String,
    onStationProgram: (StationProgram) -> Unit,
    onStationControlNumber: (String) -> Unit,
    onDeleteQueued: (String) -> Unit,
    liveReady: Boolean,
    sportKind: String,
    chipLog: ChipMemoryLog?,
    onShareChipLog: () -> Unit,
    onReadChipDump: () -> Unit,
    chipStationLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val hideKeyboardOnSelect = rememberHideKeyboardOnSelect(viewModel::selectPerson)
    val newTeamWith by viewModel.newTeamWith.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<QueuedChipEntity?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = when (mode) {
                ChipMode.Issue -> 0
                ChipMode.Finish -> 1
                ChipMode.Station -> 2
                ChipMode.Tests -> 3
            },
            edgePadding = 8.dp,
        ) {
            Tab(
                selected = mode == ChipMode.Issue,
                onClick = { viewModel.setMode(ChipMode.Issue) },
                text = { Text("Выдача") },
            )
            Tab(
                selected = mode == ChipMode.Finish,
                onClick = { viewModel.setMode(ChipMode.Finish) },
                text = { Text("Финиш") },
            )
            Tab(
                selected = mode == ChipMode.Station,
                onClick = { viewModel.setMode(ChipMode.Station) },
                text = { Text("Станция") },
            )
            Tab(
                selected = mode == ChipMode.Tests,
                onClick = { viewModel.setMode(ChipMode.Tests) },
                text = { Text("Тесты") },
            )
        }
        if (mode == ChipMode.Issue) {
            IssuePanel(
                query = query,
                people = viewModel.suggestions(),
                roster = people,
                items = items,
                selected = selected,
                selectedId = selectedId,
                courses = courses.map { it.name },
                addingNew = addingNew,
                newNumber = newNumber,
                newName = newName,
                newCourse = newCourse,
                extraCourse = extraCourse,
                newTeamWith = newTeamWith,
                fillOpen = fillOpen,
                sportKind = sportKind,
                onQuery = viewModel::onQuery,
                onSelect = hideKeyboardOnSelect,
                onClearSelection = viewModel::clearSelection,
                onNewNumber = viewModel::onNewNumber,
                onNewName = viewModel::onNewName,
                onNewCourse = viewModel::onNewCourse,
                onNewTeamWith = viewModel::onNewTeamWith,
                onCurrentCourse = viewModel::setCurrentCourse,
                onExtraCourse = viewModel::onExtraCourse,
                onAddCourse = viewModel::addSelectedCourse,
                modifier = Modifier.weight(1f),
            )
        } else if (mode == ChipMode.Station) {
            StationPanel(
                program = stationProgram,
                inspect = stationInspect,
                controlNumber = stationControlNumber,
                onProgram = onStationProgram,
                onControlNumber = onStationControlNumber,
                modifier = Modifier.weight(1f),
            )
        } else if (mode == ChipMode.Tests) {
            TestsPanel(
                people = people,
                chipLogicalId = chipLogicalId,
                courses = courses,
                testCourse = testCourse,
                chipReady = chipReady,
                busy = busy,
                sportKind = sportKind,
                chipLog = chipLog,
                chipStationLabel = chipStationLabel,
                onTestCourse = viewModel::onTestCourse,
                onCorrect = viewModel::writeCorrectCourse,
                onWrong = viewModel::writeWrongCourse,
                onRandomRogaine = viewModel::writeRandomRogaineCourse,
                onReadChipDump = onReadChipDump,
                onShareChipLog = onShareChipLog,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (items.isEmpty() && !busy) {
                Text(
                    text = "Очередь пуста. Приложите чип на финише.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color(0xFF5C5C5C),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                items(items, key = { it.id }) { item ->
                    SwipeFinishItem(item, onAskDelete = { confirmDelete = item })
                }
            }
        }
    }
    confirmDelete?.let { item ->
        val who = finishQueueWho(item)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить из финиша?") },
            text = {
                Text(
                    buildString {
                        append("$who будет убран из финишного протокола.")
                        if (liveReady) {
                            append(" Запись также пропадёт из online на сайте.")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDeleteQueued(item.id)
                    },
                ) { Text("Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }
}

private fun topTitle(section: AppSection, morePage: MorePage, sportKind: String): String = when (section) {
    AppSection.Chip -> "Чип"
    AppSection.Protocol -> "Стартовый протокол"
    AppSection.Results -> "Результаты"
    AppSection.More -> when (morePage) {
        MorePage.Menu -> "Ещё"
        MorePage.Starts -> "Старты"
        MorePage.Printer -> "Принтер"
        MorePage.Removed -> if (isRogaine(sportKind)) "Общие корректировки" else "Снятые КП"
        MorePage.Exclusions -> "Исключение перегонов"
        MorePage.Corrections -> "Персональные корректировки"
        MorePage.Publish -> "Публикация на сайт"
        MorePage.Settings -> "Настройки старта"
    }
}

@Composable
private fun IssuePanel(
    query: String,
    people: List<StartPerson>,
    roster: List<StartPerson>,
    items: List<QueuedChipEntity>,
    selected: StartPerson?,
    selectedId: Int?,
    courses: List<String>,
    addingNew: Boolean,
    newNumber: String,
    newName: String,
    newCourse: String,
    extraCourse: String,
    newTeamWith: String,
    fillOpen: Boolean,
    sportKind: String = SPORT_ORIENT,
    onQuery: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onClearSelection: () -> Unit,
    onNewNumber: (String) -> Unit,
    onNewName: (String) -> Unit,
    onNewCourse: (String) -> Unit,
    onNewTeamWith: (String) -> Unit,
    onCurrentCourse: (String) -> Unit,
    onExtraCourse: (String) -> Unit,
    onAddCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searching = query.isNotBlank()
    val numberFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(fillOpen) {
        if (!fillOpen) {
            delay(40)
            runCatching { numberFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val usedIds = remember(roster) { roster.map { it.id }.toSet() }
    val usedTeamIds = remember(roster) { roster.map { it.teamId } }
    val nextChipId = nextFreeLogicalId(usedIds)
    val nextTeamId = nextFreeTeamId(usedTeamIds)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newNumber,
                onValueChange = onNewNumber,
                singleLine = true,
                label = { Text("Номер") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(0.28f)
                    .focusRequester(numberFocus),
            )
            IconButton(
                onClick = { nextChipId?.let { onNewNumber(it.toString()) } },
                enabled = nextChipId != null,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Следующий свободный номер")
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                label = { Text("Фамилия") },
                modifier = Modifier.weight(0.52f),
            )
        }
        val rogaine = isRogaine(sportKind)
        val courseTitle = if (rogaine) "Формат" else "Дистанция"
        val laterHint = if (rogaine) {
            "Формат можно указать сейчас или после выдачи."
        } else {
            "Дистанцию можно указать сейчас или после выдачи."
        }
        if (!searching) {
            if (addingNew && newNumber.isNotBlank()) {
                EditorCard(
                    title = "№ $newNumber · нового нет в протоколе",
                    onClose = onClearSelection,
                ) {
                    Text(
                        if (rogaine) {
                            "Приложите чип — номер запишется, участник появится в протоколе. ФИО и формат можно указать сейчас или позже."
                        } else {
                            "Приложите чип — номер запишется, участник появится в протоколе. ФИО и дистанцию можно указать сейчас или позже."
                        },
                        color = Color(0xFF5C5C5C),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = onNewName,
                        singleLine = true,
                        label = { Text("ФИО") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (courses.isNotEmpty()) {
                        CourseChips(courseTitle, courses, newCourse, onNewCourse)
                    }
                    if (rogaine) {
                        IssueTeamField(newTeamWith, onNewTeamWith, nextTeamId)
                    }
                }
            } else if (selected != null) {
                EditorCard(
                    title = "№ ${selected.id} · ${selected.displayName}",
                    onClose = onClearSelection,
                ) {
                    val own = selected.courses.ifEmpty {
                        listOfNotNull(selected.currentCourse.takeIf { it.isNotBlank() })
                    }
                    val extras = courses.filter { name -> own.none { it.equals(name, true) } }
                    val formatOptions = buildList {
                        addAll(own)
                        if (rogaine) {
                            extras.forEach { name ->
                                if (none { it.equals(name, true) }) add(name)
                            }
                        }
                    }
                    if (formatOptions.isNotEmpty()) {
                        CourseChips(courseTitle, formatOptions, selected.currentCourse, onCurrentCourse)
                    } else if (courses.isNotEmpty()) {
                        if (selected.currentCourse.isBlank()) {
                            Text(
                                laterHint,
                                color = Color(0xFF5C5C5C),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        CourseChips(courseTitle, courses, selected.currentCourse, onCurrentCourse)
                    } else {
                        Text(
                            laterHint,
                            color = Color(0xFF5C5C5C),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = extraCourse,
                            onValueChange = onExtraCourse,
                            singleLine = true,
                            label = { Text(courseTitle) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CompactTextButton("Указать", onAddCourse, enabled = extraCourse.isNotBlank())
                    }
                    if (rogaine) {
                        val teamId = selected.teamId
                        if (teamId > 0) {
                            val mates = roster
                                .filter { it.teamId == teamId && it.id != selected.id }
                                .sortedBy { it.id }
                                .joinToString(" · ") { "${it.id} ${it.displayName}" }
                            Text(
                                if (mates.isEmpty()) "Команда Т$teamId" else "Команда Т$teamId · $mates",
                                color = Color(0xFF5C5C5C),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IssueTeamField(newTeamWith, onNewTeamWith, nextTeamId)
                    }
                    if (!rogaine && extras.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                CourseChips("Ещё дистанция", extras, extraCourse, onExtraCourse)
                            }
                            CompactTextButton(
                                "Добавить",
                                onAddCourse,
                                enabled = extraCourse.isNotBlank(),
                            )
                        }
                    }
                }
            }
        }
        PersonHits(
            people = people,
            items = items,
            emptyCourseLabel = if (rogaine) "без формата" else "без дистанции",
            selectedId = selectedId,
            emptyText = if (searching) "Никого не найдено" else "Протокол пуст",
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IssueTeamField(value: String, onValue: (String) -> Unit, nextId: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            label = { Text("№ команды") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Green)
                .clickable { onValue(nextId.toString()) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = nextId.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun StationPanel(
    program: StationProgram,
    inspect: String?,
    controlNumber: String,
    onProgram: (StationProgram) -> Unit,
    onControlNumber: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (program == StationProgram.Inspect) {
                "Чтение без записи. Приложите чип — появится режим, который уже записан."
            } else {
                "Программа для полевой станции. Чип после записи нельзя использовать как чип участника."
            },
            color = if (program == StationProgram.Inspect) Color(0xFF5C5C5C) else Color(0xFF8B3A3A),
            style = MaterialTheme.typography.bodyMedium,
        )
        StationChoiceGroup(
            title = "Роль станции",
            items = listOf(
                StationProgram.Control,
                StationProgram.Start,
                StationProgram.Finish,
                StationProgram.Clear,
            ),
            program = program,
            onProgram = onProgram,
        )
        if (program == StationProgram.Control) {
            OutlinedTextField(
                value = controlNumber,
                onValueChange = onControlNumber,
                singleLine = true,
                label = { Text("Номер КП") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StationChoiceGroup(
            title = "Режим",
            items = listOf(
                StationProgram.Active,
                StationProgram.Passive,
                StationProgram.Battery,
                StationProgram.Inspect,
            ),
            program = program,
            onProgram = onProgram,
        )
        if (program == StationProgram.Inspect && inspect != null) {
            Text(
                inspect,
                color = Color(0xFF1F4B2E),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stationProgramHint(program),
            color = Color(0xFF5C5C5C),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StationChoiceGroup(
    title: String,
    items: List<StationProgram>,
    program: StationProgram,
    onProgram: (StationProgram) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onProgram(item) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = program == item,
                onClick = { onProgram(item) },
            )
            Text(
                stationProgramTitle(item),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun stationProgramTitle(item: StationProgram): String = when (item) {
    StationProgram.Control -> "КП"
    StationProgram.Start -> "Старт"
    StationProgram.Finish -> "Финиш"
    StationProgram.Clear -> "Очистка"
    StationProgram.Active -> "Активный режим"
    StationProgram.Passive -> "Пассивный режим"
    StationProgram.Battery -> "Проверка батареи"
    StationProgram.Inspect -> "Проверка"
}

private fun stationProgramHint(program: StationProgram): String = when (program) {
    StationProgram.Control ->
        "Введите номер КП, приложите чип и подтвердите запись. На чип уйдёт время на 3 секунды вперёд. Крупные цифры покажут, когда приложить чип к станции."
    StationProgram.Start, StationProgram.Finish, StationProgram.Clear ->
        "Выберите тип, приложите чип и подтвердите запись. На чип уйдёт время на 3 секунды вперёд. Крупные цифры покажут, когда приложить чип к станции."
    StationProgram.Active ->
        "Станция будет постоянно искать чип, без нажатия кнопки. Сильно расходует батарею. Карта многоразовая."
    StationProgram.Passive ->
        "Станция снова ждёт нажатия кнопки. Карта многоразовая — удобно на снятие дистанции."
    StationProgram.Battery ->
        "После отметки на станции около 30 с горит светодиод, затем 1–15 миганий. Меньше 7 — заменить батарею. Часы станции сбросятся. Карта одноразовая."
    StationProgram.Inspect ->
        "Приложите чип. Запись не выполняется: на экране появится режим, который уже записан."
}

@Composable
private fun StationCountdownOverlay(countdown: StationClockCountdown) {
    var nowMs by remember(countdown.deadlineEpochMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(countdown.deadlineEpochMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(40)
        }
    }
    val remainingMs = countdown.deadlineEpochMs - nowMs
    val seconds = if (remainingMs <= 0) 0 else ((remainingMs + 999) / 1000).toInt()
    val atZero = remainingMs <= 0
    val ready = atZero && countdown.writeFinished
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF01A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(
                    countdown.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    seconds.toString(),
                    color = if (ready) Color(0xFF7CFF6B) else Color(0xFFFFE14A),
                    fontSize = 168.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    when {
                        ready -> "Сейчас приложите чип к станции"
                        atZero -> "Запись ещё идёт. Не убирайте чип с телефона."
                        else -> "На нуле приложите чип к станции. До нуля не убирайте его."
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun stationWriteConfirmText(program: StationProgram, controlNumber: Int?): String {
    if (program == StationProgram.Inspect) return "Чип не изменится."
    val wipe =
        "Отметки и номер участника на этом чипе будут стёрты. Операция только из раздела «Станция»."
    val body = when (program) {
        StationProgram.Control ->
            "На чип будет записана программа станции КП ${controlNumber ?: "—"}."
        StationProgram.Start, StationProgram.Finish, StationProgram.Clear ->
            "На чип будет записана программа станции «${program.title}» (КП ${program.command?.controlCp})."
        StationProgram.Inspect ->
            "Чип не изменится."
        StationProgram.Active ->
            "На чип будет записана карта активного режима.\nСтанция будет постоянно искать чип, без нажатия кнопки. Сильно расходует батарею. Карта многоразовая."
        StationProgram.Passive ->
            "На чип будет записана карта пассивного режима.\nСтанция снова будет ждать нажатия кнопки. Карта многоразовая."
        StationProgram.Battery ->
            "На чип будет записана карта сброса и проверки батареи.\nПосле отметки на станции около 30 секунд горит светодиод, затем 1–15 миганий. Меньше 7 — заменить батарею. Часы станции сбросятся. Карта одноразовая."
    }
    return "$body\n\n$wipe"
}

@Composable
private fun TestsPanel(
    people: List<StartPerson>,
    chipLogicalId: Int?,
    courses: List<StartCourse>,
    testCourse: String,
    chipReady: Boolean,
    busy: Boolean,
    sportKind: String,
    chipLog: ChipMemoryLog?,
    chipStationLabel: String? = null,
    onTestCourse: (String) -> Unit,
    onCorrect: () -> Unit,
    onWrong: () -> Unit,
    onRandomRogaine: () -> Unit,
    onReadChipDump: () -> Unit,
    onShareChipLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rogaine = isRogaine(sportKind)
    val person = people.find { it.id == chipLogicalId }
    val courseNames = courses.map { it.name }
    val currentControls = courses.find { it.name.equals(testCourse, ignoreCase = true) }?.controls.orEmpty()
        .filter { it != CP_START && it != CP_FINISH }
    val canWriteOrient = chipReady && person != null && testCourse.isNotBlank() && currentControls.isNotEmpty() && !busy
    val canWriteRogaine = chipReady && person != null && !busy
    val canDump = chipReady && !busy
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            !chipReady -> Text(
                "Приложите чип. Участник определится по логическому номеру из стартового протокола. Служебный чип тоже можно дампить.",
                color = Color(0xFF5C5C5C),
                style = MaterialTheme.typography.bodyMedium,
            )
            chipStationLabel != null -> Text(
                "Служебный чип: $chipStationLabel.",
                color = Color(0xFF5C5C5C),
                style = MaterialTheme.typography.bodyMedium,
            )
            chipLogicalId == null -> Text(
                "На чипе нет номера. Сначала выдайте чип на вкладке «Выдача».",
                color = Color(0xFF8B3A3A),
                style = MaterialTheme.typography.bodyMedium,
            )
            person == null -> Text(
                "№ $chipLogicalId нет в стартовом протоколе.",
                color = Color(0xFF8B3A3A),
                style = MaterialTheme.typography.bodyMedium,
            )
            rogaine -> {
                Text("${person.id} · ${person.displayName}", style = MaterialTheme.typography.titleMedium)
                if (person.currentCourse.isNotBlank()) {
                    Text("Формат: ${person.currentCourse}", color = Color(0xFF5C5C5C), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "На чип запишутся старт, случайный набор КП и финиш. Повторная запись даст другой набор.",
                    color = Color(0xFF5C5C5C),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onRandomRogaine,
                    enabled = canWriteRogaine,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Случайные КП")
                }
            }
            else -> {
                Text("${person.id} · ${person.displayName}", style = MaterialTheme.typography.titleMedium)
                val names = person.courses.ifEmpty { courseNames }
                    .ifEmpty { listOfNotNull(testCourse.takeIf { it.isNotBlank() }) }
                val extra = courseNames.filter { name -> names.none { it.equals(name, true) } }
                CourseChips("Дистанция", names + extra, testCourse, onTestCourse)
                if (currentControls.isEmpty()) {
                    Text(
                        "Нет порядка КП. Загрузите дистанции на вкладке «Протокол».",
                        color = Color(0xFF8B3A3A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "КП: ${currentControls.joinToString(" → ")}",
                        color = Color(0xFF5C5C5C),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onCorrect,
                        enabled = canWriteOrient,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Правильное")
                    }
                    Button(
                        onClick = onWrong,
                        enabled = canWriteOrient,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B3A3A)),
                    ) {
                        Text("Ошибочное")
                    }
                }
            }
        }
        Button(
            onClick = onReadChipDump,
            enabled = canDump,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Считать дамп")
        }
        ChipDumpSection(chipLog = chipLog, chipReady = chipReady, onShare = onShareChipLog)
    }
}

@Composable
private fun ChipDumpSection(
    chipLog: ChipMemoryLog?,
    chipReady: Boolean,
    onShare: () -> Unit,
) {
    Text("Полный лог чипа", style = MaterialTheme.typography.titleSmall)
    Text(
        "Полный дамп читается только по кнопке «Считать дамп». Финиш по-прежнему читает только до указателя.",
        color = Color(0xFF5C5C5C),
        style = MaterialTheme.typography.bodySmall,
    )
    if (chipLog == null) {
        Text(
            if (chipReady) "Чип на месте. Нажмите «Считать дамп», не убирая его."
            else "Приложите чип, затем нажмите «Считать дамп».",
            color = Color(0xFF5C5C5C),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val kind = chipLog.kindLine
    if (kind != null) {
        Text(
            kind,
            color = Color(0xFF1B4D3E),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val hint = chipLog.hint
    if (hint != null) {
        Text(
            "Внимание: $hint",
            color = Warn,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else if (kind == null) {
        Text(
            "Подозрительных ячеек нет. Лог сохранён.",
            color = Color(0xFF5C5C5C),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "Лог сохранён.",
            color = Color(0xFF5C5C5C),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Поделиться логом")
    }
    Text(
        chipLog.format(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = Color(0xFF333333),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CourseChips(
    label: String,
    courses: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Text(label, color = Color(0xFF5C5C5C), style = MaterialTheme.typography.labelSmall)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        courses.forEach { name ->
            FilterChip(
                selected = name.equals(current, ignoreCase = true),
                onClick = { onSelect(name) },
                label = { Text(name, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun CompactBarAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun StatusStrip(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = compactStatus(text),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Скрыть")
        }
    }
}

@Composable
private fun PersonHits(
    people: List<StartPerson>,
    items: List<QueuedChipEntity>,
    selectedId: Int?,
    emptyText: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    emptyCourseLabel: String = "без дистанции",
) {
    val finishedById = remember(items) {
        items.mapNotNull { item -> item.logicalId?.let { id -> id to item.courseName } }
            .groupBy({ it.first }, { it.second })
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        if (people.isEmpty()) {
            item {
                Text(emptyText, color = Color(0xFF5C5C5C), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(people, key = { it.id }) { person ->
            PersonProtocolLine(
                person = person,
                status = personProtocolStatus(person, finishedById[person.id].orEmpty()),
                selected = person.id == selectedId,
                onClick = { onSelect(person.id) },
                emptyCourseLabel = emptyCourseLabel,
            )
        }
    }
}

@Composable
private fun rememberHideKeyboardOnSelect(onSelect: (Int) -> Unit): (Int) -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return { id ->
        focus.clearFocus()
        keyboard?.hide()
        onSelect(id)
    }
}

private fun shouldShowStatus(status: String, section: AppSection): Boolean {
    val text = status.trim()
    if (text.isEmpty()) return false
    if (section != AppSection.Chip && isChipOperationPrompt(text)) return false
    return true
}

private fun isChipOperationPrompt(text: String): Boolean {
    val t = text.trim()
    val lower = t.lowercase()
    return lower.startsWith("приложите") ||
        lower.startsWith("введите номер и приложите") ||
        lower.startsWith("выберите участника") ||
        lower.startsWith("укажите дистанцию") ||
        lower.startsWith("чип на месте") ||
        lower.contains("приложите чип") ||
        lower.contains("отметьтесь")
}

private fun compactStatus(text: String): String {
    val t = text.trim()
    return when {
        t.contains("EACCES", ignoreCase = true) ||
            t.contains("Permission denied", ignoreCase = true) ->
            "Нет доступа к файлу протокола по USB"
        t.contains("open failed", ignoreCase = true) ->
            "Не удалось открыть файл протокола"
        t.length > 120 -> t.take(117) + "…"
        else -> t
    }
}

private fun finishQueueWho(item: QueuedChipEntity): String {
    val name = item.personName.trim()
    val num = item.logicalId?.let { "№ $it" }
    return when {
        name.isNotEmpty() && num != null -> "$num $name"
        name.isNotEmpty() -> name
        num != null -> num
        else -> "участник без номера"
    }
}

@Composable
private fun SwipeFinishItem(item: QueuedChipEntity, onAskDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onAskDelete()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF8B3A3A))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Удалить", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        QueueCard(item)
    }
}

@Composable
private fun QueueCard(item: QueuedChipEntity) {
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val title = when {
        item.personName.isNotBlank() -> "${item.logicalId ?: "—"} · ${item.personName}"
        item.logicalId != null -> "Логический номер ${item.logicalId}"
        else -> "Без логического номера"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (item.courseName.isNotBlank()) {
                Text(item.courseName)
            }
            Text("${item.punchCount} отметок · ${item.firstTime ?: "—"} – ${item.lastTime ?: "—"}")
            ChipCellLog.hintFromRawHex(item.rawHex)?.let { hint ->
                Text(
                    "Проверьте чип: $hint. Полный лог — вкладка «Тесты».",
                    color = Warn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("UID ${item.uid}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                "в очереди с ${timeFmt.format(Date(item.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}
