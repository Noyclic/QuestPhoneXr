package com.noyclic.questphonexr.input

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadAxisNormalizerTest {
    @Test
    fun normalizesTheCommonZeroToOneTriggerRange() {
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(0f, 0f, 1f), 0f)
        assertEquals(0.5f, GamepadAxisNormalizer.normalizeTrigger(0.5f, 0f, 1f), 0f)
        assertEquals(1f, GamepadAxisNormalizer.normalizeTrigger(1f, 0f, 1f), 0f)
    }

    @Test
    fun normalizesMinusOneToOneHidTriggerRange() {
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(-1f, -1f, 1f), 0f)
        assertEquals(0.5f, GamepadAxisNormalizer.normalizeTrigger(0f, -1f, 1f), 0f)
        assertEquals(1f, GamepadAxisNormalizer.normalizeTrigger(1f, -1f, 1f), 0f)
    }

    @Test
    fun clampsValuesOutsideReportedMotionRange() {
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(-3f, -1f, 1f), 0f)
        assertEquals(1f, GamepadAxisNormalizer.normalizeTrigger(3f, -1f, 1f), 0f)
    }

    @Test
    fun invalidValuesOrDegenerateRangesAreNeutral() {
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(Float.NaN, 0f, 1f), 0f)
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(0.5f, 1f, 1f), 0f)
        assertEquals(0f, GamepadAxisNormalizer.normalizeTrigger(Float.POSITIVE_INFINITY, 0f, 1f), 0f)
    }
}
