package top.niunaijun.blackboxa.view.clones

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.niunaijun.blackboxa.view.base.BaseActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * DevxyzMuse auto-update.
 *
 * On launch (throttled to once per 6 hours) the app reads version.json from
 * the DevxyzMuse GitHub repo. When it names a newer versionCode than the
 * installed build, a dialog offers the update; accepting downloads the new
 * APK with DownloadManager and opens the system installer as soon as the
 * download finishes. Publishing an update = bump versionCode/versionName
 * in version.json and attach the matching APK to a GitHub Release.
 */
object AppUpdater {

    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/JepongDevxyz/DevxyzMuse/main/version.json"
    private const val PREFS = "devxyzmuse_updater"
    private const val KEY_LAST_CHECK = "lastCheckMillis"
    private const val KEY_DOWNLOAD_ID = "downloadId"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val APK_MIME = "application/vnd.android.package-archive"

    private var dialogShownThisSession = false
    private var receiverRegistered = false

    fun check(activity: Activity) {
        if (dialogShownThisSession) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val scope = (activity as? BaseActivity)?.lifecycleScope ?: return
        scope.launch {
            val remote = withContext(Dispatchers.IO) { fetchRemote() } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val current = currentVersionCode(activity)
            if (remote.versionCode > current) {
                dialogShownThisSession = true
                showUpdateDialog(activity, remote)
            }
        }
    }

    private data class RemoteVersion(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    private fun fetchRemote(): RemoteVersion? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            RemoteVersion(
                versionCode = json.getInt("versionCode"),
                versionName = json.optString("versionName", ""),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("notes", "")
            )
        } catch (e: Exception) {
            null // offline or manifest missing: stay silent, retry next launch window
        } finally {
            conn?.disconnect()
        }
    }

    private fun currentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun showUpdateDialog(activity: Activity, remote: RemoteVersion) {
        val message = buildString {
            append("DevxyzMuse v")
            append(remote.versionName)
            append(" is ready.")
            if (remote.notes.isNotEmpty()) {
                append("\n\n")
                append(remote.notes)
            }
            append("\n\nUpdate now so the app never goes outdated.")
        }
        MaterialDialog(activity).show {
            title(text = "Update available")
            message(text = message)
            positiveButton(text = "Update now") { startDownload(activity, remote) }
            negativeButton(text = "Later")
        }
    }

    private fun startDownload(activity: Activity, remote: RemoteVersion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Allow DevxyzMuse to install updates, then tap Update now again.",
                Toast.LENGTH_LONG
            ).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (e: Exception) {
                // settings screen unavailable on this device; nothing more to do
            }
            return
        }

        val request = DownloadManager.Request(Uri.parse(remote.apkUrl))
            .setTitle("DevxyzMuse v" + remote.versionName)
            .setDescription("Downloading DevxyzMuse update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "DevxyzMuse_" + remote.versionName + ".apk"
            )
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(activity, "Update download failed to start.", Toast.LENGTH_LONG).show()
            return
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        registerCompletionReceiver(activity.applicationContext)
        Toast.makeText(activity, "Downloading DevxyzMuse update…", Toast.LENGTH_SHORT).show()
    }

    @Synchronized
    private fun registerCompletionReceiver(appContext: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val expected = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
                val finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L)
                if (finished != expected) return
                try {
                    context.unregisterReceiver(this)
                } catch (e: Exception) {
                    // already unregistered
                }
                receiverRegistered = false
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = try {
                    dm.getUriForDownloadedFile(finished)
                } catch (e: Exception) {
                    null
                } ?: return
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(install)
                } catch (e: Exception) {
                    // user can still tap the DownloadManager notification
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }
}
