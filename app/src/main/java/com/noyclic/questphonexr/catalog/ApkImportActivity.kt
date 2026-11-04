package com.noyclic.questphonexr.catalog

import android.app.AlertDialog
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
            setBackgroundColor(Color.rgb(14, 17, 23))
        }
        root.addView(button("← Библиотека").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(text("QuestPhoneXR  /  ФАЙЛЫ ИГР", 12f, 0xFF9CA9BC.toInt(), Typeface.BOLD))
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
        runtimeAssessment.details.forEach { detail ->
            card.addView(text("• $detail", 11f, 0xFFAAB4C2.toInt())
                .apply { setPadding(0, dp(4), 0, 0) })
        }
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
            statusView.text = "Для ${info.displayName} не найдено установленное Android-приложение с экраном запуска. Сначала установи APK."
            return
        }
        val archive = File(filesDir, "imported-apks/${info.fileName}")
        val missing = info.requiredFeatures.filterNot { packageManager.hasSystemFeature(it) }.toSet()
        val assessment = ApkRuntimeCompatibility.inspect(info, archive, android.os.Build.VERSION.SDK_INT,
            android.os.Build.SUPPORTED_ABIS.toList(), missing)
        if (
            assessment.route == ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED ||
            assessment.route == ApkLaunchRoute.QUEST_RUNTIME_REQUIRED ||
            assessment.route == ApkLaunchRoute.GEAR_VR_LEGACY_CANDIDATE ||
            assessment.route == ApkLaunchRoute.LEGACY_OCULUS_MOBILE_CANDIDATE
        ) {
            statusView.text = assessment.details.joinToString("\n").ifBlank {
                "Для этого APK пока нет совместимого маршрута запуска."
            }
            return
        }
        if (assessment.route == ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE) {
            // Grant the exact read-only URI to the imported game package. This
            // lets its process read EndXR snapshots even when it did not declare
            // package-visibility queries for our provider authority.
            val inputIpcGranted = runCatching {
                grantUriPermission(
                    info.packageName,
                    Uri.parse(EndXrInputContentProvider.CONTENT_URI),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            runCatching {
                startActivity(launchIntent)
                statusView.text = if (inputIpcGranted) {
                    "Передано Android. Доступ к IPC-снимку EndXR выдан. Важно: источник ввода пока очищается при паузе Sandbox; непрерывное управление во внешней игре ещё не готово."
                } else {
                    "Передано Android, но не удалось выдать IPC-доступ EndXR. Управление и полноценная игра не гарантированы."
                }
            }.onFailure { error ->
                statusView.text = "Не удалось открыть приложение: ${error.message ?: "ошибка запуска"}"
            }
            return
        }
        runCatching {
            startActivity(launchIntent)
            statusView.text = "Открываем ${info.displayName} как обычное Android-приложение, вне XR-сессии."
        }.onFailure { error ->
            statusView.text = "Android не смог открыть приложение: ${error.message ?: "ошибка запуска"}"
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

    private fun formatSize(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024f * 1024f))

    private fun button(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(0xFF303B4B.toInt(), dp(9).toFloat())
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
