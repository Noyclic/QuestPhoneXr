package com.noyclic.questphonexr.catalog

import com.noyclic.questphonexr.input.GamepadButtonCodes
import com.noyclic.questphonexr.input.VirtualControllerFrame
import com.noyclic.questphonexr.tracking.HandSide

/**
 * Stable input shape for a game-specific adapter.
 * This does not inject controls into arbitrary third-party APKs.
 */
data class GameControllerInput(
    val hand: HandSide,
    val tracked: Boolean,
    val stickX: Float,
    val stickY: Float,
    val trigger: Float,
    val grip: Float,
    val pinchPressed: Boolean,
    val rawAndroidKeyCodes: Set<Int>
)

enum class GameAction {
    MOVE_X, MOVE_Y, TURN_X, TURN_Y, PRIMARY, SECONDARY,
    GRAB, JUMP, SPRINT, CROUCH, MENU, LEFT_SABER_ACTIVE, RIGHT_SABER_ACTIVE
}

enum class ControllerSignal { STICK_X, STICK_Y, TRIGGER, GRIP, PINCH, ANDROID_KEY }

data class GameInputBinding(
    val action: GameAction,
    val hand: HandSide,
    val signal: ControllerSignal,
    val androidKeyCode: Int? = null
) {
    init {
        require(hand != HandSide.UNKNOWN) { "A game binding must use a left or right controller." }
        require(
            (signal == ControllerSignal.ANDROID_KEY && androidKeyCode != null) ||
                (signal != ControllerSignal.ANDROID_KEY && androidKeyCode == null)
        ) { "androidKeyCode is required only for ANDROID_KEY bindings." }
    }
}

data class GameInputProfile(
    val gameId: String,
    val displayName: String,
    val bindings: List<GameInputBinding>,
    val note: String
) {
    init {
        require(gameId.isNotBlank()) { "Game profile id must not be blank." }
        require(bindings.map { it.action }.distinct().size == bindings.size) {
            "A game profile can bind each action only once."
        }
    }
}

data class GameActionFrame(
    val timestampMs: Long,
    val left: GameControllerInput,
    val right: GameControllerInput,
    val actions: Map<GameAction, Float> = emptyMap()
)

interface GameInputAdapter {
    fun map(frame: VirtualControllerFrame): GameActionFrame
}

class GenericVrGameInputAdapter : GameInputAdapter {
    override fun map(frame: VirtualControllerFrame): GameActionFrame = GameActionFrame(
        timestampMs = frame.timestampMs,
        left = frame.left.toGameInput(),
        right = frame.right.toGameInput()
    )

    internal fun mapControllers(frame: VirtualControllerFrame): Pair<GameControllerInput, GameControllerInput> =
        frame.left.toGameInput() to frame.right.toGameInput()

    private fun com.noyclic.questphonexr.input.VirtualControllerState.toGameInput() =
        GameControllerInput(
            hand = hand,
            tracked = isTracked,
            stickX = stickX.coerceIn(-1f, 1f),
            stickY = stickY.coerceIn(-1f, 1f),
            trigger = trigger.coerceIn(0f, 1f),
            grip = grip.coerceIn(0f, 1f),
            pinchPressed = pinchPressed,
            rawAndroidKeyCodes = pressedKeyCodes.toSet()
        )
}

class ProfiledVrGameInputAdapter(
    val profile: GameInputProfile
) : GameInputAdapter {
    private val baseAdapter = GenericVrGameInputAdapter()

    override fun map(frame: VirtualControllerFrame): GameActionFrame {
        val (left, right) = baseAdapter.mapControllers(frame)
        val values = profile.bindings.associate { binding ->
            binding.action to resolve(binding, left, right)
        }
        return GameActionFrame(frame.timestampMs, left, right, values)
    }

    private fun resolve(
        binding: GameInputBinding,
        left: GameControllerInput,
        right: GameControllerInput
    ): Float {
        val controller = if (binding.hand == HandSide.LEFT) left else right
        return when (binding.signal) {
            ControllerSignal.STICK_X -> controller.stickX
            ControllerSignal.STICK_Y -> controller.stickY
            ControllerSignal.TRIGGER -> controller.trigger
            ControllerSignal.GRIP -> controller.grip
            ControllerSignal.PINCH -> if (controller.pinchPressed) 1f else 0f
            ControllerSignal.ANDROID_KEY ->
                binding.androidKeyCode?.let { if (it in controller.rawAndroidKeyCodes) 1f else 0f } ?: 0f
        }
    }
}

/** Android KeyEvent values used by standard gamepad layouts. Device mappings can vary. */
object StandardGamepadKeyCodes {
    const val BUTTON_A = GamepadButtonCodes.BUTTON_A
    const val BUTTON_B = GamepadButtonCodes.BUTTON_B
    const val BUTTON_START = GamepadButtonCodes.BUTTON_START
}

/**
 * Starter phone-port profiles. These are proposed action mappings, not verified bindings
 * for the original Quest applications. Each game port must deliberately consume this contract.
 */
object BuiltInGameInputProfiles {
    private const val BALDI = "baldis-basics-classic-vr"
    private const val OPEN_SABER = "open-saber"
    private const val GRANNY = "granny-vr"
    private const val QUESTCRAFT = "questcraft"

