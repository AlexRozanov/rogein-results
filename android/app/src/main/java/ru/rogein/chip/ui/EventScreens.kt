package ru.rogein.chip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.STATUS_DNS
import ru.rogein.chip.parse.STATUS_DQ
import ru.rogein.chip.parse.STATUS_ERR
import ru.rogein.chip.parse.STATUS_OK
import ru.rogein.chip.parse.addableCourseCps
import ru.rogein.chip.parse.classifyActualPunches
import ru.rogein.chip.parse.courseKey
import ru.rogein.chip.parse.courseProgress
import ru.rogein.chip.parse.filterCourseCps
import ru.rogein.chip.parse.formatDayClock
import ru.rogein.chip.parse.formatElapsed
import ru.rogein.chip.parse.orientPunchWindow
import ru.rogein.chip.parse.punchLegSeconds
import ru.rogein.chip.queue.CorrectionEntity
import ru.rogein.chip.queue.EventSettings
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.ExclusionEntity
import ru.rogein.chip.queue.ScoredResult
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson

private val CardWhite = Color.White
private val Cream = Color(0xFFF4F1EA)
private val Muted = Color(0xFF5C5C5C)
private val Danger = Color(0xFF8B3A3A)
private val OkGreen = Color(0xFF1B4D3E)
private val Warn = Color(0xFF8A5A00)

