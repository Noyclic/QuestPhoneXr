package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmposeHandRefinementCoordinatorTest {

    @Test
    fun refinesHandsSequentiallyAndPreservesMediaPipeOnlyMetadata() {
        val engine = FakeEngine()
        val coordinator = RtmposeHandRefinementCoordinator(engine)
        val firstWorld = List(21) { HandPoint(0.01f, 0.02f, 0.03f) }
        val secondWorld = List(21) { HandPoint(0.04f, 0.05f, 0.06f) }
        val original = HandTrackingFrame(
            timestampMs = 200L,
            hands = listOf(
                hand(HandSide.LEFT, firstWorld, 1L),
                hand(HandSide.RIGHT, secondWorld, 2L)
            ),
            pipelineLatencyMs = 18L,
            sourceWidthPx = 640,
            sourceHeightPx = 480
        )
        var outcome: RtmposeRefinementOutcome? = null
        val rawPixels = IntArray(640 * 480) { 0xff445566.toInt() }

        assertTrue(
            coordinator.refine(
                frame = original,
                sourceArgb = rawPixels,
                rawWidthPx = 640,
                rawHeightPx = 480,
                sourceRotationDegrees = 90
            ) { outcome = it.getOrThrow() }
        )

        val result = requireNotNull(outcome)
        assertEquals(2, result.refinedHands)
        assertEquals(0, result.skippedHands)
        assertEquals(2, engine.submittedCrops.size)
        assertEquals(HandSide.LEFT, result.frame.hands[0].side)
        assertEquals(HandSide.RIGHT, result.frame.hands[1].side)
        assertEquals(firstWorld, result.frame.hands[0].worldPoints)
        assertEquals(secondWorld, result.frame.hands[1].worldPoints)
        assertEquals(original.hands[0].trackingId, result.frame.hands[0].trackingId)
        assertEquals(original.hands[1].pinchStrength, result.frame.hands[1].pinchStrength, 0f)
        assertEquals(42L, result.frame.pipelineLatencyMs)
        assertEquals(24L, result.addedInferenceLatencyMs)
        assertEquals(0.5f, result.roiQualityScore!!, 0.0001f)
        assertTrue(engine.maxSimultaneousSubmissions == 1)
    }

    @Test
    fun failedCropKeepsMediaPipeHandAndContinuesToNextHand() {
        val engine = FakeEngine(failFirst = true)
        val coordinator = RtmposeHandRefinementCoordinator(engine)
        val original = HandTrackingFrame(
            timestampMs = 300L,
            hands = listOf(
                hand(HandSide.LEFT, emptyList(), 1L),
                hand(HandSide.RIGHT, emptyList(), 2L)
            )
        )
        var result: RtmposeRefinementOutcome? = null

        assertTrue(
            coordinator.refine(
                original,
                IntArray(640 * 480) { 0xff223344.toInt() },
                640,
                480,
                0
            ) { result = it.getOrThrow() }
        )

        val outcome = requireNotNull(result)
        assertEquals(1, outcome.refinedHands)
        assertEquals(1, outcome.skippedHands)
        assertEquals(original.hands[0].points, outcome.frame.hands[0].points)
        assertEquals(HandSide.LEFT, outcome.frame.hands[0].side)
        assertEquals(HandSide.RIGHT, outcome.frame.hands[1].side)
        assertEquals(12L, outcome.addedInferenceLatencyMs)
    }

    @Test
    fun rejectsConcurrentFrameAndInvalidRotationWithoutHoldingTheGate() {
        val pendingEngine = FakeEngine(asyncCallbacks = true)
        val coordinator = RtmposeHandRefinementCoordinator(pendingEngine)
        val frame = HandTrackingFrame(
            timestampMs = 10L,
            hands = listOf(hand(HandSide.LEFT, emptyList(), 1L))
        )
        val source = IntArray(640 * 480) { 0xff010203.toInt() }

        assertFalse(
            coordinator.refine(frame, source, 640, 480, 45) { }
        )
        assertTrue(coordinator.refine(frame, source, 640, 480, 0) { })
        assertFalse(coordinator.refine(frame.copy(timestampMs = 11L), source, 640, 480, 0) { })
        pendingEngine.completePendingSuccess()
        assertTrue(coordinator.refine(frame.copy(timestampMs = 12L), source, 640, 480, 0) { })
    }

    @Test
    fun emptyFrameCompletesWithoutInvokingLiteRt() {
        val engine = FakeEngine()
        val coordinator = RtmposeHandRefinementCoordinator(engine)
        val frame = HandTrackingFrame(timestampMs = 1L, hands = emptyList())
        var outcome: RtmposeRefinementOutcome? = null

        assertTrue(
            coordinator.refine(
                frame,
                IntArray(640 * 480),
                640,
                480,
                0
            ) { outcome = it.getOrThrow() }
        )
        assertEquals(0, requireNotNull(outcome).refinedHands)
        assertEquals(0, engine.submittedCrops.size)
        assertNull(outcome?.roiQualityScore)
    }

    private fun hand(side: HandSide, world: List<HandPoint>, id: Long): TrackedHand =
        TrackedHand(
            points = List(21) { index ->
                val x = if (index % 2 == 0) 0.2f else 0.8f
                val y = if (index % 3 == 0) 0.25f else 0.75f
                HandPoint(x, y, 0f)
            },
            pinchStrength = 0.73f,
            isPinching = true,
            side = side,
            worldPoints = world,
            trackingId = id,
            rawPoints = List(21) { HandPoint(0.1f, 0.1f, 0f) }
        )

    private class FakeEngine(
        private val failFirst: Boolean = false,
        private val asyncCallbacks: Boolean = false
    ) : RtmposeHandInferenceEngine {
        private var pending: (() -> Unit)? = null
        private var submitted = 0
        var maxSimultaneousSubmissions = 0
            private set
        private var inFlight = 0
        val submittedCrops = mutableListOf<HandRoiCrop>()
        private val input = FloatArray(RtmposeLiteRtRunner.INPUT_ELEMENT_COUNT)

        override fun acquireInputBuffer(): FloatArray? =
            if (inFlight == 0) input else null

        override fun discardInputBuffer() = Unit

        override fun submit(
            crop: HandRoiCrop,
            frameTimestampMs: Long,
            roiConfidence: Float,
            callback: (Result<RtmposeInferenceResult>) -> Unit
        ) {
            inFlight += 1
            maxSimultaneousSubmissions = maxOf(maxSimultaneousSubmissions, inFlight)
            submittedCrops += crop
            submitted += 1
            val shouldFail = failFirst && submitted == 1
            val completion = {
                inFlight -= 1
                if (shouldFail) {
                    callback(Result.failure(IllegalStateException("synthetic failure")))
                } else {
                    val refinedPoints = List(21) { HandPoint(0.4f, 0.6f, 0f) }
                    val decodedFrame = HandTrackingFrame(
                        timestampMs = frameTimestampMs,
                        hands = listOf(TrackedHand(refinedPoints))
                    )
                    callback(
                        Result.success(
                            RtmposeInferenceResult(
                                decoded = RtmposeHandDecodeResult.Success(
                                    frame = decodedFrame,
                                    qualityScore = roiConfidence
                                ),
                                endToEndInferenceLatencyMs = 12L
                            )
                        )
                    )
                }
            }
            if (asyncCallbacks) pending = completion else completion()
        }

        fun completePendingSuccess() {
            pending?.invoke()
            pending = null
        }
    }
}
