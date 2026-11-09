package com.noyclic.questphonexr.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVrMathTest {
    @Test
    fun slerpInterpolatesAlongShortestRotationPath() {
        val start = floatArrayOf(0f, 0f, 0f, 1f)
        val end = floatArrayOf(0f, 0f, 1f, 0f)

        val halfway = PhoneVrMath.slerpQuat(start, end, 0.5f)!!

        assertEquals(0f, halfway[0], 0.0001f)
        assertEquals(0f, halfway[1], 0.0001f)
        assertEquals(kotlin.math.sin(Math.PI / 4.0).toFloat(), halfway[2], 0.0001f)
        assertEquals(kotlin.math.cos(Math.PI / 4.0).toFloat(), halfway[3], 0.0001f)
        assertNormalized(halfway)
    }

    @Test
    fun slerpTreatsOppositeQuaternionSignsAsTheSameOrientation() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)
        val negatedIdentity = floatArrayOf(0f, 0f, 0f, -1f)

        val halfway = PhoneVrMath.slerpQuat(identity, negatedIdentity, 0.5f)!!

        assertEquals(0f, halfway[0], 0.0001f)
        assertEquals(0f, halfway[1], 0.0001f)
        assertEquals(0f, halfway[2], 0.0001f)
        assertEquals(1f, halfway[3], 0.0001f)
    }

    @Test
    fun slerpNormalizesInputsAndClampsInterpolationFactor() {
        val start = floatArrayOf(0f, 0f, 0f, 4f)
        val end = floatArrayOf(0f, 0f, 2f, 0f)

        val beforeStart = PhoneVrMath.slerpQuat(start, end, -1f)!!
        val afterEnd = PhoneVrMath.slerpQuat(start, end, 2f)!!

        assertEquals(1f, beforeStart[3], 0.0001f)
        assertEquals(1f, afterEnd[2], 0.0001f)
        assertNormalized(beforeStart)
        assertNormalized(afterEnd)
    }

    @Test
    fun slerpRejectsInvalidQuaternionAndNonFiniteFactor() {
        val identity = floatArrayOf(0f, 0f, 0f, 1f)

        assertNull(PhoneVrMath.slerpQuat(identity, floatArrayOf(0f, 0f, 0f, 0f), 0.5f))
        assertNull(PhoneVrMath.slerpQuat(identity, floatArrayOf(0f, Float.NaN, 0f, 1f), 0.5f))
        assertNull(PhoneVrMath.slerpQuat(identity, identity, Float.NaN))
    }

    private fun assertNormalized(value: FloatArray) {
        val norm = kotlin.math.sqrt(value.sumOf { it.toDouble() * it.toDouble() })
        assertTrue(kotlin.math.abs(norm - 1.0) < 0.0001)
    }
}
