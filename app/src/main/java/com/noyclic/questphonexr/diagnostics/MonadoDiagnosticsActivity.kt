package com.noyclic.questphonexr.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.runtime.MonadoAndroidRuntimeProbe
import com.noyclic.questphonexr.runtime.OpenXrLoaderProbe
import com.noyclic.questphonexr.runtime.OpenXrRuntimeDiscovery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-device, user-triggered Monado/OpenXR report.
 *
 * Runs in an isolated process so a native loader/runtime failure cannot take down
 * the launcher activity. Android does not grant ordinary apps unrestricted Logcat
 * access; this report therefore collects only information exposed to this app.
 */
class MonadoDiagnosticsActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var reportView: TextView
    private lateinit var collectButton: Button
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button
    @Volatile private var latestReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        reportView = TextView(this).apply {
            text = "Нажми «Собрать отчёт». Проверка не запускает XR-сессию и не создаёт swapchain."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFF151C27.toInt())
        }
        collectButton = actionButton("Собрать отчёт Monado") { collectReport() }
        copyButton = actionButton("Скопировать отчёт", ::copyReport).apply { isEnabled = false }
        shareButton = actionButton("Поделиться TXT-отчётом") { shareReport() }.apply { isEnabled = false }
        val backButton = actionButton("Назад") { finish() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF0E131B.toInt())
            addView(TextView(this@MonadoDiagnosticsActivity).apply {
                text = "MONADO · OPENXR DIAGNOSTICS"
                setTextColor(0xFF8FD3B5.toInt())
                textSize = 17f
                setPadding(0, 0, 0, 8)
            })
            addView(TextView(this@MonadoDiagnosticsActivity).apply {
                text = "Локальная диагностика телефона. Ничего не отправляется автоматически."
                setTextColor(0xFFB9C4D3.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 8)
            })
            addView(ScrollView(this@MonadoDiagnosticsActivity).apply {
                isFillViewport = true
                addView(reportView)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(collectButton)
            addView(copyButton)
            addView(shareButton)
            addView(backButton)
        }
        setContentView(panel)
    }

    private fun collectReport() {
        collectButton.isEnabled = false
        collectButton.text = "Собираем…"
        reportView.text = "Проверяем устройство, Runtime Broker, Monado и OpenXR Loader…"
        worker.execute {
            val report = buildString {
                appendLine("QuestPhoneXR · Monado/OpenXR diagnostics")
                appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine()
                appendLine("=== OPENXR RUNTIME BROKER ===")
                val discovery = runCatching { OpenXrRuntimeDiscovery.inspect(this@MonadoDiagnosticsActivity) }
                    .getOrElse { error ->
                        appendLine("Broker probe exception: ${error.javaClass.simpleName}: ${error.message}")
                        null
                    }
                discovery?.let {
                    appendLine("State: ${it.state}")
                    appendLine("Process ABI: ${it.processAbi ?: "unknown"}")
                    appendLine(it.message)
                    it.candidates.forEachIndexed { index, candidate ->
                        appendLine("Candidate ${index + 1}: ${candidate.packageName}")
                        appendLine("Library: ${candidate.libraryPath}")
                        appendLine("Exists: ${candidate.libraryFileExists}")
                        appendLine("Broker: ${candidate.brokerAuthority}")
                        appendLine("Function remapping: ${candidate.hasFunctionRemapping}")
                        appendLine()
                        appendLine("--- DIRECT MONADO LIBRARY LOAD / NEGOTIATION ---")
                        appendLine("This checks dlopen + xrNegotiateLoaderRuntimeInterface only; it does not create an XrInstance or start a session.")
                        appendLine(runCatching {
                            OpenXrLoaderProbe.inspectRuntimeLibrary(candidate.libraryPath)
                        }.getOrElse { error ->
                            "Direct library probe exception: ${error.javaClass.simpleName}: ${error.message}"
                        })
                    }
                }
                appendLine()
                appendLine("=== MONADO APK / SERVICE / BINDER ===")
                val monado = runCatching { MonadoAndroidRuntimeProbe.inspect(this@MonadoDiagnosticsActivity) }
                    .getOrElse { error ->
                        appendLine("Monado probe exception: ${error.javaClass.simpleName}: ${error.message}")
                        null
                    }
                monado?.let { appendLine(it.toDisplayText()) }
                appendLine()
                appendLine("=== OPENXR LOADER SMOKE TEST ===")
                appendLine("This test attempts xrCreateInstance but does not create a session or graphics swapchain.")
                val loader = runCatching { OpenXrLoaderProbe.inspect(this@MonadoDiagnosticsActivity) }
                    .getOrElse { error ->
                        appendLine("Loader probe exception: ${error.javaClass.simpleName}: ${error.message}")
                        null
                    }
                loader?.let { appendLine(it.toDisplayText()) }
                appendLine()
                appendLine("=== LIMITATIONS ===")
                appendLine("No unrestricted system Logcat is available to a normal app on Android 15.")
                appendLine("No XR session, Surface hand-off, swapchain, compositor or game was started.")
                appendLine("The report is local and is shared only after the user presses Share.")
            }.take(MAX_REPORT_CHARS)

            runOnUiThread {
                latestReport = report
                reportView.text = report
                collectButton.isEnabled = true
                collectButton.text = "Собрать отчёт ещё раз"
                copyButton.isEnabled = true
                shareButton.isEnabled = true
            }
        }
    }

    private fun copyReport() {
        if (latestReport.isBlank()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QuestPhoneXR diagnostics", latestReport))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (latestReport.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "QuestPhoneXR Monado/OpenXR diagnostics")
            putExtra(Intent.EXTRA_TEXT, latestReport)
        }
        startActivity(Intent.createChooser(intent, "Отправить отчёт"))
    }

    private fun actionButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val MAX_REPORT_CHARS = 80_000
    }
}
