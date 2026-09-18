package io.github.romanvht.byedpi.core

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import io.github.romanvht.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote strategy presets + probe sites, fetched from the project repository.
 * Lets us push fresh working strategies (e.g. after a TSPU update) without
 * releasing a new APK version.
 */
object RemotePresets {
    private const val TAG = "RemotePresets"
    private const val CACHE_FILE = "presets.json"
    private const val PREF_LAST_FETCH = "presets_last_fetch"
    private const val FETCH_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/dannniluk/freeboost-android/master/presets.json",
        "https://cdn.jsdelivr.net/gh/dannniluk/freeboost-android@master/presets.json",
    )

    data class StrategyDto(val name: String = "", val args: String = "")

    data class PresetsDto(
        val version: Int = 1,
        val updated: String? = null,
        val probe_sites: List<String> = emptyList(),
        val strategies: List<StrategyDto> = emptyList(),
    )

    private val gson = Gson()

    suspend fun refreshIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getPreferences()
        val last = prefs.getLong(PREF_LAST_FETCH, 0)
        if (System.currentTimeMillis() - last < FETCH_INTERVAL_MS) return@withContext
        fetch(context)?.let {
            prefs.edit(commit = true) { putLong(PREF_LAST_FETCH, System.currentTimeMillis()) }
        }
    }

    suspend fun fetch(context: Context): PresetsDto? = withContext(Dispatchers.IO) {
        for (url in SOURCES) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "freeboost-android")
                if (connection.responseCode !in 200..299) continue
                val body = connection.inputStream.bufferedReader().readText()
                val presets = gson.fromJson(body, PresetsDto::class.java) ?: continue
                File(context.filesDir, CACHE_FILE).writeText(body)
                Log.i(TAG, "Presets updated from $url (${presets.strategies.size} strategies)")
                return@withContext presets
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed from $url: ${e.message}")
            }
        }
        null
    }

    fun cached(context: Context): PresetsDto? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), PresetsDto::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted presets cache: ${e.message}")
            null
        }
    }

    fun strategyCommands(context: Context): List<String> =
        cached(context)?.strategies
            ?.map { it.args.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun probeSites(context: Context): List<String> =
        cached(context)?.probe_sites?.filter { it.isNotBlank() } ?: emptyList()
}