    val all: List<GameInputProfile> = listOf(
        GameInputProfile(
            gameId = BALDI,
            displayName = "Baldi's Basics Classic VR · phone-port",
            bindings = listOf(
                GameInputBinding(GameAction.MOVE_X, HandSide.LEFT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.MOVE_Y, HandSide.LEFT, ControllerSignal.STICK_Y),
                GameInputBinding(GameAction.TURN_X, HandSide.RIGHT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.TURN_Y, HandSide.RIGHT, ControllerSignal.STICK_Y),
                GameInputBinding(GameAction.PRIMARY, HandSide.RIGHT, ControllerSignal.TRIGGER),
                GameInputBinding(GameAction.GRAB, HandSide.RIGHT, ControllerSignal.GRIP),
                GameInputBinding(GameAction.MENU, HandSide.LEFT, ControllerSignal.ANDROID_KEY, StandardGamepadKeyCodes.BUTTON_START)
            ),
            note = "Предлагаемая раскладка для отдельного телефонного порта; не проверена с исходной Quest-сборкой."
        ),
        GameInputProfile(
            gameId = OPEN_SABER,
            displayName = "Open Saber · phone-port",
            bindings = listOf(
                GameInputBinding(GameAction.LEFT_SABER_ACTIVE, HandSide.LEFT, ControllerSignal.PINCH),
                GameInputBinding(GameAction.RIGHT_SABER_ACTIVE, HandSide.RIGHT, ControllerSignal.PINCH),
                GameInputBinding(GameAction.MENU, HandSide.LEFT, ControllerSignal.ANDROID_KEY, StandardGamepadKeyCodes.BUTTON_START)
            ),
            note = "Профиль передаёт состояния двух рук; траектория сабель требует откалиброванной 3D-позы и отдельной интеграции."
        ),
        GameInputProfile(
            gameId = GRANNY,
            displayName = "Granny VR · phone-port",
            bindings = listOf(
                GameInputBinding(GameAction.MOVE_X, HandSide.LEFT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.MOVE_Y, HandSide.LEFT, ControllerSignal.STICK_Y),
                GameInputBinding(GameAction.TURN_X, HandSide.RIGHT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.PRIMARY, HandSide.RIGHT, ControllerSignal.TRIGGER),
                GameInputBinding(GameAction.CROUCH, HandSide.LEFT, ControllerSignal.GRIP)
            ),
            note = "Предлагаемая раскладка для порта; взаимодействия и поведение должны быть привязаны в самом порте."
        ),
        GameInputProfile(
            gameId = QUESTCRAFT,
            displayName = "QuestCraft · phone-port",
            bindings = listOf(
                GameInputBinding(GameAction.MOVE_X, HandSide.LEFT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.MOVE_Y, HandSide.LEFT, ControllerSignal.STICK_Y),
                GameInputBinding(GameAction.TURN_X, HandSide.RIGHT, ControllerSignal.STICK_X),
                GameInputBinding(GameAction.TURN_Y, HandSide.RIGHT, ControllerSignal.STICK_Y),
                GameInputBinding(GameAction.PRIMARY, HandSide.RIGHT, ControllerSignal.TRIGGER),
                GameInputBinding(GameAction.SECONDARY, HandSide.LEFT, ControllerSignal.TRIGGER),
                GameInputBinding(GameAction.JUMP, HandSide.RIGHT, ControllerSignal.ANDROID_KEY, StandardGamepadKeyCodes.BUTTON_A),
                GameInputBinding(GameAction.CROUCH, HandSide.LEFT, ControllerSignal.ANDROID_KEY, StandardGamepadKeyCodes.BUTTON_B),
                GameInputBinding(GameAction.MENU, HandSide.LEFT, ControllerSignal.ANDROID_KEY, StandardGamepadKeyCodes.BUTTON_START)
            ),
            note = "Предварительная раскладка для адаптированного порта Minecraft VR, не оригинальная схема QuestCraft."
        )
    )

    fun forGame(gameId: String): GameInputProfile? = all.firstOrNull { it.gameId == gameId }

    fun createRegistry(): GameAdapterRegistry = GameAdapterRegistry(
        all.map { profile ->
            object : GameAdapterProvider {
                override val gameIds: Set<String> = setOf(profile.gameId)
                override fun createInputAdapter(): GameInputAdapter =
                    ProfiledVrGameInputAdapter(profile)
            }
        }
    )
}

interface GameAdapterProvider {
    val gameIds: Set<String>
    fun createInputAdapter(): GameInputAdapter
}

class GameAdapterRegistry(providers: List<GameAdapterProvider>) {
    private val adaptersByGameId: Map<String, GameInputAdapter> = buildMap {
        providers.forEach { provider ->
            val adapter = provider.createInputAdapter()
            provider.gameIds.forEach { gameId ->
                require(gameId.isNotBlank()) { "Game adapter ids must not be blank." }
                require(!containsKey(gameId)) { "Duplicate game adapter registration: " + gameId }
                put(gameId, adapter)
            }
        }
    }

    fun adapterFor(gameId: String): GameInputAdapter? = adaptersByGameId[gameId]
}
