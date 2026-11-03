package com.noyclic.questphonexr

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.catalog.GameLibraryActivity
import com.noyclic.questphonexr.diagnostics.CrashReportStore
import com.noyclic.questphonexr.sandbox.SandboxActivity
import com.noyclic.questphonexr.vr.PhoneVrPreviewActivity

/** The phone-first entry point: choose which QuestPhoneXR mode to open. */
class ModeSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        buildScreen()
        showPendingCrashReportIfNeeded()
    }


    private fun showPendingCrashReportIfNeeded() {
        val store = CrashReportStore(this)
        if (!store.shouldPromptForPendingReport()) return

        val report = store.readPendingReport().orEmpty()
        store.markPrompted()
        AlertDialog.Builder(this)
            .setTitle("Предыдущий запуск завершился с ошибкой")
            .setMessage(report.ifBlank { "Android сохранил сведения о предыдущем завершении приложения." })
            .setPositiveButton("Открыть GitHub") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, store.githubIssueUri()))
                }.onFailure {
                    Toast.makeText(this, "Не удалось открыть GitHub.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Поделиться отчётом") { _, _ ->
                if (!store.sharePendingReport(this)) {
                    Toast.makeText(this, "Не удалось подготовить файл отчёта.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(Color.rgb(14, 17, 23))
        }

        content.addView(text(
            "QUESTPHONE XR",
            12f,
            0xFF8FD3B5.toInt(),
            Typeface.BOLD
        ))
        content.addView(text(
            "Выбери режим",
            30f,
            Color.WHITE,
            Typeface.BOLD
        ).apply { setPadding(0, dp(3), 0, dp(4)) })
        content.addView(text(
            "Куда перейдём?",
            14f,
            0xFFD0D7E2.toInt()
        ).apply { setPadding(0, 0, 0, dp(14)) })

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modes.addView(
            modeCard(
                title = "Sandbox",
                description = "3D-сцена, захват предметов руками, MediaPipe, Joy-Con и калибровка.",
                accent = 0xFF8FD3B5.toInt(),
                buttonLabel = "Открыть песочницу"
            ) {
                startActivity(Intent(this@ModeSelectionActivity, SandboxActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(7)
            }
        )
        modes.addView(
            modeCard(
                title = "APK / Quest",
                description = "Каталог VR-игр, импорт APK, анализ Android API и локальные результаты тестов.",
                accent = 0xFF9BC7F5.toInt(),
                buttonLabel = "Открыть библиотеку"
            ) {
                startActivity(Intent(this@ModeSelectionActivity, GameLibraryActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            }
        )
        modes.addView(
            modeCard(
                title = "SteamVR · ПК",
                description = "Подключение к ПК и передача телеметрии головы и рук. Полный видеостриминг и драйвер SteamVR ещё разрабатываются.",
                accent = 0xFFFFD28A.toInt(),
                buttonLabel = "Настроить связь с ПК"
            ) {
                startActivity(
                    Intent(this@ModeSelectionActivity, SandboxActivity::class.java)
                        .putExtra(SandboxActivity.EXTRA_OPEN_PC_BRIDGE, true)
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(7)
            }
        )
        content.addView(modes)

        content.addView(text(
            "Дополнительные инструменты",
            13f,
            0xFF9CA9BC.toInt(),
            Typeface.BOLD
        ).apply { setPadding(0, dp(16), 0, dp(6)) })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tools.addView(
            actionButton("VR Shinecon · стерео-тест") {
                startActivity(Intent(this@ModeSelectionActivity, PhoneVrPreviewActivity::class.java))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            }
        )
        tools.addView(
            actionButton("Диагностика рук и контроллеров") {
                startActivity(Intent(this@ModeSelectionActivity, MainActivity::class.java))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        content.addView(tools)

        content.addView(text(
            "Выбор режима открывается при каждом обычном запуске приложения.",
            11f,
            0xFF8994A5.toInt()
        ).apply { setPadding(0, dp(10), 0, 0) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        setContentView(scroll)
    }

    private fun modeCard(
        title: String,
        description: String,
        accent: Int,
        buttonLabel: String,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(0xFF1B222D.toInt(), dp(14).toFloat())
            minimumHeight = dp(190)
        }
        card.addView(text(title, 19f, accent, Typeface.BOLD))
        card.addView(text(
            description,
            12f,
            0xFFD0D7E2.toInt()
        ).apply {
            setPadding(0, dp(8), 0, dp(14))
            minHeight = dp(84)
        })
        card.addView(actionButton(buttonLabel, onClick))
        return card
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            background = rounded(0xFF303B4B.toInt(), dp(9).toFloat())
            setOnClickListener { onClick() }
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
