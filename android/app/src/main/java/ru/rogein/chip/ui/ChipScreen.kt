package ru.rogein.chip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.rogein.chip.queue.QueuedChipEntity
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Green = Color(0xFF1B4D3E)
private val Cream = Color(0xFFF4F1EA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipScreen(viewModel: ChipQueueViewModel, csvPicker: CsvPicker) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val corrections by viewModel.corrections.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val morePage by viewModel.morePage.collectAsStateWithLifecycle()
    val overwritePrompt by viewModel.overwritePrompt.collectAsStateWithLifecycle()
    val finishReplacePrompt by viewModel.finishReplacePrompt.collectAsStateWithLifecycle()
    val issueOverwritePrompt by viewModel.issueOverwritePrompt.collectAsStateWithLifecycle()
    val stationWritePrompt by viewModel.stationWritePrompt.collectAsStateWithLifecycle()
    val stationProgram by viewModel.stationProgram.collectAsStateWithLifecycle()
    val stationControlNumber by viewModel.stationControlNumber.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val addingNew by viewModel.addingNew.collectAsStateWithLifecycle()
    val newNumber by viewModel.newNumber.collectAsStateWithLifecycle()
    val newName by viewModel.newName.collectAsStateWithLifecycle()
    val newCourse by viewModel.newCourse.collectAsStateWithLifecycle()
    val extraCourse by viewModel.extraCourse.collectAsStateWithLifecycle()
    val testCourse by viewModel.testCourse.collectAsStateWithLifecycle()
    val chipReady by viewModel.chipReady.collectAsStateWithLifecycle()
    val chipLogicalId by viewModel.chipLogicalId.collectAsStateWithLifecycle()
    val selected = people.find { it.id == selectedId }
    val results = remember(items, people, courses, settings, corrections, exclusions) {
        viewModel.scoredResults(items, people, courses, settings, corrections, exclusions)
    }
    var confirmNewStart by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hideKeyboardOnSelect = rememberHideKeyboardOnSelect(viewModel::selectPerson)
    val showStatus = !imeVisible && shouldShowStatus(status, section)

    Box {
    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        topTitle(section, morePage),
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
            if (!imeVisible) {
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
                    testCourse = testCourse,
                    chipReady = chipReady,
                    chipLogicalId = chipLogicalId,
                    people = people,
                    busy = busy,
                    mode = mode,
                    stationProgram = stationProgram,
                    stationControlNumber = stationControlNumber,
                    onStationProgram = viewModel::setStationProgram,
                    onStationControlNumber = viewModel::onStationControlNumber,
                    onDeleteQueued = viewModel::deleteQueued,
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
                    onQuery = viewModel::onQuery,
                    onSelect = hideKeyboardOnSelect,
                    onNew = viewModel::startNewPerson,
                    onClearSelection = viewModel::clearSelection,
                    onNewNumber = viewModel::onNewNumber,
                    onNewName = viewModel::onNewName,
                    onNewCourse = viewModel::onNewCourse,
                    onSavePerson = viewModel::saveManualPerson,
                    onDeletePerson = viewModel::deletePerson,
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
                    onSaveCourse = viewModel::saveCourse,
                    onDeleteCourse = viewModel::deleteCourse,
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
                    onSendLive = viewModel::sendLiveNow,
                    onAddCp = viewModel::addMissingCp,
                    onAssignCourse = viewModel::assignCourse,
                    onDeleteCorrection = viewModel::deleteCorrection,
                )
                AppSection.More -> when (morePage) {
                    MorePage.Menu -> MoreMenu(onOpen = viewModel::setMorePage)
                    MorePage.Removed -> RemovedCpsScreen(
                        courses = courses,
                        corrections = corrections,
                        onAdd = viewModel::addRemovedCp,
                        onUndo = viewModel::deleteCorrection,
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
                        onAddCp = viewModel::addMissingCp,
                        onAssignCourse = viewModel::assignCourse,
                        onDeleteCorrection = viewModel::deleteCorrection,
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
            title = { Text("Начать новый старт?") },
            text = {
                Text(
                    "Будут безвозвратно удалены стартовый протокол, дистанции, очередь финиша и корректировки. " +
                        "Адрес сайта и токен останутся. Это нужно перед загрузкой следующего старта.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNewStart = false
                        viewModel.resetForNewStart()
                    },
                ) {
                    Text("Удалить всё", color = Color(0xFF8B3A3A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewStart = false }) {
                    Text("Отмена")
                }
            },
        )
    }
    stationWritePrompt?.let { prompt ->
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
        AlertDialog(
            onDismissRequest = viewModel::cancelIssueOverwrite,
            title = { Text("Перезаписать чип?") },
            text = {
                Text("Чип уже готов для дистанции ${prompt.issuedCourse}.\nПерезаписать чип?")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmIssueOverwrite) { Text("Да") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelIssueOverwrite) { Text("Нет") }
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
    testCourse: String,
    chipReady: Boolean,
    chipLogicalId: Int?,
    people: List<StartPerson>,
    busy: Boolean,
    mode: ChipMode,
    stationProgram: StationProgram,
    stationControlNumber: String,
    onStationProgram: (StationProgram) -> Unit,
    onStationControlNumber: (String) -> Unit,
    onDeleteQueued: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hideKeyboardOnSelect = rememberHideKeyboardOnSelect(viewModel::selectPerson)
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
                selected = selected,
                selectedId = selectedId,
                courses = courses.map { it.name },
                addingNew = addingNew,
                newNumber = newNumber,
                newName = newName,
                newCourse = newCourse,
                extraCourse = extraCourse,
                onQuery = viewModel::onQuery,
                onSelect = hideKeyboardOnSelect,
                onNew = viewModel::startNewPerson,
                onClearSelection = viewModel::clearSelection,
                onNewNumber = viewModel::onNewNumber,
                onNewName = viewModel::onNewName,
                onNewCourse = viewModel::onNewCourse,
                onCurrentCourse = viewModel::setCurrentCourse,
                onExtraCourse = viewModel::onExtraCourse,
                onAddCourse = viewModel::addSelectedCourse,
                modifier = Modifier.weight(1f),
            )
        } else if (mode == ChipMode.Station) {
            StationPanel(
                program = stationProgram,
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
                onTestCourse = viewModel::onTestCourse,
                onCorrect = viewModel::writeCorrectCourse,
                onWrong = viewModel::writeWrongCourse,
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
                    SwipeFinishItem(item, onDelete = { onDeleteQueued(item.id) })
                }
            }
        }
    }
}

