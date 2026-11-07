package com.noyclic.questphonexr.runtime

import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import com.noyclic.questphonexr.tracking.NaturalGestureResult
import com.noyclic.questphonexr.tracking.NaturalHandGesture
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
    fun recoveryStateIsExposedInDiagnosticReport() {
        EndXrHandTrackingDiagnostics.begin(320, 240, maxHands = 1, inferenceIntervalMs = 100L)
        EndXrHandTrackingDiagnostics.markRecovering(
            reason = "No MediaPipe result",
            attempt = 2,
            maxAttempts = 3,
            atUptimeMs = 1_234L
        )

        val report = EndXrHandTrackingDiagnostics.report()

        assertEquals("recovering", EndXrHandTrackingDiagnostics.currentState())
        assertTrue(report.contains("service_recovery_attempt=2"))
        assertTrue(report.contains("service_recovery_limit=3"))
        assertTrue(report.contains("service_last_recovery_reason=No MediaPipe result"))
        assertTrue(report.contains("service_last_recovery_at_ms=1234"))
    }

    @Test
    fun naturalGestureRecognitionIsVisibleInDiagnosticsWithoutChangingControllerAbi() {
        EndXrHandTrackingDiagnostics.begin(640, 480, maxHands = 2, inferenceIntervalMs = 100L)
        EndXrHandTrackingDiagnostics.updateNaturalGestures(
            left = NaturalGestureResult(
                gesture = NaturalHandGesture.VICTORY,
                confidence = 0.91f,
                usedWorldLandmarks = true,
                timestampMs = 123L
            ),
            right = NaturalGestureResult.EMPTY
        )

        val report = EndXrHandTrackingDiagnostics.report()

        assertTrue(report.contains("service_left_natural_gesture=VICTORY"))
        assertTrue(report.contains("service_left_natural_gesture_confidence=0.91"))
        assertTrue(report.contains("service_left_natural_gesture_source=world"))
        assertTrue(report.contains("service_right_natural_gesture=NONE"))
    }

    @Test
    fun normalStopChangesRunningServiceToStopped() {
        EndXrHandTrackingDiagnostics.begin(640, 480, maxHands = 2, inferenceIntervalMs = 100L)
        EndXrHandTrackingDiagnostics.markRunning()

        EndXrHandTrackingDiagnostics.markStopped()

        assertEquals("stopped", EndXrHandTrackingDiagnostics.currentState())
    }
}
