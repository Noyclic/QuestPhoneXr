package com.noyclic.questphonexr.tracking

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Builds OpenXR's 26-point hand skeleton from MediaPipe's 21 world landmarks.
 *
 * The five added points are the palm center and four estimated metacarpal
 * bases. A per-joint adaptive temporal filter suppresses camera jitter while
 * increasing its response as a joint moves faster. This is reconstruction,
 * not an additional sensor measurement.
 */
internal class HandKinematicReconstructor {
    private val boneLengthReferences = FloatArray(FINGER_BONE_SEGMENTS.size) { Float.NaN }
    private val palmGapReferences = FloatArray(PALM_GAP_PAIRS.size) { Float.NaN }
    private var previousRaw: List<HandPoint>? = null
    private var previousFiltered: List<HandPoint>? = null
    private var previousTimestampMs: Long = Long.MIN_VALUE
    private var previousTrackingId: Long = Long.MIN_VALUE

    @Synchronized
    fun reconstruct(
        landmarks: List<HandPoint>,
        trackingId: Long,
        timestampMs: Long
    ): List<HandPoint>? {
        if (landmarks.size < LANDMARK_COUNT ||
            landmarks.take(LANDMARK_COUNT).any {
                !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite()
            }
        ) {
            reset()
            return null
        }

        val wrist = landmarks[0]
        fun between(target: HandPoint, amount: Float) = HandPoint(
            wrist.x + (target.x - wrist.x) * amount,
            wrist.y + (target.y - wrist.y) * amount,
            wrist.z + (target.z - wrist.z) * amount
        )
        val palmBases = intArrayOf(5, 9, 13, 17)
        val palm = HandPoint(
            palmBases.sumOf { landmarks[it].x.toDouble() }.div(4.0).toFloat(),
            palmBases.sumOf { landmarks[it].y.toDouble() }.div(4.0).toFloat(),
            palmBases.sumOf { landmarks[it].z.toDouble() }.div(4.0).toFloat()
        )
        val raw = listOf(
            palm, wrist,
            landmarks[1], landmarks[2], landmarks[3], landmarks[4],
            between(landmarks[5], METACARPAL_BASE_RATIO), landmarks[5],
            landmarks[6], landmarks[7], landmarks[8],
            between(landmarks[9], METACARPAL_BASE_RATIO), landmarks[9],
            landmarks[10], landmarks[11], landmarks[12],
            between(landmarks[13], METACARPAL_BASE_RATIO), landmarks[13],
            landmarks[14], landmarks[15], landmarks[16],
            between(landmarks[17], METACARPAL_BASE_RATIO), landmarks[17],
            landmarks[18], landmarks[19], landmarks[20]
        )

        val previous = previousFiltered
        val previousInput = previousRaw
        val elapsed = timestampMs - previousTimestampMs
        val mustReset = previous == null || previousInput == null ||
            trackingId != previousTrackingId || elapsed !in 1L..MAX_FILTER_GAP_MS
        val filtered = if (mustReset) {
            raw
        } else {
            val dtSeconds = elapsed / 1000f
            raw.indices.map { index ->
                val oldRaw = previousInput[index]
                val oldFiltered = previous[index]
                val current = raw[index]
                val speed = distance(oldRaw, current) / dtSeconds
                val alpha = (BASE_ALPHA + (speed / FAST_MOTION_SCALE) * FAST_ALPHA_GAIN)
                    .coerceIn(BASE_ALPHA, MAX_ALPHA)
                HandPoint(
                    oldFiltered.x + (current.x - oldFiltered.x) * alpha,
                    oldFiltered.y + (current.y - oldFiltered.y) * alpha,
                    oldFiltered.z + (current.z - oldFiltered.z) * alpha
                )
            }
        }

        // Apply a light kinematic constraint after temporal filtering. MediaPipe's
        // monocular world landmarks can make a finger bone appear to stretch for one
        // frame; keeping a slowly learned segment-length reference stabilizes the
        // OpenXR skeleton without changing the raw landmarks used by gestures.
        val filterDeltaSeconds = if (mustReset) {
            DEFAULT_FILTER_STEP_SECONDS
        } else {
            (elapsed / 1_000f).coerceIn(MIN_FILTER_STEP_SECONDS, MAX_FILTER_STEP_SECONDS)
        }
        val palmStabilized = stabilizePalmKnuckleSpacing(
            filtered,
            resetReference = mustReset,
            deltaSeconds = filterDeltaSeconds
        )
        val stabilized = constrainFingerBoneLengths(
            palmStabilized,
            resetReference = mustReset,
            deltaSeconds = filterDeltaSeconds
        )

        previousRaw = raw
        previousFiltered = stabilized
        previousTimestampMs = timestampMs
        previousTrackingId = trackingId
        return stabilized
    }

