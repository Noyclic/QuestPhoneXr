package com.noyclic.questphonexr.sandbox

/**
 * Low-cost anatomy proportions for the Sandbox's rounded 3D hand primitives.
 *
 * MediaPipe supplies the joint centres; the renderer turns each adjacent pair into an
 * overlapping, tapered capsule. Keeping the topology here makes the five digits and their
 * proportions deterministic and straightforward to regression-test.
 */
internal object SandboxHandMeshGeometry {
    /** Visual-only shrink factor; tracking and interaction landmarks remain untouched. */
    const val VISUAL_SCALE = 0.78f

    fun scaleVisualCoordinate(value: Float, center: Float): Float =
        center + (value - center) * VISUAL_SCALE

    const val MAX_PRIMITIVES_PER_HAND = 24
    const val BASE_PRIMITIVES_PER_HAND = 22

    val FINGER_CHAINS = arrayOf(
        intArrayOf(1, 2, 3, 4),       // thumb
        intArrayOf(5, 6, 7, 8),       // index
        intArrayOf(9, 10, 11, 12),    // middle
        intArrayOf(13, 14, 15, 16),  // ring
        intArrayOf(17, 18, 19, 20)   // little
    )

    val THUMB_WEB = intArrayOf(2, 5)

    val PALM_LINKS = arrayOf(
        intArrayOf(0, 5),
        intArrayOf(5, 9),
        intArrayOf(9, 13),
        intArrayOf(13, 17),
        intArrayOf(17, 0)
    )

    private val DIGIT_RADIUS_SCALE = floatArrayOf(
        1.00f, // thumb
        0.92f, // index
        0.98f, // middle
        0.90f, // ring
        0.78f  // little
    )

    fun fingerSegmentRadius(palmWidth: Float, digitIndex: Int, segmentIndex: Int): Float {
        val width = palmWidth.takeIf { it.isFinite() }?.coerceIn(0.12f, 0.62f)
            ?: 0.12f
        val digitScale = DIGIT_RADIUS_SCALE.getOrElse(digitIndex) { 0.85f }
        val taper = (1f - segmentIndex.coerceAtLeast(0) * 0.085f).coerceAtLeast(0.74f)
        return (width * FINGER_BASE_RADIUS_RATIO * digitScale * taper)
            .coerceIn(MIN_FINGER_RADIUS, MAX_FINGER_RADIUS)
    }

    fun palmLinkRadius(palmWidth: Float): Float =
        ((palmWidth.takeIf { it.isFinite() } ?: 0.12f) * PALM_LINK_RADIUS_RATIO)
            .coerceIn(MIN_PALM_LINK_RADIUS, MAX_PALM_LINK_RADIUS)

    private const val FINGER_BASE_RADIUS_RATIO = 0.052f
    private const val PALM_LINK_RADIUS_RATIO = 0.039f
    private const val MIN_FINGER_RADIUS = 0.012f
    private const val MAX_FINGER_RADIUS = 0.029f
    private const val MIN_PALM_LINK_RADIUS = 0.014f
    private const val MAX_PALM_LINK_RADIUS = 0.030f
}
