package com.noyclic.questphonexr.catalog

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.noyclic.questphonexr.runtime.AndroidRuntimeBrokerContract
import com.noyclic.questphonexr.runtime.AndroidRuntimeBrokerProbe
import com.noyclic.questphonexr.runtime.EndXrHandTrackingService
import com.noyclic.questphonexr.runtime.EndXrInputAccessManager
import com.noyclic.questphonexr.runtime.EndXrRuntimeEventContentProvider
import com.noyclic.questphonexr.runtime.RuntimeBrokerAbiSelector
import com.noyclic.questphonexr.ui.applyNextVreraStyle
import com.noyclic.questphonexr.ui.nextVreraToneFor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Imports APK archives into app-private storage and displays a manifest-only report. */
class ApkImportActivity : ComponentActivity() {
    private lateinit var entriesContainer: LinearLayout
    private lateinit var statusView: TextView
    private val store by lazy { ImportedApkStore(this) }
    private val importExecutor = Executors.newSingleThreadExecutor()
    private var pendingLaunchReport: String? = null
    private var pendingLaunchTitle: String = "Диагностика запуска Quest APK"
    private var awaitingBrokerReturnForEndXrId: String? = null
    private var pendingCameraLaunch: PendingEndXrLaunch? = null
    private var pendingRuntimeTelemetryPackage: String? = null
    private var pendingRuntimeTelemetryBaselineSequence: Long = 0L
    private var runtimeTelemetryCheckGeneration: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var endXrPrepDialog: AlertDialog? = null
    private val endXrPrepRows = mutableMapOf<String, TextView>()
    private var endXrPrepActionButton: Button? = null

    private data class PendingEndXrLaunch(
        val infoId: String,
        val brokerReport: String,
        val inputIpcGranted: Boolean,
        val cameraPermissionGranted: Boolean? = null
    )

    private data class RuntimeTelemetrySnapshot(
        val sequence: Long,
        val eventName: String,
        val callerPackage: String,
        val runtimePid: Int,
        val instanceId: String,
        val sessionId: String,
        val instanceCreated: Boolean,
        val sessionCreated: Boolean,
        val sessionRunning: Boolean,
        val instanceSeen: Boolean,
        val sessionSeen: Boolean,
        val sessionEverRunning: Boolean,
        val frameCount: Long
    )

