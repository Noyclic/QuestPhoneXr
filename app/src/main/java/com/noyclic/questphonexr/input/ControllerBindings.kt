package com.noyclic.questphonexr.input

import android.content.Context
import android.content.SharedPreferences

/** Physical analog source used to drive a virtual controller's trigger. */
enum class TriggerAxisSource {
    LEFT_TRIGGER,
    RIGHT_TRIGGER,
    EITHER,
    NONE
}

/** Physical stick pair routed to a virtual controller. */
enum class StickAxisSource {
    /** Uses the assigned device's main stick; dual-mapped full pads use left/right sticks per hand. */
    AUTO,
    LEFT,
    RIGHT,
    NONE
}

/** Buttons exposed to the EndXR/OpenXR virtual controller contract. */
enum class VirtualGamepadButton {
    A,
    B,
    X,
    Y,
    MENU
}

/**
 * User-editable mapping for one virtual hand. Key codes are Android KeyEvent codes;
 * zero means that a face/menu button is not mapped.
 */
data class ControllerHandBinding(
    val triggerSource: TriggerAxisSource,
    val gripKeyCode: Int,
    val stickSource: StickAxisSource,
    val buttonAKeyCode: Int,
    val buttonBKeyCode: Int,
    val buttonXKeyCode: Int,
    val buttonYKeyCode: Int,
    val menuKeyCode: Int
) {
    fun keyCodeFor(button: VirtualGamepadButton): Int = when (button) {
        VirtualGamepadButton.A -> buttonAKeyCode
        VirtualGamepadButton.B -> buttonBKeyCode
        VirtualGamepadButton.X -> buttonXKeyCode
        VirtualGamepadButton.Y -> buttonYKeyCode
        VirtualGamepadButton.MENU -> menuKeyCode
    }

    fun withButton(button: VirtualGamepadButton, keyCode: Int) = when (button) {
        VirtualGamepadButton.A -> copy(buttonAKeyCode = keyCode)
        VirtualGamepadButton.B -> copy(buttonBKeyCode = keyCode)
        VirtualGamepadButton.X -> copy(buttonXKeyCode = keyCode)
        VirtualGamepadButton.Y -> copy(buttonYKeyCode = keyCode)
        VirtualGamepadButton.MENU -> copy(menuKeyCode = keyCode)
    }

    companion object {
        // Match the familiar VR hand split: X/Y on the left hand, A/B on the right.
        // This avoids mirroring every face button to both hands when one full-size pad is shared.
        val LEFT_DEFAULT = ControllerHandBinding(
            triggerSource = TriggerAxisSource.LEFT_TRIGGER,
            gripKeyCode = GamepadButtonCodes.BUTTON_L1,
            stickSource = StickAxisSource.AUTO,
            buttonAKeyCode = 0,
            buttonBKeyCode = 0,
            buttonXKeyCode = GamepadButtonCodes.BUTTON_X,
            buttonYKeyCode = GamepadButtonCodes.BUTTON_Y,
            menuKeyCode = GamepadButtonCodes.BUTTON_START
        )
        val RIGHT_DEFAULT = ControllerHandBinding(
            triggerSource = TriggerAxisSource.RIGHT_TRIGGER,
            gripKeyCode = GamepadButtonCodes.BUTTON_R1,
            stickSource = StickAxisSource.AUTO,
            buttonAKeyCode = GamepadButtonCodes.BUTTON_A,
            buttonBKeyCode = GamepadButtonCodes.BUTTON_B,
            buttonXKeyCode = 0,
            buttonYKeyCode = 0,
            menuKeyCode = 0
        )
    }
}

