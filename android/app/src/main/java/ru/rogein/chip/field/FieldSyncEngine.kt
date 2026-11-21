package ru.rogein.chip.field

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.field.FIELD_DEFAULT_PORT
import ru.rogein.chip.parse.field.FIELD_OP_SCHEMA
import ru.rogein.chip.parse.field.FIELD_PROTOCOL_VERSION
import ru.rogein.chip.parse.field.FieldOpEnvelope
import ru.rogein.chip.parse.field.FieldSyncMode
import ru.rogein.chip.parse.field.FieldSyncRole
import ru.rogein.chip.parse.field.FieldTransport
import ru.rogein.chip.parse.field.cursorsToJson
import ru.rogein.chip.parse.field.fieldOpEnvelopeFromJson
import ru.rogein.chip.parse.field.fieldOpEnvelopeToJson
import ru.rogein.chip.parse.field.opsBundleJson
import ru.rogein.chip.parse.field.parseOpsBundle
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class FieldSyncUiState(
    val mode: String = FieldSyncMode.OFF,
    val role: String = FieldSyncRole.HOST,
    val deviceName: String = FieldDeviceStore.DEFAULT_NAME,
    val deviceId: String = "",
    val startCode: String = "",
    val hostBaseUrl: String = "",
    val localIp: String = "",
    val localIps: List<FieldLanIp> = emptyList(),
    val gatewayIp: String = "",
    val port: Int = FIELD_DEFAULT_PORT,
    val running: Boolean = false,
    val status: String = "выкл",
    val lastError: String? = null,
    val opCount: Long = 0,
    val lastSyncAt: Long = 0L,
    val peers: List<FieldPeerView> = emptyList(),
    val eventSlug: String = "",
    /** Клиент сейчас на связи с хостом (последний hello/tick успешен). */
    val linked: Boolean = false,
    /** Предпочтение клиента: auto / websocket / http. */
    val transport: String = FieldTransport.AUTO,
    /** Фактически используемый канал: websocket / http / cloud / "". */
    val activeChannel: String = "",
    /** Cloud: этот телефон — издатель live. */
    val cloudPublisher: Boolean = true,
)

/**
 * LAN-хост: HTTP + WebSocket на :8787.
 * LAN-клиент: WS / HTTP poll.
 * Cloud: HTTP poll к /api/field/{slug} на сайте (Bearer + код старта).
 */
