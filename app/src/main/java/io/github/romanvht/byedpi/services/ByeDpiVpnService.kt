package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.LocalDnsServer
import io.github.romanvht.byedpi.core.TProxyService
import io.github.romanvht.byedpi.core.Watchdog
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private val localDnsServer by lazy { LocalDnsServer(this) }
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var watchdog: Watchdog? = null
    private val mutex = Mutex()

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"
        private const val TUN_IP: String = "10.10.10.10"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        localDnsServer.stop()
        tunFd?.close()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground()

        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch {
                    start()
                }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch {
                    stop()
                }
                START_NOT_STICKY
            }

            RESUME_ACTION -> {
                lifecycleScope.launch {
                    if (prepare(this@ByeDpiVpnService) == null) {
                        start()
                    }
                }
                START_STICKY
            }

            PAUSE_ACTION -> {
                lifecycleScope.launch {
                    stop()
                    createNotificationPause()
                }
                START_NOT_STICKY
            }

            SERVICE_INTERFACE -> {
                Log.i(TAG, "Started by Android")

                if (getPreferences().mode() != Mode.VPN) {
                    Log.w(TAG, "Always-On disabled in proxy mode")
                    stopSelf()
                    return START_NOT_STICKY
                }

                lifecycleScope.launch {
                    start()
                }

                START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            updateStatus(ServiceStatus.Connected)
            return
        }

        // A leftover proxy-mode instance holds the core — starting the VPN
        // on top of it fails with "proxy stopped with code N". Stop it first.
        if (appStatus.first == AppStatus.Running && appStatus.second == Mode.Proxy) {
            Log.w(TAG, "Proxy service still running, stopping before VPN start")
            ServiceManager.stop(this)
            if (!waitForAppStatus(AppStatus.Halted)) {
                Log.e(TAG, "Failed to stop proxy service, aborting VPN start")
                updateStatus(ServiceStatus.Failed, "не удалось остановить прокси-службу")
                stopSelf()
                return
            }
        }

        try {
            mutex.withLock {
                startProxy()
                startTun2Socks()
                updateStatus(ServiceStatus.Connected)
            }
            watchdog = Watchdog(this, lifecycleScope).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            updateStatus(ServiceStatus.Failed, e.message ?: "неизвестная ошибка")
            stop()
        }
    }

    private suspend fun waitForAppStatus(target: AppStatus, timeoutMs: Long = 5000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (appStatus.first == target) return true
            delay(150)
        }
        return false
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        watchdog?.stop()
        watchdog = null

        if (status != ServiceStatus.Connected) {
            Log.w(TAG, "VPN not connected")
            updateStatus(ServiceStatus.Disconnected)
            return
        }

        mutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    stopProxy()
                    stopTun2Socks()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxy(preferences)

            delay(500)

            if (code != 0) {
                Log.e(TAG, "Proxy stopped with code $code")
                updateStatus(ServiceStatus.Failed, "код $code")
                stopTun2Socks()
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (status == ServiceStatus.Disconnected) {
            Log.w(TAG, "Proxy already disconnected")
            return
        }

        try {
            byeDpiProxy.stopProxy()
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                byeDpiProxy.jniForceClose()
            }

            proxyJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            Log.w(TAG, "VPN field not null")
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val (ip, port) = sharedPreferences.getProxyIpAndPort()

        val dns = sharedPreferences.getStringNotNull("dns_ip", "1.1.1.1")
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)
        val dohEnabled = sharedPreferences.getBoolean("byedpi_doh", true)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")

            appendLine("misc:")
            appendLine("  task-stack-size: 81920")

            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6, dohEnabled).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        if (dohEnabled) {
            // local DoH resolver on the TUN address, immune to plain-DNS hijacking
            localDnsServer.start(TUN_IP)
        }

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started. ip: $ip port: $port")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        localDnsServer.stop()

        if (tunFd == null) {
            Log.w(TAG, "VPN field is null, skipping")
            return
        }

        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TProxyService", e)
        }

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus, reason: String? = null) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        if (reason != null) {
            intent.putExtra(REASON, reason)
        }
        sendBroadcast(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            QuickTileService.updateTile()
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            ByeDpiVpnService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    private fun createBuilder(dns: String, ipv6: Boolean, dohEnabled: Boolean): Builder {
        Log.d(TAG, "DNS: $dns (DoH enabled: $dohEnabled)")
        val builder = Builder()
        builder.setSession("ByeDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress(TUN_IP, 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dohEnabled) {
            // route system DNS to our local DoH resolver bound on the TUN address
            builder.addDnsServer(TUN_IP)
        } else if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = getPreferences()
        val listType = preferences.getStringNotNull("applist_type", "disable")
        val listedApps = preferences.getSelectedApps()

        when (listType) {
            "blacklist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в черный список", e)
                    }
                }

                builder.addDisallowedApplication(applicationContext.packageName)
            }

            "whitelist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в белый список", e)
                    }
                }
            }

            "disable" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }

        return builder
    }
}
