package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrHandInputEncoderTest {
    @Test
    fun leftAndRightHandSignalsStayOnTheirOwnControllerRows() {
        val frame = HandTrackingFrame(
            timestampMs = 1L,
            hands = listOf(
                TrackedHand(
                    points = emptyList(),
                    pinchStrength = 0.86f,
                    isPinching = true,
                    side = HandSide.LEFT
                ),
                TrackedHand(
                    points = emptyList(),
                    pinchStrength = 0.34f,
                    isPinching = false,
                    side = HandSide.RIGHT
                )
            )
        )

        val (left, right) = EndXrHandInputEncoder.encode(frame)
        assertEquals(6, left.size)
        assertEquals(6, right.size)
        assertEquals(1f, left[0])
        assertEquals(0.86f, left[1])
        assertEquals(1f, right[0])
        assertEquals(0.34f, right[1])
        assertTrue(left[1] > right[1])
        assertEquals(0f, left[3])
        assertEquals(0f, right[4])
    }

    @Test
    fun missingHandsProduceNeutralInactiveRows() {
        val (left, right) = EndXrHandInputEncoder.encode(
            HandTrackingFrame(timestampMs = 2L, hands = emptyList())
        )

        assertFalse(left[0] > 0f)
        assertFalse(right[0] > 0f)
        assertTrue(left.all { it == 0f })
        assertTrue(right.all { it == 0f })
    }
}
