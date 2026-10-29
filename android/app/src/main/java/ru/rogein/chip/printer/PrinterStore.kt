package ru.rogein.chip.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID
import kotlin.concurrent.withLock

data class BondedPrinter(
    val address: String,
    val name: String,
)

class PrinterStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var socket: BluetoothSocket? = null
    private var socketAddress: String? = null

    fun selectedAddress(): String = prefs.getString(KEY_ADDRESS, "").orEmpty()

    fun selectedName(): String = prefs.getString(KEY_NAME, "").orEmpty()

    fun save(address: String, name: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        lock.withLock { closeSocketLocked() }
    }

    fun clear() {
        prefs.edit().clear().apply()
        lock.withLock { closeSocketLocked() }
    }

    fun isReady(): Boolean {
        if (selectedAddress().isBlank()) return false
        return adapter()?.isEnabled == true
    }

    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bonded(): List<BondedPrinter> {
        if (!hasConnectPermission()) return emptyList()
        val devices = adapter()?.bondedDevices.orEmpty()
        return devices.map { device ->
            BondedPrinter(
                address = device.address,
                name = device.name?.trim().orEmpty().ifBlank { device.address },
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun print(text: String) {
        send(toEscPos(text))
    }

    fun printSelfTest() {
        send(
            byteArrayOf(0x1B, 0x40) +
                byteArrayOf(0x1C, 0x2E) +
                "ROGEIN TEST 123\n\n\n".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x12, 0x54),
        )
    }

    private fun send(bytes: ByteArray) {
        val address = selectedAddress()
        if (address.isBlank()) error("Принтер не выбран. Ещё → Принтер.")
        if (!hasConnectPermission()) error("Нет доступа к Bluetooth. Ещё → Принтер.")
        val adapter = adapter() ?: error("На телефоне нет Bluetooth")
        if (!adapter.isEnabled) error("Включите Bluetooth")
        lock.withLock {
            try {
                writeLocked(adapter, address, bytes)
            } catch (_: Exception) {
                closeSocketLocked()
                writeLocked(adapter, address, bytes)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeLocked(adapter: BluetoothAdapter, address: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            adapter.cancelDiscovery()
        }
        try {
            val sock = openLocked(adapter, address)
            val stream = sock.outputStream
            stream.write(byteArrayOf(0x1B, 0x40))
            stream.flush()
            Thread.sleep(80)
            writeChunks(stream, bytes)
            Thread.sleep(400)
        } finally {
            closeSocketLocked()
        }
    }

    private fun writeChunks(stream: OutputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(256, bytes.size - offset)
            stream.write(bytes, offset, n)
            stream.flush()
            offset += n
            if (offset < bytes.size) Thread.sleep(20)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openLocked(adapter: BluetoothAdapter, address: String): BluetoothSocket {
        val current = socket
        if (current != null && current.isConnected && socketAddress == address) return current
        closeSocketLocked()
        val device = adapter.getRemoteDevice(address)
        val opened = connectDevice(device)
        socket = opened
        socketAddress = address
        return opened
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice): BluetoothSocket {
        val first = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        try {
            first.connect()
            return first
        } catch (_: IOException) {
            runCatching { first.close() }
        }
        val second = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            second.connect()
            return second
        } catch (_: IOException) {
            runCatching { second.close() }
        }
        val fallback = fallbackSocket(device)
        fallback.connect()
        return fallback
    }

    private fun fallbackSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    private fun closeSocketLocked() {
        runCatching { socket?.close() }
        socket = null
        socketAddress = null
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun toEscPos(text: String): ByteArray {
            val (codePage, body) = encodeBody(text)
            return byteArrayOf(
                0x1C, 0x2E,
                0x1B, 0x74, codePage,
                0x1B, 0x4D, 0x00,
                0x1B, 0x61, 0x00,
            ) + body + byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A)
        }

        private fun encodeBody(text: String): Pair<Byte, ByteArray> {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val cp866 = charsetOrNull("IBM866") ?: charsetOrNull("CP866")
            if (cp866 != null) return 0x11.toByte() to normalized.toByteArray(cp866)
            val cp1251 = charsetOrNull("windows-1251")
            if (cp1251 != null) return 0x2E.toByte() to normalized.toByteArray(cp1251)
            return 0x00.toByte() to normalized.toByteArray(Charsets.ISO_8859_1)
        }

        private fun charsetOrNull(name: String): Charset? =
            runCatching { charset(name) }.getOrNull()
    }
}
