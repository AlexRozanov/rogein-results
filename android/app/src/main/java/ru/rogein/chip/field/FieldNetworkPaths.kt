package ru.rogein.chip.field

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import ru.rogein.chip.parse.field.FieldSiteUplink
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Два исходящих пути: Wi‑Fi (полевой LAN, даже без интернета) и Cellular (сайт / live / cloud).
 * Без process-wide bind — только per-connection / SocketFactory.
 *
 * Для сайта [FieldNetPrefer.SITE] учитывается [siteUplink]:
 * auto — Wi‑Fi с интернетом (лучше validated), иначе LTE;
 * cellular / wifi — принудительно.
 *
 * Важно: при активном Wi‑Fi [ConnectivityManager.getAllNetworks] часто показывает
 * «фантомный» cellular без маршрута. Для LTE используем только Network из
 * [ConnectivityManager.requestNetwork], не из allNetworks.
 */
enum class FieldNetPrefer {
    /** Локальный хост :8787 — Wi‑Fi. */
    WIFI,
    /** API сайта / live / cloud. */
    SITE,
}

class FieldNetworkPaths private constructor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wifiNetwork = AtomicReference<Network?>(null)
    private val cellularNetwork = AtomicReference<Network?>(null)
    private val cellularLatch = AtomicReference(CountDownLatch(1))

    @Volatile
    private var wifiRequested = false

    @Volatile
    private var cellularRequested = false

    /** auto | cellular | wifi — откуда слать сайт. */
    @Volatile
    var siteUplink: String = FieldSiteUplink.AUTO

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork.set(network)
        }

        override fun onLost(network: Network) {
            wifiNetwork.compareAndSet(network, null)
        }

        override fun onUnavailable() {
            wifiNetwork.set(null)
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetwork.set(network)
            cellularLatch.get().countDown()
        }

        override fun onLost(network: Network) {
            if (cellularNetwork.compareAndSet(network, null)) {
                cellularLatch.set(CountDownLatch(1))
            }
        }

        override fun onUnavailable() {
            cellularNetwork.set(null)
            cellularRequested = false
            cellularLatch.get().countDown()
        }
    }

    fun ensureWifi() {
        if (wifiRequested) return
        wifiRequested = true
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.requestNetwork(req, wifiCallback)
        }.onFailure {
            wifiRequested = false
        }
        pickActive(NetworkCapabilities.TRANSPORT_WIFI, requireInternet = false)?.let {
            wifiNetwork.compareAndSet(null, it)
        }
    }

    fun ensureCellular() {
        if (cellularRequested) return
        cellularRequested = true
        if (cellularNetwork.get() == null) {
            cellularLatch.set(CountDownLatch(1))
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            // requestNetwork (не registerNetworkCallback) удерживает LTE рядом с Wi‑Fi.
            if (Build.VERSION.SDK_INT >= 26) {
                cm.requestNetwork(req, cellularCallback, mainHandler, 15_000)
            } else {
                @Suppress("DEPRECATION")
                cm.requestNetwork(req, cellularCallback)
            }
        }.onFailure {
            cellularRequested = false
            cellularLatch.get().countDown()
        }
        // Не сидим allNetworks: при Wi‑Fi с интернетом там бывает LTE без маршрута.
    }

    fun stopWifi() {
        if (!wifiRequested) return
        wifiRequested = false
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        wifiNetwork.set(null)
    }

    fun wifiBound(): Boolean = wifiNetwork.get() != null ||
        pickActive(NetworkCapabilities.TRANSPORT_WIFI, requireInternet = false) != null

    fun cellularBound(): Boolean = cellularNetwork.get() != null

    fun label(prefer: FieldNetPrefer): String = when (prefer) {
        FieldNetPrefer.WIFI -> if (peekWifi() != null) "Wi‑Fi" else ""
        FieldNetPrefer.SITE -> when (peekSiteKind()) {
            "wifi" -> "сайт:Wi‑Fi"
            "cellular" -> "сайт:LTE"
            else -> ""
        }
    }

    fun resolve(prefer: FieldNetPrefer): Network? = when (prefer) {
        FieldNetPrefer.WIFI -> {
            ensureWifi()
            peekWifi()
        }
        FieldNetPrefer.SITE -> resolveSiteNetwork(waitCellularMs = 3_000)
    }

    private fun peekWifi(): Network? {
        ensureWifi()
        return wifiNetwork.get()
            ?: pickActive(NetworkCapabilities.TRANSPORT_WIFI, requireInternet = false)
    }

    private fun resolveSiteNetwork(waitCellularMs: Long): Network? {
        ensureWifi()
        ensureCellular()
        return when (siteUplink) {
            FieldSiteUplink.CELLULAR -> {
                // Ждём реальный LTE из requestNetwork; если нет — Wi‑Fi с интернетом,
                // чтобы live не зависал на «фантомном» cellular.
                awaitCellular(waitCellularMs)
                    ?: wifiInternetCandidate()
            }
            FieldSiteUplink.WIFI -> wifiInternetCandidate()
            else -> {
                // auto: роутер с интернетом → Wi‑Fi; иначе LTE
                wifiInternetCandidate(preferValidated = true)
                    ?: awaitCellular(waitCellularMs.coerceAtMost(2_000))
                    ?: wifiInternetCandidate(preferValidated = false)
            }
        }
    }

    /** Без ожидания — для статуса в UI. */
    private fun peekSiteKind(): String? {
        ensureWifi()
        ensureCellular()
        val net = when (siteUplink) {
            FieldSiteUplink.CELLULAR ->
                cellularNetwork.get() ?: wifiInternetCandidate()
            FieldSiteUplink.WIFI -> wifiInternetCandidate()
            else -> wifiInternetCandidate(preferValidated = true)
                ?: cellularNetwork.get()
                ?: wifiInternetCandidate(preferValidated = false)
        } ?: return null
        return siteKindOf(net)
    }

    /** Какой путь реально выбран для сайта — для статуса. */
    fun resolveSiteKind(): String? {
        val net = resolveSiteNetwork(waitCellularMs = 0) ?: return null
        return siteKindOf(net)
    }

    private fun siteKindOf(net: Network): String? {
        val caps = cm.getNetworkCapabilities(net) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> null
        }
    }

    /**
     * Только Network из requestNetwork-callback.
     * Не брать cellular из [ConnectivityManager.getAllNetworks] при активном Wi‑Fi.
     */
    private fun awaitCellular(timeoutMs: Long): Network? {
        ensureCellular()
        cellularNetwork.get()?.let { return it }
        runCatching {
            cellularLatch.get().await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        return cellularNetwork.get()
    }

    private fun wifiInternetCandidate(preferValidated: Boolean = false): Network? {
        val fromCallback = wifiNetwork.get()?.takeIf { networkHasInternet(it, preferValidated) }
        if (fromCallback != null) return fromCallback
        return pickActive(
            NetworkCapabilities.TRANSPORT_WIFI,
            requireInternet = true,
            requireValidated = preferValidated,
        ) ?: if (preferValidated) {
            pickActive(NetworkCapabilities.TRANSPORT_WIFI, requireInternet = true, requireValidated = false)
        } else {
            null
        }
    }

    private fun networkHasInternet(network: Network, requireValidated: Boolean): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (requireValidated && Build.VERSION.SDK_INT >= 23 &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return false
        }
        return true
    }

    fun openConnection(url: URL, prefer: FieldNetPrefer): HttpURLConnection {
        val network = resolve(prefer)
        return if (network != null) {
            network.openConnection(url) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
    }

    fun openConnection(url: String, prefer: FieldNetPrefer): HttpURLConnection =
        openConnection(URL(url), prefer)

    /** Принудительно сайт через Wi‑Fi (fallback, если LTE не поднялся / упал). */
    fun openWifiSiteConnection(url: String): HttpURLConnection {
        ensureWifi()
        val network = wifiInternetCandidate()
        return if (network != null) {
            network.openConnection(URL(url)) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
    }

    fun siteUplinkIsCellular(): Boolean =
        siteUplink == FieldSiteUplink.CELLULAR

    fun socketFactory(prefer: FieldNetPrefer): SocketFactory {
        val network = resolve(prefer) ?: return SocketFactory.getDefault()
        return network.socketFactory
    }

    fun bindSocket(socket: Socket, prefer: FieldNetPrefer) {
        val network = resolve(prefer) ?: return
        runCatching { network.bindSocket(socket) }
    }

    private fun pickActive(
        transport: Int,
        requireInternet: Boolean,
        requireValidated: Boolean = false,
    ): Network? {
        val networks = cm.allNetworks ?: return null
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(transport)) continue
            if (requireInternet && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue
            }
            if (requireValidated && Build.VERSION.SDK_INT >= 23 &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                continue
            }
            if (Build.VERSION.SDK_INT >= 28 &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            ) {
                continue
            }
            return network
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: FieldNetworkPaths? = null

        fun get(context: Context): FieldNetworkPaths {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: FieldNetworkPaths(context.applicationContext).also { instance = it }
            }
        }

        fun getOrNull(): FieldNetworkPaths? = instance
    }
}
