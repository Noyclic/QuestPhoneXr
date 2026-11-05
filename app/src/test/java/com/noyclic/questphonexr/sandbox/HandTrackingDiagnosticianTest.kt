package com.noyclic.questphonexr.sandbox

import com.noyclic.questphonexr.tracking.HandTrackingMetricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTrackingDiagnosticianTest {
    private fun metrics(
        received: Long, submitted: Long, completed: Long, busy: Long = 0L,
        luma: Float = 120f, stddev: Float = 40f
    ) = HandTrackingMetricsSnapshot(
        receivedFrames = received,
        submittedFrames = submitted,
        completedFrames = completed,
        droppedBusyFrames = busy,
        droppedIntervalFrames = 0L,
        lastPreprocessingMs = 3L,
        averagePreprocessingMs = 3f,
        lastFrameWidthPx = 640,
        lastFrameHeightPx = 480,
        lastFrameLumaMean = luma,
        lastFrameLumaStdDev = stddev
    )

    private fun input(
        permission: Boolean = true,
        model: Long = 8_000_000L,
        anyCamera: Boolean = true,
        frontCamera: Boolean = true,
        owner: HandTrackingOwner = HandTrackingOwner.SANDBOX,
        before: HandTrackingMetricsSnapshot? = metrics(0, 0, 0),
        after: HandTrackingMetricsSnapshot? = metrics(120, 40, 40),
        hands: Int = 1,
        interval: Long = 0L
    ) = HandDiagnosisInput(
        cameraPermissionGranted = permission,
        modelAssetBytes = model,
        hasAnyCamera = anyCamera,
        hasFrontCamera = frontCamera,
        owner = owner,
        windowMs = 4_000L,
        before = before,
        after = after,
        maxHandsSeen = hands,
        lastPipelineLatencyMs = 40L,
        inferenceIntervalMs = interval
    )

    @Test fun healthyPipelineReportsAllGood() {
        assertEquals("всё работает", HandTrackingDiagnostician.diagnose(input()).verdict)
    }

    @Test fun missingPermissionIsTheVerdict() {
        val result = HandTrackingDiagnostician.diagnose(
            input(permission = false, owner = HandTrackingOwner.NONE, before = null, after = null)
        )
        assertEquals("Нет разрешения на камеру", result.verdict)
    }

    @Test fun missingModelIsReported() {
        val result = HandTrackingDiagnostician.diagnose(input(model = -1L))
        assertTrue(result.verdict.contains("hand_landmarker.task"))
    }

    @Test fun missingCameraIsReported() {
        val result = HandTrackingDiagnostician.diagnose(input(anyCamera = false))
        assertEquals("Android не сообщает о наличии камеры", result.verdict)
    }

    @Test fun noCameraFramesIsFail() {
        val result = HandTrackingDiagnostician.diagnose(input(after = metrics(0, 0, 0)))
        assertTrue(result.verdict.startsWith("Камера не передала"))
    }

    @Test fun stuckModelIsDetected() {
        val result = HandTrackingDiagnostician.diagnose(
            input(after = metrics(120, 40, 0, busy = 80))
        )
        assertTrue(result.verdict.contains("не вернул ни одного результата"))
    }

    @Test fun blackFrameIsFail() {
        val result = HandTrackingDiagnostician.diagnose(
            input(after = metrics(120, 40, 40, luma = 2f, stddev = 0f))
        )
        assertTrue(result.verdict.startsWith("Кадр почти чёрный"))
    }

    @Test fun workingModelWithoutHandsWarns() {
        val result = HandTrackingDiagnostician.diagnose(input(hands = 0))
        assertEquals("MediaPipe работает, но руки не найдены", result.verdict)
    }

    @Test fun economyIntervalWarns() {
        val result = HandTrackingDiagnostician.diagnose(input(interval = 140L))
        assertTrue(result.verdict.startsWith("Интервал обработки увеличен"))
    }
}
