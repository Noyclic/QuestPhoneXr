package com.noyclic.questphonexr.input

import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.noyclic.questphonexr.tracking.HandSide
import java.util.Locale

/**
 * Separate controller-binding workspace. It captures Android gamepad key codes and lets the
 * user route the device's standard analog trigger/stick axes to either virtual XR hand.
 */
class ControllerBindingsActivity : ComponentActivity(), InputManager.InputDeviceListener {

    private enum class Tab { AXES, BUTTONS, TEST }

    private data class CaptureTarget(
        val hand: HandSide,
        val button: VirtualGamepadButton? = null,
        val isGrip: Boolean = false
    )

    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val gamepadInput = AndroidGamepadInputAdapter { snapshots ->
        runOnUiThread {
            updateConnectedDevices(snapshots)
        }
    }

    private var profile = ControllerBindingProfile.DEFAULT
    private var tab = Tab.AXES
    private var captureTarget: CaptureTarget? = null
    private var triggerAxisCaptureHand: HandSide? = null
    private var triggerAxisCaptureDeviceId: Int? = null
    private var triggerAxisCaptureBaseline: Map<Int, Float> = emptyMap()
    private var selectedCaptureDeviceId: Int? = null
    private var latestDevices: List<PhysicalControllerSnapshot> = emptyList()

