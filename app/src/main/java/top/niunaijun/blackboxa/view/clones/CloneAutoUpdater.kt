package top.niunaijun.blackboxa.view.clones

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.DiagLog
import top.niunaijun.blackboxa.data.CloneRegistry
import top.niunaijun.blackboxa.view.base.BaseActivity

/**
 * DevxyzMuse clone auto-update (v1.0.3, her order: "auto at tahimik").
 *
 * The Muse copies inside clones never follow Play Store updates on their
 * own - a clone keeps the Muse build it was created from. On launch
 * (throttled to once per hour, fully silent) this compares the REAL Muse
 * app installed on the phone against every registered clone; wherever the
 * real app is newer, the current real APK is re-installed into that clone
 * through the same engine call used at clone creation
 * (installPackageAsUser by package name). That is an in-place update inside
 * the container: the clone's data directory is not touched, so its sign-in
 * survives - only uninstall/clear ever removes guest data.
 *
 * Every attempt and failure is written to DiagLog ("clone-auto-update:")
 * so a silent failure is still provable from the log she can share.
 */
object CloneAutoUpdater {

    private const val MUSE_PACKAGE = "com.facebook.aura"
    private const val PREFS = "devxyzmuse_clone_updater"
    private const val KEY_LAST_CHECK = "lastCheckMillis"
    private const val CHECK_INTERVAL_MS = 60L * 60L * 1000L

    private var runningThisSession = false

    fun check(activity: Activity) {
        if (runningThisSession) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val scope = (activity as? BaseActivity)?.lifecycleScope ?: return
        runningThisSession = true
        val appContext = activity.applicationContext
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runUpdate(appContext) }
            } catch (e: Exception) {
                DiagLog.log("clone-auto-update: run exception " + e)
            } finally {
                runningThisSession = false
            }
        }
    }

    private fun versionOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private fun runUpdate(context: Context) {
        val hostInfo = try {
            context.packageManager.getPackageInfo(MUSE_PACKAGE, 0)
        } catch (e: Exception) {
            DiagLog.log("clone-auto-update: host Muse not installed, skip")
            return
        }
        val hostVersion = versionOf(hostInfo)

        val records = CloneRegistry(context).loadRecords()
            .filter { it.packageName == MUSE_PACKAGE }
        if (records.isEmpty()) return

        val core = BlackBoxCore.get()
        for (record in records) {
            try {
                if (!core.isInstalled(MUSE_PACKAGE, record.userId)) continue
                val virtualInfo = core.getInstalledPackages(0, record.userId)
                    .firstOrNull { it.packageName == MUSE_PACKAGE } ?: continue
                val cloneVersion = versionOf(virtualInfo)
                if (cloneVersion >= hostVersion) continue

                val result = core.installPackageAsUser(MUSE_PACKAGE, record.userId)
                val afterInfo = try {
                    core.getInstalledPackages(0, record.userId)
                        .firstOrNull { it.packageName == MUSE_PACKAGE }
                } catch (e: Exception) {
                    null
                }
                val afterVersion = afterInfo?.let { versionOf(it) } ?: -1L
                DiagLog.log(
                    "clone-auto-update: userId=" + record.userId +
                        " from=" + cloneVersion + " host=" + hostVersion +
                        " success=" + result.success + " now=" + afterVersion +
                        " msg=" + result.msg
                )
            } catch (e: Exception) {
                // One bad clone must not stop the others.
                DiagLog.log(
                    "clone-auto-update: userId=" + record.userId + " exception " + e
                )
            }
        }
    }
}