@Composable
fun ProtocolScreen(
    people: List<StartPerson>,
    courses: List<StartCourse>,
    query: String,
    selected: StartPerson?,
    addingNew: Boolean,
    newNumber: String,
    newName: String,
    newCourse: String,
    onQuery: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onClearSelection: () -> Unit,
    onNewNumber: (String) -> Unit,
    onNewName: (String) -> Unit,
    onNewCourse: (String) -> Unit,
    onSavePerson: () -> Unit,
    onDeletePerson: (Int) -> Unit,
    onUsbProtocol: (Boolean) -> Unit,
    onUsbCourses: (Boolean) -> Unit,
    onPickProtocol: (Boolean) -> Unit,
    onPickCourses: (Boolean) -> Unit,
    onSaveCourse: (String, List<Int>, String?) -> Unit,
    onDeleteCourse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pane by remember { mutableStateOf(ProtocolPane.People) }
    var confirmDelete by remember { mutableStateOf<StartPerson?>(null) }
    var pendingLoad by remember { mutableStateOf<ProtocolLoad?>(null) }
    val searching = query.isNotBlank() && pane == ProtocolPane.People
    val filtered = people.filter { person ->
        val q = query.trim().lowercase().replace('ё', 'е')
        if (q.isEmpty()) true
        else person.name.lowercase().replace('ё', 'е').contains(q) || person.id.toString().contains(q)
    }
    val unknownCourses = people.map { it.currentCourse }.filter { name ->
        name.isNotBlank() && courses.none { it.name.equals(name, true) }
    }.distinct()
    val withoutCourse = people.count { it.currentCourse.isBlank() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text(if (pane == ProtocolPane.People) "Найти в протоколе" else "Найти дистанцию") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = pane == ProtocolPane.People,
                onClick = { pane = ProtocolPane.People },
                label = { Text("Участники") },
            )
            FilterChip(
                selected = pane == ProtocolPane.Courses,
                onClick = { pane = ProtocolPane.Courses },
                label = { Text("Дистанции") },
            )
        }
        if (!searching) {
            ProtocolLoadRow(
                label = "С компьютера",
                onProtocol = {
                    askProtocolLoad(people.isNotEmpty(), people.size, pending = { pendingLoad = it }, load = onUsbProtocol)
                },
                onCourses = {
                    askCourseLoad(courses.isNotEmpty(), courses.size, pending = { pendingLoad = it }, load = onUsbCourses)
                },
            )
            ProtocolLoadRow(
                label = "Файл CSV",
                onProtocol = {
                    askProtocolLoad(people.isNotEmpty(), people.size, pending = { pendingLoad = it }, load = onPickProtocol)
                },
                onCourses = {
                    askCourseLoad(courses.isNotEmpty(), courses.size, pending = { pendingLoad = it }, load = onPickCourses)
                },
            )
            val summary = buildList {
                if (courses.isNotEmpty()) {
                    add(courses.joinToString { "${it.name} (${it.controls.size} КП)" })
                }
                if (withoutCourse > 0) add("$withoutCourse без дистанции")
                if (unknownCourses.isNotEmpty()) add("нет КП: ${unknownCourses.joinToString()}")
            }.joinToString(" · ")
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    color = if (withoutCourse > 0 || unknownCourses.isNotEmpty()) Danger else Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pane == ProtocolPane.People) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onNew) { Text("Добавить") }
                    if (selected != null || addingNew) {
                        TextButton(onClick = onClearSelection) { Text("Свернуть") }
                    }
                }
            }
        }
        if (pane == ProtocolPane.People && !searching && (addingNew || selected != null)) {
            OutlinedTextField(
                value = newNumber,
                onValueChange = onNewNumber,
                enabled = addingNew,
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
            CourseChoice("Дистанция", courses.map { it.name }, newCourse, onNewCourse)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSavePerson) { Text(if (addingNew) "Сохранить" else "Обновить") }
                if (selected != null && !addingNew) {
                    TextButton(onClick = { confirmDelete = selected }) {
                        Text("Удалить", color = Danger)
                    }
                }
            }
        }
        if (pane == ProtocolPane.People) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (searching) "Никого не найдено" else "Протокол пуст. Загрузите CSV или добавьте участника.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(filtered, key = { it.id }) { person ->
                    val issuedMark = if (person.issued) " · выдан" else ""
                    val courseMark = person.currentCourse.ifBlank { "без дистанции" }
                    Text(
                        text = "${person.id}  ${person.name}  $courseMark$issuedMark",
                        color = when {
                            person.issued -> OkGreen
                            person.currentCourse.isBlank() -> Danger
                            else -> Color.Unspecified
                        },
                        style = if (person.id == selected?.id) {
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
        } else {
            CoursesPanel(
                courses = courses,
                query = query,
                onSave = onSaveCourse,
                onDelete = onDeleteCourse,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
    confirmDelete?.let { person ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить № ${person.id}?") },
            text = { Text("${person.name} будет убран из протокола на телефоне.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDeletePerson(person.id)
                    },
                ) { Text("Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }
    pendingLoad?.let { load ->
        AlertDialog(
            onDismissRequest = { pendingLoad = null },
            title = { Text(load.title) },
            text = { Text(load.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLoad = null
                        load.run(true)
                    },
                ) { Text("Заменить") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingLoad = null
                            load.run(false)
                        },
                    ) { Text("Добавить") }
                    TextButton(onClick = { pendingLoad = null }) { Text("Отмена") }
                }
            },
        )
    }
}

private data class ProtocolLoad(
    val title: String,
    val message: String,
    val run: (Boolean) -> Unit,
)

@Composable
private fun ProtocolLoadRow(
    label: String,
    onProtocol: () -> Unit,
    onCourses: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onProtocol) { Text("Протокол") }
        TextButton(onClick = onCourses) { Text("Дистанции") }
    }
}

private fun askProtocolLoad(
    hasPeople: Boolean,
    count: Int,
    pending: (ProtocolLoad) -> Unit,
    load: (Boolean) -> Unit,
) {
    if (!hasPeople) {
        load(false)
        return
    }
    pending(
        ProtocolLoad(
            title = "Протокол уже загружен",
            message = "Сейчас $count участников. Заменить список или добавить к нему?",
            run = load,
        ),
    )
}

private fun askCourseLoad(
    hasCourses: Boolean,
    count: Int,
    pending: (ProtocolLoad) -> Unit,
    load: (Boolean) -> Unit,
) {
    if (!hasCourses) {
        load(false)
        return
    }
    pending(
        ProtocolLoad(
            title = "Дистанции уже загружены",
            message = "Сейчас $count дистанций. Заменить или добавить новые?",
            run = load,
        ),
    )
}

private enum class ProtocolPane { People, Courses }