    private lateinit var page: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var testReadout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        profile = ControllerBindingStore.load(this)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(15, 19, 27))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(text("Бинды геймпада", 22f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Назад") { finish() })
        root.addView(header)

        statusView = text("Загружаем контроллеры…", 13f, 0xFFB8C5D5.toInt())
        statusView.setPadding(0, dp(8), 0, dp(8))
        root.addView(statusView)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabButton("Оси и курки", Tab.AXES), weightParams())
        tabs.addView(tabButton("Кнопки", Tab.BUTTONS), weightParams())
        tabs.addView(tabButton("Проверка", Tab.TEST), weightParams())
        root.addView(tabs)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(18))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(button("Вернуть стандартную раскладку") {
            profile = ControllerBindingStore.reset(this)
            captureTarget = null
            cancelTriggerAxisCapture()
            renderPage()
            Toast.makeText(this, "Стандартные бинды восстановлены", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        gamepadInput.refreshConnectedDevices()
        renderPage()
    }

    private fun tabButton(label: String, target: Tab) = button(label) {
        tab = target
        captureTarget = null
        cancelTriggerAxisCapture()
        renderPage()
    }

    private fun renderPage() {
        if (!::page.isInitialized) return
        page.removeAllViews()
        when (tab) {
            Tab.AXES -> renderAxesPage()
            Tab.BUTTONS -> renderButtonsPage()
            Tab.TEST -> renderTestPage()
        }
        statusView.text = when {
            captureTarget?.isGrip == true -> "Нажми физическую кнопку, которая должна выполнять Grip."
            captureTarget != null -> "Нажми нужную физическую кнопку на подключённом геймпаде."
            triggerAxisCaptureHand != null && triggerAxisCaptureBaseline.isEmpty() ->
                "Оставь курок отпущенным, слегка двинь любой стик и отпусти его — запишем покой."
            triggerAxisCaptureHand != null ->
                "Нейтральная позиция записана. Теперь нажми курок до конца."
            else -> connectedSummary()
        }
    }

    private fun renderAxesPage() {
        if (latestDevices.isNotEmpty()) {
            addChoice(
                page,
                "Геймпад для сканирования оси",
                latestDevices.map { (it.deviceName + " · ID " + it.deviceId) to it.deviceId },
                selectedCaptureDeviceId ?: latestDevices.first().deviceId
            ) { deviceId -> selectedCaptureDeviceId = deviceId }
        } else {
            page.addView(text(
                "Подключи контроллер перед сканированием нестандартной оси курка.",
                12f, 0xFFFFD28A.toInt()
            ))
        }
        page.addView(text(
            "Выбери, какие физические курки и стики будут управлять каждой виртуальной рукой. " +
                "Это подходит для обычных Bluetooth/USB-геймпадов и пары Joy-Con.",
            14f, 0xFFD4DCE7.toInt()
        ))
        handSection("ЛЕВАЯ ВИРТУАЛЬНАЯ РУКА", HandSide.LEFT, profile.left)
        handSection("ПРАВАЯ ВИРТУАЛЬНАЯ РУКА", HandSide.RIGHT, profile.right)
        page.addView(text(
            "Режим одного геймпада: в Sandbox открой «Контроллеры» и назначь устройство «Обе руки». " +
                "Левый и правый курки/стики одного контроллера смогут работать независимо.",
            12f, 0xFF91BFEF.toInt()
        ).apply { setPadding(0, dp(12), 0, 0) })
        page.addView(text(
            "Если у контроллера только один стик или один курок, выбери одинаковый источник для обеих рук " +
                "(например, «Левый стик» или «Любой курок»). Управление будет доступно, хотя оба виртуальных " +
                "контроллера могут двигаться синхронно и такой вариант менее удобен для VR.",
            12f, 0xFFFFD28A.toInt()
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun handSection(
        title: String,
        hand: HandSide,
        binding: ControllerHandBinding
    ) {
        val container = section(title)
        addChoice(
            container,
            "Курок",
            listOf(
                "Левый аналоговый курок (L2)" to TriggerAxisSource.LEFT_TRIGGER,
                "Правый аналоговый курок (R2)" to TriggerAxisSource.RIGHT_TRIGGER,
                "Любой стандартный курок" to TriggerAxisSource.EITHER,
                "Захваченная нестандартная ось" to TriggerAxisSource.CUSTOM_AXIS,
                "Отключено" to TriggerAxisSource.NONE
            ),
            binding.triggerSource
        ) { value -> updateHand(hand) { it.copy(triggerSource = value) } }
        container.addView(text(
            "Своя ось курка: " + (binding.customTriggerAxis?.let { axis ->
                "AXIS_" + axis + " · покой " +
                    (binding.customTriggerRestValue?.let(::format) ?: "0.00") +
                    " → нажатие " +
                    (binding.customTriggerPressedValue?.let(::format) ?: "1.00")
            } ?: "не захвачена"),
            12f, 0xFFB8C5D5.toInt()
        ))
        container.addView(button("Сканировать ось курка") {
            startTriggerAxisCapture(hand)
        })
        container.addView(button("Захватить кнопку Grip") {
            captureTarget = CaptureTarget(hand, isGrip = true)
            renderPage()
        })

        addChoice(
            container,
            "Хват / Grip",
            listOf(
                "L1 / левый бампер" to GamepadButtonCodes.BUTTON_L1,
                "R1 / правый бампер" to GamepadButtonCodes.BUTTON_R1,
                "A" to GamepadButtonCodes.BUTTON_A,
                "B" to GamepadButtonCodes.BUTTON_B,
                "X" to GamepadButtonCodes.BUTTON_X,
                "Y" to GamepadButtonCodes.BUTTON_Y,
                "Без физической кнопки" to 0
            ),
            binding.gripKeyCode
        ) { value -> updateHand(hand) { it.copy(gripKeyCode = value) } }

        addChoice(
            container,
            "Стик движения",
            listOf(
                "Авто · рекомендуемый режим" to StickAxisSource.AUTO,
                "Левый стик" to StickAxisSource.LEFT,
                "Правый стик" to StickAxisSource.RIGHT,
                "Не использовать" to StickAxisSource.NONE
            ),
            binding.stickSource
        ) { value -> updateHand(hand) { it.copy(stickSource = value) } }
    }

    private fun renderButtonsPage() {
        page.addView(text(
            "Переназначь физические кнопки под виртуальные A/B/X/Y и Menu. Нажми «Захватить», " +
                "затем нажми нужную кнопку на самом контроллере.",
            14f, 0xFFD4DCE7.toInt()
        ))
        buttonSection("ЛЕВАЯ ВИРТУАЛЬНАЯ РУКА", HandSide.LEFT, profile.left)
        buttonSection("ПРАВАЯ ВИРТУАЛЬНАЯ РУКА", HandSide.RIGHT, profile.right)
    }

    private fun buttonSection(
        title: String,
        hand: HandSide,
        binding: ControllerHandBinding
    ) {
        val container = section(title)
        VirtualGamepadButton.values().forEach { virtualButton ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val code = binding.keyCodeFor(virtualButton)
            val label = text(
                virtualButton.name + "  ←  " + keyLabel(code),
                14f, Color.WHITE
            )
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val target = CaptureTarget(hand, virtualButton)
            row.addView(button(
                if (captureTarget == target) "Нажми…" else "Захватить"
            ) {
                captureTarget = target
                statusView.text = "Нажми кнопку для " + virtualButton.name + " (" + hand.name + ")."
                renderPage()
            })
            row.addView(button("Очистить") {
                captureTarget = null
                updateHand(hand) { it.withButton(virtualButton, 0) }
                Toast.makeText(this, virtualButton.name + " отключена", Toast.LENGTH_SHORT).show()
            })
            container.addView(row)
        }
    }

    private fun renderTestPage() {
        page.addView(text(
            "Проверка показывает данные, которые Android уже получает от геймпада. " +
                "Нажми курки, стики и кнопки: значения обновятся ниже.",
            14f, 0xFFD4DCE7.toInt()
        ))
        page.addView(button("Повторно найти контроллеры") {
            latestDevices = gamepadInput.refreshConnectedDevices()
            updateTestReadout()
        })
        testReadout = text("", 13f, 0xFF9FE2BC.toInt()).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF202B38.toInt())
        }
        page.addView(testReadout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        updateTestReadout()
    }

    private fun updateTestReadout() {
        if (!::testReadout.isInitialized || tab != Tab.TEST) return
        testReadout.text = if (latestDevices.isEmpty()) {
            "Геймпады не обнаружены. Подключи Bluetooth или USB-контроллер и нажми «Повторно найти»."
        } else {
            latestDevices.joinToString("\n\n") { device ->
                buildString {
                    append(device.deviceName).append(" · ID ").append(device.deviceId).append('\n')
                    append("Левый стик: ")
                        .append(format(device.stickX)).append(", ").append(format(device.stickY)).append('\n')
                    append("Правый стик: ")
                        .append(format(device.rightStickX)).append(", ").append(format(device.rightStickY)).append('\n')
                    append("Курки L2/R2: ")
                        .append(format(device.leftTrigger)).append(" / ").append(format(device.rightTrigger)).append('\n')
                            append("Нажатые key codes: ")
                        .append(device.pressedKeyCodes.sorted().joinToString().ifBlank { "—" }).append('\n')
                    append("Аналоговые оси: ")
                        .append(device.analogAxes.entries.sortedBy { it.key }
                            .joinToString { "AXIS_" + it.key + "=" + format(it.value) }
                            .ifBlank { "не объявлены Android" })
                }
            }
        }
    }

    private fun updateHand(
        hand: HandSide,
        transform: (ControllerHandBinding) -> ControllerHandBinding
    ) {
        profile = profile.withHand(hand, transform(profile.forHand(hand)))
        ControllerBindingStore.save(this, profile)
        renderPage()
    }

    private fun captureKey(event: KeyEvent): Boolean {
        val target = captureTarget ?: return false
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        if (!isGamepad(device.sources)) return false

        captureTarget = null
        if (target.isGrip) {
            updateHand(target.hand) { it.copy(gripKeyCode = event.keyCode) }
            Toast.makeText(this, "Кнопка Grip назначена: " + keyLabel(event.keyCode), Toast.LENGTH_SHORT).show()
        } else {
            val button = target.button ?: return false
            updateHand(target.hand) { it.withButton(button, event.keyCode) }
            Toast.makeText(this, "Бинд сохранён: " + keyLabel(event.keyCode), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun startTriggerAxisCapture(hand: HandSide) {
        val device = latestDevices.firstOrNull { it.deviceId == selectedCaptureDeviceId }
            ?: latestDevices.firstOrNull()
        if (device == null) {
            Toast.makeText(this, "Сначала подключи нужный геймпад.", Toast.LENGTH_LONG).show()
            return
        }
        triggerAxisCaptureHand = hand
        triggerAxisCaptureDeviceId = device.deviceId
        // Always take a fresh neutral sample after the user starts the scan; cached values
        // may be from a previously pressed trigger and would invert the calibration.
        triggerAxisCaptureBaseline = emptyMap()
        captureTarget = null
        renderPage()
    }

    private fun captureChangedTriggerAxis() {
        val hand = triggerAxisCaptureHand ?: return
        val deviceId = triggerAxisCaptureDeviceId ?: return
        val device = latestDevices.firstOrNull { it.deviceId == deviceId } ?: return
        if (triggerAxisCaptureBaseline.isEmpty()) {
            triggerAxisCaptureBaseline = device.analogAxes.toMap()
            if (triggerAxisCaptureBaseline.isNotEmpty()) {
                statusView.text = "Нейтральная позиция записана. Теперь нажми курок до конца."
            }
            return
        }
        val candidate = device.analogAxes.entries.mapNotNull { (axis, value) ->
            if (axis in NON_TRIGGER_AXES) return@mapNotNull null
            val baseline = triggerAxisCaptureBaseline[axis] ?: return@mapNotNull null
            val travel = value - baseline
            if (kotlin.math.abs(travel) >= MIN_TRIGGER_AXIS_CAPTURE_DELTA) {
                Triple(axis, baseline, value)
            } else {
                null
            }
        }.maxByOrNull { kotlin.math.abs(it.third - it.second) } ?: return

        triggerAxisCaptureHand = null
        triggerAxisCaptureDeviceId = null
        triggerAxisCaptureBaseline = emptyMap()
        updateHand(hand) {
            it.copy(
                triggerSource = TriggerAxisSource.CUSTOM_AXIS,
                customTriggerAxis = candidate.first,
                customTriggerRestValue = candidate.second,
                customTriggerPressedValue = candidate.third
            )
        }
        Toast.makeText(
            this,
            "Курок назначен на Android axis " + candidate.first,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (captureKey(event)) return true
        if (gamepadInput.onKeyEvent(event, true)) {
            updateTestReadout()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadInput.onKeyEvent(event, false)) {
            updateTestReadout()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.onMotionEvent(event)) {
            captureChangedTriggerAxis()
            updateTestReadout()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        updateConnectedDevices(gamepadInput.refreshConnectedDevices())
        renderPage()
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        cancelTriggerAxisCapture()
        captureTarget = null
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        updateConnectedDevices(gamepadInput.refreshConnectedDevices())
        renderPage()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        gamepadInput.onDeviceRemoved(deviceId)
        updateConnectedDevices(gamepadInput.snapshots())
        if (triggerAxisCaptureDeviceId == deviceId) {
            cancelTriggerAxisCapture()
            renderPage()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        updateConnectedDevices(gamepadInput.refreshConnectedDevices())
        renderPage()
    }

    private fun updateConnectedDevices(devices: List<PhysicalControllerSnapshot>) {
        latestDevices = devices
        if (devices.none { it.deviceId == selectedCaptureDeviceId }) {
            selectedCaptureDeviceId = devices.firstOrNull()?.deviceId
        }
        updateTestReadout()
    }

    private fun cancelTriggerAxisCapture() {
        triggerAxisCaptureHand = null
        triggerAxisCaptureDeviceId = null
        triggerAxisCaptureBaseline = emptyMap()
    }

    private fun connectedSummary(): String =
        if (latestDevices.isEmpty()) {
            "Контроллеры не найдены · подключи любой Android gamepad/joystick"
        } else {
            "Найдено контроллеров: " + latestDevices.size + " · " +
                latestDevices.joinToString { it.deviceName }
        }

    private fun isGamepad(sources: Int): Boolean {
        val gamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val joystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return gamepad || joystick
    }

    private fun keyLabel(code: Int): String =
        if (code <= 0) "не назначено" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun section(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(0xFF202B38.toInt())
        val heading = text(title, 13f, 0xFF93C5FD.toInt(), true)
        heading.setPadding(0, 0, 0, dp(8))
        addView(heading)
        val margin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        page.addView(this, margin)
    }

    private fun <T> addChoice(
        parent: LinearLayout,
        label: String,
        options: List<Pair<String, T>>,
        selected: T,
        onChange: (T) -> Unit
    ) {
        parent.addView(text(label, 13f, 0xFFD4DCE7.toInt()).apply {
            setPadding(0, dp(6), 0, dp(2))
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        var selectedPosition = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)
        spinner.setSelection(selectedPosition, false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parentView: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (position == selectedPosition) return
                selectedPosition = position
                options.getOrNull(position)?.second?.let(onChange)
            }

            override fun onNothingSelected(parentView: android.widget.AdapterView<*>?) = Unit
        }
        parent.addView(spinner)
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply {
        marginEnd = dp(4)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MIN_TRIGGER_AXIS_CAPTURE_DELTA = 0.18f
        val NON_TRIGGER_AXES = setOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_RX,
            MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y
        )
    }
}
