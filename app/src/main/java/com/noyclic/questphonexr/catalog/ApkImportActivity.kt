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
            showLaunchDiagnostic(pendingLaunchTitle, report)
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
                    continueEndXrManagedLaunch(info, broker.toDisplayText())
                } else {
                    val status = broker?.toDisplayText()
                        ?: "Не удалось повторно прочитать RuntimeBroker."
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
        if (client == null) {
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
            val grantRestored = activeGrants.any { it.packageName == client.packageName }
            if (grantRestored) {
                requestCameraThenLaunch(
                    PendingEndXrLaunch(info.id, brokerReport, inputIpcGranted = true),
                    launchIntent,
                    assessment
                )
                return
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Разрешить ввод EndXR для игры?")
            .setMessage(
                "Приложение: ${client.label}\nПакет: ${client.packageName}\n\n" +
                    "NextVRera выдаст read-only доступ только к URI controller_state. " +
                    "Разрешение связано с подписью этого APK и может быть отозвано. " +
                    "Это не внедряет Android-кнопки в игру: игре нужно поддерживать OpenXR input или контракт EndXR."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Разрешить и продолжить") { _, _ ->
                runCatching { manager.authorize(client.packageName) }
                    .onSuccess {
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
            waitForEndXrCameraReady(pending, launchIntent, assessment, attempt = 0)
            return
        }

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
            launchEndXrManagedPackage(
                pending,
                launchIntent,
                assessment,
                trackingStarted = false,
                trackingStatus = "not ready (state=${state}; frame_age_ms=${frameAgeMs}; error=${reason})"
            )
            return
        }
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
            pendingLaunchReport = buildLaunchReport(
                info,
                assessment,
                pending.brokerReport,
                launcherActivityFound = true,
                inputIpcGranted = pending.inputIpcGranted,
                launchError = null,
                launchResult = "NextVRera preflight passed; Android accepted startActivity."
            ) + launchDetails
            startActivity(launchIntent)
            val message = if (trackingStarted) {
                "NextVRera подготовила EndXR: runtime-кандидат подтверждён, IPC разрешён, MediaPipe передал свежий кадр. Запрос запуска отправлен; подключение самой игры ещё нужно подтвердить."
            } else {
                "EndXR выбран как кандидат и запуск отправлен, но камера не готова: $trackingStatus"
            }
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            pendingLaunchReport = null
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
        appendLine("[ENDXR INPUT IPC]")
        appendLine("Read-only input URI grant: ${if (inputIpcGranted) "GRANTED" else "NOT GRANTED / NOT REQUIRED"}")
        appendLine("Input IPC permission does not select the OpenXR runtime and does not prove the game reads controller data.")
        appendLine()
        appendLine("[OPENXR CAPTURE]")
        appendLine("Status: NOT CONFIRMED")
        appendLine("The current host app cannot observe another process calling xrCreateInstance/xrCreateSession. Android Activity launch success is not proof of an OpenXR connection.")
        appendLine()
        appendLine("[RUNTIMEBROKER / RUNTIME SERVICE]")
        appendLine(brokerReport)
        appendLine()
        appendLine("[COMPATIBILITY DETAILS]")
        assessment.details.forEach { appendLine("- $it") }
        appendLine()
        appendLine("[NEXT STEP]")
        appendLine("Run the separate external OpenXR client probe. Definitive game capture needs a runtime-to-host event channel for xrCreateInstance, xrCreateSession and xrEndFrame.")
    }

    private fun showLaunchDiagnostic(title: String, report: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(text("OPENXR-ПЕРЕХВАТ: НЕ ПОДТВЕРЖДЁН", 13f, 0xFFFFD28A.toInt(), Typeface.BOLD)
            .apply { setPadding(0, 0, 0, dp(8)) })
        content.addView(text(
            "Это не означает, что игра точно не подключилась. Сейчас NextVRera подтверждает запуск Android и проверяет RuntimeBroker, но ещё не получает обратное событие из процесса игры.",
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
