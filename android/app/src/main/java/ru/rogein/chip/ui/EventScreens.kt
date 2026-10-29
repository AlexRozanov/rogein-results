package ru.rogein.chip.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.rogein.chip.parse.CP_FINISH
import ru.rogein.chip.parse.OrientMark
import ru.rogein.chip.parse.CP_START
import ru.rogein.chip.parse.STATUS_DNS
import ru.rogein.chip.parse.STATUS_DQ
import ru.rogein.chip.parse.STATUS_ERR
import ru.rogein.chip.parse.STATUS_OK
import ru.rogein.chip.parse.addableCourseCps
import ru.rogein.chip.parse.applyAddedCps
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
import ru.rogein.chip.queue.SavedStart
import ru.rogein.chip.queue.ScoredResult
import ru.rogein.chip.startlist.StartCourse
import ru.rogein.chip.startlist.StartPerson
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private val CardWhite = Color.White
private val Cream = Color(0xFFF4F1EA)
private val Muted = Color(0xFF5C5C5C)
private val Danger = Color(0xFF8B3A3A)
private val OkGreen = Color(0xFF1B4D3E)
private val CompactBarPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val Warn = Color(0xFF8A5A00)

@Composable
internal fun EditorCard(
    title: String? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    onClose: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Удалить",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!title.isNullOrBlank() || onClose != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onClose != null) {
                        CompactTextButton("Закрыть", onClose)
                    }
                }
            }
            content()
            if (onPrimary != null || onDelete != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onPrimary != null && primaryLabel != null) {
                        Button(
                            onClick = onPrimary,
                            enabled = primaryEnabled,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                        ) {
                            Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (onDelete != null) {
                        CompactTextButton(deleteLabel, onDelete, Danger)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveLinkRow(
    url: String,
    shareText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shown = url.removePrefix("https://").removePrefix("http://")
    val qr = remember(url) { liveQrBitmap(url) }
    var qrOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                shown,
                color = OkGreen,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { copyLiveLink(context, url) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { copyLiveLink(context, url) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = ContentCopyIcon,
                        contentDescription = "Копировать ссылку",
                        tint = OkGreen,
                    )
                }
                IconButton(
                    onClick = { shareLiveLink(context, shareText) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Поделиться",
                        tint = OkGreen,
                    )
                }
            }
        }
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = "QR-код результатов",
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .clickable { qrOpen = true }
                    .padding(4.dp),
            )
        }
    }
    if (qrOpen && qr != null) {
        LiveQrFullscreen(qr = qr, onDismiss = { qrOpen = false })
    }
}

@Composable
private fun LiveQrFullscreen(qr: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            val activityWindow = view.context.findActivity()?.window
            val previousDialog = dialogWindow?.attributes?.screenBrightness
            val previousActivity = activityWindow?.attributes?.screenBrightness
            fun setBrightness(window: android.view.Window?, value: Float) {
                window ?: return
                val lp = window.attributes
                lp.screenBrightness = value
                window.attributes = lp
            }
            setBrightness(dialogWindow, 1f)
            setBrightness(activityWindow, 1f)
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                setBrightness(
                    dialogWindow,
                    previousDialog ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                )
                setBrightness(
                    activityWindow,
                    previousActivity ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                )
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = qr,
                contentDescription = "QR-код результатов",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .aspectRatio(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Black)
            }
            Text(
                "Нажмите, чтобы закрыть",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun copyLiveLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("live", url))
    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
}

