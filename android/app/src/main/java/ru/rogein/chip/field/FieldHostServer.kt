package ru.rogein.chip.field

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.rogein.chip.parse.field.FIELD_DEFAULT_PORT
import ru.rogein.chip.parse.field.FIELD_OP_SCHEMA
import ru.rogein.chip.parse.field.FIELD_PROTOCOL_VERSION
import ru.rogein.chip.parse.field.FieldOpEnvelope
import ru.rogein.chip.parse.field.cursorsToJson
import ru.rogein.chip.parse.field.fieldOpEnvelopeFromJson
import ru.rogein.chip.parse.field.fieldOpEnvelopeToJson
import ru.rogein.chip.parse.field.opsBundleJson
import ru.rogein.chip.parse.field.parseCursorsJson
import ru.rogein.chip.parse.field.parseOpsBundle
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP + WebSocket хост полевого журнала на [FIELD_DEFAULT_PORT] (§6).
 * HTTP: hello / ops / snapshot. WS: `/field/ws` — push ops.
 */
class FieldHostServer(
    private val journal: FieldJournal,
    private val ingest: suspend (List<FieldOpEnvelope>) -> Int,
    private val eventSlug: suspend () -> String,
    private val startCode: () -> String,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val peers = ConcurrentHashMap<String, FieldPeerView>()
    private val wsSessions = ConcurrentHashMap<String, FieldWsSession>()
    private var journalListener: ((FieldOpEnvelope) -> Unit)? = null

    val isRunning: Boolean get() = running.get()

    fun peerList(): List<FieldPeerView> =
        peers.values.sortedByDescending { it.lastSeenAt }

    fun start(scope: CoroutineScope, port: Int = FIELD_DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        peers.clear()
        wsSessions.clear()
        val listener: (FieldOpEnvelope) -> Unit = { op -> broadcastOps(listOf(op)) }
        journalListener = listener
        journal.addOpListener(listener)
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port).also { serverSocket = it }
                while (isActive && running.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    } catch (_: IOException) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(socket)
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        running.set(false)
        journalListener?.let { journal.removeOpListener(it) }
        journalListener = null
        wsSessions.values.forEach { it.closeQuietly() }
        wsSessions.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        peers.clear()
    }

    private suspend fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readHttpRequest(input) ?: run {
            writeResponse(output, 400, """{"error":"bad_request"}""")
            runCatching { socket.close() }
            return
        }
        if (!authorize(request)) {
            writeResponse(output, 401, """{"error":"bad_code"}""")
            runCatching { socket.close() }
            return
        }
        notePeer(request)
        val path = request.path.substringBefore('?')
        val upgrade = request.headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true)
        if (request.method == "GET" && path == FieldWebSocket.PATH && upgrade) {
            handleWebSocket(socket, input, output, request)
            return
        }
        try {
            when {
                request.method == "GET" && path == "/field/hello" -> {
                    writeResponse(output, 200, helloJson())
                }
                request.method == "GET" && path == "/field/ops" -> {
                    val cursors = parseCursorsJson(request.query["cursors"])
                    writeResponse(output, 200, opsBundleJson(journal.opsAfter(cursors)))
                }
                request.method == "GET" && path == "/field/snapshot" -> {
                    val ops = journal.opsAfter(emptyMap())
                    val body = JSONObject()
                        .put("ops", JSONArray().also { arr ->
                            ops.forEach { arr.put(fieldOpEnvelopeToJson(it)) }
                        })
                        .put("cursors", JSONObject(cursorsToJson(journal.cursors())))
                        .put("op_count", journal.opCount())
                        .put("event_slug", eventSlug())
                        .toString()
                    writeResponse(output, 200, body)
                }
                request.method == "POST" && path == "/field/ops" -> {
                    val ops = parseOpsBundle(request.body)
                    val accepted = ingest(ops)
                    val body = JSONObject()
                        .put("accepted", accepted)
                        .put("cursors", JSONObject(cursorsToJson(journal.cursors())))
                        .toString()
                    writeResponse(output, 200, body)
                }
                else -> writeResponse(output, 404, """{"error":"not_found"}""")
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleWebSocket(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: HttpRequest,
    ) {
        val key = request.headers["sec-websocket-key"]?.trim().orEmpty()
        if (key.isEmpty()) {
            writeResponse(output, 400, """{"error":"missing_ws_key"}""")
            runCatching { socket.close() }
            return
        }
        val accept = FieldWebSocket.acceptKey(key)
        val header = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.flush()

        val deviceId = request.headers["x-rogein-device-id"]?.trim().orEmpty()
            .ifBlank { "ws-${System.currentTimeMillis()}" }
        val deviceName = FieldHttpHeaders.decodeDeviceName(
            request.headers["x-rogein-device-name"],
        )
        val session = FieldWsSession(input, output, deviceId, deviceName)
        wsSessions[deviceId] = session
        try {
            session.sendText(
                JSONObject()
                    .put("type", "hello")
                    .put("protocol_version", FIELD_PROTOCOL_VERSION)
                    .put("schema", FIELD_OP_SCHEMA)
                    .put("event_slug", eventSlug())
                    .put("host_device_id", journal.deviceId())
                    .put("host_name", journal.deviceName())
                    .put("op_count", journal.opCount())
                    .toString(),
            )
            while (session.isOpen() && running.get()) {
                val frame = try {
                    session.readFrame()
                } catch (_: EOFException) {
                    break
                } catch (_: IOException) {
                    break
                }
                try {
                    when (frame.opcode) {
                        FieldWebSocket.OP_CLOSE -> break
                        FieldWebSocket.OP_PING -> writeWsPong(output, frame.payload)
                        FieldWebSocket.OP_TEXT -> handleWsMessage(session, frame.text())
                        else -> Unit
                    }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
            // обрыв / кривой кадр — закрываем сессию
        } finally {
            wsSessions.remove(deviceId, session)
            session.closeQuietly()
            runCatching { socket.close() }
        }
    }

    private suspend fun handleWsMessage(session: FieldWsSession, text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (obj.optString("type")) {
            "sync" -> {
                val cursors = parseCursorsJson(obj.optJSONObject("cursors")?.toString() ?: "{}")
                session.cursors = cursors
                val ops = journal.opsAfter(cursors)
                session.sendText(opsPushJson(ops))
                session.cursors = mergeCursors(cursors, ops)
            }
            "ops" -> {
                val arr = obj.optJSONArray("ops") ?: JSONArray()
                val ops = buildList {
                    for (i in 0 until arr.length()) {
                        add(fieldOpEnvelopeFromJson(arr.getJSONObject(i)))
                    }
                }
                if (ops.isNotEmpty()) {
                    ingest(ops)
                }
            }
            "ping" -> session.sendText("""{"type":"pong"}""")
            else -> Unit
        }
    }

    private fun broadcastOps(ops: List<FieldOpEnvelope>) {
        if (ops.isEmpty()) return
        val msg = try {
            opsPushJson(ops)
        } catch (_: Exception) {
            return
        }
        for (session in wsSessions.values.toList()) {
            if (!session.isOpen()) continue
            try {
                session.sendText(msg)
                session.cursors = mergeCursors(session.cursors, ops)
            } catch (_: Exception) {
                session.closeQuietly()
                wsSessions.remove(session.deviceId, session)
            }
        }
    }

    private fun opsPushJson(ops: List<FieldOpEnvelope>): String {
        val arr = JSONArray()
        ops.forEach { arr.put(fieldOpEnvelopeToJson(it)) }
        return JSONObject().put("type", "ops").put("ops", arr).toString()
    }

    private fun mergeCursors(
        base: Map<String, Long>,
        ops: List<FieldOpEnvelope>,
    ): Map<String, Long> {
        if (ops.isEmpty()) return base
        val next = base.toMutableMap()
        for (op in ops) {
            val prev = next[op.deviceId] ?: 0L
            if (op.deviceSeq > prev) next[op.deviceId] = op.deviceSeq
        }
        return next
    }

    private suspend fun helloJson(): String =
        JSONObject()
            .put("protocol_version", FIELD_PROTOCOL_VERSION)
            .put("schema", FIELD_OP_SCHEMA)
            .put("event_slug", eventSlug())
            .put("host_device_id", journal.deviceId())
            .put("host_name", journal.deviceName())
            .put("op_count", journal.opCount())
            .put("websocket", true)
            .toString()

    private fun notePeer(request: HttpRequest) {
        val id = request.headers["x-rogein-device-id"]?.trim().orEmpty()
        if (id.isEmpty()) return
        val name = FieldHttpHeaders.decodeDeviceName(
            request.headers["x-rogein-device-name"],
        )
        peers[id] = FieldPeerView(
            deviceId = id,
            name = name,
            lastSeenAt = System.currentTimeMillis(),
        )
    }

    private fun authorize(request: HttpRequest): Boolean {
        val expected = startCode().trim()
        if (expected.isEmpty()) return false
        val got = request.headers["x-rogein-field-code"]?.trim().orEmpty()
        return got.equals(expected, ignoreCase = true)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            when {
                state == 0 && b == '\r'.code -> state = 1
                state == 1 && b == '\n'.code -> state = 2
                state == 2 && b == '\r'.code -> state = 3
                state == 3 && b == '\n'.code -> break
                else -> state = if (b == '\r'.code) 1 else 0
            }
            if (headerBytes.size() > 64_000) return null
        }
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val pathOnly = target.substringBefore('?')
        val queryString = target.substringAfter('?', missingDelimiterValue = "")
        val query = linkedMapOf<String, String>()
        if (queryString.isNotEmpty()) {
            queryString.split('&').forEach { pair ->
                val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
                query[key] = value
            }
        }
        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(method, pathOnly, query, headers, body)
    }

    private fun writeResponse(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            else -> "$code"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }
}