@Composable
private fun CoursesPanel(
    courses: List<StartCourse>,
    query: String,
    onSave: (String, List<Int>, String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<String?>(courses.firstOrNull()?.name) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase().replace('ё', 'е')
    val visible = if (q.isEmpty()) courses else courses.filter { it.name.lowercase().replace('ё', 'е').contains(q) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { adding = true; newName = "" }) { Text("Новая дистанция") }
        }
        if (adding) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) return@Button
                        onSave(name, emptyList(), null)
                        expanded = name
                        adding = false
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Создать") }
                TextButton(onClick = { adding = false }) { Text("Отмена") }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (q.isNotEmpty()) "Дистанции не найдены" else "Дистанций нет. Загрузите CSV или создайте.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(visible, key = { it.name }) { course ->
                CourseEditorCard(
                    course = course,
                    expanded = course.name.equals(expanded, true),
                    onToggle = { expanded = if (course.name.equals(expanded, true)) null else course.name },
                    onSave = onSave,
                    onDelete = { confirmDelete = course.name },
                )
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить дистанцию «$name»?") },
            text = { Text("Порядок КП будет удалён. Участники с этой дистанцией останутся без КП.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        if (name.equals(expanded, true)) expanded = null
                        onDelete(name)
                    },
                ) { Text("Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun CourseEditorCard(
    course: StartCourse,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (String, List<Int>, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(course.name) { mutableStateOf(course.name) }
    var addCp by remember(course.name) { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${course.name}  ${course.controls.size} КП",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(if (expanded) "▾" else "▸", color = Muted)
        }
        if (expanded) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (name.trim().isNotEmpty() && !name.trim().equals(course.name, true)) {
                TextButton(onClick = { onSave(name.trim(), course.controls, course.name) }) {
                    Text("Переименовать")
                }
            }
            if (course.controls.isEmpty()) {
                Text("КП нет. Добавьте номера в порядке дистанции.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            ReorderableCpList(
                cps = course.controls,
                onMove = { from, to ->
                    if (from == to) return@ReorderableCpList
                    val next = course.controls.toMutableList()
                    val item = next.removeAt(from)
                    next.add(to, item)
                    onSave(course.name, next, course.name)
                },
                onDelete = { index ->
                    onSave(course.name, course.controls.filterIndexed { i, _ -> i != index }, course.name)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = addCp,
                    onValueChange = { addCp = it.filter { ch -> ch.isDigit() }.take(3) },
                    singleLine = true,
                    label = { Text("Добавить КП") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = addCp.toIntOrNull()?.let { it > 0 } == true,
                    onClick = {
                        val cp = addCp.toIntOrNull() ?: return@TextButton
                        onSave(course.name, course.controls + cp, course.name)
                        addCp = ""
                    },
                ) { Text("Добавить") }
            }
            TextButton(onClick = onDelete) { Text("Удалить дистанцию", color = Danger) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ReorderableCpList(
    cps: List<Int>,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { 44.dp.toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    Column(modifier = Modifier.fillMaxWidth()) {
        cps.forEachIndexed { index, cp ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pointerInput(cps, index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = index
                                dragDy = 0f
                            },
                            onDragEnd = {
                                dragging = null
                                dragDy = 0f
                            },
                            onDragCancel = {
                                dragging = null
                                dragDy = 0f
                            },
                            onDrag = { _, dragAmount ->
                                val from = dragging ?: return@detectDragGesturesAfterLongPress
                                dragDy += dragAmount.y
                                val shift = (dragDy / rowPx).toInt()
                                if (shift != 0) {
                                    val to = (from + shift).coerceIn(0, cps.lastIndex)
                                    if (to != from) {
                                        onMove(from, to)
                                        dragging = to
                                        dragDy -= shift * rowPx
                                    }
                                }
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("≡", color = Muted, modifier = Modifier.padding(end = 8.dp))
                Text(
                    "${index + 1}.  $cp",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                )
                TextButton(onClick = { onDelete(index) }) { Text("×", color = Danger) }
            }
        }
    }
}

@Composable
fun ResultsScreen(
    results: List<ScoredResult>,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    startCp: Int,
    finishCp: Int,
    liveNote: String,
    livePending: Boolean,
    onSendLive: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase().replace('ё', 'е')
    val filtered = remember(results, q) {
        if (q.isEmpty()) results
        else results.filter { row ->
            row.name.lowercase().replace('ё', 'е').contains(q) ||
                row.logicalId?.toString()?.contains(q) == true ||
                row.course.lowercase().replace('ё', 'е').contains(q)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { courseKey(it.course) }.toList() }
    val removedCps = remember(corrections) { EventStore.removedCps(corrections) }
    LaunchedEffect(q) {
        if (q.isNotBlank()) collapsed = emptySet()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSendLive, enabled = results.isNotEmpty()) {
                Text("Отправить в online")
            }
            if (liveNote.isNotBlank() || livePending) {
                Text(
                    if (livePending && liveNote.isBlank()) "в очереди" else liveNote,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Номер или фамилия") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (results.isEmpty()) {
                item {
                    Text(
                        "Нет считанных финишей. Считайте чип на вкладке «Чип» → «Финиш».",
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "Никого не найдено",
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
            groups.forEach { (groupKey, rows) ->
                val label = rows.first().course.ifBlank { "без дистанции" }
                val open = groupKey !in collapsed
                item(key = "course-$groupKey") {
                    CourseGroupHeader(
                        name = label,
                        count = rows.size,
                        expanded = open,
                        onToggle = {
                            collapsed = if (groupKey in collapsed) collapsed - groupKey else collapsed + groupKey
                        },
                    )
                }
                if (open) {
                    items(rows, key = { it.queueId }) { row ->
                        val live = results.find { it.queueId == row.queueId } ?: row
                        ResultProtocolRow(
                            row = live,
                            expanded = expandedId == live.queueId,
                            courses = courses,
                            corrections = corrections.filter { it.logicalId == live.logicalId },
                            removedCps = removedCps,
                            startCp = startCp,
                            finishCp = finishCp,
                            onToggle = {
                                expandedId = if (expandedId == live.queueId) null else live.queueId
                            },
                            onAddCp = onAddCp,
                            onAssignCourse = onAssignCourse,
                            onDeleteCorrection = onDeleteCorrection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseGroupHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Cream)
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Свернуть дистанцию" else "Развернуть дистанцию",
                tint = Muted,
            )
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$count",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun ResultProtocolRow(
    row: ScoredResult,
    expanded: Boolean,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    removedCps: Set<Int>,
    startCp: Int,
    finishCp: Int,
    onToggle: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    val dq = row.calc.status == STATUS_DQ
    val rowColor = if (dq) Danger else Color.Unspecified
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (dq) Danger.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.place?.toString() ?: "—",
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.End,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = rowColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.logicalId?.toString() ?: "—",
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
                fontFamily = FontFamily.Monospace,
                color = rowColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = rowColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                formatElapsed(row.calc.elapsedSeconds),
                fontFamily = FontFamily.Monospace,
                color = rowColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                statusShort(row.calc.status),
                color = if (dq) Danger else statusColor(row.calc.status),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .width(56.dp)
                    .padding(start = 6.dp),
                textAlign = TextAlign.End,
            )
        }
        if (expanded) {
            ResultRowDetails(
                row = row,
                courses = courses,
                corrections = corrections,
                removedCps = removedCps,
                startCp = startCp,
                finishCp = finishCp,
                onAddCp = onAddCp,
                onAssignCourse = onAssignCourse,
                onDeleteCorrection = onDeleteCorrection,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun ResultRowDetails(
    row: ScoredResult,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    removedCps: Set<Int>,
    startCp: Int,
    finishCp: Int,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    val id = row.logicalId
    val required = filterCourseCps(
        courses.find { it.name.equals(row.course, true) }?.controls.orEmpty(),
        removedCps,
    )
    val addCounts = EventStore.addCpCounts(id, row.course, corrections)
    val window = orientPunchWindow(row.punches, startCp, finishCp)
    val addable = addableCourseCps(courseProgress(window.map { it.cp }, required, addCounts))
    var marksOpen by remember { mutableStateOf(false) }
    var courseChangeOpen by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Text(
            "${statusLabel(row.calc.status)} · ${row.calc.taken} КП · ${formatElapsed(row.calc.elapsedSeconds)}",
            color = statusColor(row.calc.status),
            style = MaterialTheme.typography.bodySmall,
        )
        if (row.calc.diagnostics.isNotEmpty()) {
            Text(row.calc.diagnostics.joinToString(", "), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { marksOpen = true }) { Text("Отметки") }
            if (id != null && courses.isNotEmpty()) {
                TextButton(onClick = { courseChangeOpen = true }) { Text("Сменить дистанцию") }
            }
        }
        if (id != null) {
            if (addable.isNotEmpty()) {
                CourseChoice(
                    "Добавить взятие КП",
                    addable.map { (cp, count) -> if (count > 1) "$cp (ещё $count)" else cp.toString() },
                    "",
                ) { value ->
                    value.substringBefore(" ").toIntOrNull()?.let { onAddCp(id, row.course, it) }
                }
            }
            val personal = corrections.filter { it.type == "add_cp" || it.type == "assign_course" }
            if (personal.isNotEmpty()) {
                Text("Персональные корректировки", color = Muted, style = MaterialTheme.typography.bodySmall)
                personal.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(correctionLabel(item), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onDeleteCorrection(item.id) }) {
                            Text("Отменить")
                        }
                    }
                }
            }
        } else {
            Text("Нет номера в протоколе — персональные корректировки недоступны.", color = Muted)
        }
    }
    if (marksOpen) {
        UsedMarksDialog(
            row = row,
            required = required,
            startCp = startCp,
            finishCp = finishCp,
            onDismiss = { marksOpen = false },
        )
    }
    if (courseChangeOpen && id != null) {
        CourseChangeDialog(
            row = row,
            courses = courses.map { it.name },
            onDismiss = { courseChangeOpen = false },
            onConfirm = { next ->
                onAssignCourse(id, row.course, next)
                courseChangeOpen = false
            },
        )
    }
}

@Composable
private fun UsedMarksDialog(
    row: ScoredResult,
    required: List<Int>,
    startCp: Int,
    finishCp: Int,
    onDismiss: () -> Unit,
) {
    val classified = classifyActualPunches(row.punches, required, startCp, finishCp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (classified.isEmpty()) {
                    Text("Нет отметок", color = Muted)
                } else {
                    classified.forEachIndexed { index, item ->
                        val mark = item.mark
                        val color = if (item.wrong) Danger else Color.Unspecified
                        val leg = if (index == 0) {
                            "—"
                        } else {
                            formatDayClock(punchLegSeconds(classified[index - 1].mark.seconds, mark.seconds))
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.width(28.dp),
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                mark.cp.toString(),
                                modifier = Modifier.width(44.dp),
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                formatDayClock(mark.seconds),
                                modifier = Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                leg,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun CourseChangeDialog(
    row: ScoredResult,
    courses: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(row.course) }
    val current = row.course.ifBlank { "без дистанции" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сменить дистанцию?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "№ ${row.logicalId ?: "—"} ${row.name} сейчас на «$current». Результат будет пересчитан.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CourseChoice("Новая дистанция", courses, draft) { draft = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank() && !draft.equals(row.course, ignoreCase = true),
                onClick = { onConfirm(draft) },
            ) { Text("Сменить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
fun MoreMenu(onOpen: (MorePage) -> Unit) {
    val items = listOf(
        MorePage.Removed to "Снятые КП",
        MorePage.Exclusions to "Исключение перегонов",
        MorePage.Corrections to "Персональные корректировки",
        MorePage.Publish to "Публикация на сайт",
        MorePage.Settings to "Настройки старта",
    )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.first.name }) { (page, title) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(page) },
            ) {
                Text(title, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun RemovedCpsScreen(
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    onAdd: (Int) -> Unit,
    onUndo: (Long) -> Unit,
) {
    val removed = corrections.filter { it.type == "remove_cp" && it.logicalId == null }
    val removedNums = EventStore.removedCps(corrections)
    val available = courses.flatMap { it.controls }.distinct().sorted()
        .filter { it !in removedNums && it != CP_START && it != CP_FINISH }
    var selected by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Если станция не срабатывала или КП поставили неправильно — снимите его. Для всех участников этот КП считается несуществующим.",
            color = Muted,
        )
        if (courses.isEmpty()) {
            Text("Сначала загрузите дистанции.", color = Danger)
        } else if (available.isEmpty()) {
            Text("Все КП с дистанций уже сняты.", color = Muted)
        } else {
            CourseChoice("КП на дистанциях", available.map { it.toString() }, selected?.toString().orEmpty()) {
                selected = it.toIntOrNull()
            }
            Button(onClick = { selected?.let(onAdd) }, enabled = selected != null) {
                Text("Снять КП")
            }
        }
        if (removed.isEmpty()) {
            Text("Снятых КП нет", color = Muted)
        } else {
            removed.forEach { row ->
                val cp = EventStore.correctionCp(row)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("КП ${cp ?: "—"}")
                    TextButton(onClick = { onUndo(row.id) }) { Text("Вернуть") }
                }
            }
        }
    }
}

@Composable
fun ExclusionsScreen(
    courses: List<StartCourse>,
    settings: EventSettings,
    corrections: List<CorrectionEntity>,
    exclusions: List<ExclusionEntity>,
    onAdd: (String, Int, Int, Int?) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val removed = EventStore.removedCps(corrections)
    var courseName by remember { mutableStateOf(courses.firstOrNull()?.name.orEmpty()) }
    var fromCp by remember { mutableStateOf<Int?>(null) }
    var toCp by remember { mutableStateOf<Int?>(null) }
    var maxMinutes by remember { mutableStateOf("") }
    var formOpen by remember { mutableStateOf(false) }
    val selected = courses.find { it.name.equals(courseName, true) }
    val legs = selected?.let { EventStore.legsFor(it, settings, removed) }.orEmpty()
    val used = exclusions.filter { it.courseName.equals(courseName, true) }
        .map { it.fromCp to it.toCp }
        .toSet()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Исключение задаётся отдельно для каждой дистанции. Время этого перегона не входит в результат.",
            color = Muted,
        )
        if (courses.isEmpty()) {
            Text("Сначала загрузите дистанции.", color = Danger)
        } else {
            Button(onClick = { formOpen = true }, enabled = courses.isNotEmpty()) {
                Text("Добавить исключение")
            }
        }
        if (exclusions.isEmpty()) {
            Text("Исключённых перегонов нет", color = Muted)
        }
        exclusions.forEach { rule ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${rule.courseName}: ${rule.fromCp} → ${rule.toCp}" +
                        (rule.maxLegSeconds?.let { " · макс. ${it / 60} мин" } ?: " · без лимита"),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(rule.id) }) { Text("Удалить", color = Danger) }
            }
        }
    }
    if (formOpen) {
        AlertDialog(
            onDismissRequest = { formOpen = false },
            title = { Text("Исключить перегон") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseChoice("Дистанция", courses.map { it.name }, courseName) { next ->
                        courseName = next
                        fromCp = null
                        toCp = null
                    }
                    if (legs.isEmpty()) {
                        Text("У дистанции нет перегонов.", color = Muted)
                    } else {
                        CourseChoice(
                            "Перегон",
                            legs.map { (from, to) ->
                                val mark = if (from to to in used) " (уже исключён)" else ""
                                "${formatCp(from, settings)} → ${formatCp(to, settings)}$mark"
                            },
                            if (fromCp != null && toCp != null) {
                                "${formatCp(fromCp!!, settings)} → ${formatCp(toCp!!, settings)}"
                            } else {
                                ""
                            },
                        ) { label ->
                            val clean = label.substringBefore(" (")
                            val parts = clean.split(" → ")
                            val from = parts.getOrNull(0)?.substringBefore(" ")?.toIntOrNull()
                            val to = parts.getOrNull(1)?.substringBefore(" ")?.toIntOrNull()
                            if (from != null && to != null && from to to !in used) {
                                fromCp = from
                                toCp = to
                            }
                        }
                    }
                    OutlinedTextField(
                        value = maxMinutes,
                        onValueChange = { maxMinutes = it.filter { ch -> ch.isDigit() }.take(3) },
                        singleLine = true,
                        label = { Text("Макс. время, мин (пусто = без лимита)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fromCp != null && toCp != null && courseName.isNotBlank(),
                    onClick = {
                        onAdd(courseName, fromCp ?: return@TextButton, toCp ?: return@TextButton, maxMinutes.toIntOrNull())
                        formOpen = false
                        fromCp = null
                        toCp = null
                        maxMinutes = ""
                    },
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { formOpen = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun CorrectionsScreen(
    results: List<ScoredResult>,
    people: List<StartPerson>,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    val personal = corrections.filter { it.logicalId != null && (it.type == "add_cp" || it.type == "assign_course") }
    var personId by remember { mutableStateOf<Int?>(results.firstOrNull()?.logicalId ?: people.firstOrNull()?.id) }
    var courseName by remember {
        mutableStateOf(results.firstOrNull()?.course ?: people.firstOrNull()?.currentCourse.orEmpty())
    }
    var addCp by remember { mutableStateOf<Int?>(null) }
    val selectedPerson = people.find { it.id == personId }
    val selectedResult = results.find { it.logicalId == personId }
    val effectiveCourse = selectedResult?.course ?: courseName.ifBlank { selectedPerson?.currentCourse.orEmpty() }
    val required = courses.find { it.name.equals(effectiveCourse, true) }?.controls.orEmpty()
        .filter { it != CP_START && it != CP_FINISH }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Как на карточке участника в десктопе: добавить взятие КП или сменить дистанцию. Результат пересчитывается сразу.",
            color = Muted,
        )
        val personLabels = (results.mapNotNull { row ->
            row.logicalId?.let { id -> "$id · ${row.name}" }
        } + people.map { "${it.id} · ${it.name}" }).distinct()
        CourseChoice("Участник", personLabels, personId?.let { id ->
            val name = results.find { it.logicalId == id }?.name ?: people.find { it.id == id }?.name
            "$id · ${name.orEmpty()}"
        }.orEmpty()) { label ->
            personId = label.substringBefore(" ·").toIntOrNull()
        }
        CourseChoice("Дистанция", courses.map { it.name }, effectiveCourse) { next ->
            courseName = next
            personId?.let { onAssignCourse(it, effectiveCourse, next) }
        }
        if (required.isNotEmpty()) {
            CourseChoice("Добавить КП", required.map { it.toString() }, addCp?.toString().orEmpty()) {
                addCp = it.toIntOrNull()
            }
            Button(
                enabled = personId != null && addCp != null,
                onClick = {
                    val id = personId ?: return@Button
                    val cp = addCp ?: return@Button
                    onAddCp(id, effectiveCourse, cp)
                },
            ) { Text("Добавить взятие КП") }
        }
        if (personal.isEmpty()) {
            Text("Персональных корректировок нет", color = Muted)
        } else {
            personal.forEach { row ->
                val who = people.find { it.id == row.logicalId }?.name ?: "№ ${row.logicalId}"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("№ ${row.logicalId} $who · ${correctionLabel(row)}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDeleteCorrection(row.id) }) { Text("Отменить") }
                }
            }
        }
    }
}

@Composable
fun PublishScreen(
    settings: EventSettings,
    busy: Boolean,
    onSave: (EventSettings) -> Unit,
    onTest: (String) -> Unit,
    onGenerateSlug: (EventSettings) -> Unit,
    onPublish: (EventSettings) -> Unit,
) {
    var api by remember(settings.apiBaseUrl) { mutableStateOf(settings.apiBaseUrl) }
    var token by remember(settings.publishToken) { mutableStateOf(settings.publishToken) }
    var slug by remember(settings.slug) { mutableStateOf(settings.slug) }
    var title by remember(settings.title) { mutableStateOf(settings.title) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Адрес — корень сайта, как в браузере: https://malahit-sprint.ru (без :18790). Адрес и токен остаются на телефоне после «Новый старт».",
            color = Muted,
        )
        Text("Доступ к серверу", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = api,
            onValueChange = { api = it },
            singleLine = true,
            label = { Text("Адрес API") },
            placeholder = { Text("https://malahit-sprint.ru") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            singleLine = true,
            label = { Text("Токен публикации") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onSave(settings.copy(apiBaseUrl = api, publishToken = token, slug = slug, title = title)) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Сохранить") }
            OutlinedButton(
                onClick = { onTest(api) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Проверить") }
        }
        Text("Этот старт", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            label = { Text("Название") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = slug,
            onValueChange = { slug = it },
            singleLine = true,
            label = { Text("Slug") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                onGenerateSlug(settings.copy(apiBaseUrl = api, publishToken = token, slug = slug, title = title))
            },
            enabled = !busy,
        ) { Text("Собрать slug из даты и названия") }
        Button(
            onClick = {
                onPublish(settings.copy(apiBaseUrl = api, publishToken = token, slug = slug, title = title))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Опубликовать") }
    }
}

@Composable
fun EventSettingsScreen(
    settings: EventSettings,
    busy: Boolean,
    onSave: (EventSettings) -> Unit,
) {
    var startCp by remember(settings.startCp) { mutableStateOf(settings.startCp.toString()) }
    var finishCp by remember(settings.finishCp) { mutableStateOf(settings.finishCp.toString()) }
    var minutes by remember(settings.controlMinutes) { mutableStateOf(settings.controlMinutes.toString()) }
    var date by remember(settings.competitionDate) { mutableStateOf(settings.competitionDate) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Параметры расчёта ориентирования. Совпадают с десктопом.", color = Muted)
        OutlinedTextField(
            value = startCp,
            onValueChange = { startCp = it.filter { ch -> ch.isDigit() }.take(3) },
            singleLine = true,
            label = { Text("КП старта") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = finishCp,
            onValueChange = { finishCp = it.filter { ch -> ch.isDigit() }.take(3) },
            singleLine = true,
            label = { Text("КП финиша") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter { ch -> ch.isDigit() }.take(4) },
            singleLine = true,
            label = { Text("Контрольное время, мин") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            singleLine = true,
            label = { Text("Дата соревнования (ГГГГ-ММ-ДД)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy,
            onClick = {
                onSave(
                    settings.copy(
                        startCp = startCp.toIntOrNull() ?: CP_START,
                        finishCp = finishCp.toIntOrNull() ?: CP_FINISH,
                        controlMinutes = minutes.toIntOrNull() ?: 120,
                        competitionDate = date.trim(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить") }
    }
}

@Composable
fun CourseChoice(
    label: String,
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Text(label, color = Muted)
    ChipRow(options, current, onSelect)
}

@Composable
internal fun ChipRow(
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(options.size) { index ->
            val name = options[index]
            FilterChip(
                selected = name.equals(current, ignoreCase = true),
                onClick = { onSelect(name) },
                label = { Text(name) },
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    STATUS_OK -> "OK"
    STATUS_DQ -> "дискв."
    STATUS_DNS -> "не стартовал"
    STATUS_ERR -> "ошибка"
    else -> status
}

private fun statusShort(status: String): String = when (status) {
    STATUS_OK -> "OK"
    STATUS_DQ -> "дискв."
    STATUS_DNS -> "н/с"
    STATUS_ERR -> "ошибка"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    STATUS_OK -> OkGreen
    STATUS_DQ, STATUS_ERR -> Danger
    STATUS_DNS -> Muted
    else -> Warn
}

private fun formatCp(cp: Int, settings: EventSettings): String = when (cp) {
    settings.startCp -> "$cp старт"
    settings.finishCp -> "$cp финиш"
    else -> cp.toString()
}

internal fun correctionLabel(row: CorrectionEntity): String = when (row.type) {
    "add_cp" -> "добавлен КП ${EventStore.correctionCp(row) ?: "—"}"
    "assign_course" -> "дистанция «${EventStore.correctionCourse(row)}»"
    "remove_cp" -> "снят КП ${EventStore.correctionCp(row) ?: "—"}"
    else -> row.type
}
