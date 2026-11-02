package com.noyclic.questphonexr.catalog

import com.noyclic.questphonexr.input.ControllerInputSource
import com.noyclic.questphonexr.input.VirtualControllerFrame
import com.noyclic.questphonexr.input.VirtualControllerState
import com.noyclic.questphonexr.tracking.HandSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputProfilesTest {
    @Test
    fun allInitialGamesHaveExplicitProvisionalProfiles() {
        val registry = BuiltInGameInputProfiles.createRegistry()
        listOf(
            "baldis-basics-classic-vr",
            "open-saber",
            "granny-vr",
            "questcraft"
        ).forEach { id ->
            assertNotNull("Missing input profile for $id", registry.adapterFor(id))
        }
        assertNull(registry.adapterFor("unknown-game"))
    }

    @Test
    fun baldiProfileMapsMovementAndInteractionActions() {
        val frame = VirtualControllerFrame(
            timestampMs = 200L,
            left = state(HandSide.LEFT, stickX = 0.45f, stickY = -0.8f),
            right = state(HandSide.RIGHT, stickX = 0.2f, trigger = 0.75f, grip = 0.3f)
        )
        val adapter = BuiltInGameInputProfiles.createRegistry()
            .adapterFor("baldis-basics-classic-vr")!!
        val actions = adapter.map(frame).actions

        assertEquals(0.45f, actions[GameAction.MOVE_X]!!, 0.001f)
        assertEquals(-0.8f, actions[GameAction.MOVE_Y]!!, 0.001f)
        assertEquals(0.2f, actions[GameAction.TURN_X]!!, 0.001f)
        assertEquals(0.75f, actions[GameAction.PRIMARY]!!, 0.001f)
        assertEquals(0.3f, actions[GameAction.GRAB]!!, 0.001f)
    }

    @Test
    fun openSaberProfileUsesSeparateHandActivationSignals() {
        val frame = VirtualControllerFrame(
            timestampMs = 300L,
            left = state(HandSide.LEFT, pinchPressed = true),
            right = state(HandSide.RIGHT, pinchPressed = false)
        )
        val adapter = BuiltInGameInputProfiles.createRegistry().adapterFor("open-saber")!!
        val actions = adapter.map(frame).actions

        assertEquals(1f, actions[GameAction.LEFT_SABER_ACTIVE]!!, 0f)
        assertEquals(0f, actions[GameAction.RIGHT_SABER_ACTIVE]!!, 0f)
    }

    @Test
    fun questcraftProfileReadsAndroidGamepadButtonsExplicitly() {
        val frame = VirtualControllerFrame(
            timestampMs = 400L,
            left = state(HandSide.LEFT, keys = setOf(StandardGamepadKeyCodes.BUTTON_B)),
            right = state(HandSide.RIGHT, keys = setOf(StandardGamepadKeyCodes.BUTTON_A))
        )
        val adapter = BuiltInGameInputProfiles.createRegistry().adapterFor("questcraft")!!
        val actions = adapter.map(frame).actions

        assertTrue(actions[GameAction.JUMP] == 1f)
        assertTrue(actions[GameAction.CROUCH] == 1f)
    }

    private fun state(
        hand: HandSide,
        stickX: Float = 0f,
        stickY: Float = 0f,
        trigger: Float = 0f,
        grip: Float = 0f,
        pinchPressed: Boolean = false,
        keys: Set<Int> = emptySet()
    ) = VirtualControllerState(
        hand = hand,
        isTracked = false,
        pose = null,
        trigger = trigger,
        grip = grip,
        pinchPressed = pinchPressed,
        stickX = stickX,
        stickY = stickY,
        pressedKeyCodes = keys,
        source = ControllerInputSource.GAMEPAD
    )
}
