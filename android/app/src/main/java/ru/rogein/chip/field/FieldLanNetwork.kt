package ru.rogein.chip.field

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class FieldLanIp(
    val address: String,
    val iface: String,
) {
    val label: String get() = ifaceLabel(iface)
}

data class FieldLanSnapshot(
    val localIps: List<FieldLanIp>,
    val gatewayIp: String,
) {
    val primaryIp: String
        get() = localIps.firstOrNull()?.address.orEmpty()
}

/**
 * Локальные IPv4 (Wi‑Fi / точка доступа) и шлюз DHCP (телефон, который раздаёт сеть).
 * Сотовые интерфейсы (ccmni, rmnet, …) не показываем — к обмену по LAN они не относятся.
 */
fun readFieldLanSnapshot(context: Context): FieldLanSnapshot {
    val local = linkedMapOf<String, FieldLanIp>()
    runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            val addr = ipv4FromLittleEndian(ipInt)
            if (addr.isNotBlank()) local[addr] = FieldLanIp(addr, "wifi")
        }
    }
    runCatching {
        val nifs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nif in nifs) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.orEmpty()
            if (isCellularIface(name)) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress?.substringBefore('%').orEmpty()
                if (host.isBlank() || host.startsWith("169.254.")) continue
                local.putIfAbsent(host, FieldLanIp(host, name.ifBlank { "net" }))
            }
        }
    }
    val ranked = local.values.sortedWith(
        compareBy<FieldLanIp> { rankIface(it.iface) }.thenBy { it.address },
    )
    val gateway = readDhcpGateway(context)
    return FieldLanSnapshot(localIps = ranked, gatewayIp = gateway)
}

fun ifaceLabel(name: String): String {
    val n = name.lowercase()
    return when {
        n == "wifi" || n.startsWith("wlan") -> "Wi‑Fi"
        n.startsWith("ap") || n.contains("tether") || n.contains("softap") -> "точка доступа"
        n.startsWith("eth") -> "Ethernet"
        else -> name
    }
}

private fun isCellularIface(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("rmnet") ||
        n.startsWith("ccmni") ||
        n.startsWith("pdp") ||
        n.startsWith("wwan") ||
        n.startsWith("v4-rmnet") ||
        n.contains("mobile") ||
        n.contains("cellular")
}

private fun readDhcpGateway(context: Context): String {
    return runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@runCatching ""
        @Suppress("DEPRECATION")
        val dhcp = wifi.dhcpInfo ?: return@runCatching ""
        val gw = dhcp.gateway
        if (gw == 0) return@runCatching ""
        ipv4FromLittleEndian(gw)
    }.getOrDefault("")
}

private fun ipv4FromLittleEndian(value: Int): String {
    if (value == 0) return ""
    return listOf(
        value and 0xff,
        value shr 8 and 0xff,
        value shr 16 and 0xff,
        value shr 24 and 0xff,
    ).joinToString(".")
}

private fun rankIface(name: String): Int {
    val n = name.lowercase()
    return when {
        n.startsWith("ap") || n.contains("tether") || n.contains("softap") || n.contains("wlan1") -> 0
        n.startsWith("wlan") || n == "wifi" -> 1
        else -> 5
    }
}
