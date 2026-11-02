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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameLibraryActivity : ComponentActivity() {
    private lateinit var cardsContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var reportStore: GameTestReportStore
    private val currentDevice by lazy { DeviceFingerprint.current() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportStore = SharedPreferencesGameTestReportStore(this)
        buildScreen()
        renderGames()
        window.decorView.post {
            val crashReports = CrashReportStore(this)
            if (crashReports.shouldPromptForPendingReport()) {
                crashReports.markPrompted()
                showCrashReportDialog()
            }
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(Color.rgb(14, 17, 23))
        }

        root.addView(text(
            "QuestPhoneXR  /  БИБЛИОТЕКА",
            12f,
            0xFF9CA9BC.toInt(),
            Typeface.BOLD
        ))
        root.addView(text(
            "VR-игры",
            30f,
            Color.WHITE,
            Typeface.BOLD
        ).apply { setPadding(0, dp(3), 0, dp(4)) })
        root.addView(text(
            "Сборки, способы адаптации и реальные результаты тестов.",
            14f,
            0xFFD0D7E2.toInt()
        ))
        root.addView(text(
            "Устройство: " + currentDevice.displayName + " · Android " + currentDevice.androidVersion,
            13f,
            0xFF8FD3B5.toInt()
        ).apply { setPadding(0, dp(10), 0, dp(10)) })

        val disclaimer = text(
            "Важно: наличие Quest APK не означает совместимость с телефоном. " +
                "Статус «проверено» появляется только после сохранения реального теста.",
            12f,
            0xFFFFD28A.toInt()
        )
        disclaimer.background = rounded(0xFF29251D.toInt(), dp(12).toFloat())
        disclaimer.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.addView(disclaimer)

        val importApkButton = button("Мои APK · импорт и анализ").apply {
            setOnClickListener {
                startActivity(Intent(this@GameLibraryActivity, ApkImportActivity::class.java))
            }
        }
        root.addView(importApkButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(button("Сообщить об ошибке · Crash-лог").apply {
            setOnClickListener { showCrashReportDialog() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        searchInput = EditText(this).apply {
            hint = "Поиск игры…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8A95A5.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFF202630.toInt(), dp(10).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderGames()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val searchParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(10) }
        root.addView(searchInput, searchParams)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
        }
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(cardsContainer)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val vrPreviewButton = button("VR Shinecon · запустить стерео-тест")
        vrPreviewButton.setOnClickListener {
            startActivity(Intent(this, PhoneVrPreviewActivity::class.java))
        }
        val vrParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        root.addView(vrPreviewButton, vrParams)

        val sandboxButton = button("Запустить VR-песочницу")
        sandboxButton.setOnClickListener {
            startActivity(Intent(this, SandboxActivity::class.java))
        }
        val sandboxParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        root.addView(sandboxButton, sandboxParams)

        val trackingButton = button("Открыть тест отслеживания рук")
        trackingButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        val trackingParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        root.addView(trackingButton, trackingParams)

        root.addView(text(
            "Ручные отчёты хранятся только на этом телефоне. Публикация и общая база появятся отдельно.",
            11f,
            0xFF8994A5.toInt()
        ).apply { setPadding(0, dp(8), 0, 0) })

        setContentView(root)
    }


    private fun showCrashReportDialog() {
        val store = CrashReportStore(this)
        val report = store.readPendingReport()
        val hasCrash = report != null
        val preview = TextView(this).apply {
            text = report ?: CrashReportFormatter.issueBody(null)
            textSize = 11f
            setTextColor(0xFFE1E7EF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            addView(preview)
            setBackgroundColor(0xFF111720.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(text(
                if (hasCrash) {
                    "После сбоя QuestPhoneXR сохранил отчёт только на этом телефоне. Проверь его содержимое. Ничего не отправится без твоего нажатия."
                } else {
                    "Автоматического crash-лога пока нет. Можно открыть GitHub и описать проблему вручную."
                },
                13f,
                0xFF424A56.toInt()
            ))
            if (hasCrash) {
                addView(text(
                    "В отчёт входят версия приложения, модель устройства, версия Android, имя потока и stack trace. IMEI, Android ID и местоположение не собираются.",
                    12f,
                    0xFF596474.toInt()
                ).apply { setPadding(0, dp(7), 0, dp(7)) })
            }
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (hasCrash) "Отчёт о сбое" else "Сообщить об ошибке")
            .setView(content)
            .setPositiveButton("Открыть GitHub") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, store.githubIssueUri()))
                }.onFailure {
                    Toast.makeText(this, "Не удалось открыть GitHub.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(if (hasCrash) "Поделиться файлом" else "Копировать шаблон", null)
            .setNegativeButton(if (hasCrash) "Удалить отчёт" else "Закрыть", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (hasCrash) {
                    if (!store.sharePendingReport(this)) {
                        Toast.makeText(this, "Не удалось подготовить файл отчёта.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "QuestPhoneXR issue template",
                        CrashReportFormatter.issueBody(null)
                    ))
                    Toast.makeText(this, "Шаблон скопирован.", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (hasCrash) {
                    store.deletePendingReport()
                    Toast.makeText(this, "Локальный crash-отчёт удалён.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun renderGames() {
        if (!::cardsContainer.isInitialized || !::searchInput.isInitialized) return
        cardsContainer.removeAllViews()
        val query = searchInput.text.toString().trim().lowercase(Locale.ROOT)
        val visibleGames = GameCatalog.entries.filter {
            it.title.lowercase(Locale.ROOT).contains(query) ||
                it.summary.lowercase(Locale.ROOT).contains(query) ||
                it.compatibilityNotes.lowercase(Locale.ROOT).contains(query)
        }

        if (visibleGames.isEmpty()) {
            cardsContainer.addView(text("Ничего не найдено.", 15f, 0xFFD0D7E2.toInt()))
            return
        }

        visibleGames.forEach { game ->
            cardsContainer.addView(createGameCard(game))
        }
    }

    private fun createGameCard(game: GameCatalogEntry): View {
        val reports = reportStore.getReports(game.id)
        val assessment = GameCompatibilityEvaluator.evaluate(currentDevice, reports)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(0xFF1B222D.toInt(), dp(14).toFloat())
        }

        card.addView(text(game.title, 19f, Color.WHITE, Typeface.BOLD))
        val stateColor = when (assessment.state) {
            CompatibilityState.NOT_TESTED -> 0xFFB8C2D0.toInt()
            CompatibilityState.LAUNCHES -> 0xFF8FE0B5.toInt()
            CompatibilityState.LIMITED -> 0xFFFFD28A.toInt()
            CompatibilityState.FAILED -> 0xFFFF9696.toInt()
        }
        val stateLabel = if (assessment.state == CompatibilityState.NOT_TESTED) {
            "НЕ ПРОВЕРЕНО НА ЭТОМ ТЕЛЕФОНЕ"
        } else {
            assessment.state.label.uppercase(Locale.ROOT)
        }
        val badge = text(stateLabel, 11f, stateColor, Typeface.BOLD)
        badge.setPadding(0, dp(5), 0, dp(7))
        card.addView(badge)

        card.addView(text(game.summary, 13f, 0xFFD0D7E2.toInt()))
        card.addView(text("Путь: " + game.portingPath.label, 12f, 0xFFB8C7DB.toInt())
            .apply { setPadding(0, dp(7), 0, dp(2)) })
        card.addView(text("Нагрузка: " + game.expectedLoad.label, 12f, 0xFFB8C7DB.toInt()))
        BuiltInGameInputProfiles.forGame(game.id)?.let { profile ->
            card.addView(text("Профиль управления для порта: " + profile.displayName, 12f, 0xFF9BC7F5.toInt())
                .apply { setPadding(0, dp(7), 0, dp(2)) })
            card.addView(text(profile.note, 11f, 0xFFAAB4C2.toInt()))
        }
        card.addView(text(game.compatibilityNotes, 12f, 0xFFAAB4C2.toInt())
            .apply { setPadding(0, dp(7), 0, dp(6)) })

        val latest = assessment.latestReport
        if (latest == null) {
            val reportText = if (reports.isEmpty()) {
                "Отчётов пока нет. Совместимость не подтверждена."
            } else {
                "Есть отчёты с других устройств: " + assessment.reportsForOtherDevices +
                    ". На этой модели теста пока нет."
            }
            card.addView(text(reportText, 12f, 0xFF9CA9BC.toInt()))
        } else {
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                .format(Date(latest.testedAtEpochMs))
            var reportLine = "Тест: " + currentDevice.displayName + " · Android " +
                latest.device.androidVersion + " · " + date
            if (assessment.androidVersionMatches == false) {
                reportLine += " (версия Android отличается)"
            }
            card.addView(text(reportLine, 12f, 0xFF8FD3B5.toInt()))
            val fpsLine = if (latest.averageFps == null) {
                "FPS: не измерялся"
            } else {
                "FPS: средний " + latest.averageFps +
                    (latest.minimumFps?.let { ", минимум " + it } ?: "")
            }
            card.addView(text(fpsLine, 12f, 0xFFD0D7E2.toInt()))
            if (latest.notes.isNotBlank()) {
                card.addView(text("Заметки: " + latest.notes, 12f, 0xFFAAB4C2.toInt())
                    .apply { setPadding(0, dp(4), 0, dp(4)) })
            }
            if (latest.isManualEntry) {
                card.addView(text("Локальный ручной отчёт — не опубликован", 11f, 0xFF8994A5.toInt()))
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val testButton = button("Записать тест").apply {
            setOnClickListener { showAddReportDialog(game) }
        }
        actions.addView(testButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sourceButton = button("Проект").apply {
            setOnClickListener { openProject(game.projectUrl) }
        }
        val sourceParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(8) }
        actions.addView(sourceButton, sourceParams)
        card.addView(actions)

        if (game.secondaryUrl != null) {
            val secondary = button(game.secondaryUrlLabel ?: "Дополнительная страница").apply {
                setOnClickListener { openProject(game.secondaryUrl) }
            }
            card.addView(secondary)
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        card.layoutParams = params
        return card
    }

    private fun showAddReportDialog(game: GameCatalogEntry) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        form.addView(text(
            "Записывай результат только после реального запуска игры. Отчёт будет сохранён локально.",
            13f,
            0xFF424A56.toInt()
        ))

        val outcomeSpinner = Spinner(this)
        val outcomes = TestOutcome.values().toList()
        outcomeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            outcomes.map { it.label }
        )
        form.addView(outcomeSpinner)

        val averageFpsInput = numericInput("Средний FPS (необязательно)")
        val minimumFpsInput = numericInput("Минимальный FPS (необязательно)")
        val notesInput = EditText(this).apply {
            hint = "Версия игры, управление, вылеты, нагрев и другие заметки"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setTextColor(Color.BLACK)
        }
        form.addView(averageFpsInput)
        form.addView(minimumFpsInput)
        form.addView(notesInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Тест: " + game.title)
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val averageFps = parseFps(averageFpsInput.text.toString())
                val minimumFps = parseFps(minimumFpsInput.text.toString())
                if (averageFpsInput.text.isNotBlank() && averageFps == null) {
                    averageFpsInput.error = "Укажи число от 1 до 240"
                    return@setOnClickListener
                }
                if (minimumFpsInput.text.isNotBlank() && minimumFps == null) {
                    minimumFpsInput.error = "Укажи число от 1 до 240"
                    return@setOnClickListener
                }
                if (averageFps != null && minimumFps != null && minimumFps > averageFps) {
                    minimumFpsInput.error = "Минимальный FPS не может быть выше среднего"
                    return@setOnClickListener
                }

                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                }.getOrDefault("unknown")
                reportStore.addReport(
                    game.id,
                    GameTestReport(
                        device = currentDevice,
                        outcome = outcomes[outcomeSpinner.selectedItemPosition],
                        testedAtEpochMs = System.currentTimeMillis(),
                        averageFps = averageFps,
                        minimumFps = minimumFps,
                        appVersion = version,
                        notes = notesInput.text.toString().trim()
                    )
                )
                dialog.dismiss()
                renderGames()
                Toast.makeText(this, "Локальный отчёт сохранён.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun numericInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(Color.BLACK)
    }

    private fun parseFps(value: String): Int? {
        if (value.isBlank()) return null
        return value.trim().toIntOrNull()?.takeIf { it in 1..240 }
    }

    private fun openProject(url: String?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Ссылка пока не добавлена.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Не удалось открыть страницу проекта.", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
