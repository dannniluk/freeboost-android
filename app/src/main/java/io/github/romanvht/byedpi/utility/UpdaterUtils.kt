package io.github.romanvht.byedpi.utility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: checks GitHub Releases, downloads the universal APK and
 * offers one-tap install. No servers of ours, no accounts — just GitHub.
 */
object UpdaterUtils {
    private const val TAG = "UpdaterUtils"
    private const val API_URL = "https://api.github.com/repos/dannniluk/freeboost-android/releases/latest"
    private const val PREF_LAST_CHECK = "last_update_check"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 42

    suspend fun checkAndOfferUpdate(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getPreferences()
        if (!prefs.getBoolean("byedpi_auto_update", true)) return@withContext

        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return@withContext
        prefs.edit(commit = true) { putLong(PREF_LAST_CHECK, now) }

        val release = checkLatest() ?: return@withContext
        if (!isNewer(release.first, BuildConfig.VERSION_NAME)) return@withContext
        val apkUrl = release.second ?: return@withContext

        Log.i(TAG, "Update available: ${release.first} (current ${BuildConfig.VERSION_NAME})")
        val apk = download(apkUrl, context) ?: return@withContext
        showInstallNotification(context, apk, release.first)
    }

    /** returns (tag, universal apk url) */
    private fun checkLatest(): Pair<String, String?>? {
        return try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "freeboost-android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk") && name.contains("universal")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (tag.isBlank()) null else Pair(tag, apkUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val remoteParts = parse(remote)
        val currentParts = parse(current)
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val rv = remoteParts.getOrNull(i) ?: 0
            val cv = currentParts.getOrNull(i) ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun download(url: String, context: Context): File? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "freeboost-android")
            if (connection.responseCode !in 200..299) return null
            val file = File(context.cacheDir, "update.apk")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed: ${e.message}")
            null
        }
    }

    private fun showInstallNotification(context: Context, apk: File, tag: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_text, tag))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