class FieldSyncEngine(
    context: Context,
    private val journal: FieldJournal,
    private val applier: FieldOpApplier,
    private val eventSlug: suspend () -> String,
    private val siteBaseUrl: suspend () -> String = { "" },
    private val publishToken: suspend () -> String = { "" },
) {
    private val appContext = context.applicationContext
    private val device = journal.device
    private val mutex = Mutex()
    private val lastPostedSeq = AtomicLong(0L)
    private var hostServer: FieldHostServer? = null
    private var loopJob: Job? = null
    private var pushJob: Job? = null
    private var scope: CoroutineScope? = null
    private var cachedSlug: String = ""
    private var activeChannel: String = ""
    private val liveWs = AtomicReference<WebSocket?>(null)
    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow(readUiState(running = false, status = "выкл"))
    val state: StateFlow<FieldSyncUiState> = _state.asStateFlow()

    fun refreshUi() {
        _state.value = readUiState(
            running = hostServer?.isRunning == true || loopJob?.isActive == true,
            status = _state.value.status,
            lastError = _state.value.lastError,
            lastSyncAt = _state.value.lastSyncAt,
            peers = hostServer?.peerList().orEmpty(),
            linked = _state.value.linked,
            activeChannel = activeChannel,
        )
    }

    suspend fun probeHost(hostBaseUrl: String, startCode: String): String? {
        val base = FieldDeviceStore.normalizeHostEndpoint(hostBaseUrl)
        if (base.isEmpty()) return "укажите IP хоста"
        val code = startCode.trim()
        if (code.isEmpty()) return "нужен пароль"
        return try {
            hello(base, codeOverride = code)
            null
        } catch (e: Exception) {
            e.message ?: "нет связи"
        }
    }

    fun updateSettings(
        mode: String,
        role: String,
        deviceName: String,
        startCode: String,
        hostBaseUrl: String,
        transport: String = device.transport,
        cloudPublisher: Boolean = device.cloudPublisher,
    ) {
        device.syncMode = mode
        device.syncRole = role
        device.deviceName = deviceName
        device.startCode = startCode.trim()
        device.hostBaseUrl = hostBaseUrl
        device.transport = transport
        device.cloudPublisher = cloudPublisher
        refreshUi()
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        stopInternal()
        if (!device.fieldSyncEnabled) {
            _state.value = readUiState(running = false, status = "выкл")
            return
        }
        if (device.startCode.isBlank()) {
            _state.value = readUiState(
                running = false,
                status = "нужен пароль",
                lastError = "Задайте пароль обмена — без него сеть не запускается",
            )
            return
        }
        if (device.cloudEnabled) {
            loopJob = scope.launch(Dispatchers.IO) {
                cloudPollLoop()
            }
            _state.value = readUiState(running = true, status = "облако…")
            return
        }
        if (device.isHost) {
            activeChannel = ""
            runCatching { FieldHostKeepAlive.start(appContext) }
            val server = FieldHostServer(
                journal = journal,
                ingest = { ops -> ingestRemote(ops) },
                eventSlug = { eventSlug() },
                startCode = { device.startCode },
            )
            hostServer = server
            server.start(scope)
            loopJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    cachedSlug = runCatching { eventSlug() }.getOrDefault("")
                    refreshUiRunning(
                        status = if (server.isRunning) {
                            "хост · HTTP+WebSocket :$FIELD_DEFAULT_PORT"
                        } else {
                            "хост: ошибка порта"
                        },
                    )
                    delay(2_000)
                }
            }
            _state.value = readUiState(
                running = true,
                status = "хост · HTTP+WebSocket :$FIELD_DEFAULT_PORT",
            )
        } else if (device.isClient) {
            loopJob = scope.launch(Dispatchers.IO) {
                clientMainLoop()
            }
            _state.value = readUiState(running = true, status = "клиент…")
        }
    }

    fun stop() {
        stopInternal()
        _state.value = readUiState(
            running = false,
            status = if (device.fieldSyncEnabled) "остановлен" else "выкл",
            linked = false,
            activeChannel = "",
        )
    }

    /** Проверка cloud hello (токен + код + slug). null = ок, иначе текст ошибки. */
    suspend fun probeCloud(startCode: String): String? {
        val code = startCode.trim()
        if (code.isEmpty()) return "укажите пароль обмена"
        return try {
            helloCloud(codeOverride = code)
            null
        } catch (e: Exception) {
            e.message ?: "нет связи с сайтом"
        }
    }

    fun becomeHost(scope: CoroutineScope) {
        device.syncMode = FieldSyncMode.LAN
        device.syncRole = FieldSyncRole.HOST
        start(scope)
    }

    fun onJournalCleared() {
        lastPostedSeq.set(0L)
        refreshUi()
    }

    suspend fun pullFullSnapshot(): Int {
        if (!device.isClient) error("снимок забирает только клиент")
        val base = device.normalizedHostUrl()
        if (base.isEmpty()) error("укажите адрес хоста")
        hello(base)
        val conn = open(base, "/field/snapshot", "GET")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный пароль")
            if (code !in 200..299) error("snapshot HTTP $code: $body")
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("ops") ?: JSONArray()
            val ops = buildList {
                for (i in 0 until arr.length()) {
                    add(fieldOpEnvelopeFromJson(arr.getJSONObject(i)))
                }
            }
            val applied = ingestRemote(ops)
            _state.value = readUiState(
                running = true,
                status = "снимок: +$applied · ${channelLabel()}",
                lastError = null,
                lastSyncAt = System.currentTimeMillis(),
                linked = true,
                activeChannel = activeChannel,
            )
            return applied
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun clientMainLoop() {
        val prefer = device.transport
        when (prefer) {
            FieldTransport.HTTP -> {
                activeChannel = FieldTransport.HTTP
                httpPollLoop()
            }
            FieldTransport.WEBSOCKET -> {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    runCatching { runWsSession() }
                        .onSuccess { ok ->
                            if (!ok) {
                                _state.value = readUiState(
                                    running = true,
                                    status = "нет связи · WebSocket",
                                    lastError = "WebSocket недоступен",
                                    linked = false,
                                    activeChannel = "",
                                )
                            }
                        }
                        .onFailure { err ->
                            _state.value = readUiState(
                                running = true,
                                status = "нет связи · WebSocket",
                                lastError = err.message,
                                linked = false,
                                activeChannel = "",
                            )
                        }
                    delay(2_000)
                }
            }
            else -> {
                // auto: сначала WS, при обрыве — HTTP, периодически снова пробуем WS
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val wsOk = runCatching { runWsSession() }.getOrDefault(false)
                    if (wsOk) {
                        delay(500)
                        continue
                    }
                    activeChannel = FieldTransport.HTTP
                    _state.value = readUiState(
                        running = true,
                        status = "опрос HTTP (запасной канал)",
                        lastError = null,
                        linked = false,
                        activeChannel = activeChannel,
                    )
                    // HTTP poll with occasional WS retry
                    var ticks = 0
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        runCatching { clientHttpTick() }
                            .onFailure { err ->
                                _state.value = readUiState(
                                    running = true,
                                    status = "нет связи · HTTP",
                                    lastError = err.message,
                                    lastSyncAt = _state.value.lastSyncAt,
                                    linked = false,
                                    activeChannel = FieldTransport.HTTP,
                                )
                            }
                        delay(2_000)
                        ticks++
                        if (ticks % 15 == 0) break // ~30s → снова WS
                    }
                }
            }
        }
    }

    private suspend fun httpPollLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            runCatching { clientHttpTick() }
                .onFailure { err ->
                    _state.value = readUiState(
                        running = true,
                        status = "нет связи · HTTP",
                        lastError = err.message,
                        lastSyncAt = _state.value.lastSyncAt,
                        linked = false,
                        activeChannel = FieldTransport.HTTP,
                    )
                }
            delay(2_000)
        }
    }

    private suspend fun cloudPollLoop() {
        activeChannel = "cloud"
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            runCatching { cloudHttpTick() }
                .onFailure { err ->
                    _state.value = readUiState(
                        running = true,
                        status = "нет связи · облако",
                        lastError = err.message,
                        lastSyncAt = _state.value.lastSyncAt,
                        linked = false,
                        activeChannel = "cloud",
                    )
                }
            delay(2_000)
        }
    }

    private suspend fun cloudHttpTick() {
        if (device.startCode.isBlank()) {
            _state.value = readUiState(
                running = true,
                status = "нужен пароль",
                linked = false,
                activeChannel = "cloud",
            )
            return
        }
        helloCloud()
        val posted = postOwnCloud()
        val pulled = pullRemoteCloud()
        val note = buildString {
            append("связано · облако")
            if (device.cloudPublisher) append(" · издатель live")
            if (posted > 0) append(", +$posted своих")
            if (pulled > 0) append(", +$pulled чужих")
        }
        activeChannel = "cloud"
        _state.value = readUiState(
            running = true,
            status = note,
            lastError = null,
            lastSyncAt = System.currentTimeMillis(),
            linked = true,
            activeChannel = "cloud",
        )
    }

    private suspend fun cloudFieldBase(): String {
        val site = siteBaseUrl().trim().trimEnd('/')
        if (site.isEmpty()) error("укажите адрес сайта в настройках")
        val token = publishToken().trim()
        if (token.isEmpty()) error("укажите токен публикации в настройках")
        lastPublishToken = token
        val slug = eventSlug().trim()
        if (slug.isEmpty()) error("укажите slug старта")
        cachedSlug = slug
        val encodedSlug = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
        return "$site/api/field/$encodedSlug"
    }

    private suspend fun helloCloud(codeOverride: String? = null) {
        val base = cloudFieldBase()
        val conn = open(base, "/hello", "GET", codeOverride, bearer = true)
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный токен или пароль")
            if (code !in 200..299) error("hello HTTP $code: $body")
            val obj = JSONObject(body)
            val protocol = obj.optInt("protocol_version", 0)
            if (protocol > FIELD_PROTOCOL_VERSION) {
                error("сайт новее (protocol $protocol) — обновите приложение")
            }
            val schema = obj.optInt("schema", 0)
            if (schema > FIELD_OP_SCHEMA) {
                error("сайт schema $schema — обновите приложение")
            }
            val remoteSlug = obj.optString("event_slug").trim()
            cachedSlug = remoteSlug.ifBlank { cachedSlug }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun postOwnCloud(): Int {
        val after = lastPostedSeq.get()
        val ops = journal.ownOpsAfter(after)
        if (ops.isEmpty()) return 0
        val base = cloudFieldBase()
        val conn = open(base, "/ops", "POST", bearer = true)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = opsBundleJson(ops).toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный токен или пароль")
            if (code !in 200..299) error("POST ops HTTP $code: $body")
            val maxSeq = ops.maxOf { it.deviceSeq }
            lastPostedSeq.updateAndGet { prev -> maxOf(prev, maxSeq) }
            return ops.size
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun pullRemoteCloud(): Int {
        val cursors = cursorsToJson(journal.cursors())
        val encoded = URLEncoder.encode(cursors, Charsets.UTF_8.name())
        val base = cloudFieldBase()
        val conn = open(base, "/ops?cursors=$encoded", "GET", bearer = true)
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный токен или пароль")
            if (code !in 200..299) error("GET ops HTTP $code: $body")
            val ops = parseOpsBundle(body)
            return ingestRemote(ops)
        } finally {
            conn.disconnect()
        }
    }

    /** true — сессия закрылась штатно; false — ошибка/не удалось открыть. */
    private suspend fun runWsSession(): Boolean {
        val base = device.normalizedHostUrl()
        if (base.isEmpty() || device.startCode.isBlank()) {
            _state.value = readUiState(
                running = true,
                status = "укажите адрес и пароль",
                linked = false,
                activeChannel = "",
            )
            return false
        }
        // HTTP hello сначала — проверка пароля/slug
        try {
            hello(base)
        } catch (e: Exception) {
            _state.value = readUiState(
                running = true,
                status = "нет связи",
                lastError = e.message,
                linked = false,
                activeChannel = "",
            )
            return false
        }
        val wsUrl = base.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + FieldWebSocket.PATH
        return suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            fun finish(ok: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                if (cont.isActive) cont.resume(ok)
            }
            val request = Request.Builder()
                .url(wsUrl)
                .header("X-RogeIn-Field-Code", device.startCode.trim())
                .header("X-RogeIn-Device-Id", device.deviceId)
                .header("X-RogeIn-Device-Name", device.deviceName)
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        liveWs.set(webSocket)
                        activeChannel = FieldTransport.WEBSOCKET
                        pushJob?.cancel()
                        pushJob = scope?.launch(Dispatchers.IO) {
                            try {
                                val cursors = journal.cursors()
                                webSocket.send(
                                    JSONObject()
                                        .put("type", "sync")
                                        .put("cursors", JSONObject(cursorsToJson(cursors)))
                                        .toString(),
                                )
                                while (isActive && liveWs.get() === webSocket) {
                                    pushOwnOverWs(webSocket)
                                    delay(1_000)
                                }
                            } catch (_: Exception) {
                                runCatching { webSocket.close(1011, "push-error") }
                            }
                        }
                        _state.value = readUiState(
                            running = true,
                            status = "связано · WebSocket",
                            lastError = null,
                            lastSyncAt = System.currentTimeMillis(),
                            linked = true,
                            activeChannel = FieldTransport.WEBSOCKET,
                        )
                    } catch (e: Exception) {
                        runCatching { webSocket.close(1011, e.message) }
                        cleanupWs(webSocket)
                        finish(false)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope?.launch(Dispatchers.IO) {
                        runCatching { handleWsMessage(text) }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(1000, null) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cleanupWs(webSocket)
                    finish(true)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    cleanupWs(webSocket)
                    finish(false)
                }
            }
            val ws = okHttp.newWebSocket(request, listener)
            cont.invokeOnCancellation {
                runCatching { ws.close(1000, "cancel") }
                cleanupWs(ws)
                finish(false)
            }
        }
    }

    private fun cleanupWs(webSocket: WebSocket) {
        liveWs.compareAndSet(webSocket, null)
        pushJob?.cancel()
        pushJob = null
        if (liveWs.get() == null) activeChannel = ""
    }

    private suspend fun pushOwnOverWs(webSocket: WebSocket) {
        val after = lastPostedSeq.get()
        val ops = journal.ownOpsAfter(after)
        if (ops.isEmpty()) return
        val arr = JSONArray()
        ops.forEach { arr.put(fieldOpEnvelopeToJson(it)) }
        val ok = webSocket.send(JSONObject().put("type", "ops").put("ops", arr).toString())
        if (ok) {
            val maxSeq = ops.maxOf { it.deviceSeq }
            lastPostedSeq.updateAndGet { prev -> maxOf(prev, maxSeq) }
        }
    }

    private suspend fun handleWsMessage(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (obj.optString("type")) {
            "hello" -> {
                cachedSlug = obj.optString("event_slug").ifBlank { cachedSlug }
            }
            "ops" -> {
                val arr = obj.optJSONArray("ops") ?: JSONArray()
                val ops = buildList {
                    for (i in 0 until arr.length()) {
                        add(fieldOpEnvelopeFromJson(arr.getJSONObject(i)))
                    }
                }
                val n = ingestRemote(ops)
                if (n > 0) {
                    _state.value = readUiState(
                        running = true,
                        status = "связано · WebSocket (+$n)",
                        lastError = null,
                        lastSyncAt = System.currentTimeMillis(),
                        linked = true,
                        activeChannel = FieldTransport.WEBSOCKET,
                    )
                }
            }
            "pong" -> Unit
            else -> Unit
        }
    }

    private fun stopInternal() {
        loopJob?.cancel()
        loopJob = null
        pushJob?.cancel()
        pushJob = null
        liveWs.getAndSet(null)?.close(1000, "stop")
        hostServer?.stop()
        hostServer = null
        activeChannel = ""
        runCatching { FieldHostKeepAlive.stop(appContext) }
    }

    private suspend fun clientHttpTick() {
        val base = device.normalizedHostUrl()
        if (base.isEmpty()) {
            _state.value = readUiState(
                running = true,
                status = "укажите адрес хоста",
                lastError = null,
                linked = false,
                activeChannel = FieldTransport.HTTP,
            )
            return
        }
        if (device.startCode.isBlank()) {
            _state.value = readUiState(
                running = true,
                status = "нужен пароль",
                lastError = null,
                linked = false,
                activeChannel = FieldTransport.HTTP,
            )
            return
        }
        hello(base)
        val posted = postOwn(base)
        val pulled = pullRemote(base)
        val note = buildString {
            append("связано · HTTP")
            if (posted > 0) append(", +$posted своих")
            if (pulled > 0) append(", +$pulled чужих")
        }
        activeChannel = FieldTransport.HTTP
        _state.value = readUiState(
            running = true,
            status = note,
            lastError = null,
            lastSyncAt = System.currentTimeMillis(),
            linked = true,
            activeChannel = FieldTransport.HTTP,
        )
    }

    private suspend fun hello(base: String, codeOverride: String? = null) {
        val conn = open(base, "/field/hello", "GET", codeOverride)
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный пароль")
            if (code !in 200..299) error("hello HTTP $code: $body")
            val obj = JSONObject(body)
            val protocol = obj.optInt("protocol_version", 0)
            if (protocol > FIELD_PROTOCOL_VERSION) {
                error("хост новее (protocol $protocol) — обновите приложение")
            }
            val schema = obj.optInt("schema", 0)
            if (schema > FIELD_OP_SCHEMA) {
                error("хост schema $schema — обновите приложение")
            }
            val remoteSlug = obj.optString("event_slug").trim()
            cachedSlug = remoteSlug.ifBlank { cachedSlug }
            val localSlug = eventSlug().trim()
            if (remoteSlug.isNotEmpty() && localSlug.isNotEmpty() &&
                !remoteSlug.equals(localSlug, ignoreCase = true)
            ) {
                error("другой slug: у хоста «$remoteSlug», у вас «$localSlug»")
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun postOwn(base: String): Int {
        val after = lastPostedSeq.get()
        val ops = journal.ownOpsAfter(after)
        if (ops.isEmpty()) return 0
        val conn = open(base, "/field/ops", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = opsBundleJson(ops).toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный пароль")
            if (code !in 200..299) error("POST ops HTTP $code: $body")
            val maxSeq = ops.maxOf { it.deviceSeq }
            lastPostedSeq.updateAndGet { prev -> maxOf(prev, maxSeq) }
            return ops.size
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun pullRemote(base: String): Int {
        val cursors = cursorsToJson(journal.cursors())
        val encoded = URLEncoder.encode(cursors, Charsets.UTF_8.name())
        val conn = open(base, "/field/ops?cursors=$encoded", "GET")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == 401) error("неверный пароль")
            if (code !in 200..299) error("GET ops HTTP $code: $body")
            val ops = parseOpsBundle(body)
            return ingestRemote(ops)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun ingestRemote(ops: List<FieldOpEnvelope>): Int = mutex.withLock {
        var applied = 0
        for (op in ops) {
            if (journal.acceptRemote(op)) {
                runCatching { applier.apply(op) }
                applied++
            }
        }
        applied
    }

    @Volatile
    private var lastPublishToken: String = ""

    private fun open(
        base: String,
        pathAndQuery: String,
        method: String,
        codeOverride: String? = null,
        bearer: Boolean = false,
    ): HttpURLConnection {
        val url = URL(base.trimEnd('/') + pathAndQuery)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "X-RogeIn-Field-Code",
                (codeOverride ?: device.startCode).trim(),
            )
            setRequestProperty("X-RogeIn-Device-Id", device.deviceId)
            setRequestProperty("X-RogeIn-Device-Name", device.deviceName)
            if (bearer && lastPublishToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $lastPublishToken")
            }
        }
    }

    private fun channelLabel(): String = when (activeChannel) {
        FieldTransport.WEBSOCKET -> "WebSocket"
        FieldTransport.HTTP -> "HTTP"
        "cloud" -> "облако"
        else -> ""
    }

    private fun refreshUiRunning(status: String) {
        _state.value = readUiState(
            running = true,
            status = status,
            lastError = _state.value.lastError,
            lastSyncAt = _state.value.lastSyncAt,
            peers = hostServer?.peerList().orEmpty(),
            linked = _state.value.linked,
            activeChannel = activeChannel,
        )
    }

    private fun readUiState(
        running: Boolean,
        status: String,
        lastError: String? = null,
        lastSyncAt: Long = 0L,
        peers: List<FieldPeerView> = emptyList(),
        linked: Boolean = false,
        activeChannel: String = this.activeChannel,
    ): FieldSyncUiState {
        val lan = readFieldLanSnapshot(appContext)
        return FieldSyncUiState(
            mode = device.syncMode,
            role = device.syncRole,
            deviceName = device.deviceName,
            deviceId = device.deviceId,
            startCode = device.startCode,
            hostBaseUrl = device.hostBaseUrl,
            localIp = lan.primaryIp,
            localIps = lan.localIps,
            gatewayIp = lan.gatewayIp,
            port = FIELD_DEFAULT_PORT,
            running = running,
            status = status,
            lastError = lastError,
            opCount = 0L,
            lastSyncAt = lastSyncAt,
            peers = peers,
            eventSlug = cachedSlug,
            linked = linked,
            transport = device.transport,
            activeChannel = activeChannel,
            cloudPublisher = device.cloudPublisher,
        )
    }
}