    /**
     * Keeps adjacent knuckle spacing from jumping independently for one frame.
     *
     * Each correction translates a complete finger chain, rather than moving a
     * single MCP joint. This preserves the finger's measured articulation and
     * all within-finger bone lengths. The learned spacing adapts continuously so
     * intentional finger splay is allowed instead of being locked to one pose.
     */
    private fun stabilizePalmKnuckleSpacing(
        points: List<HandPoint>,
        resetReference: Boolean,
        deltaSeconds: Float
    ): List<HandPoint> {
        if (resetReference) palmGapReferences.fill(Float.NaN)
        if (points.size != JOINT_COUNT) {
            palmGapReferences.fill(Float.NaN)
            return points
        }

        val output = points.toMutableList()
        val adaptationAlpha = (
            1.0 - exp((-deltaSeconds / PALM_GAP_ADAPTATION_SECONDS).toDouble())
        ).toFloat().coerceIn(0f, MAX_PALM_GAP_ADAPTATION_ALPHA)

        // Learn from the uncorrected, temporally filtered shape before the
        // pairwise corrections move any of the finger chains.
        for (pairIndex in PALM_GAP_PAIRS.indices) {
            val pair = PALM_GAP_PAIRS[pairIndex]
            val measured = distance(points[pair.leftMcp], points[pair.rightMcp])
            if (!measured.isFinite() || measured < MIN_PALM_GAP_METERS) continue

            val reference = palmGapReferences[pairIndex]
            if (!reference.isFinite() || reference < MIN_PALM_GAP_METERS) {
                palmGapReferences[pairIndex] = measured
            } else {
                palmGapReferences[pairIndex] =
                    reference + (measured - reference) * adaptationAlpha
            }
        }

        // Two inexpensive position-based passes reduce coupling between the
        // three neighboring gaps that share middle/ring finger chains.
        repeat(PALM_GAP_SOLVER_ITERATIONS) {
            for (pairIndex in PALM_GAP_PAIRS.indices) {
                val pair = PALM_GAP_PAIRS[pairIndex]
                val reference = palmGapReferences[pairIndex]
                if (!reference.isFinite() || reference < MIN_PALM_GAP_METERS) continue

                val left = output[pair.leftMcp]
                val right = output[pair.rightMcp]
                val dx = right.x - left.x
                val dy = right.y - left.y
                val dz = right.z - left.z
                val measured = sqrt(dx * dx + dy * dy + dz * dz)
                if (!measured.isFinite() || measured < MIN_PALM_GAP_METERS) continue

                val allowed = reference * (measured / reference).coerceIn(
                    MIN_PALM_GAP_RATIO,
                    MAX_PALM_GAP_RATIO
                )
                val corrected = measured + (allowed - measured) * PALM_GAP_CORRECTION_WEIGHT
                val halfShift = (corrected - measured) * 0.5f / measured
                val shiftX = dx * halfShift
                val shiftY = dy * halfShift
                val shiftZ = dz * halfShift

                translateFingerChain(output, pair.leftChainStart, pair.leftChainEnd,
                    -shiftX, -shiftY, -shiftZ)
                translateFingerChain(output, pair.rightChainStart, pair.rightChainEnd,
                    shiftX, shiftY, shiftZ)
            }
        }

        // The palm joint is defined as the center of the four non-thumb MCPs.
        // Recompute it after moving complete finger chains so the PALM anchor
        // cannot lag behind the corrected knuckle constellation.
        val knuckleIndices = PALM_KNUCKLE_INDICES
        val knuckleCount = knuckleIndices.size.toDouble()
        output[PALM_JOINT_INDEX] = HandPoint(
            (knuckleIndices.sumOf { output[it].x.toDouble() } / knuckleCount).toFloat(),
            (knuckleIndices.sumOf { output[it].y.toDouble() } / knuckleCount).toFloat(),
            (knuckleIndices.sumOf { output[it].z.toDouble() } / knuckleCount).toFloat()
        )
        return output
    }

