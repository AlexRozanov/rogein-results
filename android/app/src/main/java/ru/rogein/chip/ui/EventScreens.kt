package ru.rogein.chip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.STATUS_DNS
import ru.rogein.chip.parse.STATUS_DQ
import ru.rogein.chip.parse.STATUS_ERR
import ru.rogein.chip.parse.STATUS_OK
import ru.rogein.chip.parse.formatElapsed
import ru.rogein.chip.queue.CorrectionEntity
import ru.rogein.chip.queue.EventSettings
import ru.rogein.chip.queue.EventStore
import ru.rogein.chip.queue.ExclusionEntity
import ru.rogein.chip.queue.ScoredResult
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson

private val CardWhite = Color.White
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
    onPickProtocol: () -> Unit,
    onPickCourses: () -> Unit,
    replaceExisting: Boolean,
    onReplaceExisting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf<StartPerson?>(null) }
    val searching = query.isNotBlank()
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
            label = { Text("Найти в протоколе") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!searching) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPickProtocol, modifier = Modifier.weight(1f)) {
                    Text("Протокол CSV")
                }
                Button(onClick = onPickCourses, modifier = Modifier.weight(1f)) {
                    Text("Дистанции CSV")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Заменить текущие", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = replaceExisting, onCheckedChange = onReplaceExisting)
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNew) { Text("Добавить") }
                if (selected != null || addingNew) {
                    TextButton(onClick = onClearSelection) { Text("Свернуть") }
                }
            }
        }
        if (!searching && (addingNew || selected != null)) {
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
}

@Composable
fun ResultsScreen(
    results: List<ScoredResult>,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    liveNote: String,
    livePending: Boolean,
    onSendLive: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    var opened by remember { mutableStateOf<ScoredResult?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Button(
                onClick = onSendLive,
                enabled = results.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отправить в online")
            }
            if (liveNote.isNotBlank() || livePending) {
                Text(
                    if (livePending && liveNote.isBlank()) "в очереди" else liveNote,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (results.isEmpty()) {
            item {
                Text("Нет считанных финишей. Считайте чип на вкладке «Чип» → «Финиш».", color = Muted)
            }
        }
        items(results, key = { it.queueId }) { row ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { opened = row },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${row.logicalId ?: "—"} · ${row.name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(row.course.ifBlank { "без дистанции" })
                    Text(
                        listOfNotNull(
                            row.place?.let { "место $it" },
                            statusLabel(row.calc.status),
                            formatElapsed(row.calc.elapsedSeconds),
                            "${row.calc.taken} КП",
                        ).joinToString(" · "),
                        color = statusColor(row.calc.status),
                    )
                }
            }
        }
    }
    opened?.let { row ->
        val live = results.find { it.queueId == row.queueId } ?: row
        ResultDetailDialog(
            row = live,
            courses = courses,
            corrections = corrections.filter { it.logicalId == live.logicalId },
            onDismiss = { opened = null },
            onAddCp = onAddCp,
            onAssignCourse = onAssignCourse,
            onDeleteCorrection = onDeleteCorrection,
        )
    }
}

@Composable
private fun ResultDetailDialog(
    row: ScoredResult,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    onDismiss: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    val id = row.logicalId
    val required = courses.find { it.name.equals(row.course, true) }?.controls.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${row.logicalId ?: "—"} · ${row.name}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("${row.course.ifBlank { "без дистанции" }} · ${statusLabel(row.calc.status)} · ${formatElapsed(row.calc.elapsedSeconds)}")
                if (row.calc.diagnostics.isNotEmpty()) {
                    Text(row.calc.diagnostics.joinToString(", "), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Отметки: ${row.punches.joinToString(" ") { it.cp.toString() }.ifBlank { "нет" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (id != null) {
                    Text("Сменить дистанцию", color = Muted)
                    CourseChoice("Дистанция", courses.map { it.name }, row.course) { next ->
                        onAssignCourse(id, row.course, next)
                    }
                    val addable = required.filter { it != CP_START && it != CP_FINISH }
                    if (addable.isNotEmpty()) {
                        Text("Добавить взятие КП", color = Muted)
                        CourseChoice("КП", addable.map { it.toString() }, "") { value ->
                            value.toIntOrNull()?.let { onAddCp(id, row.course, it) }
                        }
                    }
                    val personal = corrections.filter { it.type == "add_cp" || it.type == "assign_course" }
                    if (personal.isNotEmpty()) {
                        Text("Персональные корректировки", color = Muted)
                        personal.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(correctionLabel(item), modifier = Modifier.weight(1f))
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
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
