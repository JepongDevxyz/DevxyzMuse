package top.niunaijun.blackboxa.view.clones

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.DiagLog
import top.niunaijun.blackboxa.data.CloneRegistry

/**
 * DevxyzMuse clone auto-update (v1.0.3-v1.0.6, her order: "auto at tahimik",
 * reaffirmed 2026-11-21: "Automatic dapat").
 *
 * The Muse copies inside clones never follow Play Store updates on their
 * own - a clone keeps the Muse build it was created from. This compares
 * the REAL Muse app installed on the phone against every registered clone;
 * wherever the real app is newer, the current real APK is re-installed
 * into that clone through the same engine call used at clone creation
 * (installPackageAsUser by package name). That is an in-place update
 * inside the container: the clone's data directory is not touched, so its
 * sign-in survives - only uninstall/clear ever removes guest data.
 *
 * v1.0.6 - WHY THE OLD TRIGGER COULD NEVER WORK (root cause, proven
 * from the engine's own code 2026-11-21): v1.0.3 ran this check from
 * ClonesActivity.onCreate, BEFORE the engine service is up. The
 * activity itself proves the timing - engine work is deliberately
 * deferred to the service-available callback registered in onResume,
 * because engine work in onCreate "can stall the launch before the
 * window ever draws". Before the service is bound, the package-manager
 * proxy answers isInstalled() = false and getInstalledPackages() = empty
 * list, so every clone was silently skipped ("not installed, skip", or
 * the null-virtual-info continue with no log at all) - and the 1-hour
 * throttle stamp was already consumed, so the next launch did not retry
 * either. The updater was dead on arrival; no clone ever updated.
 *
 * The fix, three parts:
 *  1. The check now runs ONLY when the engine says it is ready
 *     (BlackBoxCore.addServiceAvailableCallback - the engine's own
 *     readiness signal, which also fires immediately when the engine is
 *     already up), with a short readiness poll as backstop.
 *  2. The 1-hour throttle is stamped ONLY after a real check actually
 *     ran (the engine answered at least one clone's version). A
 *     not-ready attempt stamps nothing, so the next launch retries.
 *  3. A PACKAGE_REPLACED receiver (MuseUpdateReceiver) fires the
 *     moment the real Muse app updates on the Play Store - throttle
 *     bypassed - so clones follow immediately, not only on next launch.
 *
 * Every check outcome, attempt and failure is written to DiagLog
 * ("clone-auto-update:") so a silent failure is still provable from the
 * log she can share. An update only counts when the re-read version
 * proves it (afterVersion >= hostVersion) - a claimed success that
 * leaves the version stale is logged STILL STALE and never toasted.
 * When one or more clones actually updated, a single small toast says
 * so - still fully automatic, just no longer invisible.
 */
object CloneAutoUpdater {

    const val MUSE_PACKAGE = "com.facebook.aura"
    private const val PREFS = "devxyzmuse_clone_updater"
    private const val KEY_LAST_CHECK = "lastCheckMillis"
    private const val CHECK_INTERVAL_MS = 60L * 60L * 1000L

    // Engine-readiness poll: 1s steps. The launch path can wait a full
    // minute (foreground activity); the broadcast path gets 8s and then
    // falls back to the cleared throttle (next launch does it).
    private const val POLL_STEP_MS = 1000L

    private var runningThisSession = false

    // App-wide scope: the check must survive the activity (and run from a
    // broadcast receiver, which has no lifecycle at all).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Entry from ClonesActivity.onCreate: throttled, runs when the engine is ready. */
    fun check(activity: Activity) {
        schedule(activity.applicationContext, maxWaitMs = 60_000L, allowToast = true)
    }

    /** Entry from MuseUpdateReceiver: throttle bypassed, short engine wait. */
    fun checkNow(context: Context, onDone: (() -> Unit)? = null) {
        clearThrottle(context)
        schedule(context.applicationContext, maxWaitMs = 8_000L, allowToast = false, onDone = onDone)
    }