    private fun translateFingerChain(
        points: MutableList<HandPoint>,
        startIndex: Int,
        endIndex: Int,
        x: Float,
        y: Float,
        z: Float
    ) {
        for (index in startIndex..endIndex) {
            val point = points[index]
            points[index] = HandPoint(point.x + x, point.y + y, point.z + z)
        }
    }

    /**
     * Softly limits temporal bone-length wobble on the five finger phalange chains.
     *
     * This is deliberately conservative: it corrects only 35% of the difference,
     * allows an 18% per-frame length envelope, and slowly adapts the reference when
     * measurements remain plausible. Raw MediaPipe data and pinch decisions are
     * untouched; only the reconstructed OpenXR joint positions use this constraint.
     */
    private fun constrainFingerBoneLengths(
        points: List<HandPoint>,
        resetReference: Boolean,
        deltaSeconds: Float
    ): List<HandPoint> {
        if (resetReference) boneLengthReferences.fill(Float.NaN)
        if (points.size != JOINT_COUNT) {
            boneLengthReferences.fill(Float.NaN)
            return points
        }

        val output = points.toMutableList()
        for (segmentIndex in FINGER_BONE_SEGMENTS.indices) {
            val (parentIndex, childIndex) = FINGER_BONE_SEGMENTS[segmentIndex]
            val parent = output[parentIndex]
            val child = output[childIndex]
            val dx = child.x - parent.x
            val dy = child.y - parent.y
            val dz = child.z - parent.z
            val measuredLength = sqrt(dx * dx + dy * dy + dz * dz)
            if (!measuredLength.isFinite() || measuredLength < MIN_BONE_LENGTH_METERS) {
                continue
            }

            var referenceLength = boneLengthReferences[segmentIndex]
            if (!referenceLength.isFinite() || referenceLength <= MIN_BONE_LENGTH_METERS) {
                boneLengthReferences[segmentIndex] = measuredLength
                continue
            }

            val ratio = measuredLength / referenceLength
            if (ratio in MIN_REFERENCE_ADAPTATION_RATIO..MAX_REFERENCE_ADAPTATION_RATIO) {
                // Use elapsed time, not frames, so adaptation remains consistent
                // when the phone changes its camera/inference rate under load.
                val adaptationAlpha = (
                    1f - exp((-deltaSeconds / REFERENCE_ADAPTATION_SECONDS).toDouble()).toFloat()
                ).coerceIn(0f, MAX_REFERENCE_ADAPTATION_ALPHA)
                referenceLength += (measuredLength - referenceLength) * adaptationAlpha
                boneLengthReferences[segmentIndex] = referenceLength
            }

            val boundedTargetLength = referenceLength * ratio.coerceIn(
                MIN_BONE_LENGTH_RATIO,
                MAX_BONE_LENGTH_RATIO
            )
            val correctedLength = measuredLength +
                (boundedTargetLength - measuredLength) * BONE_CORRECTION_WEIGHT
            val scale = correctedLength / measuredLength
            output[childIndex] = HandPoint(
                parent.x + dx * scale,
                parent.y + dy * scale,
                parent.z + dz * scale
            )
        }
        return output
    }

