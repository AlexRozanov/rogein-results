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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.rogein.chip.field.FieldOpEntity
import ru.rogein.chip.parse.field.FieldOpType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardWhite = Color.White
private val Muted = Color(0xFF5C5C5C)
private val Danger = Color(0xFFB00020)

@Composable
fun FieldJournalScreen(
    snapshot: FieldJournalSnapshot?,
    onRefresh: () -> Unit,
    onClear: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onRefresh() }
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (snapshot == null) {
            Text(
                "Загрузка…",
                color = Muted,
                modifier = Modifier.padding(16.dp),
            )
            return
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(snapshot.deviceName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "device_id  ${snapshot.deviceId}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "операций  ${snapshot.opCount}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRefresh) { Text("Обновить") }
                    TextButton(
                        onClick = onExport,
                        enabled = snapshot.opCount > 0,
                    ) { Text("Экспорт") }
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = snapshot.opCount > 0,
                    ) { Text("Очистить", color = Danger) }
                }
            }
        }
        if (snapshot.ops.isEmpty()) {
            Text(
                "Журнал пуст. Выдайте чип или считайте финиш — появятся chip_issue / chip_read.",
                color = Muted,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(snapshot.ops, key = { it.opId }) { op ->
                    FieldOpCard(op)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = {
                Text(
                    "Будут удалены все ${snapshot?.opCount ?: 0} операций обмена. " +
                        "Локальный протокол и финиш не трогаются. На других телефонах журнал останется.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text("Очистить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun FieldOpCard(op: FieldOpEntity) {
    var expanded by remember(op.opId) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    fieldOpTitle(op.type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatOpClock(op.clockMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (expanded) {
                Text(
                    "#${op.deviceSeq}  ${op.type}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                if (op.eventSlug.isNotBlank()) {
                    Text(
                        "slug  ${op.eventSlug}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Text(
                    "actor  ${op.actor}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "op_id  ${op.opId}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    op.payloadJson,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun fieldOpTitle(type: String): String = when (type) {
    FieldOpType.CHIP_ISSUE -> "Выдача чипа"
    FieldOpType.CHIP_READ -> "Чтение на финише"
    FieldOpType.PERSON_UPSERT -> "Протокол: участник"
    FieldOpType.PERSON_DELETE -> "Протокол: удаление"
    FieldOpType.PERSON_COURSE -> "Протокол: дистанция"
    FieldOpType.PERSON_TEAM_JOIN -> "Команда: объединить"
    FieldOpType.PERSON_TEAM_LEAVE -> "Команда: выйти"
    FieldOpType.PERSON_TEAM_ASSIGN -> "Команда: вступить"
    FieldOpType.CORRECTION_ADD_CP -> "Правка: +КП"
    FieldOpType.CORRECTION_REMOVE_CP -> "Правка: −КП"
    FieldOpType.CORRECTION_DNF -> "Правка: DNF"
    FieldOpType.CORRECTION_CLEAR_DNF -> "Правка: снять DNF"
    FieldOpType.CORRECTION_COPY_PUNCHES -> "Правка: копия отметок"
    FieldOpType.CORRECTION_ASSIGN_COURSE -> "Правка: дистанция"
    FieldOpType.CORRECTION_DELETE -> "Правка: отмена"
    FieldOpType.EVENT_CONFIG -> "Настройки старта"
    FieldOpType.QUEUE_DROP -> "Финиш: удаление"
    FieldOpType.FINISH_REASSIGN -> "Финиш: смена номера"
    FieldOpType.COURSE_UPSERT -> "Дистанция: сохранение"
    FieldOpType.COURSE_DELETE -> "Дистанция: удаление"
    FieldOpType.EXCLUSION_UPSERT -> "Исключение перегона"
    FieldOpType.EXCLUSION_DELETE -> "Снять исключение"
    else -> type
}

private fun formatOpClock(ms: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(ms))
}