/** Global fallback profile; it can be overridden later by per-game profiles. */
data class ControllerBindingProfile(
    val left: ControllerHandBinding = ControllerHandBinding.LEFT_DEFAULT,
    val right: ControllerHandBinding = ControllerHandBinding.RIGHT_DEFAULT
) {
    fun forHand(hand: com.noyclic.questphonexr.tracking.HandSide): ControllerHandBinding =
        when (hand) {
            com.noyclic.questphonexr.tracking.HandSide.LEFT -> left
            com.noyclic.questphonexr.tracking.HandSide.RIGHT -> right
            com.noyclic.questphonexr.tracking.HandSide.UNKNOWN -> left
        }

    fun withHand(
        hand: com.noyclic.questphonexr.tracking.HandSide,
        binding: ControllerHandBinding
    ) = when (hand) {
        com.noyclic.questphonexr.tracking.HandSide.LEFT -> copy(left = binding)
        com.noyclic.questphonexr.tracking.HandSide.RIGHT -> copy(right = binding)
        com.noyclic.questphonexr.tracking.HandSide.UNKNOWN -> this
    }

    companion object {
        val DEFAULT = ControllerBindingProfile()
    }
}

/** Persists the selected global input layout; button values are stable Android key codes. */
object ControllerBindingStore {
    private const val PREFERENCES = "endxr_controller_bindings"
    private const val VERSION = 1

    fun load(context: Context): ControllerBindingProfile {
        val prefs = preferences(context)
        if (prefs.getInt("version", 0) != VERSION) return ControllerBindingProfile.DEFAULT
        return ControllerBindingProfile(
            left = readHand(prefs, "left", ControllerHandBinding.LEFT_DEFAULT),
            right = readHand(prefs, "right", ControllerHandBinding.RIGHT_DEFAULT)
        )
    }

    fun save(context: Context, profile: ControllerBindingProfile) {
        preferences(context).edit().apply {
            putInt("version", VERSION)
            writeHand(this, "left", profile.left)
            writeHand(this, "right", profile.right)
        }.apply()
    }

    fun reset(context: Context): ControllerBindingProfile {
        val defaults = ControllerBindingProfile.DEFAULT
        save(context, defaults)
        return defaults
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun readHand(
        prefs: SharedPreferences,
        prefix: String,
        defaults: ControllerHandBinding
    ) = ControllerHandBinding(
        triggerSource = enumValue(
            prefs.getString("$prefix.trigger", null),
            defaults.triggerSource
        ),
        gripKeyCode = prefs.getInt("$prefix.grip", defaults.gripKeyCode).coerceAtLeast(0),
        stickSource = enumValue(
            prefs.getString("$prefix.stick", null),
            defaults.stickSource
        ),
        buttonAKeyCode = prefs.getInt("$prefix.a", defaults.buttonAKeyCode).coerceAtLeast(0),
        buttonBKeyCode = prefs.getInt("$prefix.b", defaults.buttonBKeyCode).coerceAtLeast(0),
        buttonXKeyCode = prefs.getInt("$prefix.x", defaults.buttonXKeyCode).coerceAtLeast(0),
        buttonYKeyCode = prefs.getInt("$prefix.y", defaults.buttonYKeyCode).coerceAtLeast(0),
        menuKeyCode = prefs.getInt("$prefix.menu", defaults.menuKeyCode).coerceAtLeast(0)
    )

    private fun writeHand(
        editor: SharedPreferences.Editor,
        prefix: String,
        binding: ControllerHandBinding
    ) {
        editor.putString("$prefix.trigger", binding.triggerSource.name)
        editor.putInt("$prefix.grip", binding.gripKeyCode.coerceAtLeast(0))
        editor.putString("$prefix.stick", binding.stickSource.name)
        editor.putInt("$prefix.a", binding.buttonAKeyCode.coerceAtLeast(0))
        editor.putInt("$prefix.b", binding.buttonBKeyCode.coerceAtLeast(0))
        editor.putInt("$prefix.x", binding.buttonXKeyCode.coerceAtLeast(0))
        editor.putInt("$prefix.y", binding.buttonYKeyCode.coerceAtLeast(0))
        editor.putInt("$prefix.menu", binding.menuKeyCode.coerceAtLeast(0))
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
