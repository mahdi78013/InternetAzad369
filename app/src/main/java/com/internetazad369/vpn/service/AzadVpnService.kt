package com.internetazad369.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.internetazad369.vpn.MainActivity
import com.internetazad369.vpn.core.UnderlyingNetworks
import com.internetazad369.vpn.data.model.ConnectionState
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.NetworkInterface as JavaNetInterface
import java.util.Collections

class AzadVpnService : VpnService(), PlatformInterface, CommandServerHandler, CommandClientHandler {

    companion object {
        private const val TAG = "AzadVpnService"
        const val ACTION_START = "com.internetazad369.vpn.START"
        const val ACTION_STOP = "com.internetazad369.vpn.STOP"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        const val CHANNEL_ID = "azad_vpn_channel"
        const val NOTIFICATION_ID = 369

        @Volatile
        var isServiceRunning = false
            private set

        private var isLibboxSetupDone = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var activeServerName: String = "اتصال هوشمند (Auto)"

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var lastReportedInterface: Pair<String, Int>? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: activeServerName
        activeServerName = serverName
        showForegroundNotification("در حال برقراری اتصال امن...")

        when (action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        startCore(configJson, serverName)
                    }
                }
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        stopCore()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startCore(configJson: String, serverName: String) {
        if (configJson.isBlank()) {
            VpnServiceStateHolder.updateState(ConnectionState.Error("پیکربندی سرور نامعتبر است"))
            stopCore()
            return
        }

        try {
            VpnServiceStateHolder.updateState(ConnectionState.Connecting("راه‌اندازی هسته sing-box..."))

            // Initialize libbox runtime options once
            if (!isLibboxSetupDone) {
                val setupOptions = SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = filesDir.absolutePath
                    tempPath = cacheDir.absolutePath
                    fixAndroidStack = true
                }
                Libbox.setup(setupOptions)
                isLibboxSetupDone = true
            }

            // Cleanup any existing instances safely
            cleanupCoreInstances()

            // Initialize and start sing-box command server
            val server = Libbox.newCommandServer(this@AzadVpnService, this@AzadVpnService)
            server.start()
            commandServer = server
            startServiceDroppingBadNodes(server, configJson)

            // Attach client to read live traffic and connection status
            val clientOptions = CommandClientOptions().apply {
                statusInterval = 1000 // 1 second intervals for real stats
            }
            val client = Libbox.newCommandClient(this@AzadVpnService, clientOptions)
            client.connect()
            commandClient = client

            isServiceRunning = true
            VpnServiceStateHolder.updateState(
                ConnectionState.Connected(
                    serverName = serverName,
                    connectedTimestamp = System.currentTimeMillis()
                )
            )
            showForegroundNotification("متصل • تونل ایمن فعال است")
            Log.i(TAG, "sing-box core started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box core: ${e.message}", e)
            VpnServiceStateHolder.updateState(ConnectionState.Error("خطا در راه‌اندازی هسته: ${e.message ?: "ناشناخته"}"))
            stopCore(preserveError = true)
        }
    }

    /**
     * One broken node ("initialize outbound[N] ...") makes sing-box refuse to start at all. Instead of failing the
     * whole connection, drop that outbound (and its reference in the urltest group) and retry.
     */
    private fun startServiceDroppingBadNodes(server: CommandServer, initialConfig: String) {
        var config = initialConfig
        var attempts = 0
        while (true) {
            try {
                server.startOrReloadService(config, OverrideOptions())
                return
            } catch (e: Exception) {
                val index = Regex("outbound\\[(\\d+)\\]").find(e.message.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                val reduced = if (index != null && attempts < 20) removeOutbound(config, index) else null
                if (reduced == null) throw e
                Log.w(TAG, "Dropping invalid outbound[$index]: ${e.message}")
                config = reduced
                attempts++
            }
        }
    }

    private fun removeOutbound(config: String, index: Int): String? {
        return try {
            val root = org.json.JSONObject(config)
            val outbounds = root.getJSONArray("outbounds")
            if (index !in 0 until outbounds.length()) return null
            val tag = outbounds.getJSONObject(index).optString("tag")
            if (!tag.startsWith("node-")) return null
            val kept = org.json.JSONArray()
            var remainingNodes = 0
            for (i in 0 until outbounds.length()) {
                if (i == index) continue
                val o = outbounds.getJSONObject(i)
                if (o.optString("tag").startsWith("node-")) remainingNodes++
                if (o.optString("type") == "urltest") {
                    val list = o.getJSONArray("outbounds")
                    val newList = org.json.JSONArray()
                    for (j in 0 until list.length()) if (list.getString(j) != tag) newList.put(list.getString(j))
                    o.put("outbounds", newList)
                }
                kept.put(o)
            }
            if (remainingNodes == 0) null else root.put("outbounds", kept).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun stopCore(preserveError: Boolean = false) {
        if (!preserveError) {
            VpnServiceStateHolder.updateState(ConnectionState.Disconnecting)
        }
        cleanupCoreInstances()

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tunFd: ${e.message}")
        }
        tunFd = null

        isServiceRunning = false
        if (!preserveError) {
            VpnServiceStateHolder.reset()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "sing-box core stopped cleanly (preserveError=$preserveError)")
    }

    private fun cleanupCoreInstances() {
        try {
            commandClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting commandClient: ${e.message}")
        }
        commandClient = null

        try {
            commandServer?.closeService()
            commandServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing commandServer: ${e.message}")
        }
        commandServer = null

        unregisterNetworkMonitor()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system or another VPN app")
        serviceScope.launch {
            lifecycleMutex.withLock {
                stopCore()
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cleanupCoreInstances()
        isServiceRunning = false
        super.onDestroy()
    }

    // ==========================================
    // PlatformInterface Implementation (libbox)
    // ==========================================

    override fun openTun(options: TunOptions): Int {
        val builder = Builder()
            .setSession("اینترنت آزاد ۳۶۹ — $activeServerName")
            .setMtu(options.mtu.takeIf { it > 0 } ?: 1500)
        // IMPORTANT: no setBlocking(true). sing-tun wraps the fd with os.NewFile and does NOT switch it to
        // non-blocking when the fd comes from the platform; a blocking tun fd breaks Go's poller and makes
        // stop/reload hang. The official SFA client leaves the default (non-blocking).

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // IPv4 only. The generated sing-box config has an IPv4-only tun and `strategy: ipv4_only`.
        // With no IPv6 address/route on the VPN, Android makes IPv6 unreachable for apps immediately,
        // so they fall back to IPv4 instead of hanging on a v6 path nobody serves.
        var hasIpv4 = false
        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
            hasIpv4 = true
        }
        if (!hasIpv4) {
            builder.addAddress("172.19.0.1", 30)
        }
        builder.addRoute("0.0.0.0", 0)

        // DNS: libbox hands us the tun peer address (172.19.0.2). Packets to it enter the tun and are
        // caught by the `hijack-dns` route rules, so no query ever leaves the device in clear text.
        var hasDns = false
        try {
            val dnsIt = options.dnsServerAddress
            while (dnsIt.hasNext()) {
                val dns = dnsIt.next()
                if (dns.isNotBlank()) {
                    builder.addDnsServer(dns)
                    hasDns = true
                }
            }
        } catch (_: Exception) {}

        if (!hasDns) {
            builder.addDnsServer("172.19.0.2")
        }

        // Exclude own package so our subscription downloads / probes never loop into the tunnel.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot exclude own package: ${e.message}")
        }

        val pfd = builder.establish() ?: throw IllegalStateException("builder.establish() returned null")
        tunFd = pfd
        Log.i(TAG, "TUN interface established successfully with fd: ${pfd.fd}")

        // Tell Android which real network carries the VPN's own traffic (validation, metered state, etc.).
        connectivityManager?.let { cm ->
            UnderlyingNetworks.pick(cm)?.let { net ->
                try {
                    setUnderlyingNetworks(arrayOf(net))
                } catch (e: Exception) {
                    Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
                }
            }
        }
        return pfd.detachFd()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) {
            Log.w(TAG, "protect(fd=$fd) failed: socket may loop into the tunnel")
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun useProcFS(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        try {
            val interfaces = JavaNetInterface.getNetworkInterfaces()
            for (javaIf in Collections.list(interfaces)) {
                if (javaIf.isUp) {
                    val libIf = NetworkInterface().apply {
                        name = javaIf.name
                        index = javaIf.index
                        mtu = try { javaIf.mtu } catch (_: Exception) { 1500 }
                        // libbox drops interfaces whose flags lack IFF_UP; without this the list was always empty.
                        flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    list.add(libIf)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating interfaces: ${e.message}")
        }

        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < list.size
            override fun next(): NetworkInterface = list[idx++]
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        lastReportedInterface = null
        val cm = connectivityManager ?: return

        // NOT_VPN is essential: without it the callback also fires for OUR OWN tun interface and the core
        // would be told that "the default interface" is the tunnel itself.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reportDefaultInterface()
            override fun onLost(network: Network) = reportDefaultInterface()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                reportDefaultInterface()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                reportDefaultInterface()
        }
        networkCallback = callback

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network monitor: ${e.message}")
        }
        // Initial value must be delivered synchronously, the core waits for it.
        reportDefaultInterface(force = true)
    }

    /**
     * Reports the real (non-VPN) default interface to libbox.
     * libbox resolves the interface with `ByIndex(index)`; passing 0 (as before) makes that lookup fail
     * and the default interface stays nil forever. -1 means "no network".
     */
    @Synchronized
    private fun reportDefaultInterface(force: Boolean = false) {
        val cm = connectivityManager ?: return
        val listener = defaultInterfaceListener ?: return

        val network = UnderlyingNetworks.pick(cm)
        val name = network?.let { UnderlyingNetworks.interfaceName(cm, it) }
        val index = name?.let { UnderlyingNetworks.interfaceIndex(it) } ?: -1
        val current: Pair<String, Int>? = if (name != null && index > 0) name to index else null

        if (!force && current == lastReportedInterface) return
        lastReportedInterface = current

        try {
            if (current == null || network == null) {
                listener.updateDefaultInterface("", -1, false, false)
            } else {
                val metered = cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                Log.i(TAG, "default interface -> ${current.first} (#${current.second}), metered=$metered")
                listener.updateDefaultInterface(current.first, current.second, metered, false)
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateDefaultInterface failed: ${e.message}")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        unregisterNetworkMonitor()
        defaultInterfaceListener = null
        lastReportedInterface = null
    }

    private fun unregisterNetworkMonitor() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    override fun findConnectionOwner(ipProtocol: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): io.nekohasekai.libbox.ConnectionOwner {
        throw UnsupportedOperationException()
    }

    override fun lookupUser(name: String): io.nekohasekai.libbox.PlatformUser {
        throw UnsupportedOperationException()
    }

    override fun lookupSFTPServer(): String = throw UnsupportedOperationException()

    override fun openShellSession(user: io.nekohasekai.libbox.PlatformUser?, path: String?, args: io.nekohasekai.libbox.StringIterator?, dir: String?, cols: Int, rows: Int): io.nekohasekai.libbox.ShellSession {
        throw UnsupportedOperationException()
    }

    override fun checkPlatformShell() {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
    override fun cancelNotification(tag: String, id: Int) {}
    override fun readSystemSSHHostKey(): String = ""
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? = null
    override fun registerMyInterface(name: String) {}
    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions): io.nekohasekai.libbox.BridgeSession = throw UnsupportedOperationException()
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null
    override fun tailscaleHostname(): String = ""

    // ==========================================
    // CommandServerHandler Implementation
    // ==========================================

    override fun serviceStop() {
        serviceScope.launch {
            lifecycleMutex.withLock {
                stopCore()
            }
        }
    }

    override fun serviceReload() {}
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun writeDebugMessage(message: String) {
        Log.d(TAG, "sing-box debug: $message")
    }
    override fun connectSSHAgent(): Int = -1
    override fun triggerNativeCrash() {}

    // ==========================================
    // CommandClientHandler Implementation
    // ==========================================

    override fun connected() {
        Log.d(TAG, "CommandClient connected")
    }

    override fun disconnected(message: String) {
        Log.d(TAG, "CommandClient disconnected: $message")
    }

    override fun clearLogs() {}
    override fun setDefaultLogLevel(level: Int) {}

    override fun writeStatus(status: StatusMessage) {
        VpnServiceStateHolder.updateTraffic(
            uplinkTotal = status.uplinkTotal,
            downlinkTotal = status.downlinkTotal,
            uplinkSpeed = status.uplink,
            downlinkSpeed = status.downlink
        )

        val downKb = status.downlink / 1024
        val upKb = status.uplink / 1024
        val speedStr = "↓ $downKb KB/s  ↑ $upKb KB/s"
        updateNotificationText(speedStr)
    }

    override fun writeLogs(iterator: io.nekohasekai.libbox.LogIterator) {}
    override fun writeGroups(iterator: io.nekohasekai.libbox.OutboundGroupIterator) {}
    override fun writeOutbounds(iterator: io.nekohasekai.libbox.OutboundGroupItemIterator) {}
    override fun writeDNSQuery(query: io.nekohasekai.libbox.DnsQuery) {}
    override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents) {}
    override fun initializeClashMode(iterator: io.nekohasekai.libbox.StringIterator, currentMode: String) {}
    override fun updateClashMode(newMode: String) {}

    // ==========================================
    // Notification & Foreground Management
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "اینترنت آزاد ۳۶۹",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت تونل امن اینترنت آزاد ۳۶۹"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationText(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AzadVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("اینترنت آزاد ۳۶۹ — $activeServerName")
            .setContentText(statusText)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", pendingStop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
