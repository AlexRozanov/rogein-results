package ru.rogein.chip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
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

private val Green = Color(0xFF1B4D3E)
private val Cream = Color(0xFFF4F1EA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipScreen(viewModel: ChipQueueViewModel) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val addingNew by viewModel.addingNew.collectAsStateWithLifecycle()
    val newNumber by viewModel.newNumber.collectAsStateWithLifecycle()
    val newName by viewModel.newName.collectAsStateWithLifecycle()
    val newCourse by viewModel.newCourse.collectAsStateWithLifecycle()
    val extraCourse by viewModel.extraCourse.collectAsStateWithLifecycle()
    val testCourse by viewModel.testCourse.collectAsStateWithLifecycle()
    val chipReady by viewModel.chipReady.collectAsStateWithLifecycle()
    val selected = people.find { it.id == selectedId }
    var confirmNewStart by remember { mutableStateOf(false) }

    Box {
    Scaffold(
        containerColor = Cream,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Малахит — чип") },
                actions = {
                    TextButton(onClick = viewModel::reloadStartList) {
                        Text("Обновить", color = Color.White)
                    }
                    TextButton(onClick = { confirmNewStart = true }) {
                        Text("Новый старт", color = Color.White)
                    }
                    if (mode == ChipMode.Finish && items.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearQueue) {
                            Text("Очистить", color = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Green,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = when (mode) {
                    ChipMode.Issue -> 0
                    ChipMode.Finish -> 1
                    ChipMode.Tests -> 2
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
                    selected = mode == ChipMode.Tests,
                    onClick = { viewModel.setMode(ChipMode.Tests) },
                    text = { Text("Тесты") },
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                    onSelect = viewModel::selectPerson,
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
            } else if (mode == ChipMode.Tests) {
                TestsPanel(
                    query = query,
                    people = viewModel.suggestions(),
                    selected = selected,
                    selectedId = selectedId,
                    courses = courses,
                    testCourse = testCourse,
                    chipReady = chipReady,
                    busy = busy,
                    onQuery = viewModel::onQuery,
                    onSelect = viewModel::selectPerson,
                    onClearSelection = viewModel::clearSelection,
                    onTestCourse = viewModel::onTestCourse,
                    onCorrect = viewModel::writeCorrectCourse,
                    onWrong = viewModel::writeWrongCourse,
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (items.isEmpty() && !busy) {
                    Text(
                        text = "Очередь пуста. Отметки остаются здесь, а копия для десктопа пишется в Загрузки/rogein.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = Color(0xFF5C5C5C),
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
                        QueueCard(item)
                    }
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
                    "Будут безвозвратно удалены стартовый протокол, дистанции и очередь финиша. " +
                        "Данные на телефоне потеряются. Это нужно перед загрузкой следующего старта.",
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newName,
                onValueChange = onNewName,
                singleLine = true,
                label = { Text("ФИО") },
                modifier = Modifier.fillMaxWidth(),
            )
            CourseChips("Дистанция", courses, newCourse, onNewCourse)
            OutlinedTextField(
                value = newCourse,
                onValueChange = onNewCourse,
                singleLine = true,
                label = { Text("Или введите дистанцию") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (selected != null) {
            Text("${selected.id} · ${selected.name}", style = MaterialTheme.typography.titleMedium)
            CourseChips("Текущая", selected.courses.ifEmpty { courses }, selected.currentCourse, onCurrentCourse)
            val extras = courses.filter { name -> selected.courses.none { it.equals(name, true) } }
            if (extras.isNotEmpty() || extraCourse.isNotEmpty()) {
                CourseChips("Ещё дистанция", extras, extraCourse, onExtraCourse)
                TextButton(onClick = onAddCourse, enabled = extraCourse.isNotBlank()) {
                    Text("Добавить дистанцию")
                }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(people, key = { it.id }) { person ->
                val issuedMark = if (person.issued) " · выдан" else ""
                val label = "${person.id}  ${person.name}  ${person.currentCourse}$issuedMark"
                Text(
                    text = label,
                    color = if (person.issued) Green else Color.Unspecified,
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
}

@Composable
private fun TestsPanel(
    query: String,
    people: List<StartPerson>,
    selected: StartPerson?,
    selectedId: Int?,
    courses: List<StartCourse>,
    testCourse: String,
    chipReady: Boolean,
    busy: Boolean,
    onQuery: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onClearSelection: () -> Unit,
    onTestCourse: (String) -> Unit,
    onCorrect: () -> Unit,
    onWrong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val courseNames = courses.map { it.name }
    val currentControls = courses.find { it.name.equals(testCourse, ignoreCase = true) }?.controls.orEmpty()
        .filter { it != CP_START && it != CP_FINISH }
    val canWrite = chipReady && selected != null && testCourse.isNotBlank() && currentControls.isNotEmpty() && !busy
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
        if (selected != null) {
            TextButton(onClick = onClearSelection) { Text("Снять выделение") }
            Text("${selected.id} · ${selected.name}", style = MaterialTheme.typography.titleMedium)
            val names = selected.courses.ifEmpty { courseNames }.ifEmpty { listOfNotNull(testCourse.takeIf { it.isNotBlank() }) }
            val extra = courseNames.filter { name -> names.none { it.equals(name, true) } }
            CourseChips("Дистанция", names + extra, testCourse, onTestCourse)
            if (currentControls.isEmpty()) {
                Text(
                    "У дистанции нет порядка КП. Импортируйте CSV на компьютере и отправьте протокол.",
                    color = Color(0xFF8B3A3A),
                )
            } else {
                Text(
                    "КП: ${currentControls.joinToString(" → ")}",
                    color = Color(0xFF5C5C5C),
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
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(people, key = { it.id }) { person ->
                val issuedMark = if (person.issued) " · выдан" else ""
                val label = "${person.id}  ${person.name}  ${person.currentCourse}$issuedMark"
                Text(
                    text = label,
                    color = if (person.issued) Green else Color.Unspecified,
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
