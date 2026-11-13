package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import com.noyclic.questphonexr.tracking.HandTrackingFrame
import com.noyclic.questphonexr.tracking.TrackingProviderAvailability
import com.noyclic.questphonexr.tracking.TrackingProviderId
import com.noyclic.questphonexr.tracking.TrackingProviderManager
import com.noyclic.questphonexr.tracking.TrackingProviderManagerSnapshot

/**
 * Common entrance from every tracking implementation into the existing EndXR input path.
 *
 * Provider-specific code stops at HandTrackingFrame. This bridge owns arbitration and then
 * sends only the selected fresh frame through the shared gesture/pose/joint encoder.
 */
internal object EndXrTrackingInputBridge {
    private val providerManager = TrackingProviderManager()

    fun publish(
        providerId: TrackingProviderId,
        frame: HandTrackingFrame,
        qualityScore: Float = 1f
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val selected = providerManager.publishFrame(
            providerId = providerId,
            frame = frame,
            qualityScore = qualityScore,
            receivedAtUptimeMs = now
        ) ?: return false

        EndXrHandTrackingDiagnostics.onFrame(selected.frame, selected.providerId.wireId)
        val encoded = EndXrHandInputEncoder.encode(selected.frame)
        EndXrHandTrackingDiagnostics.updateNaturalGestures(
            encoded.leftGesture,
            encoded.rightGesture
        )
        EndXrInputSnapshotStore.publishTrackingFrame(
            leftRow = encoded.leftRow,
            rightRow = encoded.rightRow,
            leftPose = encoded.leftPose,
            rightPose = encoded.rightPose,
            leftJoints = encoded.leftJoints,
            rightJoints = encoded.rightJoints
        )
        return true
    }

    fun selectProvider(providerId: TrackingProviderId): Boolean {
        val selected = providerManager.selectProvider(providerId)
        if (selected) EndXrInputSnapshotStore.clear()
        return selected
    }

    fun setAutoMode() {
        providerManager.setAutoMode()
        EndXrInputSnapshotStore.clear()
    }

    fun updateProviderAvailability(
        providerId: TrackingProviderId,
        availability: TrackingProviderAvailability
    ): Boolean {
        val updated = providerManager.updateAvailability(providerId, availability)
        if (updated) EndXrInputSnapshotStore.clear()
        return updated
    }

    fun clearFrames() = providerManager.clearFrames()

    fun diagnosticSnapshot(): TrackingProviderManagerSnapshot =
        providerManager.snapshot(SystemClock.uptimeMillis())
}