private fun topTitle(section: AppSection, morePage: MorePage): String = when (section) {
    AppSection.Chip -> "Чип"
    AppSection.Protocol -> "Стартовый протокол"
    AppSection.Results -> "Результаты"
    AppSection.More -> when (morePage) {
        MorePage.Menu -> "Ещё"
        MorePage.Removed -> "Снятые КП"
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
    selected: StartPerson?,
    selectedId: Int?,
    courses: List<String>,
    addingNew: Boolean,
    newNumber: String,
    newName: String,
    newCourse: String,
    extraCourse: String,
    onQuery: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onClearSelection: () -> Unit,
    onNewNumber: (String) -> Unit,
    onNewName: (String) -> Unit,
    onNewCourse: (String) -> Unit,
    onCurrentCourse: (String) -> Unit,
    onExtraCourse: (String) -> Unit,
    onAddCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searching = query.isNotBlank()
    val numberFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(addingNew) {
        if (addingNew) {
            delay(40)
            runCatching { numberFocus.requestFocus() }
            keyboard?.show()
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Номер или фамилия") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!searching) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNew) { Text("Новый") }
                if (selected != null || addingNew) {
                    TextButton(onClick = onClearSelection) { Text("Снять выделение") }
                }
            }
            if (addingNew) {
                OutlinedTextField(
                    value = newNumber,
                    onValueChange = onNewNumber,
                    singleLine = true,
                    label = { Text("Номер чипа") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(numberFocus),
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = onNewName,
                    singleLine = true,
                    label = { Text("ФИО") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (newCourse.isBlank()) {
                    Text(
                        "Укажите дистанцию, чтобы выдать чип.",
                        color = Color(0xFF8B3A3A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CourseChips("Дистанция", courses, newCourse, onNewCourse)
            } else if (selected != null) {
                Text("${selected.id} · ${selected.name}", style = MaterialTheme.typography.titleMedium)
                if (selected.currentCourse.isBlank()) {
                    Text(
                        "Укажите дистанцию, чтобы выдать чип.",
                        color = Color(0xFF8B3A3A),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val names = (selected.courses + courses).distinct()
                CourseChips("Дистанция", names, selected.currentCourse, onCurrentCourse)
                val extras = courses.filter { name -> selected.courses.none { it.equals(name, true) } }
                if (selected.currentCourse.isBlank() || extras.isNotEmpty() || extraCourse.isNotEmpty()) {
                    if (selected.currentCourse.isBlank()) {
                        OutlinedTextField(
                            value = extraCourse,
                            onValueChange = onExtraCourse,
                            singleLine = true,
                            label = { Text("Или введите дистанцию") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = onAddCourse, enabled = extraCourse.isNotBlank()) {
                            Text("Указать дистанцию")
                        }
                    } else if (extras.isNotEmpty() || extraCourse.isNotEmpty()) {
                        CourseChips("Ещё дистанция", extras, extraCourse, onExtraCourse)
                        TextButton(onClick = onAddCourse, enabled = extraCourse.isNotBlank()) {
                            Text("Добавить дистанцию")
                        }
                    }
                }
            }
        }
        PersonHits(
            people = people,
            selectedId = selectedId,
            emptyText = if (searching) "Никого не найдено" else "Протокол пуст",
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StationPanel(
    program: StationProgram,
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
            "Программа для полевой станции. Чип после записи нельзя использовать как чип участника.",
            color = Color(0xFF8B3A3A),
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
            items = listOf(StationProgram.Active, StationProgram.Passive, StationProgram.Battery),
            program = program,
            onProgram = onProgram,
        )
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
}

private fun stationProgramHint(program: StationProgram): String = when (program) {
    StationProgram.Control ->
        "Введите номер КП, приложите чип и подтвердите запись. Вместе с ролью на станцию уйдёт текущее время телефона."
    StationProgram.Start, StationProgram.Finish, StationProgram.Clear ->
        "Выберите тип, приложите чип и подтвердите запись. Вместе с ролью на станцию уйдёт текущее время телефона."
    StationProgram.Active ->
        "Станция будет постоянно искать чип, без нажатия кнопки. Сильно расходует батарею. Карта многоразовая."
    StationProgram.Passive ->
        "Станция снова ждёт нажатия кнопки. Карта многоразовая — удобно на снятие дистанции."
    StationProgram.Battery ->
        "После отметки на станции около 30 с горит светодиод, затем 1–15 миганий. Меньше 7 — заменить батарею. Часы станции сбросятся. Карта одноразовая."
}

private fun stationWriteConfirmText(program: StationProgram, controlNumber: Int?): String {
    val wipe =
        "Отметки и номер участника на этом чипе будут стёрты. Операция только из раздела «Станция»."
    val body = when (program) {
        StationProgram.Control ->
            "На чип будет записана программа станции КП ${controlNumber ?: "—"}."
        StationProgram.Start, StationProgram.Finish, StationProgram.Clear ->
            "На чип будет записана программа станции «${program.title}» (КП ${program.command.controlCp})."
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
    onTestCourse: (String) -> Unit,
    onCorrect: () -> Unit,
    onWrong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = people.find { it.id == chipLogicalId }
    val courseNames = courses.map { it.name }
    val currentControls = courses.find { it.name.equals(testCourse, ignoreCase = true) }?.controls.orEmpty()
        .filter { it != CP_START && it != CP_FINISH }
    val canWrite = chipReady && person != null && testCourse.isNotBlank() && currentControls.isNotEmpty() && !busy
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            !chipReady -> Text(
                "Приложите чип. Участник определится по логическому номеру из стартового протокола.",
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
            else -> {
                Text("${person.id} · ${person.name}", style = MaterialTheme.typography.titleMedium)
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
                        enabled = canWrite,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Правильное")
                    }
                    Button(
                        onClick = onWrong,
                        enabled = canWrite,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B3A3A)),
                    ) {
                        Text("Ошибочное")
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseChips(
    label: String,
    courses: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Text(label, color = Color(0xFF5C5C5C))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        courses.forEach { name ->
            FilterChip(
                selected = name.equals(current, ignoreCase = true),
                onClick = { onSelect(name) },
                label = { Text(name) },
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
    selectedId: Int?,
    emptyText: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            val issuedMark = if (person.issued) " · выдан" else ""
            val courseMark = person.currentCourse.ifBlank { "без дистанции" }
            Text(
                text = "${person.id}  ${person.name}  $courseMark$issuedMark",
                color = when {
                    person.issued -> Green
                    person.currentCourse.isBlank() -> Color(0xFF8B3A3A)
                    else -> Color.Unspecified
                },
                style = if (person.id == selectedId) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(person.id) }
                    .padding(vertical = 8.dp),
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
    if (section != AppSection.Chip && isIdleChipPrompt(text)) return false
    return true
}

private fun isIdleChipPrompt(text: String): Boolean {
    return text.startsWith("Приложите") ||
        text.startsWith("Выберите участника") ||
        text.startsWith("Укажите дистанцию") ||
        text.startsWith("Чип на месте")
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

@Composable
private fun SwipeFinishItem(item: QueuedChipEntity, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
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
            Text("UID ${item.uid}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                "в очереди с ${timeFmt.format(Date(item.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}
