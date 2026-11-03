package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenXrProbeReportTest {
    @Test
    fun sustainedFrameLoopIsRecognizedAsSuccessful() {
        val report = OpenXrProbeReport.parse(
            """{"status":"SUSTAINED_FRAMES_SUBMITTED_GLES","xrResult":0,"message":"30 stereo frames submitted"}"""
        )

        assertTrue(report.isSuccessful)
        assertEquals(0, report.xrResult)
        assertEquals("30 stereo frames submitted", report.message)
    }

    @Test
    fun persistentFrameSubmissionIsRecognizedAndReportsFrameCount() {
        val report = OpenXrProbeReport.parse(
            """{"status":"FRAME_SUBMITTED","xrResult":0,"framesSubmitted":42,"message":"persistent session active"}"""
        )

        assertTrue(report.isSuccessful)
        assertEquals(42L, report.framesSubmitted)
        assertTrue(report.toDisplayText().contains("42"))
    }

    @Test
    fun persistentReportParsesHeadTrackingValidityAndMotion() {
        val report = OpenXrProbeReport.parse(
            """{"status":"FRAME_SUBMITTED","xrResult":0,"framesSubmitted":120,"sessionState":5,"poseSamples":118,"orientationValidSamples":118,"positionValidSamples":0,"fullTrackingSamples":0,"orientationTrackedSamples":118,"positionTrackedSamples":0,"fullTrackedSamples":0,"lastViewStateFlags":5,"maxHeadPositionDeltaMeters":0.0,"maxHeadRotationDeltaDegrees":12.5,"message":"tracking report"}"""
        )

        assertTrue(report.isSuccessful)
        assertEquals(120L, report.framesSubmitted)
        assertEquals(5, report.sessionState)
        assertEquals(118L, report.poseSamples)
        assertEquals(118L, report.orientationValidSamples)
        assertEquals(0L, report.positionValidSamples)
        assertEquals(0L, report.fullTrackingSamples)
        assertEquals(118L, report.orientationTrackedSamples)
        assertEquals(0L, report.positionTrackedSamples)
        assertEquals(0L, report.fullTrackedSamples)
        assertEquals(5L, report.lastViewStateFlags)
        assertEquals(0.0, report.maxHeadPositionDeltaMeters!!, 0.0001)
        assertEquals(12.5, report.maxHeadRotationDeltaDegrees!!, 0.0001)
        assertTrue(report.toDisplayText().contains("FOCUSED"))
        assertTrue(report.toDisplayText().contains("ориентация"))
        assertTrue(report.toDisplayText().contains("Активное tracking"))
    }

    @Test
    fun oldReportsRemainReadableWithoutTrackingFields() {
        val report = OpenXrProbeReport.parse(
            """{"status":"FRAME_SUBMITTED","xrResult":0,"framesSubmitted":8,"message":"legacy persistent report"}"""
        )

        assertTrue(report.isSuccessful)
        assertEquals(8L, report.framesSubmitted)
        assertEquals(null, report.sessionState)
        assertEquals(null, report.poseSamples)
        assertEquals(null, report.maxHeadPositionDeltaMeters)
    }

    @Test
    fun legacySingleFrameSuccessRemainsRecognized() {
        val report = OpenXrProbeReport.parse(
            """{"status":"FIRST_FRAME_SUBMITTED_GLES","xrResult":0,"message":"single frame"}"""
        )

        assertTrue(report.isSuccessful)
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
    fun sandboxGlSurfaceLifecycleWaitsForValidSize() {
        val lifecycle = OpenXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        assertEquals(OpenXrGlSurfaceLifecycle.State.WAITING_FOR_SIZE, lifecycle.state)
        lifecycle.onGlSurfaceChanged(0, 720)
        assertEquals(OpenXrGlSurfaceLifecycle.State.WAITING_FOR_SIZE, lifecycle.state)
        lifecycle.onGlSurfaceChanged(1280, 720)
        assertEquals(OpenXrGlSurfaceLifecycle.State.READY, lifecycle.state)
    }

    @Test
    fun sandboxGlSurfaceLifecycleRestoresReadyStateAfterPause() {
        val lifecycle = OpenXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        lifecycle.onGlSurfaceChanged(1280, 720)
        val generation = lifecycle.surfaceGeneration
        lifecycle.onActivityPaused()
        assertEquals(OpenXrGlSurfaceLifecycle.State.PAUSED, lifecycle.state)
        lifecycle.onActivityResumed()
        assertEquals(OpenXrGlSurfaceLifecycle.State.READY, lifecycle.state)
        assertEquals(generation, lifecycle.surfaceGeneration)
    }

    @Test
    fun sandboxGlSurfaceLifecycleCountsSurfaceRecreation() {
        val lifecycle = OpenXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        lifecycle.onGlSurfaceChanged(1280, 720)
        lifecycle.onGlSurfaceDestroyed()
        assertEquals(OpenXrGlSurfaceLifecycle.State.WAITING_FOR_SURFACE, lifecycle.state)
        lifecycle.onGlSurfaceCreated()
        assertEquals(2L, lifecycle.surfaceGeneration)
    }

    @Test
    fun malformedNativeReportBecomesAnExplicitFailure() {
        val report = OpenXrProbeReport.parse("not-json")

        assertFalse(report.isSuccessful)
        assertEquals("INVALID_REPORT", report.status)
        assertTrue(report.message.isNotBlank())
    }
}