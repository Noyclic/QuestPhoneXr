package com.noyclic.questphonexr.tracking

import kotlin.math.sqrt

/** Shared power-grip interpretation for Sandbox and the EndXR input bridge. */
internal object HandGestureEstimator {
    private val fingerMcpIndices = intArrayOf(5, 9, 13, 17)

    fun isPowerGrip(hand: TrackedHand): Boolean {
        val worldCount = curledFingerCount(hand.worldPoints)
        val curledFingers = worldCount ?: curledFingerCount(hand.points) ?: return false
        return curledFingers >= MIN_POWER_GRIP_FINGERS
    }

    internal fun curledFingerCount(points: List<HandPoint>): Int? {
        if (points.size < LANDMARK_COUNT) return null
        val wrist = points[0]
        var completeFingers = 0
        var curledFingers = 0
        for (mcpIndex in fingerMcpIndices) {
            val mcp = points[mcpIndex]
            val pip = points[mcpIndex + 1]
            val dip = points[mcpIndex + 2]
            val tip = points[mcpIndex + 3]
            val palmLength = distance(wrist, mcp)
            if (!palmLength.isFinite() || palmLength < MIN_PALM_FINGER_LENGTH) continue
            val segments = floatArrayOf(
                distance(mcp, pip), distance(pip, dip), distance(dip, tip)
            )
            if (segments.any { !it.isFinite() || it / palmLength < MIN_FINGER_SEGMENT_RATIO }) {
                continue
            }
            completeFingers++
            val foldedLength = distance(mcp, tip)
            if (foldedLength.isFinite() && foldedLength / palmLength < POWER_GRIP_FOLD_RATIO) {
                curledFingers++
            }
        }
        return curledFingers.takeIf { completeFingers >= MIN_POWER_GRIP_FINGERS }
    }

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private const val LANDMARK_COUNT = 21
    private const val MIN_PALM_FINGER_LENGTH = 0.025f
    private const val MIN_FINGER_SEGMENT_RATIO = 0.055f
    private const val POWER_GRIP_FOLD_RATIO = 0.92f
    private const val MIN_POWER_GRIP_FINGERS = 3
}
