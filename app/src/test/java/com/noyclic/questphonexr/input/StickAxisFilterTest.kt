package com.noyclic.questphonexr.input

import org.junit.Assert.assertEquals
import org.junit.Test

class StickAxisFilterTest {
    @Test
    fun valuesInsideDeadZoneBecomeZero() {
        val result = StickAxisFilter.normalize(0.08f, 0.08f, deadZone = 0.14f)

        assertEquals(0f, result.x, 0f)
        assertEquals(0f, result.y, 0f)
    }

    @Test
    fun valuesOutsideDeadZoneAreRescaledToUseFullRange() {
        val result = StickAxisFilter.normalize(0.57f, 0f, deadZone = 0.14f)

        assertEquals(0.5f, result.x, 0.001f)
        assertEquals(0f, result.y, 0f)
    }

    @Test
    fun diagonalInputIsNormalizedWithoutExceedingUnitMagnitude() {
        val result = StickAxisFilter.normalize(1f, 1f)
        val magnitude = kotlin.math.sqrt(result.x * result.x + result.y * result.y)

        assertEquals(1f, magnitude, 0.001f)
        assertEquals(result.x, result.y, 0.001f)
    }

    @Test
    fun nonFiniteAxisValuesAreTreatedAsNeutral() {
        val result = StickAxisFilter.normalize(Float.NaN, Float.POSITIVE_INFINITY)

        assertEquals(0f, result.x, 0f)
        assertEquals(0f, result.y, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deadZoneCannotBeOneOrHigher() {
        StickAxisFilter.normalize(0f, 0f, deadZone = 1f)
    }
}
