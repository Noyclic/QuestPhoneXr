package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandSide
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerFrameUdpSenderTest {
    @Test
    fun serializesACompleteVersionedControllerSnapshot() {
        val empty = VirtualControllerState(
            hand = HandSide.LEFT,
            isTracked = false,
            pose = null,
            trigger = 0f,
            grip = 0f,
            pinchPressed = false,
            stickX = 0.25f,
            stickY = -0.5f,
            pressedKeyCodes = setOf(96, 97),
            source = ControllerInputSource.GAMEPAD
        )
        val frame = VirtualControllerFrame(1234L, empty, empty)
        val sender = ControllerFrameUdpSender(onStatus = {})
        val method = ControllerFrameUdpSender::class.java.getDeclaredMethod(
            "encode", VirtualControllerFrame::class.java, java.lang.Long.TYPE
        )
        method.isAccessible = true
        val json = method.invoke(sender, frame, 7L) as String
        sender.close()

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"sequence\":7"))
        assertTrue(json.contains("\"timestampMs\":1234"))
        assertTrue(json.contains("\"stickX\":0.25"))
        assertTrue(json.contains("\"keys\":[96,97]"))
        assertTrue(json.contains("\"pose\":null"))
    }
}
