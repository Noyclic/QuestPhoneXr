package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrHandTrackingDiagnosticsTest {

    @Test
    fun stopPreservesCameraStartFailureForDiagnostics() {
        EndXrHandTrackingDiagnostics.begin(640, 480, maxHands = 2, inferenceIntervalMs = 100L)
        EndXrHandTrackingDiagnostics.markError("CameraX could not bind")

        EndXrHandTrackingDiagnostics.markStopped()

        assertEquals("error", EndXrHandTrackingDiagnostics.currentState())
    }

    @Test
    fun reportSeparatesPreferredDelegateFromDelegateActuallyCreated() {
        EndXrHandTrackingDiagnostics.begin(
            640, 480, maxHands = 1, inferenceIntervalMs = 0L, preferGpuDelegate = true
        )
        EndXrHandTrackingDiagnostics.updateMetrics(
            HandTrackingMetricsSnapshot(
                receivedFrames = 1L,
                submittedFrames = 1L,
                completedFrames = 1L,
                droppedBusyFrames = 0L,
                droppedIntervalFrames = 0L,
                lastPreprocessingMs = 1L,
                averagePreprocessingMs = 1f,
                delegateName = "CPU"
            )
        )

        val report = EndXrHandTrackingDiagnostics.report()

        assertTrue(report.contains("service_preferred_delegate=GPU"))
        assertTrue(report.contains("service_actual_delegate=CPU"))
        assertTrue(report.contains("service_mediapipe_delegate=CPU"))
    }

    @Test
    fun normalStopChangesRunningServiceToStopped() {
        EndXrHandTrackingDiagnostics.begin(640, 480, maxHands = 2, inferenceIntervalMs = 100L)
        EndXrHandTrackingDiagnostics.markRunning()

        EndXrHandTrackingDiagnostics.markStopped()

        assertEquals("stopped", EndXrHandTrackingDiagnostics.currentState())
    }
}
