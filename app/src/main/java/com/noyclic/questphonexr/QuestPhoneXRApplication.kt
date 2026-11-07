package com.noyclic.questphonexr

import android.app.Application
import android.os.Process
import android.util.Log
import com.noyclic.questphonexr.diagnostics.CrashReportStore
import com.noyclic.questphonexr.runtime.EndXrInputAccessManager
import kotlin.system.exitProcess

/** Installs local diagnostics and restores only previously approved EndXR input grants. */
class QuestPhoneXRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        restoreApprovedEndXrInputGrants()
        val crashReports = CrashReportStore(this)
        crashReports.capturePreviousProcessExit()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashReports.saveCrash(thread, error)
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * Android's package-specific URI grants are temporary and are cleared on
     * device reboot. The user's package + signer approvals live in private
     * preferences, so re-apply only those exact approvals after process start.
     * Do the PackageManager scan off the main thread to keep app launch responsive.
     */
    private fun restoreApprovedEndXrInputGrants() {
        Thread({
            runCatching {
                EndXrInputAccessManager(applicationContext).reconcileGrants()
            }.onSuccess { restored ->
                if (restored.isNotEmpty()) {
                    Log.i(TAG, "Restored EndXR input URI grants for ${restored.size} approved app(s)")
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not restore approved EndXR input URI grants", error)
            }
        }, "EndXR-InputGrantRestore").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TAG = "QuestPhoneXR"
    }
}
