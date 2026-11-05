package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class EndXrHandTrackingDiagnosticsTest {

    @Test
    fun stopPreservesCameraStartFailureForDiagnostics() {
        EndXrHandTrackingDiagnostics.begin(640, 480)
        EndXrHandTrackingDiagnostics.markError("CameraX could not bind")

        EndXrHandTrackingDiagnostics.markStopped()

        assertEquals("error", EndXrHandTrackingDiagnostics.currentState())
    }

    @Test
    fun normalStopChangesRunningServiceToStopped() {
        EndXrHandTrackingDiagnostics.begin(640, 480)
        EndXrHandTrackingDiagnostics.markRunning()

        EndXrHandTrackingDiagnostics.markStopped()

        assertEquals("stopped", EndXrHandTrackingDiagnostics.currentState())
    }
}
