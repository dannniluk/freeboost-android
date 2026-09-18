package io.github.romanvht.byedpi.core

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.SiteCheckUtils
import io.github.romanvht.byedpi.utility.getCmdArgs
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getProxyIpAndPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Headless strategy autotune: tests candidate bye-dpi command lines through
 * the local proxy against probe sites and applies the best one.
 * Reuses the same machinery as the manual "Strategy testing" screen,
 * but requires zero user interaction.
 */
class StrategyAutotune(private val context: Context) {

    companion object {
        private const val TAG = "StrategyAutotune"
        const val PREF_AUTOTUNE_DONE = "autotune_done"
        const val PREF_LAST_AUTOTUNE = "last_autotune_at"

        val FALLBACK_PROBE_SITES = listOf(
            "https://www.youtube.com/generate_204",
            "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
        )

        /** QUIC blocking must always be present, whatever strategy wins. */
        fun normalizeArgs(cmd: String): String {
            val trimmed = cmd.trim()
            return if (trimmed.contains("--quic-block")) trimmed else "$trimmed --quic-block"
        }
    }

    data class Result(val command: String, val success: Int, val total: Int)

    private val prefs by lazy { context.getPreferences() }

    fun candidates(): List<String> {
        val remote = RemotePresets.strategyCommands(context)
        return remote.ifEmpty { loadBundled() }
    }

    private fun loadBundled(): List<String> = try {
        context.assets.open("proxytest_strategies.list").bufferedReader().readText()
            .lines()
            .map { it.trim().replace("{sni}", "\"google.com\"") }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load bundled strategies", e)
        emptyList()
    }

    fun probeSites(): List<String> =
        RemotePresets.probeSites(context).takeIf { it.isNotEmpty() } ?: FALLBACK_PROBE_SITES

    suspend fun run(onProgress: ((index: Int, total: Int) -> Unit)? = null): Result? =
        withContext(Dispatchers.IO) {
            val commands = candidates()
            if (commands.isEmpty()) {
                Log.w(TAG, "No candidate strategies available")
                return@withContext null
            }

            val sites = probeSites()
            val requestsCount = 2
            val requestTimeout = 5L
            val (ip, port) = prefs.getProxyIpAndPort()
            val checker = SiteCheckUtils(ip, port.toInt())

            val savedCmd = prefs.getCmdArgs()
            val wasRunning = appStatus.first == AppStatus.Running
            val originalMode = appStatus.second
            val perfectScore = sites.size * requestsCount

            var best: Result? = null

            try {
                if (wasRunning) {
                    ServiceManager.stop(context)
                    waitForStatus(AppStatus.Halted)
                }

                for ((index, command) in commands.withIndex()) {
                    if ((best?.success ?: -1) >= perfectScore) break // perfect score found

                    onProgress?.invoke(index, commands.size)
                    Log.i(TAG, "Testing ${index + 1}/${commands.size}: $command")

                    prefs.edit(commit = true) {
                        putString("byedpi_cmd_args", normalizeArgs(command))
                    }
                    ServiceManager.start(context, Mode.Proxy)

                    if (!waitForStatus(AppStatus.Running)) {
                        Log.w(TAG, "Proxy failed to start for candidate ${index + 1}")
                        ServiceManager.stop(context)
                        waitForStatus(AppStatus.Halted)
                        continue
                    }
                    delay(800)

                    val results = checker.checkSitesAsync(
                        sites = sites,
                        requestsCount = requestsCount,
                        requestTimeout = requestTimeout,
                        concurrentRequests = 8,
                        fullLog = false,
                    )
                    val success = results.sumOf { it.second }
                    Log.i(TAG, "Candidate ${index + 1}: $success/$perfectScore")

                    ServiceManager.stop(context)
                    waitForStatus(AppStatus.Halted)

                    if (success > (best?.success ?: -1)) {
                        best = Result(normalizeArgs(command), success, perfectScore)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autotune failed", e)
            } finally {
                val finalCommand = best?.command ?: savedCmd
                prefs.edit(commit = true) {
                    putString("byedpi_cmd_args", normalizeArgs(finalCommand))
                    putBoolean(PREF_AUTOTUNE_DONE, true)
                    putLong(PREF_LAST_AUTOTUNE, System.currentTimeMillis())
                }
                if (wasRunning) {
                    ServiceManager.start(context, originalMode)
                }
            }

            Log.i(TAG, "Autotune complete: ${best?.command} (${best?.success}/${best?.total})")
            best
        }

    private suspend fun waitForStatus(status: AppStatus, timeoutMs: Long = 5000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (appStatus.first == status) return true
            delay(150)
        }
        return false
    }
}
