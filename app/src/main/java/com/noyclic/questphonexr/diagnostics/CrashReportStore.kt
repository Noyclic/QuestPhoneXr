package com.noyclic.questphonexr.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores one bounded crash report in app-private storage.
 *
 * Reports are never transmitted automatically. The user explicitly opens a prefilled GitHub
 * issue or shares the local text file after reviewing its contents.
 */
class CrashReportStore(context: Context) {
    private val appContext = context.applicationContext
    private val reportDirectory = File(appContext.filesDir, REPORT_DIRECTORY)
    private val reportFile = File(reportDirectory, REPORT_FILE_NAME)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun pendingReport(): File? =
        reportFile.takeIf { it.isFile && it.length() > 0L }

    fun readPendingReport(): String? =
        pendingReport()?.let { file -> runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }

    fun shouldPromptForPendingReport(): Boolean {
        val file = pendingReport() ?: return false
        return preferences.getLong(KEY_PROMPTED_MODIFIED, -1L) != file.lastModified()
    }

    fun markPrompted() {
        val file = pendingReport() ?: return
        preferences.edit().putLong(KEY_PROMPTED_MODIFIED, file.lastModified()).apply()
    }

    fun deletePendingReport(): Boolean {
        val deleted = !reportFile.exists() || reportFile.delete()
        if (deleted) preferences.edit().remove(KEY_PROMPTED_MODIFIED).apply()
        return deleted
    }

    fun githubIssueUri(): Uri {
        val report = readPendingReport()
        return Uri.parse(GITHUB_NEW_ISSUE_URL).buildUpon()
            .appendQueryParameter("title", CrashReportFormatter.issueTitle(report != null))
            .appendQueryParameter("body", CrashReportFormatter.issueBody(report))
            .build()
    }

    fun sharePendingReport(activityContext: Context): Boolean {
        val file = pendingReport() ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                activityContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "QuestPhoneXR crash report")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activityContext.startActivity(
                Intent.createChooser(shareIntent, "Поделиться crash-отчётом")
            )
        }.onFailure {
            Log.e(TAG, "Could not share crash report", it)
        }.isSuccess
    }

    internal fun saveCrash(thread: Thread, error: Throwable) {
        runCatching {
            if (!reportDirectory.exists() && !reportDirectory.mkdirs()) {
                error("Could not create crash report directory")
            }
            val versionName = runCatching {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                    ?: "unknown"
            }.getOrDefault("unknown")
            val stackTrace = StringWriter().also { writer ->
                error.printStackTrace(PrintWriter(writer))
            }.toString().take(MAX_STORED_STACK_TRACE_CHARS)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'Z", Locale.US)
                .format(Date())
            val report = buildString {
                appendLine("QuestPhoneXR crash report")
                appendLine("Time: $timestamp")
                appendLine("App version: $versionName")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(stackTrace)
            }.take(MAX_REPORT_FILE_CHARS)

            val temporary = File(reportDirectory, "$REPORT_FILE_NAME.tmp")
            temporary.writeText(report, Charsets.UTF_8)
            if (reportFile.exists() && !reportFile.delete()) {
                error("Could not replace previous crash report")
            }
            if (!temporary.renameTo(reportFile)) {
                temporary.copyTo(reportFile, overwrite = true)
                temporary.delete()
            }
            preferences.edit().remove(KEY_PROMPTED_MODIFIED).apply()
        }.onFailure {
            Log.e(TAG, "Could not persist crash report", it)
        }
    }

    private companion object {
        const val TAG = "QuestPhoneXR.CrashLog"
        const val REPORT_DIRECTORY = "crash-reports"
        const val REPORT_FILE_NAME = "latest-crash.txt"
        const val PREFERENCES_NAME = "crash_report_state"
        const val KEY_PROMPTED_MODIFIED = "prompted_report_modified"
        const val MAX_STORED_STACK_TRACE_CHARS = 48_000
        const val MAX_REPORT_FILE_CHARS = 52_000
        const val GITHUB_NEW_ISSUE_URL = "https://github.com/Noyclic/QuestPhoneXr/issues/new"
    }
}
