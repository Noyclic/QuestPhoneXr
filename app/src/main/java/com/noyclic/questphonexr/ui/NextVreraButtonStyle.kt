package com.noyclic.questphonexr.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/**
 * Shared, high-contrast button language for the NextVRera / GVXRS Android UI.
 * Keep the color meaning consistent: mint = main action, blue = utility,
 * violet = navigation/configuration, amber = caution, red = destructive.
 */
enum class NextVreraButtonTone {
    PRIMARY,
    BLUE,
    VIOLET,
    WARNING,
    DANGER,
    SECONDARY
}

fun nextVreraToneFor(label: CharSequence?): NextVreraButtonTone {
    val value = label?.toString()?.lowercase().orEmpty()
    return when {
        listOf("сброс", "выйти", "отозвать", "удалить", "остановить", "отключить", "reset", "delete", "stop").any(value::contains) ->
            NextVreraButtonTone.DANGER

        listOf("подключ", "запустить", "добавить", "применить", "сохранить", "разрешить", "включить", "назначить", "импорт", "калибров", "open", "launch", "connect", "apply", "save").any(value::contains) ->
            NextVreraButtonTone.PRIMARY

        listOf("диагност", "проверить", "копировать", "показать", "выбор", "профиль", "разрешение", "контроллер", "broker", "report", "copy", "check").any(value::contains) ->
            NextVreraButtonTone.BLUE

        else -> NextVreraButtonTone.SECONDARY
    }
}

fun Button.applyNextVreraStyle(
    tone: NextVreraButtonTone = nextVreraToneFor(text)
): Button {
    val palette = when (tone) {
        NextVreraButtonTone.PRIMARY ->
            Palette(0xFF63E4C8.toInt(), 0xFF39BDAF.toInt(), 0xFF082B27.toInt(), 0xFFB0FFF0.toInt())
        NextVreraButtonTone.BLUE ->
            Palette(0xFF79C4FF.toInt(), 0xFF5596E8.toInt(), 0xFF08233B.toInt(), 0xFFC5E5FF.toInt())
        NextVreraButtonTone.VIOLET ->
            Palette(0xFFB3A6FF.toInt(), 0xFF8175E8.toInt(), 0xFF211A45.toInt(), 0xFFE2DBFF.toInt())
        NextVreraButtonTone.WARNING ->
            Palette(0xFFFFD38E.toInt(), 0xFFE7AA60.toInt(), 0xFF30200D.toInt(), 0xFFFFE9C5.toInt())
        NextVreraButtonTone.DANGER ->
            Palette(0xFFD86B7C.toInt(), 0xFFA84059.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFC0CB.toInt())
        NextVreraButtonTone.SECONDARY ->
            Palette(0xFF344B65.toInt(), 0xFF26394F.toInt(), 0xFFF4F7FC.toInt(), 0xFF819AB6.toInt())
    }

    isAllCaps = false
    setTextColor(palette.foreground)
    textSize = 14f
    minHeight = dp(46)
    minimumHeight = dp(46)
    minimumWidth = 0
    setPadding(dp(16), dp(10), dp(16), dp(10))

    val shape = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(palette.start, palette.end)
    ).apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), palette.stroke)
    }
    background = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        shape,
        null
    )
    return this
}

private data class Palette(
    val start: Int,
    val end: Int,
    val foreground: Int,
    val stroke: Int
) {
    val ripple: Int
        get() = 0x44FFFFFF
}

private fun Button.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