    @Synchronized
    fun reset() {
        previousRaw = null
        previousFiltered = null
        previousTimestampMs = Long.MIN_VALUE
        previousTrackingId = Long.MIN_VALUE
        boneLengthReferences.fill(Float.NaN)
        palmGapReferences.fill(Float.NaN)
    }

    private fun distance(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        // OpenXR joint indices: thumb 2..5, index 6..10, middle 11..15,
        // ring 17..20 and little finger 22..25. Constrain only measured
        // phalange segments; estimated base joints remain softly anchored to the MCPs.
        private data class PalmGapPair(
            val leftMcp: Int,
            val rightMcp: Int,
            val leftChainStart: Int,
            val leftChainEnd: Int,
            val rightChainStart: Int,
            val rightChainEnd: Int
        )
        // Proximal joints are MCP landmarks in the reconstructed OpenXR order.
        // Only adjacent non-thumb knuckle gaps are constrained; thumb opposition
        // and independent finger articulation remain free.
        val PALM_GAP_PAIRS = arrayOf(
            PalmGapPair(7, 12, 6, 10, 11, 15),
            PalmGapPair(12, 17, 11, 15, 16, 20),
            PalmGapPair(17, 22, 16, 20, 21, 25)
        )
        val FINGER_BONE_SEGMENTS = arrayOf(
            2 to 3, 3 to 4, 4 to 5,
            6 to 7, 7 to 8, 8 to 9, 9 to 10,
            11 to 12, 12 to 13, 13 to 14, 14 to 15,
            16 to 17, 17 to 18, 18 to 19, 19 to 20,
            21 to 22, 22 to 23, 23 to 24, 24 to 25
        )
        const val PALM_JOINT_INDEX = 0
        val PALM_KNUCKLE_INDICES = intArrayOf(7, 12, 17, 22)
        const val MIN_PALM_GAP_METERS = 0.002f
        const val MIN_PALM_GAP_RATIO = 0.86f
        const val MAX_PALM_GAP_RATIO = 1.14f
        const val PALM_GAP_CORRECTION_WEIGHT = 0.35f
        const val PALM_GAP_ADAPTATION_SECONDS = 0.70f
        const val MAX_PALM_GAP_ADAPTATION_ALPHA = 0.06f
        const val PALM_GAP_SOLVER_ITERATIONS = 2
        const val MIN_BONE_LENGTH_METERS = 0.001f
        const val MIN_REFERENCE_ADAPTATION_RATIO = 0.75f
        const val MAX_REFERENCE_ADAPTATION_RATIO = 1.25f
        const val REFERENCE_ADAPTATION_SECONDS = 4.0f
        const val MAX_REFERENCE_ADAPTATION_ALPHA = 0.05f
        const val MIN_BONE_LENGTH_RATIO = 0.82f
        const val MAX_BONE_LENGTH_RATIO = 1.18f
        const val BONE_CORRECTION_WEIGHT = 0.35f
        const val JOINT_COUNT = 26
        const val LANDMARK_COUNT = 21
        const val METACARPAL_BASE_RATIO = 0.58f
        const val BASE_ALPHA = 0.36f
        const val FAST_ALPHA_GAIN = 0.58f
        const val FAST_MOTION_SCALE = 1.4f
        const val MAX_ALPHA = 0.94f
        const val MAX_FILTER_GAP_MS = 220L
        const val MIN_FILTER_STEP_SECONDS = 1f / 240f
        const val MAX_FILTER_STEP_SECONDS = 0.1f
        const val DEFAULT_FILTER_STEP_SECONDS = 1f / 60f
    }
}
