package com.noyclic.questphonexr.catalog

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import androidx.core.content.FileProvider
import com.noyclic.questphonexr.runtime.EndXrInputContentProvider
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
        if (launchIntent == null) {
            val message = "Не найден экран запуска для пакета ${info.packageName}. Проверь, что APK действительно установлен целиком (включая split APK), а не только импортирован."
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        val archive = File(filesDir, "imported-apks/${info.fileName}")
        val missing = info.requiredFeatures.filterNot { packageManager.hasSystemFeature(it) }.toSet()
        val assessment = ApkRuntimeCompatibility.inspect(
            info,
            archive,
            android.os.Build.VERSION.SDK_INT,
            android.os.Build.SUPPORTED_ABIS.toList(),
            missing
        )

        // Static APK heuristics must warn, not silently prevent the user from
        // testing an installed app. In particular, Quest/Meta markers do not
        // prove that Android cannot start its launcher Activity.
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
                    startInstalledPackage(info, launchIntent, assessment)
                }
                .show()
            return
        }

        startInstalledPackage(info, launchIntent, assessment)
    }

    private fun startInstalledPackage(
        info: ImportedApkInfo,
        launchIntent: Intent,
        assessment: ApkRuntimeAssessment
    ) {
        val ipcGranted = if (assessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE) {
            runCatching {
                grantUriPermission(
                    info.packageName,
                    Uri.parse(EndXrInputContentProvider.CONTENT_URI),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
        } else {
            false
        }

        runCatching {
            startActivity(launchIntent)
            val message = when {
                assessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE && ipcGranted ->
                    "Команда запуска отправлена Android. IPC-доступ EndXR выдан; загрузка OpenXR Runtime самой игрой ещё должна быть подтверждена."
                assessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE ->
                    "Команда запуска отправлена Android, но IPC-доступ EndXR не удалось выдать."
                else ->
                    "Команда запуска отправлена Android. Это пока не подтверждает работу XR/Quest Runtime."
            }
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            val message = "Android не смог открыть ${info.displayName}: ${error.message ?: error.javaClass.simpleName}"
            statusView.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
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