private fun liveQrBitmap(url: String): ImageBitmap? {
    return try {
        val size = 1024
        val matrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF1B4D3E.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private val ContentCopyIcon: ImageVector = ImageVector.Builder(
    name = "ContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.NonZero,
    ) {
        moveTo(16f, 1f)
        horizontalLineTo(4f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        verticalLineTo(17f)
        horizontalLineTo(4f)
        verticalLineTo(3f)
        horizontalLineTo(16f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        horizontalLineTo(8f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        verticalLineTo(21f)
        curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
        horizontalLineTo(19f)
        curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
        verticalLineTo(7f)
        curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
        close()
        moveTo(19f, 21f)
        horizontalLineTo(8f)
        verticalLineTo(7f)
        horizontalLineTo(19f)
        verticalLineTo(21f)
        close()
    }
}.build()

private fun shareLiveLink(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Результаты online")
    }
    context.startActivity(Intent.createChooser(intent, "Результаты online"))
}

@Composable
internal fun CompactTextButton(
    label: String,
    onClick: () -> Unit,
    color: Color = OkGreen,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

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
    val personNumberFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(addingNew, pane) {
        if (addingNew && pane == ProtocolPane.People) {
            delay(40)
            runCatching { personNumberFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val filtered = people.filter { person ->
        val q = query.trim().lowercase().replace('ё', 'е')
        if (q.isEmpty()) true
        else person.name.lowercase().replace('ё', 'е').contains(q) || person.id.toString().contains(q)
    }
    val unknownCourses = people.map { it.currentCourse }.filter { name ->
        name.isNotBlank() && courses.none { it.name.equals(name, true) }
    }.distinct()
    val withoutCourse = people.count { it.currentCourse.isBlank() }
    var loadMenu by remember { mutableStateOf(false) }
    var requestAddCourse by remember { mutableStateOf(false) }
    val summary = buildList {
        if (courses.isNotEmpty()) {
            add(courses.joinToString { "${it.name} (${it.controls.size} КП)" })
        }
        if (withoutCourse > 0) add("$withoutCourse без дистанции")
        if (unknownCourses.isNotEmpty()) add("нет КП: ${unknownCourses.joinToString()}")
    }.joinToString(" · ")
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = {
                Text(if (pane == ProtocolPane.People) "Номер или фамилия" else "Название дистанции")
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProtocolTab(
                label = "Участники",
                selected = pane == ProtocolPane.People,
                onClick = { pane = ProtocolPane.People },
            )
            ProtocolTab(
                label = "Дистанции",
                selected = pane == ProtocolPane.Courses,
                onClick = { pane = ProtocolPane.Courses },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!searching) {
                if (pane == ProtocolPane.People) {
                    TextButton(
                        onClick = onNew,
                        contentPadding = CompactBarPadding,
                    ) { Text("Добавить") }
                } else {
                    TextButton(
                        onClick = { requestAddCourse = true },
                        contentPadding = CompactBarPadding,
                    ) { Text("Новая") }
                }
                if (selected == null && !addingNew) {
                Box {
                    TextButton(
                        onClick = { loadMenu = true },
                        contentPadding = CompactBarPadding,
                    ) { Text("Загрузить") }
                    DropdownMenu(expanded = loadMenu, onDismissRequest = { loadMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Протокол с компьютера") },
                            onClick = {
                                loadMenu = false
                                askProtocolLoad(people.isNotEmpty(), people.size, pending = { pendingLoad = it }, load = onUsbProtocol)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Дистанции с компьютера") },
                            onClick = {
                                loadMenu = false
                                askCourseLoad(courses.isNotEmpty(), courses.size, pending = { pendingLoad = it }, load = onUsbCourses)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Протокол CSV") },
                            onClick = {
                                loadMenu = false
                                askProtocolLoad(people.isNotEmpty(), people.size, pending = { pendingLoad = it }, load = onPickProtocol)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Дистанции CSV") },
                            onClick = {
                                loadMenu = false
                                askCourseLoad(courses.isNotEmpty(), courses.size, pending = { pendingLoad = it }, load = onPickCourses)
                            },
                        )
                    }
                }
                }
            }
        }
        if (!searching && summary.isNotEmpty()) {
            Text(
                summary,
                color = if (withoutCourse > 0 || unknownCourses.isNotEmpty()) Danger else Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pane == ProtocolPane.People && !searching && (addingNew || selected != null)) {
            EditorCard(
                title = if (addingNew) {
                    "Новый участник"
                } else {
                    "№ ${selected?.id} · ${selected?.displayName.orEmpty()}"
                },
                primaryLabel = if (addingNew) "Сохранить" else "Обновить",
                onPrimary = onSavePerson,
                onClose = onClearSelection,
                onDelete = if (selected != null && !addingNew) {
                    { confirmDelete = selected }
                } else {
                    null
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newNumber,
                        onValueChange = onNewNumber,
                        enabled = addingNew,
                        singleLine = true,
                        label = { Text("Номер") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .weight(0.35f)
                            .focusRequester(personNumberFocus),
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = onNewName,
                        singleLine = true,
                        label = { Text("ФИО") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSavePerson() }),
                        modifier = Modifier.weight(0.65f),
                    )
                }
                CourseChoice("Дистанция", courses.map { it.name }, newCourse, onNewCourse)
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
                        text = "${person.id}  ${person.displayName}  $courseMark$issuedMark",
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
                requestAdd = requestAddCourse,
                onRequestAddConsumed = { requestAddCourse = false },
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
            text = { Text("${person.displayName} будет убран из протокола на телефоне.") },
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
private fun ProtocolTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) OkGreen else Muted,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) OkGreen.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
    requestAdd: Boolean,
    onRequestAddConsumed: () -> Unit,
    onSave: (String, List<Int>, String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<String?>(courses.firstOrNull()?.name) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val newCourseFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestAdd) {
        if (requestAdd) {
            adding = true
            newName = ""
            onRequestAddConsumed()
        }
    }
    LaunchedEffect(adding) {
        if (adding) {
            delay(40)
            runCatching { newCourseFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val q = query.trim().lowercase().replace('ё', 'е')
    val visible = if (q.isEmpty()) courses else courses.filter { it.name.lowercase().replace('ё', 'е').contains(q) }
    val listState = rememberLazyListState()
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        if (adding) {
            EditorCard(
                title = "Новая дистанция",
                primaryLabel = "Создать",
                primaryEnabled = newName.isNotBlank(),
                onPrimary = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        onSave(name, emptyList(), null)
                        expanded = name
                        adding = false
                        newName = ""
                    }
                },
                onClose = { adding = false },
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Название") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val name = newName.trim()
                            if (name.isEmpty()) return@KeyboardActions
                            onSave(name, emptyList(), null)
                            expanded = name
                            adding = false
                            newName = ""
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(newCourseFocus),
                )
            }
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { listCoords = it },
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
                    listState = listState,
                    listCoordinates = { listCoords },
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
    listState: LazyListState,
    listCoordinates: () -> LayoutCoordinates?,
    onToggle: () -> Unit,
    onSave: (String, List<Int>, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(course.name) { mutableStateOf(course.name) }
    var addCp by remember(course.name) { mutableStateOf("") }
    val addBottomFocus = remember { FocusRequester() }
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
            EditorCard(
                onClose = onToggle,
                onDelete = onDelete,
                deleteLabel = "Удалить дистанцию",
            ) {
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
                    listState = listState,
                    listCoordinates = listCoordinates,
                    onMove = { from, to ->
                        if (from == to) return@ReorderableCpList
                        val next = course.controls.toMutableList()
                        val item = next.removeAt(from)
                        next.add(to, item)
                        onSave(course.name, next, course.name)
                    },
                    onChange = { index, cp ->
                        if (index !in course.controls.indices || course.controls[index] == cp) return@ReorderableCpList
                        val next = course.controls.toMutableList()
                        next[index] = cp
                        onSave(course.name, next, course.name)
                    },
                    onInsertAfter = { index, cp ->
                        val next = course.controls.toMutableList()
                        next.add((index + 1).coerceIn(0, next.size), cp)
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
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val cp = addCp.toIntOrNull() ?: return@KeyboardActions
                                onSave(course.name, course.controls + cp, course.name)
                                addCp = ""
                            },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(addBottomFocus),
                    )
                    TextButton(
                        enabled = addCp.toIntOrNull()?.let { it > 0 } == true,
                        onClick = {
                            val cp = addCp.toIntOrNull() ?: return@TextButton
                            onSave(course.name, course.controls + cp, course.name)
                            addCp = ""
                            runCatching { addBottomFocus.requestFocus() }
                        },
                    ) { Text("Добавить") }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ReorderableCpList(
    cps: List<Int>,
    listState: LazyListState,
    listCoordinates: () -> LayoutCoordinates?,
    onMove: (Int, Int) -> Unit,
    onChange: (Int, Int) -> Unit,
    onInsertAfter: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val rowPx = with(density) { 56.dp.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var pointerYInViewport by remember { mutableStateOf(0f) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }
    var addAfter by remember { mutableStateOf<Int?>(null) }
    var addValue by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    val editFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editHadFocus by remember { mutableStateOf(false) }
    fun commitEdit() {
        val index = editingIndex ?: return
        val cp = editValue.text.toIntOrNull()
        editingIndex = null
        editHadFocus = false
        focusManager.clearFocus()
        if (cp != null && cp > 0) onChange(index, cp)
    }
    fun selectAllEdit() {
        val text = editValue.text
        editValue = TextFieldValue(text, TextRange(0, text.length))
    }
    LaunchedEffect(editingIndex) {
        editHadFocus = false
        if (editingIndex != null) {
            delay(16)
            runCatching { editFocus.requestFocus() }
            keyboard?.show()
            delay(30)
            selectAllEdit()
        }
    }
    LaunchedEffect(addAfter) {
        if (addAfter != null) {
            delay(80)
            runCatching { addFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val from = dragFrom
    val dragTo = if (from == null) {
        null
    } else {
        (from + (dragDy / rowPx).roundToInt()).coerceIn(0, cps.lastIndex)
    }
    fun pointerInViewport(row: LayoutCoordinates?, local: Offset): Float? {
        val list = listCoordinates() ?: return null
        if (row == null || !row.isAttached || !list.isAttached) return null
        return list.windowToLocal(row.localToWindow(local)).y
    }
    LaunchedEffect(from != null) {
        if (from == null) return@LaunchedEffect
        while (isActive && dragFrom != null) {
            val viewportH = listState.layoutInfo.viewportSize.height.toFloat()
            if (viewportH > 0f) {
                val edge = rowPx.coerceAtLeast(48f)
                val y = pointerYInViewport
                val factor = when {
                    y < edge -> -((edge - y) / edge).coerceIn(0f, 1f)
                    y > viewportH - edge -> ((y - (viewportH - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (factor != 0f) {
                    val scrolled = listState.scrollBy(factor * rowPx * 0.45f)
                    if (scrolled != 0f) dragDy += scrolled
                }
            }
            delay(16)
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
        cps.forEachIndexed { index, cp ->
            val isDragging = from == index
            val isEditing = editingIndex == index
            val slotShift = when {
                from == null || dragTo == null || index == from -> 0f
                from < dragTo && index in (from + 1)..dragTo -> -rowPx
                dragTo < from && index in dragTo until from -> rowPx
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = slotShift,
                label = "cp-shift-$index",
            )
            val translationY = if (isDragging) dragDy else animatedShift
            val spacerClicks = remember { MutableInteractionSource() }
            var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 2f else 0f)
                    .heightIn(min = 56.dp)
                    .graphicsLayer {
                        this.translationY = translationY
                        shadowElevation = if (isDragging) 10f else 0f
                    }
                    .background(if (isDragging) Color(0xFFD7E6DF) else Color.Transparent)
                    .onGloballyPositioned { rowCoords = it }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                menuFor = null
                                if (editingIndex != null) commitEdit()
                                dragFrom = index
                                dragDy = 0f
                                pointerInViewport(rowCoords, start)?.let { pointerYInViewport = it }
                            },
                            onDragEnd = {
                                val start = dragFrom
                                val dy = dragDy
                                dragFrom = null
                                dragDy = 0f
                                if (start != null) {
                                    val end = (start + (dy / rowPx).roundToInt()).coerceIn(0, cps.lastIndex)
                                    if (end != start) onMove(start, end)
                                }
                            },
                            onDragCancel = {
                                dragFrom = null
                                dragDy = 0f
                            },
                            onDrag = { change, dragAmount ->
                                if (dragFrom == null) return@detectDragGesturesAfterLongPress
                                dragDy += dragAmount.y
                                pointerInViewport(rowCoords, change.position)?.let { pointerYInViewport = it }
                            },
                        )
                    }
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { next ->
                            val digits = next.text.filter { ch -> ch.isDigit() }.take(3)
                            editValue = if (digits == next.text) next else TextFieldValue(digits, TextRange(digits.length))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                        modifier = Modifier
                            .widthIn(min = 80.dp, max = 128.dp)
                            .padding(vertical = 4.dp)
                            .focusRequester(editFocus)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    editHadFocus = true
                                } else if (editHadFocus && editingIndex == index) {
                                    commitEdit()
                                }
                            },
                    )
                } else {
                    Text(
                        cp.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable {
                                menuFor = null
                                if (editingIndex != null && editingIndex != index) commitEdit()
                                editingIndex = index
                                val text = cp.toString()
                                editValue = TextFieldValue(text, TextRange(0, text.length))
                            }
                            .padding(10.dp),
                    )
                }
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .then(
                            if (editingIndex != null) {
                                Modifier.clickable(
                                    indication = null,
                                    interactionSource = spacerClicks,
                                ) { commitEdit() }
                            } else {
                                Modifier
                            },
                        ),
                )
                Box {
                    IconButton(
                        onClick = {
                            if (editingIndex != null) commitEdit()
                            menuFor = if (menuFor == index) null else index
                        },
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Действия с КП", tint = Muted)
                    }
                    DropdownMenu(
                        expanded = menuFor == index,
                        onDismissRequest = { menuFor = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Добавить КП") },
                            onClick = {
                                menuFor = null
                                addAfter = index
                                addValue = ""
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Вверх") },
                            enabled = index > 0,
                            onClick = {
                                menuFor = null
                                onMove(index, index - 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Вниз") },
                            enabled = index < cps.lastIndex,
                            onClick = {
                                menuFor = null
                                onMove(index, index + 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить", color = Danger) },
                            onClick = {
                                menuFor = null
                                confirmDelete = index
                            },
                        )
                    }
                }
            }
        }
    }
    if (from != null && dragTo != null) {
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (dragTo * rowPx).roundToInt()) }
                .fillMaxWidth()
                .height(3.dp)
                .background(OkGreen),
        )
    }
    }
    addAfter?.let { index ->
        AlertDialog(
            onDismissRequest = { addAfter = null },
            title = { Text("Добавить КП") },
            text = {
                OutlinedTextField(
                    value = addValue,
                    onValueChange = { addValue = it.filter { ch -> ch.isDigit() }.take(3) },
                    singleLine = true,
                    label = { Text("Номер КП") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val cp = addValue.toIntOrNull() ?: return@KeyboardActions
                            val after = addAfter ?: return@KeyboardActions
                            addAfter = null
                            addValue = ""
                            onInsertAfter(after, cp)
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(addFocus),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = addValue.toIntOrNull()?.let { it > 0 } == true,
                    onClick = {
                        val cp = addValue.toIntOrNull() ?: return@TextButton
                        val after = addAfter ?: return@TextButton
                        addAfter = null
                        addValue = ""
                        onInsertAfter(after, cp)
                    },
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { addAfter = null }) { Text("Отмена") }
            },
        )
    }
    confirmDelete?.let { index ->
        val cp = cps.getOrNull(index)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить КП${if (cp != null) " $cp" else ""}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDelete(index)
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
    startCp: Int,
    finishCp: Int,
    liveNote: String,
    livePending: Boolean,
    liveUrl: String?,
    liveShareText: String?,
    onSendLive: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
    canPrint: Boolean = false,
    onPrint: (ScoredResult) -> Unit = {},
    onPickFinishCsv: () -> Unit = {},
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
            TextButton(onClick = onPickFinishCsv) {
                Text("Загрузить CSV")
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
        if (liveUrl != null) {
            LiveLinkRow(
                url = liveUrl,
                shareText = liveShareText ?: liveUrl,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
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
                            canPrint = canPrint,
                            onPrint = onPrint,
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
    canPrint: Boolean = false,
    onPrint: (ScoredResult) -> Unit = {},
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
                onClose = onToggle,
                onAddCp = onAddCp,
                onAssignCourse = onAssignCourse,
                onDeleteCorrection = onDeleteCorrection,
                canPrint = canPrint,
                onPrint = onPrint,
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
    onClose: () -> Unit,
    onAddCp: (Int, String, Int) -> Unit,
    onAssignCourse: (Int, String, String) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
    canPrint: Boolean,
    onPrint: (ScoredResult) -> Unit,
) {
    val id = row.logicalId
    val required = filterCourseCps(
        courses.find { it.name.equals(row.course, true) }?.controls.orEmpty(),
        removedCps,
    )
    val addCounts = EventStore.addCpCounts(id, row.course, corrections)
    val window = orientPunchWindow(row.punches, startCp, finishCp)
    val expanded = applyAddedCps(window, required, addCounts, startCp, finishCp)
    val addable = addableCpsFor(row, courses, corrections, removedCps, startCp, finishCp)
    var marksOpen by remember { mutableStateOf(false) }
    var courseChangeOpen by remember { mutableStateOf(false) }
    EditorCard(
        onClose = onClose,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
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
            val personal = corrections.filter {
                (it.type == "add_cp" || it.type == "assign_course") && it.logicalId == id
            }
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
            punches = expanded,
            required = required,
            startCp = startCp,
            finishCp = finishCp,
            onDismiss = { marksOpen = false },
            canPrint = canPrint,
            onPrint = { onPrint(row) },
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
    punches: List<OrientMark>,
    required: List<Int>,
    startCp: Int,
    finishCp: Int,
    onDismiss: () -> Unit,
    canPrint: Boolean,
    onPrint: () -> Unit,
) {
    val classified = classifyActualPunches(punches, required, startCp, finishCp)
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
                                if (mark.added) "${mark.cp}*" else mark.cp.toString(),
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
        dismissButton = {
            if (canPrint) {
                TextButton(onClick = onPrint) { Text("Печать") }
            }
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
fun StartsScreen(
    starts: List<SavedStart>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onNewStart: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SavedStart?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Текущий старт не удаляется при «Новый старт» — он остаётся в этом списке. CSV читает десктоп: финишный дамп, стартовый протокол и дистанции.",
                color = Muted,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewStart) { Text("Новый старт") }
            }
        }
        if (starts.isEmpty()) {
            item { Text("Сохранённых стартов нет.", color = Muted) }
        }
        items(starts, key = { it.id }) { start ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !start.current) { onOpen(start.id) },
                        ) {
                            Text(start.label, style = MaterialTheme.typography.titleMedium)
                            val bits = buildList {
                                if (start.date.isNotBlank()) add(start.date)
                                add("${start.personCount} уч.")
                                add("${start.chipCount} чипов")
                                if (start.current) add("текущий")
                            }
                            Text(bits.joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (!start.current) {
                                Text("Нажмите, чтобы открыть", color = OkGreen, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        CompactTextButton("CSV", onClick = { onExport(start.id) })
                        if (!start.current) {
                            IconButton(onClick = { pendingDelete = start }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить старт", tint = Danger)
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить старт?") },
            text = {
                Text(
                    "«${start.label}» будет удалён безвозвратно: протокол, дистанции, считанные чипы и корректировки. " +
                        "Сначала экспортируйте CSV, если данные ещё нужны.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(start.id)
                    },
                ) {
                    Text("Удалить", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun MoreMenu(onOpen: (MorePage) -> Unit) {
    val items = listOf(
        MorePage.Starts to "Старты и экспорт",
        MorePage.Printer to "Принтер",
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
    LaunchedEffect(available) {
        if (selected != null && selected !in available) selected = null
    }
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
            ChoiceMenu(
                label = "КП на дистанциях",
                options = available,
                current = selected,
                placeholder = "Выберите КП",
                optionLabel = { "КП $it" },
                onSelect = { selected = it },
            )
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
    var selectedLeg by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var maxMinutes by remember { mutableStateOf("") }
    val selectedCourse = courses.find { it.name.equals(courseName, true) }
    val used = exclusions.filter { it.courseName.equals(courseName, true) }
        .map { it.fromCp to it.toCp }
        .toSet()
    val legs = selectedCourse?.let { EventStore.legsFor(it, settings, removed) }.orEmpty()
        .filter { it !in used }
    LaunchedEffect(courseName) {
        selectedLeg = null
    }
    LaunchedEffect(legs) {
        if (selectedLeg != null && selectedLeg !in legs) selectedLeg = null
    }
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
            ChoiceMenu(
                label = "Дистанция",
                options = courses.map { it.name },
                current = courseName.ifBlank { null },
                placeholder = "Выберите дистанцию",
                optionLabel = { it },
                onSelect = { courseName = it },
            )
            if (legs.isEmpty()) {
                Text(
                    if (selectedCourse == null) "Выберите дистанцию." else "Все перегоны этой дистанции уже исключены.",
                    color = Muted,
                )
            } else {
                ChoiceMenu(
                    label = "Перегон",
                    options = legs,
                    current = selectedLeg,
                    placeholder = "Выберите перегон",
                    optionLabel = { (from, to) ->
                        "${formatCp(from, settings)} → ${formatCp(to, settings)}"
                    },
                    onSelect = { selectedLeg = it },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = maxMinutes,
                    onValueChange = { maxMinutes = it.filter { ch -> ch.isDigit() }.take(3) },
                    singleLine = true,
                    label = { Text("Макс. мин") },
                    placeholder = { Text("нет") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(108.dp),
                )
                Button(
                    onClick = {
                        val leg = selectedLeg ?: return@Button
                        onAdd(courseName, leg.first, leg.second, maxMinutes.toIntOrNull())
                        selectedLeg = null
                        maxMinutes = ""
                    },
                    enabled = selectedLeg != null && courseName.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Добавить") }
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
}

@Composable
fun CorrectionsScreen(
    results: List<ScoredResult>,
    people: List<StartPerson>,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    startCp: Int,
    finishCp: Int,
    onAddCp: (Int, String, Int) -> Unit,
    onDeleteCorrection: (Long) -> Unit,
) {
    val personal = corrections.filter { it.logicalId != null && (it.type == "add_cp" || it.type == "assign_course") }
    val removedCps = EventStore.removedCps(corrections)
    val candidates = results.mapNotNull { row ->
        val id = row.logicalId ?: return@mapNotNull null
        val missed = addableCpsFor(row, courses, corrections, removedCps, startCp, finishCp)
        if (missed.isEmpty()) null else Triple(row, id, missed)
    }
    var personId by remember { mutableStateOf<Int?>(null) }
    var addCp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(candidates.map { it.second }) {
        if (personId != null && candidates.none { it.second == personId }) {
            personId = null
            addCp = null
        }
    }
    val selected = candidates.find { it.second == personId }
    val missed = selected?.third.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Только участники с невзятыми КП. После выбора появляется список пропущенных пунктов — как в финишном протоколе.",
            color = Muted,
        )
        if (candidates.isEmpty()) {
            Text("Нет участников с пропущенными КП.", color = Muted)
        } else {
            ChoiceMenu(
                label = "Участник",
                options = candidates,
                current = selected,
                placeholder = "Выберите участника",
                optionLabel = { (row, id, rowMissed) ->
                    val left = rowMissed.sumOf { item -> item.second }
                    "$id · ${row.name} · не хватает $left"
                },
                onSelect = {
                    personId = it.second
                    addCp = null
                },
            )
            if (selected != null) {
                ChoiceMenu(
                    label = "Невзятые КП",
                    options = missed,
                    current = missed.find { it.first == addCp },
                    placeholder = "Выберите КП",
                    optionLabel = { (cp, count) -> if (count > 1) "$cp (ещё $count)" else "КП $cp" },
                    onSelect = { addCp = it.first },
                )
                Button(
                    enabled = addCp != null,
                    onClick = {
                        val id = personId ?: return@Button
                        val cp = addCp ?: return@Button
                        onAddCp(id, selected.first.course, cp)
                        addCp = null
                    },
                ) { Text("Добавить взятие КП") }
            }
        }
        if (personal.isEmpty()) {
            Text("Персональных корректировок нет", color = Muted)
        } else {
            personal.forEach { row ->
                val who = results.find { it.logicalId == row.logicalId }?.name
                    ?: people.find { it.id == row.logicalId }?.name
                    ?: ""
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
        val previewUrl = EventStore.liveViewerUrl(api, slug)
        if (previewUrl != null) {
            LiveLinkRow(
                url = previewUrl,
                shareText = EventStore.liveShareText(
                    settings.copy(apiBaseUrl = api, slug = slug, title = title),
                ) ?: previewUrl,
            )
        } else {
            Text("Ссылка на результаты появится, когда заполнены адрес сайта и slug.", color = Muted)
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceMenu(
    label: String,
    options: List<T>,
    current: T?,
    placeholder: String,
    enabled: Boolean = true,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val open = expanded && enabled && options.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (enabled && options.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = current?.let(optionLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled && options.isNotEmpty(),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled && options.isNotEmpty())
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun addableCpsFor(
    row: ScoredResult,
    courses: List<StartCourse>,
    corrections: List<CorrectionEntity>,
    removedCps: Set<Int>,
    startCp: Int,
    finishCp: Int,
): List<Pair<Int, Int>> {
    val id = row.logicalId ?: return emptyList()
    val required = filterCourseCps(
        courses.find { it.name.equals(row.course, true) }?.controls.orEmpty(),
        removedCps,
    )
    val addCounts = EventStore.addCpCounts(id, row.course, corrections)
    val window = orientPunchWindow(row.punches, startCp, finishCp)
    val expanded = applyAddedCps(window, required, addCounts, startCp, finishCp)
    return addableCourseCps(courseProgress(expanded.map { it.cp }, required))
}

@Composable
fun CourseChoice(
    label: String,
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
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
