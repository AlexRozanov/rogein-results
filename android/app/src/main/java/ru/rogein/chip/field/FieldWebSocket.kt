package ru.rogein.chip.field

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Минимальный WebSocket (RFC6455): text frames, без фрагментации.
 * Хост: unmasked → клиент; клиент: masked → хост.
 */
object FieldWebSocket {
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_PAYLOAD = 4 * 1024 * 1024

    const val PATH = "/field/ws"

    fun acceptKey(secWebSocketKey: String): String {
        val dig = MessageDigest.getInstance("SHA-1")
            .digest((secWebSocketKey.trim() + MAGIC).toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(dig, Base64.NO_WRAP)
    }

    fun writeText(out: OutputStream, text: String, mask: Boolean) {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD) { "ws payload too large" }
        val header = ByteArrayOutputStream()
        header.write(0x81) // FIN + text
        if (mask) {
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(0x80 or 126)
                    header.write((payload.size ushr 8) and 0xFF)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    val len = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong())
                    header.write(len.array())
                }
            }
            val key = Random.nextBytes(4)
            header.write(key)
            out.write(header.toByteArray())
            val masked = ByteArray(payload.size)
            for (i in payload.indices) masked[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
            out.write(masked)
        } else {
            when {
                payload.size < 126 -> header.write(payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(126)
                    header.write((payload.size ushr 8) and 0xFF)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(127)
                    val len = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong())
                    header.write(len.array())
                }
            }
            out.write(header.toByteArray())
            out.write(payload)
        }
        out.flush()
    }

    fun readFrame(input: InputStream): WsFrame {
        val b0 = input.read().takeIf { it >= 0 } ?: throw EOFException()
        val b1 = input.read().takeIf { it >= 0 } ?: throw EOFException()
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        when (len) {
            126L -> {
                val b = readExact(input, 2)
                len = ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toLong()
            }
            127L -> {
                val b = readExact(input, 8)
                len = 0L
                for (i in 0 until 8) len = (len shl 8) or (b[i].toInt() and 0xFF).toLong()
            }
        }
        if (len > MAX_PAYLOAD) throw IOException("ws frame too large: $len")
        val maskKey = if (masked) readExact(input, 4) else null
        val payload = readExact(input, len.toInt())
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(opcode = opcode, payload = payload)
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    const val OP_TEXT = 0x1
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA
}

data class WsFrame(val opcode: Int, val payload: ByteArray) {
    fun text(): String = payload.toString(Charsets.UTF_8)
}

/** Долгоживущая WS-сессия на стороне хоста. */
class FieldWsSession(
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    val deviceId: String,
    val deviceName: String,
) {
    private val closed = AtomicBoolean(false)
    @Volatile
    var cursors: Map<String, Long> = emptyMap()

    fun isOpen(): Boolean = !closed.get()

    @Synchronized
    fun sendText(text: String) {
        if (closed.get()) return
        try {
            FieldWebSocket.writeText(output, text, mask = false)
        } catch (_: Exception) {
            closeQuietly()
        }
    }

    fun readFrame(): WsFrame = FieldWebSocket.readFrame(input)

    fun closeQuietly() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

fun writeWsPong(out: BufferedOutputStream, payload: ByteArray) {
    val header = ByteArrayOutputStream()
    header.write(0x8A) // FIN + pong
    if (payload.size < 126) {
        header.write(payload.size)
    } else {
        header.write(126)
        header.write((payload.size ushr 8) and 0xFF)
        header.write(payload.size and 0xFF)
    }
    out.write(header.toByteArray())
    out.write(payload)
    out.flush()
}