    private val endXrCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingCameraLaunch ?: return@registerForActivityResult
        pendingCameraLaunch = null
        val info = store.getAll().firstOrNull { it.id == pending.infoId }
        if (info == null) {
            statusView.text = "APK пропал из локальной библиотеки; запуск отменён."
            return@registerForActivityResult
        }
        startEndXrManagedLaunch(
            info,
            pending.copy(cameraPermissionGranted = granted)
        )
    }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        renderEntries()
    }

    override fun onResume() {
        super.onResume()
        if (::entriesContainer.isInitialized) renderEntries()
        val report = pendingLaunchReport
        if (report != null) {
            pendingLaunchReport = null
            pollRuntimeTelemetryAfterReturn(
                title = pendingLaunchTitle,
                report = report,
                expectedPackage = pendingRuntimeTelemetryPackage,
                baselineSequence = pendingRuntimeTelemetryBaselineSequence,
                generation = runtimeTelemetryCheckGeneration,
                attempt = 0
            )
        }

        val pendingId = awaitingBrokerReturnForEndXrId
        if (pendingId != null) {
            awaitingBrokerReturnForEndXrId = null
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val info = store.getAll().firstOrNull { it.id == pendingId } ?: return@post
                val broker = runCatching { AndroidRuntimeBrokerProbe.inspect(this, runtimeAbiFor(info)) }
                    .getOrNull()
                if (broker?.endXrIsLikelySelected == true) {
                    updateEndXrPrepStep(
                        "broker",
                        "EndXR снова подтверждён как вероятный кандидат Loader.",
                        0xFF8FE0B5.toInt()
                    )
                    continueEndXrManagedLaunch(info, broker.toDisplayText())
                } else {
                    val status = broker?.toDisplayText()
                        ?: "Не удалось повторно прочитать RuntimeBroker."
                    updateEndXrPrepStep(
                        "broker",
                        "EndXR всё ещё не выбран. Повтори проверку после выбора в Broker.",
                        0xFFFFD28A.toInt()
                    )
                    endXrPrepActionButton?.apply {
                        isEnabled = true
                        text = "Проверить снова"
                    }
                    statusView.text = "EndXR пока не подтверждён как активный runtime. Проверь выбор в Runtime Broker и повтори запуск.\n$status"
                    Toast.makeText(
                        this,
                        "EndXR всё ещё не выбран. NextVRera не будет запускать игру как будто перехват уже включён.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        importExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundResource(com.noyclic.questphonexr.R.drawable.bg_nextvrera_atmosphere)
        }
        root.addView(button("← Библиотека").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(text("NextVRera  /  ФАЙЛЫ ИГР", 12f, 0xFF9CA9BC.toInt(), Typeface.BOLD))
        root.addView(text("Импорт APK", 28f, Color.WHITE, Typeface.BOLD))
        root.addView(text(
            "Выбери APK через системный файловый менеджер. QuestPhoneXR скопирует его в своё закрытое хранилище и прочитает метаданные.",
            13f, 0xFFD0D7E2.toInt()
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        root.addView(text(
            "Важно: импорт не устанавливает и не запускает игру. Здесь мы проверяем манифест, Android API, ABI и VR-признаки. Совместимость с будущей XR-оболочкой проверяется отдельно.",
            12f, 0xFFFFD28A.toInt()
        ).apply {
            background = rounded(0xFF29251D.toInt(), dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })

        root.addView(button("Выбрать APK из файлов").apply {
            setOnClickListener {
                documentPicker.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                ))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        statusView = text("Файлы хранятся только внутри QuestPhoneXR.", 12f, 0xFF9CA9BC.toInt())
            .apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(statusView)

        entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(entriesContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(root)
        }
        setContentView(pageScroll)
    }

    private fun importApk(uri: Uri) {
        val originalName = queryDisplayName(uri)
        statusView.text = "Копирование и анализ APK…"
        importExecutor.execute {
            var temporaryFile: File? = null
            var finalFile: File? = null
            val result = runCatching {
                val directory = File(filesDir, "imported-apks")
                if (!directory.exists() && !directory.mkdirs()) {
                    error("Не удалось создать каталог импорта.")
                }
                val id = UUID.randomUUID().toString().replace("-", "")
                val temp = File(directory, "incoming-$id.apk")
                temporaryFile = temp
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Файловый провайдер не открыл APK.")
                if (temp.length() == 0L) error("Выбранный файл пуст.")

                val inspected = ApkArchiveInspector.inspect(
                    applicationContext,
                    temp,
                    id,
                    originalName
                )
                val safePackage = inspected.packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val savedName = "${safePackage}_${inspected.versionCode}_${id.take(8)}.apk"
                val destination = File(directory, savedName)
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                finalFile = destination
                inspected.copy(
                    fileName = savedName,
                    sizeBytes = destination.length(),
                    importedAtEpochMs = System.currentTimeMillis()
                )
            }
            if (result.isFailure) {
                temporaryFile?.delete()
                finalFile?.delete()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { info ->
                        store.save(info)
                        statusView.text = "Импортирован: ${info.displayName} · ${formatSize(info.sizeBytes)}"
                        renderEntries()
                        Toast.makeText(this, "APK добавлен в локальную библиотеку.", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        statusView.text = "Не удалось импортировать APK: ${error.message ?: "ошибка чтения"}"
                    }
                )
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "unknown.apk"
    }

    private fun renderEntries() {
        if (!::entriesContainer.isInitialized) return
        entriesContainer.removeAllViews()
        val entries = store.getAll().sortedByDescending { it.importedAtEpochMs }
        if (entries.isEmpty()) {
            entriesContainer.addView(text(
                "Пока нет импортированных APK. Нажми «Выбрать APK из файлов», чтобы добавить первую сборку.",
                14f, 0xFFB8C2D0.toInt()
            ).apply { setPadding(0, dp(16), 0, dp(16)) })
            return
        }
        entries.forEach { info -> entriesContainer.addView(createEntryCard(info)) }
    }

    private fun createEntryCard(info: ImportedApkInfo): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF202630.toInt(), dp(12).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(text(info.displayName, 18f, Color.WHITE, Typeface.BOLD))
        card.addView(text(info.packageName, 12f, 0xFF9BC7F5.toInt()))
        card.addView(text(
            "Версия: ${info.versionName.ifBlank { "без номера" }} (${info.versionCode}) · ${formatSize(info.sizeBytes)}",
            12f, 0xFFD0D7E2.toInt()
        ).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(text(
            "Android API: минимум ${info.minSdk}, target ${info.targetSdk}",
            12f, 0xFFB8C7DB.toInt()
        ))
        card.addView(text(
            "Нативные ABI: ${info.nativeAbis.takeIf { it.isNotEmpty() }?.joinToString() ?: "не обнаружены"}",
            12f, 0xFFB8C7DB.toInt()
        ))
        if (info.requiredFeatures.isNotEmpty()) {
            card.addView(text(
                "Обязательные функции: ${info.requiredFeatures.joinToString()}",
                11f, 0xFFB8C7DB.toInt()
            ).apply { setPadding(0, dp(3), 0, 0) })
        }
        val report = ApkCompatibilityAnalyzer.warnings(this, info)
        report.forEach { warning ->
            card.addView(text("• $warning", 12f, 0xFFFFD28A.toInt())
                .apply { setPadding(0, dp(6), 0, 0) })
        }

        val apkFile = File(filesDir, "imported-apks/${info.fileName}")
        val missingFeatures = info.requiredFeatures.filterNot {
            packageManager.hasSystemFeature(it)
        }.toSet()
        val runtimeAssessment = ApkRuntimeCompatibility.inspect(
            info = info,
            archive = apkFile,
            deviceApiLevel = android.os.Build.VERSION.SDK_INT,
            supportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            missingRequiredFeatures = missingFeatures
        )
        card.addView(text(
            "Маршрут совместимости: ${runtimeAssessment.route.label}",
            12f,
            if (runtimeAssessment.route == ApkLaunchRoute.ANDROID_APP_CANDIDATE) 0xFF8FE0B5.toInt() else 0xFFFFD28A.toInt(),
            Typeface.BOLD
        ).apply { setPadding(0, dp(9), 0, dp(3)) })
        card.addView(text(
            "Движок: ${runtimeAssessment.engine}" +
                (runtimeAssessment.xrTechnologies.takeIf { it.isNotEmpty() }?.joinToString(
                    prefix = " · XR: "
                ) ?: ""),
            11f,
            0xFFB8C7DB.toInt()
        ))
        val audit = runtimeAssessment.audit
        addAuditLine(card, "SHA-256", info.sha256.ifBlank { "не вычислен (старая запись)" })
        addAuditLine(card, "ABI-проверка", audit.abiSummary)
        addAuditLine(
            card,
            "Нативные библиотеки",
            audit.nativeLibraries.takeIf { it.isNotEmpty() }?.joinToString() ?: "не обнаружены"
        )
        if (audit.sdkRuntimeMarkers.isNotEmpty()) {
            addAuditLine(card, "SDK / Runtime следы", audit.sdkRuntimeMarkers)
        }
        if (audit.manifestReferences.isNotEmpty()) {
            addAuditLine(card, "VR-записи манифеста", audit.manifestReferences)
        }
        if (audit.metadataValues.isNotEmpty()) {
            addAuditLine(card, "VR-значения метаданных", audit.metadataValues)
        }
        if (audit.componentReferences.isNotEmpty()) {
            addAuditLine(card, "VR-компоненты Android", audit.componentReferences)
        }
        runtimeAssessment.details.forEach { detail ->
            card.addView(text("• $detail", 11f, 0xFFAAB4C2.toInt())
                .apply { setPadding(0, dp(4), 0, 0) })
        }
        card.addView(button("Скопировать полный отчёт").apply {
            setOnClickListener { copyAuditReport(info, runtimeAssessment) }
        })
        card.addView(text(
            "Импортировано: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(info.importedAtEpochMs)),
            11f, 0xFF8994A5.toInt()
        ).apply { setPadding(0, dp(7), 0, dp(5)) })
        card.addView(button("Открыть установленное приложение").apply {
            setOnClickListener { launchInstalled(info) }
        })
        if (runtimeAssessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE) {
            card.addView(button("Запустить через NextVRera + EndXR").apply {
                setOnClickListener { launchThroughNextVreraEndXr(info) }
            })
        }
        card.addView(button("Установить на Android").apply {
            setOnClickListener { installOnAndroid(info) }
        })
        card.addView(button("Удалить импорт").apply {
            setOnClickListener {
                AlertDialog.Builder(this@ApkImportActivity)
                    .setTitle("Удалить APK?")
                    .setMessage("Файл «${info.originalName}» будет удалён из хранилища QuestPhoneXR.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ ->
                        store.remove(info.id)
                        renderEntries()
                        statusView.text = "Локальный APK удалён."
                    }
                    .show()
            }
        })
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        card.layoutParams = params
        return card
    }


    private fun launchInstalled(info: ImportedApkInfo) {
        val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
        val archive = File(filesDir, "imported-apks/${info.fileName}")
        val missing = info.requiredFeatures.filterNot { packageManager.hasSystemFeature(it) }.toSet()
        val assessment = ApkRuntimeCompatibility.inspect(
            info,
            archive,
            android.os.Build.VERSION.SDK_INT,
            android.os.Build.SUPPORTED_ABIS.toList(),
            missing
        )
        val brokerReport = runCatching {
            AndroidRuntimeBrokerProbe.inspect(this, runtimeAbiFor(info)).toDisplayText()
        }.getOrElse {
            "RuntimeBroker probe failed: ${it.message ?: it.javaClass.simpleName}"
        }

        if (launchIntent == null) {
            val report = buildLaunchReport(
                info, assessment, brokerReport, false, false, null,
                "Установленный пакет не предоставил Android launcher Activity."
            )
            val message = "Не найден экран запуска для пакета ${info.packageName}. Проверь, что APK действительно установлен целиком (включая split APK), а не только импортирован."
            statusView.text = message
            showLaunchDiagnostic("APK не удалось открыть", report)
            return
        }

        // Static APK heuristics must warn, not silently prevent the user from
        // testing an installed app. Quest/Meta markers do not prove that Android
        // cannot start its launcher Activity.
        if (assessment.route != ApkLaunchRoute.ANDROID_APP_CANDIDATE &&
            assessment.route != ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE
        ) {
            AlertDialog.Builder(this)
                .setTitle("Запуск с предупреждением")
                .setMessage(
                    assessment.details.joinToString("\n").ifBlank {
                        "Для этого APK совместимость не подтверждена."
                    } + "\n\nПопробовать открыть установленное приложение обычным способом Android?"
                )
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Всё равно открыть") { _, _ ->
                    startInstalledPackage(info, launchIntent, assessment, brokerReport)
                }
                .show()
            return
        }

        startInstalledPackage(info, launchIntent, assessment, brokerReport)
    }

    private fun startInstalledPackage(
        info: ImportedApkInfo,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment,
        brokerReport: String
    ) {
        // Ordinary Android launch is not an implicit consent to expose EndXR input.
        // Use the dedicated NextVRera + EndXR preflight for a scoped, user-approved URI grant.
        val ipcGranted = false

        runCatching {
            pendingLaunchTitle = "Отчёт после запуска: ${info.displayName}"
            pendingLaunchReport = buildLaunchReport(
                info, assessment, brokerReport, true, ipcGranted, null,
                "Android принял запрос startActivity."
            )
            startActivity(launchIntent)
            val message = when {
                assessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE ->
                    "Это обычный запуск Android без подготовки EndXR. Для Broker-проверки, разрешения ввода и фонового трекинга используй «Запустить через NextVRera + EndXR»."
                else ->
                    "Команда запуска отправлена Android. Это пока не подтверждает работу XR/Quest Runtime."
            }
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            pendingLaunchReport = null
            val message = error.message ?: error.javaClass.simpleName
            val report = buildLaunchReport(
                info, assessment, brokerReport, true, ipcGranted,
                message, "Android отклонил запуск Activity."
            )
            statusView.text = "Android не смог открыть ${info.displayName}: ${message}"
            showLaunchDiagnostic("Ошибка запуска APK", report)
        }
    }

    /**
     * Managed NextVRera path: verify the Loader's likely active Broker candidate,
     * obtain explicit package-scoped input approval, start camera tracking, then
     * launch the installed APK. It does not claim that the game called xrCreateInstance.
     */
    private fun launchThroughNextVreraEndXr(info: ImportedApkInfo) {
        val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
        if (launchIntent == null) {
            statusView.text = "Не найден launcher Activity для ${info.packageName}. Установи полный APK-набор (включая split APK), затем повтори."
            return
        }
        val assessment = assessImportedApk(info)
        if (assessment.route != ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE) {
            AlertDialog.Builder(this)
                .setTitle("EndXR preflight недоступен")
                .setMessage(assessment.details.joinToString("\n").ifBlank {
                    "APK не распознан как OpenXR-кандидат."
                })
                .setPositiveButton("Закрыть", null)
                .show()
            return
        }

        showEndXrLaunchPreparation(info, assessment)
    }

    private fun showEndXrLaunchPreparation(
        info: ImportedApkInfo,
        assessment: ApkRuntimeAssessment
    ) {
        endXrPrepDialog?.dismiss()
        endXrPrepRows.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            setBackgroundResource(com.noyclic.questphonexr.R.drawable.bg_nextvrera_atmosphere)
        }
        root.addView(text("NEXTVRERA  /  ENDXR", 12f, 0xFF9BC7F5.toInt(), Typeface.BOLD))
        root.addView(text("Подготовка XR-сеанса", 25f, Color.WHITE, Typeface.BOLD)
            .apply { setPadding(0, dp(6), 0, dp(5)) })
        root.addView(text(
            info.displayName + "\n" + info.packageName,
            13f, 0xFFD0D7E2.toInt()
        ).apply { setPadding(0, 0, 0, dp(12)) })

        val steps = listOf(
            "apk" to ("1 · APK и ABI" to "Статическая проверка завершена."),
            "broker" to ("2 · RuntimeBroker" to "Ожидает проверки активного OpenXR runtime."),
            "input" to ("3 · Ввод EndXR" to "Ожидает проверки разрешения controller_state."),
            "camera" to ("4 · Камера и MediaPipe" to "Ожидает разрешения и свежего кадра."),
            "launch" to ("5 · Запуск игры" to "Запрос запуска ещё не отправлен."),
            "capture" to ("6 · Связь игры с EndXR" to "Не подтверждена; нужен сигнал из процесса игры.")
        )
        for ((key, pair) in steps) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFF202630.toInt(), dp(10).toFloat())
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            card.addView(text(pair.first, 13f, Color.WHITE, Typeface.BOLD))
            val status = text(pair.second, 12f, 0xFF9CA9BC.toInt())
                .apply { setPadding(0, dp(3), 0, 0) }
            card.addView(status)
            endXrPrepRows[key] = status
            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) })
        }

        root.addView(text(
            "RuntimeBroker показывает вероятного кандидата Loader, но не доказывает загрузку EndXR игрой. Реальное подключение можно подтвердить только событиями xrCreateInstance / xrCreateSession / xrEndFrame.",
            11f, 0xFFFFD28A.toInt()
        ).apply {
            background = rounded(0xFF29251D.toInt(), dp(9).toFloat())
            setPadding(dp(11), dp(9), dp(11), dp(9))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = button("Проверить и подготовить").apply {
            setOnClickListener {
                isEnabled = false
                text = "Проверяем RuntimeBroker…"
                runEndXrLaunchPreflight(info)
            }
        }
        endXrPrepActionButton = start
        actions.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button("Назад").apply {
            setOnClickListener { endXrPrepDialog?.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8) })
        root.addView(actions)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        val dialog = AlertDialog.Builder(this).setView(scroll).create()
        endXrPrepDialog = dialog
        dialog.setOnDismissListener {
            if (endXrPrepDialog === dialog) {
                endXrPrepDialog = null
                endXrPrepActionButton = null
                endXrPrepRows.clear()
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        updateEndXrPrepStep(
            "apk",
            "Готово · " + assessment.route.label + " · ABI: " + assessment.audit.abiSummary,
            0xFF8FE0B5.toInt()
        )
    }

    private fun updateEndXrPrepStep(
        key: String,
        value: String,
        color: Int = 0xFF9CA9BC.toInt()
    ) {
        endXrPrepRows[key]?.apply {
            text = value
            setTextColor(color)
        }
    }

    private fun runEndXrLaunchPreflight(info: ImportedApkInfo) {
        updateEndXrPrepStep("broker", "Читаем RuntimeBroker и ABI-кандидата…")
        val broker = runCatching {
            AndroidRuntimeBrokerProbe.inspect(this, runtimeAbiFor(info))
        }.getOrElse { error ->
            updateEndXrPrepStep(
                "broker",
                "Ошибка чтения RuntimeBroker: " + (error.message ?: error.javaClass.simpleName),
                0xFFFFD28A.toInt()
            )
            endXrPrepActionButton?.apply {
                isEnabled = true
                text = "Повторить проверку"
            }
            return
        }
        if (!broker.endXrIsLikelySelected) {
            updateEndXrPrepStep(
                "broker",
                "EndXR не выбран как вероятный кандидат Loader. Выбери его в Khronos RuntimeBroker.",
                0xFFFFD28A.toInt()
            )
            endXrPrepActionButton?.apply {
                isEnabled = true
                text = "Проверить снова после выбора"
            }
            showEndXrBrokerSelectionRequired(info, broker)
            return
        }
        updateEndXrPrepStep(
            "broker",
            "EndXR найден как вероятный кандидат Loader · ABI " + (broker.loaderAbi ?: "unknown") + ".",
            0xFF8FE0B5.toInt()
        )
        endXrPrepActionButton?.apply {
            isEnabled = false
            text = "Подготовка ввода и камеры…"
        }
        continueEndXrManagedLaunch(info, broker.toDisplayText())
    }

    private fun showEndXrBrokerSelectionRequired(
        info: ImportedApkInfo,
        broker: com.noyclic.questphonexr.runtime.RuntimeBrokerProbeReport
    ) {
        val details = buildString {
            appendLine("ABI проверки: ${broker.loaderAbi ?: "неизвестно"}")
            appendLine("Broker, который Loader будет опрашивать: ${broker.loaderSelectedBroker ?: "не найден"}")
            appendLine("Кандидат runtime: ${broker.loaderSelectedRuntimePackage ?: "не выбран"}")
            appendLine("Библиотека кандидата существует: ${broker.loaderSelectedRuntimeLibraryExists}")
            appendLine("EndXR выбран как кандидат: ${if (broker.endXrIsLikelySelected) "ДА" else "НЕТ"}")
            appendLine()
            appendLine("Открой Khronos Runtime Broker и выбери «EndXR Phone Runtime» (может отображаться как EndXR OpenXR Runtime).")
            appendLine("После возврата NextVRera автоматически перепроверит активный runtime. Игра не будет запущена до этого шага.")
        }
        AlertDialog.Builder(this)
            .setTitle("Сначала выбери EndXR")
            .setMessage(details)
            .setNegativeButton("Отмена") { _, _ ->
                awaitingBrokerReturnForEndXrId = null
            }
            .setPositiveButton("Открыть Runtime Broker") { _, _ ->
                awaitingBrokerReturnForEndXrId = info.id
                openInstallableRuntimeBroker()
            }
            .setNeutralButton("Открыть страницу Broker") { _, _ ->
                awaitingBrokerReturnForEndXrId = info.id
                runCatching {
                    startActivity(Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_PLAY_STORE_URL)
                    ))
                }.onFailure { error ->
                    awaitingBrokerReturnForEndXrId = null
                    Toast.makeText(this, "Не удалось открыть страницу Broker: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun openInstallableRuntimeBroker() {
        val brokerIntent = Intent().setClassName(
            AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_PACKAGE,
            AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_MAIN_ACTIVITY
        )
        runCatching { startActivity(brokerIntent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(AndroidRuntimeBrokerContract.INSTALLABLE_BROKER_PLAY_STORE_URL)
                    ))
                }.onFailure { error ->
                    awaitingBrokerReturnForEndXrId = null
                    Toast.makeText(this, "Не удалось открыть Runtime Broker: ${error.message ?: "приложение недоступно"}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun continueEndXrManagedLaunch(
        info: ImportedApkInfo,
        brokerReport: String
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
        if (launchIntent == null) {
            statusView.text = "Приложение больше не предоставляет launcher Activity."
            return
        }
        val assessment = assessImportedApk(info)
        if (assessment.route != ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE) {
            statusView.text = "Статический APK-аудит больше не считает пакет OpenXR-кандидатом. Запуск остановлен."
            return
        }

        val manager = EndXrInputAccessManager(this)
        val client = manager.installedLaunchableClients()
            .firstOrNull { it.packageName == info.packageName }
        updateEndXrPrepStep("input", "Проверяем подпись APK и существующее разрешение ввода…")
        if (client == null) {
            updateEndXrPrepStep(
                "input",
                "Не удалось подтвердить доверенный клиент. Разрешение ввода не будет выдано автоматически.",
                0xFFFFD28A.toInt()
            )
            AlertDialog.Builder(this)
                .setTitle("Нет безопасного IPC-доступа")
                .setMessage(
                    "NextVRera не смог подтвердить подпись установленного приложения «${info.packageName}». " +
                        "Продолжить без передачи ввода EndXR? Сам запуск всё ещё не гарантирует совместимость."
                )
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Запустить без ввода") { _, _ ->
                    requestCameraThenLaunch(
                        PendingEndXrLaunch(info.id, brokerReport, inputIpcGranted = false),
                        launchIntent,
                        assessment
                    )
                }
                .show()
            return
        }

        if (manager.isAuthorized(client)) {
            val activeGrants = manager.reconcileGrants()
            val inputGrantRestored = activeGrants.any { it.packageName == client.packageName }
            val telemetryGrantRestored = manager.isRuntimeTelemetryAuthorized(client)
            if (inputGrantRestored && telemetryGrantRestored) {
                updateEndXrPrepStep(
                    "input",
                    "Доступы восстановлены: controller_state read-only и runtime_event write-only.",
                    0xFF8FE0B5.toInt()
                )
                requestCameraThenLaunch(
                    PendingEndXrLaunch(info.id, brokerReport, inputIpcGranted = true),
                    launchIntent,
                    assessment
                )
                return
            } else if (inputGrantRestored) {
                updateEndXrPrepStep(
                    "input",
                    "Старое согласие покрывает только чтение контроллеров. Для обратной телеметрии нужно отдельное подтверждение.",
                    0xFFFFD28A.toInt()
                )
            }
        }

        updateEndXrPrepStep(
            "input",
            "Ожидает подтверждения доступа к контроллерам и отдельного write-only разрешения для runtime_event.",
            0xFFFFD28A.toInt()
        )
        AlertDialog.Builder(this)
            .setTitle("Разрешить ввод EndXR для игры?")
            .setMessage(
                "Приложение: ${client.label}\nПакет: ${client.packageName}\n\n" +
                    "NextVRera выдаст этому приложению read-only доступ к controller_state, " +
                    "а EndXR получит write-only доступ к отдельному URI runtime_event, чтобы сообщать " +
                    "о xrCreateInstance, xrCreateSession и передаче кадров. Оба доступа ограничены " +
                    "этим пакетом и связаны с подписью APK; их можно отозвать. " +
                    "Это не внедряет Android-кнопки в игру: игре нужно поддерживать OpenXR input или контракт EndXR."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Разрешить и продолжить") { _, _ ->
                runCatching { manager.authorize(client.packageName) }
                    .onSuccess {
                        updateEndXrPrepStep(
                            "input",
                            "controller_state read-only и runtime_event write-only разрешены этому приложению.",
                            0xFF8FE0B5.toInt()
                        )
                        requestCameraThenLaunch(
                            PendingEndXrLaunch(info.id, brokerReport, inputIpcGranted = true),
                            launchIntent,
                            assessment
                        )
                    }
                    .onFailure { error ->
                        AlertDialog.Builder(this)
                            .setTitle("Не удалось разрешить ввод")
                            .setMessage("${error.message ?: "Android отклонил URI grant."}\n\nЗапустить игру без ввода EndXR?")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Запустить без ввода") { _, _ ->
                                requestCameraThenLaunch(
                                    PendingEndXrLaunch(info.id, brokerReport, inputIpcGranted = false),
                                    launchIntent,
                                    assessment
                                )
                            }
                            .show()
                    }
            }
            .show()
    }

    /**
     * RuntimeBroker selection is ABI-specific. Prefer the installed package's
     * extracted native-library directory (which reflects Android's chosen ABI),
     * then fall back to the APK ABI list ordered by this device's preference.
     */
    private fun runtimeAbiFor(info: ImportedApkInfo): String? {
        val nativeLibraryDir = runCatching {
            packageManager.getApplicationInfo(info.packageName, 0).nativeLibraryDir
        }.getOrNull()
        return RuntimeBrokerAbiSelector.select(
            deviceSupportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            apkNativeAbis = info.nativeAbis,
            installedNativeLibraryDir = nativeLibraryDir
        )
    }

    private fun assessImportedApk(info: ImportedApkInfo): ApkRuntimeAssessment {
        val archive = File(filesDir, "imported-apks/${info.fileName}")
        val missing = info.requiredFeatures
            .filterNot { packageManager.hasSystemFeature(it) }
            .toSet()
        return ApkRuntimeCompatibility.inspect(
            info = info,
            archive = archive,
            deviceApiLevel = android.os.Build.VERSION.SDK_INT,
            supportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            missingRequiredFeatures = missing
        )
    }

    private fun requestCameraThenLaunch(
        pending: PendingEndXrLaunch,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment
    ) {
        updateEndXrPrepStep("camera", "Проверяем разрешение камеры…")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startEndXrManagedLaunch(
                pending.copy(cameraPermissionGranted = true),
                launchIntent,
                assessment
            )
        } else {
            pendingCameraLaunch = pending
            endXrCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startEndXrManagedLaunch(
        info: ImportedApkInfo,
        pending: PendingEndXrLaunch
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)
        if (launchIntent == null) {
            statusView.text = "Не найден launcher Activity для ${info.packageName}."
            return
        }
        startEndXrManagedLaunch(
            pending,
            launchIntent,
            assessImportedApk(info)
        )
    }

    private fun startEndXrManagedLaunch(
        pending: PendingEndXrLaunch,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment
    ) {
        if (pending.cameraPermissionGranted != true) {
            updateEndXrPrepStep(
                "camera",
                "Разрешение камеры отсутствует. Игра может запуститься без MediaPipe-трекинга.",
                0xFFFFD28A.toInt()
            )
            launchEndXrManagedPackage(
                pending,
                launchIntent,
                assessment,
                trackingStarted = false,
                trackingStatus = "camera permission not granted"
            )
            return
        }

        if (EndXrHandTrackingService.isRunning() &&
            EndXrHandTrackingService.diagnosticState() == "running"
        ) {
            updateEndXrPrepStep("camera", "Сервис трекинга активен; проверяем свежесть последнего кадра.")
            waitForEndXrCameraReady(pending, launchIntent, assessment, attempt = 0)
            return
        }

        updateEndXrPrepStep("camera", "Запускаем foreground-сервис камеры и ждём свежий кадр MediaPipe…")
        runCatching {
            ContextCompat.startForegroundService(
                this,
                EndXrHandTrackingService.createResumeIntent(this)
            )
        }.onFailure { error ->
            EndXrHandTrackingService.markStartFailed(
                error.message ?: error.javaClass.simpleName
            )
            launchEndXrManagedPackage(
                pending,
                launchIntent,
                assessment,
                trackingStarted = false,
                trackingStatus = "foreground service start failed: ${error.message ?: error.javaClass.simpleName}"
            )
            return
        }
        waitForEndXrCameraReady(pending, launchIntent, assessment, attempt = 0)
    }

    private fun waitForEndXrCameraReady(
        pending: PendingEndXrLaunch,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment,
        attempt: Int
    ) {
        if (isFinishing || isDestroyed) return
        val state = EndXrHandTrackingService.diagnosticState()
        val frameAgeMs = if (EndXrHandTrackingService.latestFrameReceivedAtMs() > 0L) {
            SystemClock.uptimeMillis() - EndXrHandTrackingService.latestFrameReceivedAtMs()
        } else {
            Long.MAX_VALUE
        }
        val frameReady = EndXrHandTrackingService.latestFrame().timestampMs > 0L &&
            frameAgeMs in 0L..1_500L
        if (state == "running" && frameReady) {
            updateEndXrPrepStep(
                "camera",
                "MediaPipe готов · свежий кадр получен (" + frameAgeMs + " мс).",
                0xFF8FE0B5.toInt()
            )
            launchEndXrManagedPackage(
                pending,
                launchIntent,
                assessment,
                trackingStarted = true,
                trackingStatus = "running; fresh MediaPipe frame observed"
            )
            return
        }
        if (state == "error" || state == "stopped" || attempt >= 28) {
            val reason = EndXrHandTrackingService.diagnosticLastError()
            updateEndXrPrepStep(
                "camera",
                "Кадр не получен · state=" + state + " · age_ms=" + frameAgeMs + " · " + reason,
                0xFFFFD28A.toInt()
            )
            launchEndXrManagedPackage(
                pending,
                launchIntent,
                assessment,
                trackingStarted = false,
                trackingStatus = "not ready (state=${state}; frame_age_ms=${frameAgeMs}; error=${reason})"
            )
            return
        }
        updateEndXrPrepStep(
            "camera",
            "Ожидаем свежий кадр MediaPipe… " + (attempt * 250) + " мс; service=" + state
        )
        mainHandler.postDelayed({
            waitForEndXrCameraReady(pending, launchIntent, assessment, attempt + 1)
        }, 250L)
    }

    private fun launchEndXrManagedPackage(
        pending: PendingEndXrLaunch,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment,
        trackingStarted: Boolean,
        trackingStatus: String
    ) {
        val info = store.getAll().firstOrNull { it.id == pending.infoId }
        if (info == null) {
            statusView.text = "APK удалён из NextVRera; запуск отменён."
            return
        }
        val broker = runCatching { AndroidRuntimeBrokerProbe.inspect(this, runtimeAbiFor(info)) }.getOrNull()
        if (broker?.endXrIsLikelySelected != true) {
            statusView.text = "RuntimeBroker больше не подтверждает EndXR. Игра не запущена; повтори preflight."
            Toast.makeText(this, statusView.text, Toast.LENGTH_LONG).show()
            return
        }

        val launchDetails = buildString {
            appendLine()
            appendLine("[NEXTVRERA ENDXR MANAGED LAUNCH]")
            appendLine("loader_predicted_endxr_selected=${broker.endXrIsLikelySelected}")
            appendLine("loader_broker_query_target=${broker.loaderSelectedBroker}")
            appendLine("loader_selected_package=${broker.loaderSelectedRuntimePackage}")
            appendLine("loader_abi=${broker.loaderAbi}")
            appendLine("input_uri_granted=${pending.inputIpcGranted}")
            appendLine("camera_tracking=${if (trackingStarted) "READY" else "NOT_READY"}")
            appendLine("camera_tracking_status=$trackingStatus")
            appendLine("camera_tracking_fps=${EndXrHandTrackingService.diagnosticFps()}")
            appendLine("camera_tracking_state=${EndXrHandTrackingService.diagnosticState()}")
            appendLine("game_xr_runtime_connection=NOT_CONFIRMED")
            appendLine("A real game connection still requires evidence from xrCreateInstance/xrCreateSession in that game process.")
        }
        runCatching {
            pendingLaunchTitle = "NextVRera / EndXR · ${info.displayName}"
            runtimeTelemetryCheckGeneration += 1L
            pendingRuntimeTelemetryPackage = info.packageName
            pendingRuntimeTelemetryBaselineSequence =
                readLatestRuntimeTelemetry()?.sequence ?: 0L
            pendingLaunchReport = buildLaunchReport(
                info,
                assessment,
                pending.brokerReport,
                launcherActivityFound = true,
                inputIpcGranted = pending.inputIpcGranted,
                launchError = null,
                launchResult = "NextVRera preflight passed; Android accepted startActivity."
            ) + launchDetails
            updateEndXrPrepStep("launch", "Передаём запрос запуска в Android…")
            startActivity(launchIntent)
            updateEndXrPrepStep(
                "launch",
                "Android принял startActivity для " + info.displayName + ".",
                0xFF8FE0B5.toInt()
            )
            updateEndXrPrepStep(
                "capture",
                "Ожидает реального события xrCreateInstance / xrCreateSession / xrEndFrame.",
                0xFFFFD28A.toInt()
            )
            endXrPrepDialog?.dismiss()
            val message = if (trackingStarted) {
                "NextVRera подготовила EndXR: runtime-кандидат подтверждён, IPC разрешён, MediaPipe передал свежий кадр. Запрос запуска отправлен; подключение самой игры ещё нужно подтвердить."
            } else {
                "EndXR выбран как кандидат и запуск отправлен, но камера не готова: $trackingStatus"
            }
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            updateEndXrPrepStep(
                "launch",
                "Android отклонил запуск: " + (error.message ?: error.javaClass.simpleName),
                0xFFFFD28A.toInt()
            )
            pendingLaunchReport = null
            runtimeTelemetryCheckGeneration += 1L
            pendingRuntimeTelemetryPackage = null
            pendingRuntimeTelemetryBaselineSequence = 0L
            val report = buildLaunchReport(
                info,
                assessment,
                pending.brokerReport,
                launcherActivityFound = true,
                inputIpcGranted = pending.inputIpcGranted,
                launchError = error.message ?: error.javaClass.simpleName,
                launchResult = "NextVRera preflight passed, but Android rejected startActivity."
            ) + launchDetails
            statusView.text = "Не удалось запустить ${info.displayName}: ${error.message ?: error.javaClass.simpleName}"
            showLaunchDiagnostic("Ошибка NextVRera / EndXR запуска", report)
        }
    }

    private fun buildLaunchReport(
        info: ImportedApkInfo,
        assessment: ApkRuntimeAssessment,
        brokerReport: String,
        launcherActivityFound: Boolean,
        inputIpcGranted: Boolean,
        launchError: String?,
        launchResult: String
    ): String = buildString {
        appendLine("NextVRera / EndXR — Quest APK launch diagnostic")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Device: ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("[APK]")
        appendLine("Name: ${info.displayName}")
        appendLine("Package: ${info.packageName}")
        appendLine("Version: ${info.versionName} (${info.versionCode})")
        appendLine("Route: ${assessment.route.label}")
        appendLine("Engine: ${assessment.engine}")
        appendLine("XR markers: ${assessment.xrTechnologies.joinToString().ifBlank { "none detected" }}")
        appendLine("ABI: ${assessment.audit.abiSummary}")
        appendLine()
        appendLine("[ANDROID LAUNCH]")
        appendLine("Launcher Activity: ${if (launcherActivityFound) "FOUND" else "NOT FOUND"}")
        appendLine("Result: $launchResult")
        appendLine("startActivity error: ${launchError ?: "none"}")
        appendLine()
        appendLine("[ENDXR INPUT / TELEMETRY IPC]")
        appendLine("Read-only controller_state URI grant: ${if (inputIpcGranted) "GRANTED" else "NOT GRANTED"}")
        appendLine("Write-only runtime_event URI grant: ${if (inputIpcGranted) "GRANTED" else "NOT GRANTED"}")
        appendLine("These scoped grants do not select the OpenXR runtime and do not prove the game consumes controller data.")
        appendLine()
        appendLine("[OPENXR CAPTURE]")
        appendLine("Status: awaiting package-scoped EndXR runtime telemetry after the game returns.")
        appendLine("Runtime selection, xrCreateInstance, xrCreateSession and successfully composited projection images are tracked as separate evidence levels.")
        appendLine()
        appendLine("[RUNTIMEBROKER / RUNTIME SERVICE]")
        appendLine(brokerReport)
        appendLine()
        appendLine("[COMPATIBILITY DETAILS]")
        assessment.details.forEach { appendLine("- $it") }
        appendLine()
        appendLine("[NEXT STEP]")
        appendLine("Close or return from the game to let NextVRera collect the latest runtime telemetry. A separate external OpenXR probe remains useful for independent loader/runtime validation.")
    }

    private fun pollRuntimeTelemetryAfterReturn(
        title: String,
        report: String,
        expectedPackage: String?,
        baselineSequence: Long,
        generation: Long,
        attempt: Int
    ) {
        if (isFinishing || isDestroyed || generation != runtimeTelemetryCheckGeneration) return

        val telemetry = readLatestRuntimeTelemetry()
        val telemetryMatchesLaunch = telemetry != null &&
            telemetry.sequence > baselineSequence &&
            telemetry.callerPackage == expectedPackage &&
            telemetry.instanceSeen
        if (!telemetryMatchesLaunch && attempt < 8) {
            statusView.text =
                "Игра закрылась. Ожидаем финальные события EndXR… ${(attempt + 1) * 250} мс"
            mainHandler.postDelayed({
                pollRuntimeTelemetryAfterReturn(
                    title,
                    report,
                    expectedPackage,
                    baselineSequence,
                    generation,
                    attempt + 1
                )
            }, 250L)
            return
        }

        val telemetryReport = buildRuntimeTelemetryReturnReport(
            expectedPackage,
            baselineSequence,
            telemetry,
            telemetryMatchesLaunch
        )
        val captureLevel = when {
            !telemetryMatchesLaunch || telemetry == null -> 0
            telemetry.sessionSeen && telemetry.frameCount > 0L -> 3
            telemetry.sessionSeen -> 2
            else -> 1
        }
        showLaunchDiagnostic(
            title,
            report + telemetryReport,
            runtimeCaptureLevel = captureLevel
        )
        pendingRuntimeTelemetryPackage = null
        pendingRuntimeTelemetryBaselineSequence = 0L
    }

    private fun readLatestRuntimeTelemetry(): RuntimeTelemetrySnapshot? {
        val uri = Uri.parse(EndXrRuntimeEventContentProvider.CONTENT_URI)
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    "sequence",
                    "event_name",
                    "caller_package",
                    "runtime_pid",
                    "instance_id",
                    "session_id",
                    "instance_created",
                    "session_created",
                    "session_running",
                    "instance_seen",
                    "session_seen",
                    "session_ever_running",
                    "frame_count"
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                RuntimeTelemetrySnapshot(
                    sequence = cursor.getLong(0),
                    eventName = cursor.getString(1).orEmpty(),
                    callerPackage = cursor.getString(2).orEmpty(),
                    runtimePid = cursor.getInt(3),
                    instanceId = cursor.getString(4).orEmpty(),
                    sessionId = cursor.getString(5).orEmpty(),
                    instanceCreated = cursor.getInt(6) != 0,
                    sessionCreated = cursor.getInt(7) != 0,
                    sessionRunning = cursor.getInt(8) != 0,
                    instanceSeen = cursor.getInt(9) != 0,
                    sessionSeen = cursor.getInt(10) != 0,
                    sessionEverRunning = cursor.getInt(11) != 0,
                    frameCount = cursor.getLong(12)
                )
            }
        }.getOrNull()
    }

    private fun buildRuntimeTelemetryReturnReport(
        expectedPackage: String?,
        baselineSequence: Long,
        telemetry: RuntimeTelemetrySnapshot?,
        matchesLaunch: Boolean
    ): String = buildString {
        appendLine()
        appendLine("[ENDXR RUNTIME TELEMETRY]")
        appendLine("Expected game package: ${expectedPackage ?: "unknown"}")
        appendLine("Baseline sequence before launch: $baselineSequence")
        if (!matchesLaunch || telemetry == null) {
            appendLine("Status: NO NEW AUTHORIZED RUNTIME EVENT RECEIVED")
            appendLine("The game may not have loaded EndXR, may not have reached xrCreateInstance, or may not have runtime-event permission.")
            return@buildString
        }
        appendLine("Caller package: ${telemetry.callerPackage}")
        appendLine("Runtime process PID: ${telemetry.runtimePid}")
        appendLine("Last event: ${telemetry.eventName}")
        appendLine("Instance created during this launch: ${telemetry.instanceSeen}")
        appendLine("Session created during this launch: ${telemetry.sessionSeen}")
        appendLine("Session began during this launch: ${telemetry.sessionEverRunning}")
        appendLine("Session currently active: ${telemetry.sessionRunning}")
        appendLine("Successfully composited game projection frames: ${telemetry.frameCount}")
        appendLine("Runtime instance id: ${telemetry.instanceId}")
        appendLine("Runtime session id: ${telemetry.sessionId.ifBlank { "none" }}")
        appendLine(
            "Capture level: " + when {
                telemetry.instanceSeen && telemetry.sessionSeen && telemetry.frameCount > 0L ->
                    "XR INSTANCE + SESSION + FRAME SUBMISSION CONFIRMED"
                telemetry.instanceSeen && telemetry.sessionSeen ->
                    "XR INSTANCE + SESSION CONFIRMED; NO SUCCESSFUL FRAME OBSERVED"
                telemetry.instanceSeen ->
                    "XR INSTANCE CONFIRMED; SESSION NOT CREATED"
                else -> "PARTIAL EVENT ONLY"
            }
        )
    }

    private fun showLaunchDiagnostic(
        title: String,
        report: String,
        runtimeCaptureLevel: Int = 0
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val captureHeader = when (runtimeCaptureLevel) {
            3 -> "ENDXR RUNTIME: ИЗОБРАЖЕНИЕ ИГРЫ СКОМПОЗИРОВАНО"
            2 -> "ENDXR RUNTIME: XR-СЕССИЯ СОЗДАНА"
            1 -> "ENDXR RUNTIME: xrCreateInstance ПОДТВЕРЖДЁН"
            else -> "OPENXR-ПЕРЕХВАТ: НЕ ПОДТВЕРЖДЁН"
        }
        val captureColor = when (runtimeCaptureLevel) {
            0 -> 0xFFFFD28A.toInt()
            1 -> 0xFFFFD28A.toInt()
            else -> 0xFF8FE0B5.toInt()
        }
        val captureExplanation = when (runtimeCaptureLevel) {
            3 -> "EndXR получил событие из процесса игры, увидел создание XR-сессии и успешно скомпозировал projection layer игры. Число подтверждённых изображений указано ниже."
            2 -> "EndXR получил xrCreateInstance и xrCreateSession из процесса игры, но ещё не подтвердил композицию изображения игры."
            1 -> "Процесс игры действительно вызвал xrCreateInstance в EndXR, однако создание XR-сессии пока не подтверждено."
            else -> "NextVRera не получила новое событие xrCreateInstance от процесса выбранной игры. Это не доказывает несовместимость: игра могла не дойти до OpenXR или не иметь разрешения на telemetry URI."
        }
        content.addView(text(
            captureHeader,
            13f,
            captureColor,
            Typeface.BOLD
        ).apply { setPadding(0, 0, 0, dp(8)) })
        content.addView(text(
            captureExplanation,
            12f, 0xFFD0D7E2.toInt()
        ).apply { setPadding(0, 0, 0, dp(10)) })
        content.addView(text(report, 10f, 0xFFB8C2D0.toInt()))
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Копировать лог", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NextVRera OpenXR launch log", report))
                Toast.makeText(this, "Лог скопирован. Вставь его сюда.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun installOnAndroid(info: ImportedApkInfo) {
        val archive = File(filesDir, "imported-apks/${info.fileName}")
        if (!archive.isFile || archive.length() == 0L) {
            statusView.text = "Файл APK не найден. Импортируй его заново."
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                statusView.text = "Открой настройки Android и разреши установку приложений для QuestPhoneXR."
            }
            Toast.makeText(
                this,
                "Разреши установку из QuestPhoneXR, затем снова нажми «Установить на Android».",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        runCatching {
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                archive
            )
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(installIntent)
            statusView.text = "Передано системному установщику Android: ${info.displayName}"
        }.onFailure { error ->
            statusView.text = "Не удалось открыть системный установщик: ${error.message ?: "ошибка"}"
        }
    }

    private fun copyAuditReport(info: ImportedApkInfo, assessment: ApkRuntimeAssessment) {
        val audit = assessment.audit
        val report = buildString {
            appendLine("QuestPhoneXR — статический APK-аудит")
            appendLine("Название: ${info.displayName}")
            appendLine("Пакет: ${info.packageName}")
            appendLine("Файл: ${info.originalName}")
            appendLine("Версия: ${info.versionName} (${info.versionCode})")
            appendLine("Размер: ${formatSize(info.sizeBytes)}")
            appendLine("SHA-256: ${info.sha256.ifBlank { "не вычислен" }}")
            appendLine("Android: min API ${info.minSdk}, target API ${info.targetSdk}")
            appendLine("Устройство: ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Маршрут: ${assessment.route.label}")
            appendLine("Движок: ${assessment.engine}")
            appendLine("XR: ${assessment.xrTechnologies.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("ABI: ${audit.abiSummary}")
            appendLine("Нативные библиотеки: ${audit.nativeLibraries.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("SDK/Runtime-маркеры: ${audit.sdkRuntimeMarkers.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("VR-маркеры манифеста: ${audit.manifestReferences.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("VR-значения метаданных: ${audit.metadataValues.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("VR-компоненты: ${audit.componentReferences.joinToString().ifBlank { "не обнаружены" }}")
            appendLine("Разрешения: ${info.requestedPermissions.joinToString().ifBlank { "не перечислены" }}")
            appendLine("Activities: ${info.activityNames.joinToString().ifBlank { "не перечислены" }}")
            appendLine("Services: ${info.serviceNames.joinToString().ifBlank { "не перечислены" }}")
            appendLine("Providers: ${info.providerNames.joinToString().ifBlank { "не перечислены" }}")
            appendLine()
            appendLine("Детали и ограничения:")
            assessment.details.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Отчёт статический: он не доказывает успешную установку, создание VR-сессии или игровую совместимость.")
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QuestPhoneXR APK audit", report))
        Toast.makeText(this, "Полный APK-отчёт скопирован.", Toast.LENGTH_SHORT).show()
    }

    private fun addAuditLine(container: LinearLayout, label: String, value: String) {
        container.addView(text(
            "$label: $value",
            11f,
            0xFF9FB7D1.toInt()
        ).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun addAuditLine(container: LinearLayout, label: String, values: List<String>) {
        val visible = values.take(6)
        val suffix = if (values.size > visible.size) " и ещё ${values.size - visible.size}" else ""
        addAuditLine(container, label, visible.joinToString() + suffix)
    }

    private fun formatSize(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024f * 1024f))

    private fun button(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        applyNextVreraStyle(nextVreraToneFor(label))
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
