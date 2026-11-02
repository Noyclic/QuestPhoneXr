package com.noyclic.questphonexr.catalog

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

data class GameActionFrame(
    val timestampMs: Long,
    val left: GameControllerInput,
    val right: GameControllerInput
)

interface GameInputAdapter {
    fun map(frame: VirtualControllerFrame): GameActionFrame
}

/** Converts QuestPhoneXR's controller frame into a stable per-game input contract. */
class GenericVrGameInputAdapter : GameInputAdapter {
    override fun map(frame: VirtualControllerFrame): GameActionFrame = GameActionFrame(
        timestampMs = frame.timestampMs,
        left = frame.left.toGameInput(),
        right = frame.right.toGameInput()
    )

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

/** A provider explicitly opts in to game ids; there is no unsafe catch-all APK hook. */
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
