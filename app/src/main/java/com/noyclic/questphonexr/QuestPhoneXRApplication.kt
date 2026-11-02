package com.noyclic.questphonexr

import android.app.Application
import android.os.Process
import com.noyclic.questphonexr.diagnostics.CrashReportStore
import kotlin.system.exitProcess

/** Installs a local-only uncaught-exception handler before the first Activity is created. */
class QuestPhoneXRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                CrashReportStore(this).saveCrash(thread, error)
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
