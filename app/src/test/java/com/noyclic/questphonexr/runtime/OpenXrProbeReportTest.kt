package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenXrProbeReportTest {
    @Test
    fun firstFrameStatusIsTheOnlySuccessfulRuntimeProbeResult() {
        val report = OpenXrProbeReport.parse(
            """{"status":"FIRST_FRAME_SUBMITTED_GLES","xrResult":0,"message":"xrEndFrame submitted two views"}"""
        )

        assertTrue(report.isSuccessful)
        assertEquals(0, report.xrResult)
        assertEquals("xrEndFrame submitted two views", report.message)
    }

    @Test
    fun failedRuntimeStagePreservesItsXrResult() {
        val report = OpenXrProbeReport.parse(
            """{"status":"SESSION_READY_TIMEOUT","xrResult":-7,"message":"READY was not received"}"""
        )

        assertFalse(report.isSuccessful)
        assertEquals("SESSION_READY_TIMEOUT", report.status)
        assertEquals(-7, report.xrResult)
    }

    @Test
    fun malformedNativeReportBecomesAnExplicitFailure() {
        val report = OpenXrProbeReport.parse("not-json")

        assertFalse(report.isSuccessful)
        assertEquals("INVALID_REPORT", report.status)
        assertTrue(report.message.isNotBlank())
    }
}