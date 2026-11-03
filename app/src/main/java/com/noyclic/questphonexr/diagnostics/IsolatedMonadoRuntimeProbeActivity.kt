package com.noyclic.questphonexr.diagnostics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.runtime.OpenXrLoaderProbe

/**
 * Executes a direct Monado runtime probe in its own Android process.
 *
 * This process intentionally makes no Khronos Loader calls before invoking
 * Monado's own xrInitializeLoaderKHR and xrCreateInstance entry points.
 */
class IsolatedMonadoRuntimeProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val status = TextView(this).apply {
            text = "Проверяем Monado в чистом отдельном процессе…"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF101722.toInt())
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFF101722.toInt())
                addView(
                    status,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )

        val libraryPath = intent.getStringExtra(EXTRA_RUNTIME_LIBRARY_PATH)
        if (libraryPath.isNullOrBlank()) {
            finishWithReport("DIRECT_RUNTIME_PATH_MISSING: no Monado library path was supplied.")
            return
        }

        Thread({
            val report = buildString {
                append("Direct probe process: ")
                    .append(runCatching { android.app.Application.getProcessName() }.getOrDefault("unknown"))
                    .append(" · pid ").append(android.os.Process.myPid())
                append("\nWorker thread: ").append(Thread.currentThread().name)
                append("\nDevice: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                append("\nMonado library: ").append(libraryPath)
                append("\n\n")
                append(runCatching {
                    OpenXrLoaderProbe.inspectRuntimeInstanceFresh(
                        this@IsolatedMonadoRuntimeProbeActivity,
                        libraryPath
                    )
                }.getOrElse { error ->
                    "Direct Monado probe exception: " +
                        (error.message ?: error.javaClass.simpleName)
                })
            }
            runOnUiThread {
                status.text = report
                finishWithReport(report)
            }
        }, "MonadoDirectProbe").start()
    }

    private fun finishWithReport(report: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_REPORT, report)
        )
        finish()
    }

    companion object {
        const val EXTRA_RUNTIME_LIBRARY_PATH =
            "com.noyclic.questphonexr.extra.MONADO_LIBRARY_PATH"
        const val EXTRA_REPORT =
            "com.noyclic.questphonexr.extra.MONADO_DIRECT_REPORT"
    }
}
