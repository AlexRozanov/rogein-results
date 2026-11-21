package ru.rogein.chip.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import ru.rogein.chip.field.FieldDeviceStore
import ru.rogein.chip.field.FieldPeerView
import ru.rogein.chip.field.FieldSyncUiState
import ru.rogein.chip.parse.field.FieldSyncMode
import ru.rogein.chip.parse.field.FieldSyncRole
import ru.rogein.chip.parse.field.FieldTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Muted = Color(0xFF5C5C5C)
private val Danger = Color(0xFFB00020)

private const val PASSWORD_HINT =
    "Общий пароль судейской сети: без него соседний клуб в той же Wi‑Fi не сядет в ваш журнал. " +
        "Slug для этого не подходит — он часто совпадает с публичной ссылкой на результаты. " +
        "Slug сверяется отдельно: телефоны должны быть на одном старте."

private const val NETWORK_HINT =
    "На клиентах введите адрес хоста (обычно IP этого телефона) и тот же пароль. " +
        "Если интернет раздаёт другой телефон — смотрите строку «Раздающий Wi‑Fi». " +
        "Пока хост запущен, экран не гаснет, а в шторке висит уведомление — так Android не усыпляет Wi‑Fi."

@Composable
fun FieldSyncScreen(
    state: FieldSyncUiState,
    eventSlug: String = "",
    hostProbe: FieldHostProbeUi = FieldHostProbeUi(),
    onRefresh: () -> Unit,
    onSaveAndApply: (
        mode: String,
        role: String,
        deviceName: String,
        startCode: String,
        hostBaseUrl: String,
        transport: String,
        cloudPublisher: Boolean,
    ) -> Unit,
    onStop: () -> Unit,
    onBecomeHost: () -> Unit = {},
    onPullSnapshot: () -> Unit = {},
    onProbeHost: (hostIp: String, hostPort: Int, startCode: String) -> Unit = { _, _, _ -> },
    onProbeCloud: (startCode: String) -> Unit = {},
    onClaimPublisher: (force: Boolean) -> Unit = {},
    /** Сохранить режим в prefs без запуска (после Стоп при переключении чипа). */
    onDraftMode: (mode: String, cloudPublisher: Boolean) -> Unit = { _, _ -> },
) {
    LaunchedEffect(Unit) { onRefresh() }
    val context = LocalContext.current
    var mode by remember(state.mode) { mutableStateOf(state.mode) }
    var role by remember(state.role) { mutableStateOf(state.role) }
    var deviceName by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var startCode by remember(state.startCode) { mutableStateOf(state.startCode) }
    val parsedHost = remember(state.hostBaseUrl) {
        FieldDeviceStore.parseHostEndpoint(state.hostBaseUrl)
    }
    var hostIp by remember(state.hostBaseUrl) { mutableStateOf(parsedHost.first) }
    var hostPort by remember(state.hostBaseUrl) {
        mutableStateOf(parsedHost.second.toString())
    }
    var transport by remember(state.transport) { mutableStateOf(state.transport) }
    var cloudPublisher by remember(state.cloudPublisher) { mutableStateOf(state.cloudPublisher) }
    var passwordHintOpen by remember { mutableStateOf(false) }
    var networkHintOpen by remember { mutableStateOf(false) }
    var becomeHostConfirm by remember { mutableStateOf(false) }
    var transportHintOpen by remember { mutableStateOf(false) }
    var stealPublisherConfirm by remember { mutableStateOf(false) }
    val slug = eventSlug.ifBlank { state.eventSlug }

    fun selectMode(next: String) {
        if (next == mode) return
        if (state.running) onStop()
        mode = next
        val publisher = if (next == FieldSyncMode.CLOUD) {
            cloudPublisher = false
            false
        } else {
            cloudPublisher
        }
        onDraftMode(next, publisher)
    }

    fun applyJoinPayload(raw: String) {
        val join = FieldDeviceStore.parseJoinUri(raw)
        if (join != null) {
            hostIp = join.host
            hostPort = join.port.toString()
            if (join.code.isNotBlank()) startCode = join.code
            Toast.makeText(context, "QR принят", Toast.LENGTH_SHORT).show()
            return
        }
        val (host, port) = FieldDeviceStore.parseHostEndpoint(raw)
        if (host.isNotEmpty()) {
            hostIp = host
            hostPort = port.toString()
            Toast.makeText(context, "Адрес принят", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Не удалось разобрать QR", Toast.LENGTH_SHORT).show()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        applyJoinPayload(contents)
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("QR подключения к хосту")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        } else {
            Toast.makeText(context, "Нужен доступ к камере", Toast.LENGTH_SHORT).show()
        }
    }

    // Проверка связи до «Запустить»
    LaunchedEffect(mode, role, hostIp, hostPort, startCode, state.running) {
        if (mode != FieldSyncMode.LAN || role != FieldSyncRole.CLIENT || state.running) return@LaunchedEffect
        delay(450)
        val port = hostPort.toIntOrNull() ?: FieldDeviceStore.DEFAULT_PORT
        onProbeHost(hostIp, port, startCode)
    }
    LaunchedEffect(mode, startCode, state.running) {
        if (mode != FieldSyncMode.CLOUD || state.running) return@LaunchedEffect
        delay(450)
        onProbeCloud(startCode)
    }

    val hostLinked = if (state.running) state.linked else hostProbe.reachable
    val canStartClient = hostProbe.reachable && !hostProbe.checking &&
        hostIp.isNotBlank() && startCode.isNotBlank()
    val canStartCloud = hostProbe.reachable && !hostProbe.checking && startCode.isNotBlank()
    val showBecomeHost = mode == FieldSyncMode.LAN &&
        role == FieldSyncRole.CLIENT && !hostLinked && !hostProbe.checking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (mode == FieldSyncMode.LAN) {
            Text(
                "Один хост в локальной Wi‑Fi. Клиенты шлют выдачу и финиш хосту и забирают чужие операции. Публикация на сайт — отдельно.",
                color = Muted,
            )
        }
        Text("Режим", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == FieldSyncMode.OFF,
                onClick = { selectMode(FieldSyncMode.OFF) },
                label = { Text("Выкл") },
            )
            FilterChip(
                selected = mode == FieldSyncMode.LAN,
                onClick = { selectMode(FieldSyncMode.LAN) },
                label = { Text("Локальная сеть") },
            )
            FilterChip(
                selected = mode == FieldSyncMode.CLOUD,
                onClick = { selectMode(FieldSyncMode.CLOUD) },
                label = { Text("Облако") },
            )
        }
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            singleLine = true,
            label = { Text("Имя аппарата") },
            placeholder = { Text("Выдача-1") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (mode == FieldSyncMode.LAN) {
            Text("Роль", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = role == FieldSyncRole.HOST,
                    onClick = { role = FieldSyncRole.HOST },
                    label = { Text("Хост") },
                )
                FilterChip(
                    selected = role == FieldSyncRole.CLIENT,
                    onClick = { role = FieldSyncRole.CLIENT },
                    label = { Text("Клиент") },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = startCode,
                    onValueChange = {
                        startCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6)
                    },
                    singleLine = true,
                    label = { Text("Пароль") },
                    placeholder = { Text("4–6 символов") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { passwordHintOpen = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Зачем нужен пароль",
                        tint = Muted,
                    )
                }
            }
            FieldLanAddressesBlock(
                state = state,
                role = role,
                onNetworkHint = { networkHintOpen = true },
            )
            if (role == FieldSyncRole.CLIENT) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Канал связи",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { transportHintOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Про канал", tint = Muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = transport == FieldTransport.AUTO,
                        onClick = { transport = FieldTransport.AUTO },
                        label = { Text("Авто") },
                    )
                    FilterChip(
                        selected = transport == FieldTransport.WEBSOCKET,
                        onClick = { transport = FieldTransport.WEBSOCKET },
                        label = { Text("WebSocket") },
                    )
                    FilterChip(
                        selected = transport == FieldTransport.HTTP,
                        onClick = { transport = FieldTransport.HTTP },
                        label = { Text("HTTP") },
                    )
                }
                if (state.running && state.activeChannel.isNotBlank()) {
                    Text(
                        "Сейчас: ${
                            when (state.activeChannel) {
                                FieldTransport.WEBSOCKET -> "WebSocket (мгновенно)"
                                FieldTransport.HTTP -> "HTTP-опрос (~2 с)"
                                else -> state.activeChannel
                            }
                        }",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (role == FieldSyncRole.HOST) {
                Text(
                    "Хост всегда принимает и WebSocket, и HTTP. Выбор канала — на клиентах.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (role == FieldSyncRole.CLIENT) {
                val exampleIp = state.gatewayIp.ifBlank { state.localIp }.ifBlank { "192.168.43.1" }
                Text(
                    "IP хоста или QR / ссылка rogein-field://…",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = hostIp,
                        onValueChange = { raw ->
                            val join = FieldDeviceStore.parseJoinUri(raw)
                            if (join != null) {
                                hostIp = join.host
                                hostPort = join.port.toString()
                                if (join.code.isNotBlank()) startCode = join.code
                            } else {
                                hostIp = raw.filter { ch -> ch.isDigit() || ch == '.' }
                            }
                        },
                        singleLine = true,
                        label = { Text("IP хоста") },
                        supportingText = {
                            Text("например $exampleIp", color = Muted)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hostPort,
                        onValueChange = { raw ->
                            hostPort = raw.filter { it.isDigit() }.take(5)
                        },
                        singleLine = true,
                        label = { Text("Порт") },
                        supportingText = {
                            Text("обычно ${state.port}", color = Muted)
                        },
                        modifier = Modifier.weight(0.42f),
                    )
                    IconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                scanLauncher.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("QR подключения к хосту")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(true),
                                )
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            FieldQrScanIcon,
                            contentDescription = "Сканировать QR",
                            tint = Muted,
                        )
                    }
                }
                when {
                    hostProbe.checking -> Text("Проверка связи с хостом…", color = Muted)
                    hostProbe.reachable -> Text("Хост отвечает — можно запускать.", color = Color(0xFF1B4D3E))
                    hostIp.isNotBlank() && startCode.isNotBlank() && !hostProbe.error.isNullOrBlank() ->
                        Text("Нет связи: ${hostProbe.error}", color = Danger)
                    hostIp.isBlank() || startCode.isBlank() ->
                        Text("Заполните IP и пароль — проверим связь с хостом.", color = Muted)
                }
            }
            if (role == FieldSyncRole.HOST && startCode.isNotBlank()) {
                FieldHostQrBlock(
                    state = state,
                    slug = slug,
                    startCode = startCode,
                )
            }
            if (state.running && role == FieldSyncRole.HOST) {
                FieldPeersBlock(peers = state.peers)
            }
        }
        if (mode == FieldSyncMode.CLOUD) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = startCode,
                    onValueChange = {
                        startCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6)
                    },
                    singleLine = true,
                    label = { Text("Пароль") },
                    placeholder = { Text("4–6 символов") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { passwordHintOpen = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Зачем нужен пароль",
                        tint = Muted,
                    )
                }
            }
            Text(
                "Адрес сайта, токен и slug — из настроек публикации. Первый телефон с этим паролем задаёт код сессии на сайте.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Издавать online",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = cloudPublisher,
                    onCheckedChange = { want ->
                        if (want && state.cloudPublisherName.isNotBlank() &&
                            !state.cloudPublisherName.equals(deviceName, ignoreCase = true) &&
                            state.cloudPublisherName != state.deviceId
                        ) {
                            stealPublisherConfirm = true
                        } else {
                            cloudPublisher = want
                            if (want && state.running) onClaimPublisher(false)
                        }
                    },
                )
            }
            when {
                cloudPublisher -> Text(
                    "Этот телефон — единственный live-издатель (роль держится на сайте).",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                state.cloudPublisherName.isNotBlank() -> Text(
                    "Сейчас издатель: ${state.cloudPublisherName}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    "Live с этого телефона не уходит — только журнал.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                hostProbe.checking -> Text("Проверка связи с сайтом…", color = Muted)
                hostProbe.reachable -> Text("Сайт отвечает — можно запускать.", color = Color(0xFF1B4D3E))
                startCode.isNotBlank() && !hostProbe.error.isNullOrBlank() ->
                    Text("Нет связи: ${hostProbe.error}", color = Danger)
                startCode.isBlank() ->
                    Text("Задайте пароль — проверим сайт, токен и slug.", color = Muted)
            }
            if (slug.isNotBlank()) {
                Text(
                    "slug  $slug",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        Text("Статус: ${state.status}", style = MaterialTheme.typography.bodyMedium)
        if (!state.lastError.isNullOrBlank()) {
            Text(state.lastError, color = Danger)
        }
        Text(
            "device_id  ${state.deviceId}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        val passwordOk = mode == FieldSyncMode.OFF || startCode.isNotBlank()
        if ((mode == FieldSyncMode.LAN || mode == FieldSyncMode.CLOUD) && startCode.isBlank()) {
            Text("Задайте пароль — без него обмен не запускается.", color = Danger)
        }
        if (mode == FieldSyncMode.OFF) {
            Button(
                onClick = {
                    onSaveAndApply(mode, role, deviceName, startCode, "", transport, cloudPublisher)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        } else if (state.running) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Стоп") }
            if (mode == FieldSyncMode.LAN && state.role == FieldSyncRole.CLIENT) {
                if (state.linked) {
                    OutlinedButton(
                        onClick = onPullSnapshot,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Забрать снимок с хоста") }
                }
                if (showBecomeHost) {
                    OutlinedButton(
                        onClick = { becomeHostConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Стать хостом") }
                }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить статус / IP")
            }
        } else {
            val startEnabled = when (mode) {
                FieldSyncMode.CLOUD -> passwordOk && canStartCloud
                FieldSyncMode.LAN -> passwordOk && (role != FieldSyncRole.CLIENT || canStartClient)
                else -> passwordOk
            }
            Button(
                onClick = {
                    val port = hostPort.toIntOrNull() ?: FieldDeviceStore.DEFAULT_PORT
                    val endpoint = if (mode == FieldSyncMode.LAN) {
                        FieldDeviceStore.buildHostEndpoint(hostIp, port)
                    } else {
                        ""
                    }
                    onSaveAndApply(mode, role, deviceName, startCode, endpoint, transport, cloudPublisher)
                },
                enabled = startEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Запустить") }
            if (showBecomeHost) {
                OutlinedButton(
                    onClick = { becomeHostConfirm = true },
                    enabled = passwordOk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Стать хостом") }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить статус / IP")
            }
        }
    }
    if (passwordHintOpen) {
        AlertDialog(
            onDismissRequest = { passwordHintOpen = false },
            title = { Text("Пароль обмена") },
            text = { Text(PASSWORD_HINT) },
            confirmButton = {
                TextButton(onClick = { passwordHintOpen = false }) { Text("Понятно") }
            },
        )
    }
    if (networkHintOpen) {
        AlertDialog(
            onDismissRequest = { networkHintOpen = false },
            title = { Text("Адреса в сети") },
            text = { Text(NETWORK_HINT) },
            confirmButton = {
                TextButton(onClick = { networkHintOpen = false }) { Text("Понятно") }
            },
        )
    }
    if (becomeHostConfirm) {
        AlertDialog(
            onDismissRequest = { becomeHostConfirm = false },
            title = { Text("Стать хостом?") },
            text = {
                Text(
                    "Этот телефон поднимет сервер со своим журналом. " +
                        "Старый хост должен быть выключен — два хоста в одной сети нельзя.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        becomeHostConfirm = false
                        role = FieldSyncRole.HOST
                        onBecomeHost()
                    },
                ) { Text("Стать хостом") }
            },
            dismissButton = {
                TextButton(onClick = { becomeHostConfirm = false }) { Text("Отмена") }
            },
        )
    }
    if (stealPublisherConfirm) {
        AlertDialog(
            onDismissRequest = { stealPublisherConfirm = false },
            title = { Text("Перехватить online?") },
            text = {
                Text(
                    "Сейчас online шлёт «${state.cloudPublisherName}». " +
                        "Два издателя затрут друг друга — перехватить роль на этот телефон?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stealPublisherConfirm = false
                        cloudPublisher = true
                        onClaimPublisher(true)
                    },
                ) { Text("Перехватить") }
            },
            dismissButton = {
                TextButton(onClick = { stealPublisherConfirm = false }) { Text("Отмена") }
            },
        )
    }
    if (transportHintOpen) {
        AlertDialog(
            onDismissRequest = { transportHintOpen = false },
            title = { Text("Канал связи") },
            text = {
                Text(
                    "Авто — сначала WebSocket (мгновенная доставка), при сбое переключается на HTTP-опрос раз в ~2 с.\n\n" +
                        "WebSocket — только сокет, без запасного опроса.\n\n" +
                        "HTTP — только опрос, как раньше. Удобно, если на сети режут Upgrade.\n\n" +
                        "Хост всегда слушает оба канала; переключатель нужен только клиенту. " +
                        "Смена канала — Стоп → выбрать → Запустить.",
                )
            },
            confirmButton = {
                TextButton(onClick = { transportHintOpen = false }) { Text("Понятно") }
            },
        )
    }
}

@Composable
private fun FieldHostQrBlock(
    state: FieldSyncUiState,
    slug: String,
    startCode: String,
) {
    val hostIp = state.localIps.firstOrNull()?.address
        ?: state.localIp.takeIf { it.isNotBlank() }
        ?: return
    val joinUri = FieldDeviceStore.buildJoinUri(hostIp, state.port, slug, startCode)
    val qr = remember(joinUri) { fieldJoinQrBitmap(joinUri) }
    Text("QR для клиентов", style = MaterialTheme.typography.titleMedium)
    Text(
        "Клиент вставляет ссылку в поле IP или сканирует QR другой программой.",
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
    if (qr != null) {
        Image(
            painter = BitmapPainter(qr),
            contentDescription = "QR подключения",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .size(200.dp),
            contentScale = ContentScale.Fit,
        )
    }
    FieldLanAddressRow(
        title = joinUri,
        detail = if (slug.isBlank()) "slug пуст — лучше заполнить в публикации" else null,
        copyValue = joinUri,
        shareSubject = "Подключение к хосту Rogein",
    )
}

@Composable
private fun FieldPeersBlock(peers: List<FieldPeerView>) {
    Text("Устройства в сети", style = MaterialTheme.typography.titleMedium)
    if (peers.isEmpty()) {
        Text("Пока никто не подключался.", color = Muted)
        return
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    peers.forEach { peer ->
        Text(
            "${peer.name}  ·  ${fmt.format(Date(peer.lastSeenAt))}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            peer.deviceId.take(8) + "…",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

private fun fieldJoinQrBitmap(url: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val size = 512
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

@Composable
private fun FieldLanAddressesBlock(
    state: FieldSyncUiState,
    role: String,
    onNetworkHint: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Адреса в сети",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNetworkHint, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Как подключаться",
                tint = Muted,
            )
        }
    }
    if (state.localIps.isEmpty()) {
        Text("Локальный IP пока не виден — включите Wi‑Fi или точку доступа, затем «Обновить».", color = Muted)
    } else {
        Text("Этот телефон:", color = Muted)
        state.localIps.forEach { row ->
            val shareValue = if (role == FieldSyncRole.HOST) {
                "http://${row.address}:${state.port}"
            } else {
                row.address
            }
            FieldLanAddressRow(
                title = "${row.address}  (${row.label})",
                detail = if (role == FieldSyncRole.HOST) shareValue else null,
                copyValue = shareValue,
                shareSubject = "Адрес хоста Rogein",
            )
        }
    }
    val gateway = state.gatewayIp
    if (gateway.isNotBlank()) {
        Text("Раздающий Wi‑Fi (шлюз):", color = Muted)
        val shareValue = if (role == FieldSyncRole.CLIENT) {
            "http://$gateway:${state.port}"
        } else {
            gateway
        }
        FieldLanAddressRow(
            title = gateway,
            detail = if (role == FieldSyncRole.CLIENT) {
                "часто адрес хоста, если он раздаёт сеть"
            } else {
                "если хост не этот телефон"
            },
            copyValue = shareValue,
            shareSubject = "Адрес хоста Rogein",
        )
    } else {
        Text(
            "Шлюз DHCP не определён (нормально, если этот телефон сам раздаёт точку доступа).",
            color = Muted,
        )
    }
}

@Composable
private fun FieldLanAddressRow(
    title: String,
    detail: String?,
    copyValue: String,
    shareSubject: String,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!detail.isNullOrBlank() && detail != title) {
                Text(
                    detail,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        IconButton(
            onClick = { copyFieldText(context, copyValue, "Скопировано") },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(FieldContentCopyIcon, contentDescription = "Копировать", tint = Muted)
        }
        IconButton(
            onClick = { shareFieldText(context, copyValue, shareSubject) },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Default.Share, contentDescription = "Отправить", tint = Muted)
        }
    }
}

private fun copyFieldText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("rogein-field", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private fun shareFieldText(context: Context, text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(intent, subject))
}

private val FieldQrScanIcon: ImageVector = ImageVector.Builder(
    name = "FieldQrScan",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Углы рамки сканера
        moveTo(3f, 5f)
        verticalLineTo(3f)
        horizontalLineToRelative(2f)
        verticalLineTo(1f)
        horizontalLineTo(1f)
        verticalLineToRelative(4f)
        close()
        moveTo(21f, 1f)
        horizontalLineToRelative(-4f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        close()
        moveTo(5f, 21f)
        horizontalLineTo(3f)
        verticalLineToRelative(-2f)
        horizontalLineTo(1f)
        verticalLineToRelative(4f)
        horizontalLineToRelative(4f)
        close()
        moveTo(21f, 19f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(4f)
        close()
        // QR-подобная середина
        moveTo(7f, 7f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(4f)
        horizontalLineTo(7f)
        close()
        moveTo(13f, 7f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(-4f)
        close()
        moveTo(7f, 13f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineTo(7f)
        close()
        moveTo(13f, 15f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(-2f)
        close()
        moveTo(17f, 15f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(-2f)
        close()
    }
}.build()

private val FieldContentCopyIcon: ImageVector = ImageVector.Builder(
    name = "FieldContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f)
        horizontalLineTo(4f)
        curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(2f)
        verticalLineTo(3f)
        horizontalLineToRelative(12f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        horizontalLineTo(8f)
        curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
        verticalLineToRelative(14f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(11f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(7f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
        moveTo(19f, 21f)
        horizontalLineTo(8f)
        verticalLineTo(7f)
        horizontalLineToRelative(11f)
        verticalLineToRelative(14f)
        close()
    }
}.build()
