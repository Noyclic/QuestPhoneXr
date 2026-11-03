package com.noyclic.questphonexr.input

import com.noyclic.questphonexr.tracking.HandPoint
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrTrackingPacketCodecTest {
    @Test
    fun encodesProtocolVersionHeadMatrixAndBothHands() {
        val left = hand(HandSide.LEFT, pinch = true)
        val right = hand(HandSide.RIGHT, pinch = false)
        val json = XrTrackingPacketCodec.encode(
            HandTrackingFrame(1234L, listOf(left, right)),
            IDENTITY,
            9L
        )

        assertTrue(json.contains("\"protocol\":\"QuestPhoneXR\""))
        assertTrue(json.contains("\"type\":\"tracking\""))
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"sequence\":9"))
        assertTrue(json.contains("\"timestampMs\":1234"))
        assertTrue(json.contains("\"rotationMatrix\":[1.0,0.0,0.0"))
        assertTrue(json.contains("\"side\":\"LEFT\""))
        assertTrue(json.contains("\"side\":\"RIGHT\""))
        assertTrue(json.contains("\"pinchPressed\":true"))
        assertTrue(json.contains("\"imageLandmarks\":["))
        assertTrue(json.contains("\"worldLandmarks\":["))
    }

    @Test
    fun missingHeadRotationIsExplicitlyMarkedAsUntracked() {
        val json = XrTrackingPacketCodec.encode(
            HandTrackingFrame(10L, emptyList()),
            null,
            1L
        )

        assertTrue(json.contains("\"head\":{\"tracked\":false,\"rotationMatrix\":null}"))
        assertTrue(json.contains("\"hands\":[]"))
    }

    @Test
    fun nonFiniteAndOutOfRangeValuesCannotBreakJsonNumbers() {
        val invalidHand = TrackedHand(
            points = listOf(
                HandPoint(Float.NaN, Float.POSITIVE_INFINITY, -99f)
            ),
            pinchStrength = Float.NaN,
            isPinching = false,
            side = HandSide.LEFT
        )
        val matrix = floatArrayOf(
            Float.NaN, 2f, -2f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val json = XrTrackingPacketCodec.encode(
            HandTrackingFrame(-1L, listOf(invalidHand)),
            matrix,
            -1L
        )

        assertTrue(json.contains("\"sequence\":0"))
        assertTrue(json.contains("\"timestampMs\":0"))
        assertTrue(json.contains("\"rotationMatrix\":[0.0,1.0,-1.0"))
        assertTrue(json.contains("\"pinchStrength\":0.0"))
        assertTrue(json.contains("\"x\":0.0"))
        assertTrue(json.contains("\"y\":0.0"))
        assertTrue(json.contains("\"z\":-2.0"))
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test
    fun eachHandIsLimitedToTwentyOneLandmarksAndAtMostTwoHandsAreSent() {
        val points = List(30) { index -> HandPoint(index / 30f, 0.5f, 0f) }
        val frame = HandTrackingFrame(
            20L,
            listOf(
                TrackedHand(points, side = HandSide.LEFT),
                TrackedHand(points, side = HandSide.RIGHT),
                TrackedHand(points, side = HandSide.UNKNOWN)
            )
        )
        val json = XrTrackingPacketCodec.encode(frame, null, 3L)

        assertEquals(2, Regex("\"side\":").findAll(json).count())
        assertEquals(42, Regex("\"x\":").findAll(json).count())
    }

    private fun hand(side: HandSide, pinch: Boolean) = TrackedHand(
        points = List(21) { index -> HandPoint(index / 20f, 0.5f, -0.1f) },
        pinchStrength = if (pinch) 0.9f else 0.1f,
        isPinching = pinch,
        side = side,
        worldPoints = List(21) { index -> HandPoint(index / 100f, 0.02f, 0.01f) }
    )

    private companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
    }
}
