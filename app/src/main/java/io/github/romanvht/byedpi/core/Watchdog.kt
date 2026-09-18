package io.github.romanvht.byedpi.core

import android.util.Log
import androidx.core.content.edit
import io.github.romanvht.byedpi.services.ByeDpiVpnService
import io.github.romanvht.byedpi.utility.SiteCheckUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getProxyIpAndPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic health check while the VPN is connected.
 *
 * Probes real endpoints through the local bye-dpi proxy (so the probe path is
 * exactly the path user traffic takes). If probes keep failing — TSPU
 * reclassification, strategy death — triggers an automatic strategy autotune,
 * which restarts the tunnel with a working strategy. The user does nothing.
 */
class Watchdog(
    private val service: ByeDpiVpnService,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "Watchdog"
        private const val PROBE_INTERVAL_MS = 20_000L
        private const val FAIL_THRESHOLD = 3
        private const val COOLDOWN_MS = 10 * 60 * 1000L
    }

    private var job: Job? = null

    fun start() {
        val prefs = service.getPreferences()
        if (!prefs.getBoolean("byedpi_watchdog_enable", true)) return

        val (ip, port) = prefs.getProxyIpAndPort()
        val checker = SiteCheckUtils(ip, port.toInt())
        val sites = StrategyAutotune.FALLBACK_PROBE_SITES.take(1)

        var fails = 0

        job = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Watchdog started")

            while (isActive) {
                delay(PROBE_INTERVAL_MS)

                val ok = try {
                    val results = checker.checkSitesAsync(
                        sites = sites,
                        requestsCount = 1,
                        requestTimeout = 6,
                        concurrentRequests = 1,
                        fullLog = false,
                    )
                    results.all { it.second > 0 }
                } catch (e: Exception) {
                    Log.w(TAG, "probe error: ${e.message}")
                    false
                }

                if (ok) {
                    if (fails > 0) Log.i(TAG, "probe recovered")
                    fails = 0
                    continue
                }

                fails++
                Log.w(TAG, "probe failed ($fails/$FAIL_THRESHOLD)")
                if (fails < FAIL_THRESHOLD) continue

                val now = System.currentTimeMillis()
                val last = prefs.getLong(StrategyAutotune.PREF_LAST_AUTOTUNE, 0)
                if (now - last < COOLDOWN_MS) {
                    Log.w(TAG, "autotune cooldown active, keep probing")
                    continue
                }
                prefs.edit(commit = true) {
                    putLong(StrategyAutotune.PREF_LAST_AUTOTUNE, now)
                }

                Log.w(TAG, "connection degraded — running autotune")
                // App-scoped on purpose: this coroutine must survive the VPN
                // service being stopped by the autotune itself.
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    try {
                        StrategyAutotune(service).run()
                    } catch (e: Exception) {
                        Log.e(TAG, "watchdog autotune failed", e)
                    }
                }
                break // this service instance goes away with the tunnel restart
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
