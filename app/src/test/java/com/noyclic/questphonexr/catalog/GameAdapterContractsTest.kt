package com.noyclic.questphonexr.catalog

import com.noyclic.questphonexr.input.ControllerInputSource
import com.noyclic.questphonexr.input.VirtualControllerFrame
import com.noyclic.questphonexr.input.VirtualControllerState
import com.noyclic.questphonexr.tracking.HandSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAdapterContractsTest {
    @Test
    fun genericAdapterPreservesNormalizedControllerInput() {
        val left = state(HandSide.LEFT, stickX = 1.5f, trigger = 0.8f)
        val right = state(HandSide.RIGHT, stickY = -0.4f, grip = 0.6f)
        val mapped = GenericVrGameInputAdapter().map(VirtualControllerFrame(123L, left, right))

        assertEquals(123L, mapped.timestampMs)
        assertEquals(1f, mapped.left.stickX, 0.001f)
        assertEquals(0.8f, mapped.left.trigger, 0.001f)
        assertEquals(-0.4f, mapped.right.stickY, 0.001f)
        assertEquals(0.6f, mapped.right.grip, 0.001f)
    }

    @Test
    fun registryOnlyReturnsExplicitlyRegisteredGameAdapters() {
        val provider = object : GameAdapterProvider {
            override val gameIds = setOf("baldis-basics-classic-vr")
            override fun createInputAdapter(): GameInputAdapter = GenericVrGameInputAdapter()
        }
        val registry = GameAdapterRegistry(listOf(provider))

        assertTrue(registry.adapterFor("baldis-basics-classic-vr") != null)
        assertNull(registry.adapterFor("questcraft"))
    }

    private fun state(
        side: HandSide,
        stickX: Float = 0f,
        stickY: Float = 0f,
        trigger: Float = 0f,
        grip: Float = 0f
    ) = VirtualControllerState(
        hand = side,
        isTracked = false,
        pose = null,
        trigger = trigger,
        grip = grip,
        pinchPressed = false,
        stickX = stickX,
        stickY = stickY,
        pressedKeyCodes = setOf(96),
        source = ControllerInputSource.GAMEPAD
    )
}
