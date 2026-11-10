package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGestureEstimatorTest {
    @Test
    fun threeCurledFingerChainsActivatePowerGrip() {
        assertTrue(HandGestureEstimator.isPowerGrip(handWithCurledFingers(3)))
        assertTrue(HandGestureEstimator.isPowerGrip(handWithCurledFingers(4)))
    }

    @Test
    fun fewerThanThreeCurledFingerChainsDoNotActivatePowerGrip() {
        assertFalse(HandGestureEstimator.isPowerGrip(handWithCurledFingers(2)))
    }

    @Test
    fun incompleteWorldChainsFallBackToCompleteImageLandmarks() {
        val hand = handWithCurledFingers(3).copy(worldPoints = emptyList())
        assertTrue(HandGestureEstimator.isPowerGrip(hand))
    }

    @Test
    fun incompleteFingerChainsAreNotCountedAsAClosedHand() {
        assertNull(HandGestureEstimator.curledFingerCount(emptyList()))
    }

    private fun handWithCurledFingers(curledCount: Int): TrackedHand {
        val wrist = HandPoint(0f, 0f, 0f)
        val points = MutableList(21) { wrist }
        val bases = listOf(
            HandPoint(0.035f, 0.030f, 0f), HandPoint(0f, 0.055f, 0f),
            HandPoint(-0.025f, 0.045f, 0f), HandPoint(-0.040f, 0.025f, 0f)
        )
        val mcps = intArrayOf(5, 9, 13, 17)
        for (finger in mcps.indices) {
            val mcp = bases[finger]
            val pip = HandPoint(mcp.x, mcp.y + 0.018f, 0f)
            val dip = if (finger < curledCount) HandPoint(mcp.x + 0.012f, mcp.y + 0.016f, 0f)
                else HandPoint(mcp.x, mcp.y + 0.036f, 0f)
            val tip = if (finger < curledCount) HandPoint(mcp.x + 0.006f, mcp.y + 0.006f, 0f)
                else HandPoint(mcp.x, mcp.y + 0.060f, 0f)
            points[mcps[finger]] = mcp
            points[mcps[finger] + 1] = pip
            points[mcps[finger] + 2] = dip
            points[mcps[finger] + 3] = tip
        }
        return TrackedHand(points = points, worldPoints = points, side = HandSide.LEFT)
    }
}
