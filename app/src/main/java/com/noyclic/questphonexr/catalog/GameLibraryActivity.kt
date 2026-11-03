package com.noyclic.questphonexr.catalog

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.MainActivity
import com.noyclic.questphonexr.vr.PhoneVrPreviewActivity
import com.noyclic.questphonexr.sandbox.SandboxActivity
import com.noyclic.questphonexr.diagnostics.CrashReportStore
import com.noyclic.questphonexr.diagnostics.CrashReportFormatter
import com.noyclic.questphonexr.runtime.OpenXrRuntimeDiscovery
import com.noyclic.questphonexr.runtime.MonadoAndroidRuntimeProbe
import com.noyclic.questphonexr.performance.OpenGlesEglProbe
import com.noyclic.questphonexr.performance.AndroidDeviceCompatibilityProbe
import com.noyclic.questphonexr.performance.DeviceCompatibilityReport
import com.noyclic.questphonexr.performance.NativeVulkanProbe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameLibraryActivity : ComponentActivity() {
    private lateinit var cardsContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var reportStore: GameTestReportStore
    private lateinit var openXrRuntimeStatusView: TextView
    private lateinit var deviceCompatibilityStatusView: TextView
    private val currentDevice by lazy { DeviceFingerprint.current() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportStore = SharedPreferencesGameTestReportStore(this)
        buildScreen()
        renderGames()
        refreshOpenXrRuntimeStatus()
        refreshDeviceCompatibility()
        window.decorView.post {
            val crashReports = CrashReportStore(this)
            if (crashReports.shouldPromptForPendingReport()) {
                crashReports.markPrompted()
                showCrashReportDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::openXrRuntimeStatusView.isInitialized) refreshOpenXrRuntimeStatus()
    }

    private fun openRuntimeBrokerSetup() {
        val brokerPackage = "org.khronos.openxr.runtime_broker"
        val launchIntent = packageManager.getLaunchIntentForPackage(brokerPackage)
        if (launchIntent != null) {
            runCatching { startActivity(launchIntent) }
                .onFailure {
                    Toast.makeText(this, "Не удалось открыть OpenXR Runtime Broker.", Toast.LENGTH_LONG).show()
                }
            return
        }

        val storeUri = Uri.parse(
            "https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker"
        )
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, storeUri)) }
            .onFailure {
                Toast.makeText(this, "Открой Google Play и найди OpenXR Runtime Broker от Khronos.", Toast.LENGTH_LONG).show()
            }
    }

    private fun refreshOpenXrRuntimeStatus() {
        if (!::openXrRuntimeStatusView.isInitialized) return
        val report = OpenXrRuntimeDiscovery.inspect(this)
        openXrRuntimeStatusView.text = buildString {
            append(report.message)
            report.processAbi?.let { append("\nABI процесса: ").append(it) }
            report.activeCandidate?.let { append("\nБиблиотека: ").append(it.libraryPath) }
        }
    }

    private fun refreshDeviceCompatibility(showDetails: Boolean = false) {
        if (!::deviceCompatibilityStatusView.isInitialized) return
        val report = runCatching { AndroidDeviceCompatibilityProbe.inspect(this) }
            .getOrElse {
                deviceCompatibilityStatusView.text = "Не удалось прочитать характеристики устройства: " +
                    (it.message ?: "неизвестная ошибка")
                deviceCompatibilityStatusView.setTextColor(0xFFFF9696.toInt())
                return
            }

        val levelColor = when (report.level) {
            com.noyclic.questphonexr.performance.DeviceCompatibilityLevel.SUITABLE -> 0xFF8FE0B5.toInt()
            com.noyclic.questphonexr.performance.DeviceCompatibilityLevel.LIMITED -> 0xFFFFD28A.toInt()
            com.noyclic.questphonexr.performance.DeviceCompatibilityLevel.NOT_RECOMMENDED -> 0xFFFFB36B.toInt()
            com.noyclic.questphonexr.performance.DeviceCompatibilityLevel.XR_TRACKING_UNSUPPORTED -> 0xFFFF9696.toInt()
        }
        deviceCompatibilityStatusView.text = report.level.label + "\n" + report.summary
        deviceCompatibilityStatusView.setTextColor(levelColor)

        if (showDetails) showDeviceCompatibilityDetails(report)
    }

    private fun showDeviceCompatibilityDetails(report: DeviceCompatibilityReport) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Проверка GPU и Monado")
            .setMessage("Проверяем временный GLES-контекст, Vulkan capabilities, APK и Android services…")
            .setCancelable(false)
            .create()
        progress.show()

        Thread({
            val nativeVulkan = runCatching { NativeVulkanProbe.inspect() }
            val gles = runCatching { OpenGlesEglProbe.inspect() }
            val monado = runCatching { MonadoAndroidRuntimeProbe.inspect(this) }
            val binder = runCatching { MonadoAndroidRuntimeProbe.checkBinderHandshake(this) }
            val activeRuntime = runCatching { OpenXrRuntimeDiscovery.inspect(this) }

            val runtimeText = activeRuntime.fold(
                { result ->
                    buildString {
                        append(result.message)
                        result.processAbi?.let { append("\nABI процесса: ").append(it) }
                        result.activeCandidate?.let {
                            append("\nАктивный пакет: ").append(it.packageName)
                            append("\nБиблиотека: ").append(it.libraryPath)
                        }
                    }
                },
                { error -> "Ошибка Runtime Broker discovery: " + (error.message ?: error.javaClass.simpleName) }
            )

            val details = buildString {
                append(report.deviceName)
                append(" · Android ").append(report.androidVersion)
                append(" (SDK ").append(report.sdkVersion).append(")\n")
                append("CPU: ").append(report.processorCount).append(" потоков · ")
                    .append(if (report.is64BitProcess) "64-bit" else "32-bit").append("\n")
                append("ABI: ").append(report.supportedAbis.joinToString().ifBlank { "неизвестно" }).append("\n")
                append("RAM: ").append(report.totalRamMb).append(" МБ всего, ")
                    .append(report.availableRamMb).append(" МБ свободно сейчас\n")
                append("OpenGL ES (Android): ").append(report.openGlEsVersion).append("\n")
                append("Vulkan (Android): ").append(report.vulkanApiVersion ?: "версия не сообщена")
                report.vulkanHardwareLevel?.let { append(" · hardware level ").append(it) }
                append("\n")
                report.rotationVectorSensorName?.let {
                    append("Датчик ориентации: ").append(it).append("\n")
                }

                append("\nАППАРАТНЫЕ ПРОВЕРКИ\n")
                report.checks.forEach { check ->
                    append("• ").append(check.title).append(" — ").append(check.status.label)
                        .append("\n").append(check.detail).append("\n\n")
                }

                append("NATIVE VULKAN PROBE\n")
                append(nativeVulkan.fold(
                    { it.toDisplayText() },
                    { error -> "Ошибка Vulkan probe: " + (error.message ?: error.javaClass.simpleName) }
                ))
                append("\n\nEGL / OPENGL ES PROBE\n")
                append(gles.fold(
                    { it.toDisplayText() },
                    { error -> "Ошибка EGL/GLES probe: " + (error.message ?: error.javaClass.simpleName) }
                ))
                append("\n\nMONADO ANDROID APK\n")
                append(monado.fold(
                    { it.toDisplayText() },
                    { error -> "Ошибка проверки Monado APK: " + (error.message ?: error.javaClass.simpleName) }
                ))
                append("\n\nMONADO BINDER HANDSHAKE\n")
                append(binder.fold(
                    { result ->
                        buildString {
                            append("Статус: ").append(result.status.name)
                            append("\nУспешно: ").append(if (result.isSuccessful) "да" else "нет")
                            append("\n").append(result.detail)
                            result.interfaceDescriptor?.let {
                                append("\nInterface descriptor: ").append(it)
                            }
                            if (result.isSuccessful) {
                                append("\nBinder-сервис доступен. connect()/passAppSurface() пока намеренно не вызываются.")
                            } else {
                                append("\nXR initialization не запускается, чтобы не повторять прежний NPE.")
                            }
                        }
                    },
                    { error ->
                        "Безопасная Binder-проверка завершилась ошибкой: " +
                            (error.message ?: error.javaClass.simpleName) +
                            "\nXR initialization не запускается."
                    }
                ))
                append("\n\nOPENXR RUNTIME BROKER\n").append(runtimeText)

                append("\n\nOPENXR LOADER SMOKE TEST\n")
                append("Пока намеренно не запускается: прежний xrCreateInstance приводил к NullPointerException внутри Monado Client при вызове IMonado.passAppSurface(). Сначала проверяем, проходит ли безопасный Binder bind без передачи Surface.")
                append("\n\nИТОГ\n")
                append("Следующий шаг зависит от Binder результата. Если IMonado успешно обнаружен, можно переходить к отдельной безопасной проверке connect() и Surface handoff; если bind не проходит, исправляем Android service lifecycle/доступ без запуска XR.")
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    runCatching { progress.dismiss() }
                    val detailsDialog = AlertDialog.Builder(this)
                        .setTitle("Диагностика Redmi / Monado")
                        .setMessage(details)
                        .setPositiveButton("Закрыть", null)
                        .setNeutralButton("Копировать отчёт", null)
                        .create()
                    detailsDialog.setOnShowListener {
                        detailsDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "QuestPhoneXR compatibility report",
                                    details
                                )
                            )
                            Toast.makeText(this, "Отчёт скопирован. Теперь вставь его сюда одним сообщением.", Toast.LENGTH_LONG).show()
                        }
                    }
                    detailsDialog.show()
                } else {
                    runCatching { progress.dismiss() }
                }
            }
        }, "QuestPhoneXR-compatibility").start()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(Color.rgb(14, 17, 23))
        }

        root.addView(button("← Выбор режима").apply {
            setOnClickListener { startActivity(Intent(this@GameLibraryActivity, MainActivity::class.java)) }
        })

        root.addView(title("Библиотека XR игр"))

        val compatibilityPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF202631.toInt(), dp(12).toFloat())
        }
        compatibilityPanel.addView(label("Совместимость устройства"))
        deviceCompatibilityStatusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(8))
        }
        compatibilityPanel.addView(deviceCompatibilityStatusView)
        compatibilityPanel.addView(button("Проверить устройство и Monado").apply {
            setOnClickListener { refreshDeviceCompatibility(showDetails = true) }
        })
        compatibilityPanel.addView(button("Открыть OpenXR Runtime Broker").apply {
            setOnClickListener { openRuntimeBrokerSetup() }
        })
        root.addView(compatibilityPanel, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        openXrRuntimeStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB9C2D0.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        root.addView(openXrRuntimeStatusView)

        searchInput = EditText(this).apply {
            hint = "Поиск игры…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8993A3.toInt())
            background = rounded(0xFF202631.toInt(), dp(10).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, dp(48)).apply {
            bottomMargin = dp(12)
        })

        val scroll = ScrollView(this)
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(cardsContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderGames(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        setContentView(root)
    }

    private fun renderGames(query: String = searchInputText()) {
        cardsContainer.removeAllViews()
        val normalized = query.trim().lowercase(Locale.getDefault())
        val games = GameCatalog.load(this).filter {
            normalized.isBlank() || it.title.lowercase(Locale.getDefault()).contains(normalized)
        }
        if (games.isEmpty()) {
            cardsContainer.addView(label("Ничего не найдено.").apply {
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }
        games.forEach { game ->
            cardsContainer.addView(gameCard(game))
        }
    }

    private fun searchInputText(): String = if (::searchInput.isInitialized) searchInput.text.toString() else ""

    private fun gameCard(game: GameCatalogEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF1B2029.toInt(), dp(12).toFloat())
        }
        card.addView(title(game.title, 18f))
        card.addView(label(game.description))
        card.addView(label("Источник: ${game.source.label}"))
        card.addView(label(game.compatibilitySummary))
        card.addView(button(if (game.installedApkUri != null) "Запустить" else "Добавить APK").apply {
            setOnClickListener { onGameAction(game) }
        })
        return card
    }

    private fun onGameAction(game: GameCatalogEntry) {
        if (game.installedApkUri != null) {
            Toast.makeText(this, "Запуск XR-приложения пока подключается через runtime bridge.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ApkImportActivity::class.java))
    }

    private fun title(text: String, size: Float = 22f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFB9C2D0.toInt())
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun button(text: String): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF2A3342.toInt())
        minimumHeight = dp(44)
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showCrashReportDialog() {
        val store = CrashReportStore(this)
        val report = store.readPendingReport() ?: return
        val details = CrashReportFormatter.format(report)
        AlertDialog.Builder(this)
            .setTitle("Предыдущий сбой QuestPhoneXR")
            .setMessage(details)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Копировать", null)
            .setNegativeButton("Поделиться", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("QuestPhoneXR crash report", details)
                        )
                        Toast.makeText(this, "Crash report скопирован.", Toast.LENGTH_SHORT).show()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, details)
                        }
                        startActivity(Intent.createChooser(share, "Поделиться crash report"))
                    }
                }
            }
            .show()
    }
}
