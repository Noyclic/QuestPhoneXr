package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCompatibilityEvaluatorTest {
    private val redmi = DeviceFingerprint(
        manufacturer = "Xiaomi",
        model = "23124RA7EO",
        androidVersion = "15",
        apiLevel = 35
    )

    @Test
    fun emptyReportsStayUntested() {
        val result = GameCompatibilityEvaluator.evaluate(redmi, emptyList())

        assertEquals(CompatibilityState.NOT_TESTED, result.state)
        assertEquals(0, result.reportsForThisDevice)
        assertFalse(result.androidVersionMatches ?: false)
    }

    @Test
    fun matchingDeviceReportDeterminesCompatibilityState() {
        val report = report(
            model = "23124RA7EO",
            outcome = TestOutcome.PLAYABLE_WITH_ISSUES
        )

        val result = GameCompatibilityEvaluator.evaluate(redmi, listOf(report))

        assertEquals(CompatibilityState.LIMITED, result.state)
        assertEquals(1, result.reportsForThisDevice)
        assertTrue(result.androidVersionMatches == true)
    }

    @Test
    fun reportFromAnotherPhoneDoesNotMarkCurrentPhoneAsTested() {
        val report = report(
            model = "Pixel 8",
            manufacturer = "Google",
            outcome = TestOutcome.LAUNCHED
        )

        val result = GameCompatibilityEvaluator.evaluate(redmi, listOf(report))

        assertEquals(CompatibilityState.NOT_TESTED, result.state)
        assertEquals(0, result.reportsForThisDevice)
        assertEquals(1, result.reportsForOtherDevices)
    }

    private fun report(
        model: String,
        outcome: TestOutcome,
        manufacturer: String = "Xiaomi"
    ) = GameTestReport(
        device = DeviceFingerprint(manufacturer, model, "15", 35),
        outcome = outcome,
        testedAtEpochMs = 100L,
        averageFps = 24,
        minimumFps = 17,
        appVersion = "0.1.0",
        notes = ""
    )
}