    fun clearThrottle(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, 0L).apply()
    }

    private fun schedule(
        appContext: Context,
        maxWaitMs: Long,
        allowToast: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        if (runningThisSession) {
            onDone?.invoke()
            return
        }
        // Read-only throttle check here; the stamp is written only after
        // a real check ran (see below) - a not-ready attempt must not
        // consume the hour.
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            onDone?.invoke()
            return
        }
        runningThisSession = true
        // addServiceAvailableCallback runs the runnable immediately when
        // the engine is already up, otherwise the moment it becomes
        // ready - the engine's own readiness signal.
        BlackBoxCore.get().addServiceAvailableCallback(Runnable {
            scope.launch {
                try {
                    val ready = withContext(Dispatchers.IO) { waitForEngine(appContext, maxWaitMs) }
                    if (!ready) {
                        DiagLog.log(
                            "clone-auto-update: engine not ready after " + maxWaitMs +
                                "ms, will retry on next launch (throttle NOT stamped)"
                        )
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) { runUpdate(appContext) }
                    if (result.answered) {
                        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    }
                    if (allowToast && result.updated > 0) {
                        val msg = if (result.updated == 1) {
                            "Na-update ang clone sa bagong Muse"
                        } else {
                            "Na-update ang " + result.updated + " clones sa bagong Muse"
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    DiagLog.log("clone-auto-update: run exception " + e)
                } finally {
                    runningThisSession = false
                    onDone?.invoke()
                }
            }
        })
    }

    /**
     * True once the virtual package manager answers for a registered
     * clone (isInstalled true), or when there are no Muse clones at all
     * (nothing to wait for - the check itself logs "no clones").
     * Before the engine service is bound, the proxy answers false, so
     * this is the signal that separates "not ready yet" from "ready".
     */
    private fun waitForEngine(context: Context, maxWaitMs: Long): Boolean {
        val records = try {
            CloneRegistry(context).loadRecords().filter { it.packageName == MUSE_PACKAGE }
        } catch (e: Exception) {
            return false
        }
        if (records.isEmpty()) return true
        val core = BlackBoxCore.get()
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (core.isInstalled(MUSE_PACKAGE, records[0].userId)) return true
            } catch (e: Exception) {
                // Not ready yet - keep waiting.
            }
            try {
                Thread.sleep(POLL_STEP_MS)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun versionOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private data class CheckResult(val updated: Int, val answered: Boolean)

    private fun runUpdate(context: Context): CheckResult {
        val hostInfo = try {
            context.packageManager.getPackageInfo(MUSE_PACKAGE, 0)
        } catch (e: Exception) {
            DiagLog.log("clone-auto-update: host Muse not installed, skip")
            return CheckResult(0, true)
        }
        val hostVersion = versionOf(hostInfo)

        val records = CloneRegistry(context).loadRecords()
            .filter { it.packageName == MUSE_PACKAGE }
        if (records.isEmpty()) {
            DiagLog.log("clone-auto-update: check host=" + hostVersion + " no clones")
            return CheckResult(0, true)
        }

        var updated = 0
        var answered = false
        val core = BlackBoxCore.get()
        for (record in records) {
            try {
                if (!core.isInstalled(MUSE_PACKAGE, record.userId)) {
                    DiagLog.log(
                        "clone-auto-update: check userId=" + record.userId +
                            " not installed, skip"
                    )
                    continue
                }
                val virtualInfo = core.getInstalledPackages(0, record.userId)
                    .firstOrNull { it.packageName == MUSE_PACKAGE }
                if (virtualInfo == null) {
                    // The engine answered isInstalled=true but cannot
                    // produce the package info: not ready, or the
                    // registration is broken. Loud, and NOT counted as
                    // an answered check - the throttle must not be
                    // stamped on this.
                    DiagLog.log(
                        "clone-auto-update: userId=" + record.userId +
                            " virtual info missing (engine not ready?)"
                    )
                    continue
                }
                answered = true
                val cloneVersion = versionOf(virtualInfo)
                if (cloneVersion >= hostVersion) {
                    DiagLog.log(
                        "clone-auto-update: check userId=" + record.userId +
                            " clone=" + cloneVersion + " host=" + hostVersion +
                            " up-to-date"
                    )
                    continue
                }

                val result = core.installPackageAsUser(MUSE_PACKAGE, record.userId)
                val afterInfo = try {
                    core.getInstalledPackages(0, record.userId)
                        .firstOrNull { it.packageName == MUSE_PACKAGE }
                } catch (e: Exception) {
                    null
                }
                val afterVersion = afterInfo?.let { versionOf(it) } ?: -1L
                // The re-read version is the proof, not result.success:
                // a claimed success that leaves the version stale counts
                // for nothing and must never toast.
                val reallyUpdated = afterVersion >= hostVersion
                DiagLog.log(
                    "clone-auto-update: userId=" + record.userId +
                        " from=" + cloneVersion + " host=" + hostVersion +
                        " success=" + result.success + " now=" + afterVersion +
                        " msg=" + result.msg +
                        (if (reallyUpdated) "" else " STILL STALE")
                )
                if (reallyUpdated) updated++
            } catch (e: Exception) {
                // One bad clone must not stop the others.
                DiagLog.log(
                    "clone-auto-update: userId=" + record.userId + " exception " + e
                )
            }
        }
        return CheckResult(updated, answered)
    }
}

/**
 * Fires the moment the real Muse app is replaced (Play Store update):
 * clears the updater throttle and runs the clone update right away, so
 * the clones follow immediately instead of waiting for the next
 * DevxyzMuse launch. Registered in the manifest (PACKAGE_REPLACED,
 * scheme "package"); only com.facebook.aura is acted on.
 */
class MuseUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_REPLACED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg != CloneAutoUpdater.MUSE_PACKAGE) return
        DiagLog.log("clone-auto-update: host Muse replaced on Play Store, updating clones now")
        val pending = goAsync()
        CloneAutoUpdater.checkNow(context) { pending.finish() }
    }
}
