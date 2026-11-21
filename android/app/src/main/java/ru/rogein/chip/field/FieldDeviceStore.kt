package ru.rogein.chip.field

import android.content.Context
import ru.rogein.chip.parse.field.FIELD_DEFAULT_PORT
import ru.rogein.chip.parse.field.FieldSyncMode
import ru.rogein.chip.parse.field.FieldSyncRole
import ru.rogein.chip.parse.field.FieldTransport
import java.util.UUID

/**
 * Устойчивый id телефона и настройки полевого обмена (§3.3, §7).
 * device_id не сбрасывается с очисткой старта.
 */
class FieldDeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
            if (existing.isNotEmpty()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.trim().orEmpty().ifBlank { DEFAULT_NAME }
        set(value) {
            val next = value.trim().ifBlank { DEFAULT_NAME }
            prefs.edit().putString(KEY_DEVICE_NAME, next).apply()
        }

    var syncMode: String
        get() = prefs.getString(KEY_SYNC_MODE, FieldSyncMode.OFF)?.trim().orEmpty()
            .ifBlank { FieldSyncMode.OFF }
        set(value) {
            val next = when (value.trim()) {
                FieldSyncMode.LAN -> FieldSyncMode.LAN
                FieldSyncMode.CLOUD -> FieldSyncMode.CLOUD
                else -> FieldSyncMode.OFF
            }
            prefs.edit().putString(KEY_SYNC_MODE, next).apply()
        }

    var syncRole: String
        get() = prefs.getString(KEY_SYNC_ROLE, FieldSyncRole.HOST)?.trim().orEmpty()
            .ifBlank { FieldSyncRole.HOST }
        set(value) {
            val next = when (value.trim()) {
                FieldSyncRole.CLIENT -> FieldSyncRole.CLIENT
                else -> FieldSyncRole.HOST
            }
            prefs.edit().putString(KEY_SYNC_ROLE, next).apply()
        }

    /** В cloud: этот телефон шлёт PUT /api/live (один издатель на старт). По умолчанию выкл. */
    var cloudPublisher: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_PUBLISHER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOUD_PUBLISHER, value).apply()
        }

    var startCode: String
        get() = prefs.getString(KEY_START_CODE, null)?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_START_CODE, value.trim()).apply()
        }

    var hostBaseUrl: String
        get() = prefs.getString(KEY_HOST_URL, null)?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_HOST_URL, normalizeHostEndpoint(value)).apply()
        }

    /** Клиентский канал: auto | websocket | http. */
    var transport: String
        get() = prefs.getString(KEY_TRANSPORT, FieldTransport.AUTO)?.trim().orEmpty()
            .ifBlank { FieldTransport.AUTO }
        set(value) {
            val next = when (value.trim()) {
                FieldTransport.WEBSOCKET -> FieldTransport.WEBSOCKET
                FieldTransport.HTTP -> FieldTransport.HTTP
                else -> FieldTransport.AUTO
            }
            prefs.edit().putString(KEY_TRANSPORT, next).apply()
        }

    val lanEnabled: Boolean
        get() = syncMode == FieldSyncMode.LAN

    val cloudEnabled: Boolean
        get() = syncMode == FieldSyncMode.CLOUD

    /** LAN или cloud — журнал активен. */
    val fieldSyncEnabled: Boolean
        get() = lanEnabled || cloudEnabled

    val isHost: Boolean
        get() = lanEnabled && syncRole == FieldSyncRole.HOST

    val isClient: Boolean
        get() = lanEnabled && syncRole == FieldSyncRole.CLIENT

    fun normalizedHostUrl(): String = normalizeHostEndpoint(hostBaseUrl)

    companion object {
        private const val PREFS = "field_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_SYNC_ROLE = "sync_role"
        private const val KEY_START_CODE = "start_code"
        private const val KEY_HOST_URL = "host_url"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_CLOUD_PUBLISHER = "cloud_publisher"
        const val DEFAULT_NAME = "Телефон"
        const val DEFAULT_PORT = FIELD_DEFAULT_PORT

        /** Разбор сохранённого URL / `IP:порт` → host и порт (порт по умолчанию [DEFAULT_PORT]). */
        fun parseHostEndpoint(raw: String, defaultPort: Int = DEFAULT_PORT): Pair<String, Int> {
            var value = raw.trim()
            if (value.isEmpty()) return "" to defaultPort
            value = value.removePrefix("http://").removePrefix("https://").trimEnd('/')
            // IPv6 в скобках: [fe80::1]:8787
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end > 0) {
                    val host = value.substring(1, end)
                    val rest = value.substring(end + 1).removePrefix(":")
                    val port = rest.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                    return host to port
                }
            }
            val colon = value.lastIndexOf(':')
            if (colon > 0) {
                val host = value.substring(0, colon)
                val portPart = value.substring(colon + 1)
                val port = portPart.toIntOrNull()?.takeIf { it in 1..65535 }
                if (port != null && host.count { it == '.' } >= 1) {
                    return host to port
                }
            }
            return value to defaultPort
        }

        fun buildHostEndpoint(ip: String, port: Int = DEFAULT_PORT): String {
            val host = ip.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
                .trimEnd(':')
            if (host.isEmpty()) return ""
            val safePort = port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return "http://$host:$safePort"
        }

        fun normalizeHostEndpoint(raw: String, defaultPort: Int = DEFAULT_PORT): String {
            val (host, port) = parseHostEndpoint(raw, defaultPort)
            return buildHostEndpoint(host, port)
        }

        /** `rogein-field://<ip>:<port>/<slug>/<code>` (§7.4). */
        fun buildJoinUri(host: String, port: Int, slug: String, code: String): String {
            val h = host.trim()
            val s = slug.trim().trim('/')
            val c = code.trim()
            val p = port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return "rogein-field://$h:$p/$s/$c"
        }

        fun parseJoinUri(raw: String): FieldJoinUri? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            val body = when {
                value.startsWith("rogein-field://", ignoreCase = true) ->
                    value.removePrefix("rogein-field://").removePrefix("ROGEIN-FIELD://")
                value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) -> return null
                else -> return null
            }
            val slash = body.indexOf('/')
            val hostPort = if (slash >= 0) body.substring(0, slash) else body
            val rest = if (slash >= 0) body.substring(slash + 1) else ""
            val (host, port) = parseHostEndpoint(hostPort)
            if (host.isEmpty()) return null
            val codeSlash = rest.lastIndexOf('/')
            val slug: String
            val code: String
            if (codeSlash >= 0) {
                slug = rest.substring(0, codeSlash).trim('/')
                code = rest.substring(codeSlash + 1).trim()
            } else {
                slug = ""
                code = rest.trim()
            }
            if (code.isEmpty()) return null
            return FieldJoinUri(host = host, port = port, slug = slug, code = code)
        }
    }
}

data class FieldJoinUri(
    val host: String,
    val port: Int,
    val slug: String,
    val code: String,
)

data class FieldPeerView(
    val deviceId: String,
    val name: String,
    val lastSeenAt: Long,
)
