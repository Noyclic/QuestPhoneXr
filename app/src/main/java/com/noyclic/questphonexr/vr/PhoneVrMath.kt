package com.noyclic.questphonexr.vr

/** Small row-major 3x3 matrix helpers used to keep the VR preview independent of Android APIs. */
internal object PhoneVrMath {
    fun lensDistortionStrength(percent: Int): Float =
        percent.coerceIn(
            ViewerProfile.MIN_LENS_DISTORTION_PERCENT,
            ViewerProfile.MAX_LENS_DISTORTION_PERCENT
        ) / 100f

    /** Mirrors the radial texture-coordinate warp used by the GLES lens composite shader. */
    fun distortedTextureCoordinate(u: Float, v: Float, strength: Float): FloatArray {
        val centeredX = (u - 0.5f) * 2f
        val centeredY = (v - 0.5f) * 2f
        val radiusSquared = centeredX * centeredX + centeredY * centeredY
        val radialScale = 1f + strength * radiusSquared
        return floatArrayOf(
            0.5f + centeredX * radialScale * 0.5f,
            0.5f + centeredY * radialScale * 0.5f
        )
    }

    /**
     * Spherical interpolation for quaternions stored as [x, y, z, w].
     * q and -q encode the same physical orientation, so the destination sign
     * is adjusted to follow the shortest rotational path.
     */
    fun slerpQuat(a: FloatArray, b: FloatArray, t: Float): FloatArray? {
        val start = normalizedQuaternion(a) ?: return null
        val destination = normalizedQuaternion(b) ?: return null
        if (!t.isFinite()) return null

        val factor = t.coerceIn(0f, 1f)
        val end = destination.copyOf()
        var dot = quaternionDot(start, end)
        if (dot < 0f) {
            dot = -dot
            for (index in 0..3) end[index] = -end[index]
        }
        dot = dot.coerceIn(0f, 1f)

        if (dot > QUATERNION_DOT_THRESHOLD) {
            val linear = FloatArray(4) { index ->
                start[index] + factor * (end[index] - start[index])
            }
            return normalizedQuaternion(linear)
        }

        val theta0 = kotlin.math.acos(dot.toDouble())
        val sinTheta0 = kotlin.math.sin(theta0)
        if (!sinTheta0.isFinite() || kotlin.math.abs(sinTheta0) < QUATERNION_EPSILON) {
            return start
        }

        val theta = theta0 * factor.toDouble()
        val weightA = kotlin.math.sin(theta0 - theta) / sinTheta0
        val weightB = kotlin.math.sin(theta) / sinTheta0
        val result = FloatArray(4) { index ->
            (weightA * start[index].toDouble() +
                weightB * end[index].toDouble()).toFloat()
        }
        return normalizedQuaternion(result)
    }

    /** Returns a normalized copy of [x, y, z, w], or null for invalid input. */
    fun normalizedQuaternion(quaternion: FloatArray): FloatArray? {
        if (quaternion.size < 4 || (0..3).any { !quaternion[it].isFinite() }) return null
        val normSquared = (0..3).sumOf {
            quaternion[it].toDouble() * quaternion[it].toDouble()
        }
        if (!normSquared.isFinite() || normSquared < QUATERNION_EPSILON.toDouble()) return null
        val inverseNorm = 1.0 / kotlin.math.sqrt(normSquared)
        return FloatArray(4) { index -> (quaternion[index] * inverseNorm).toFloat() }
    }

    private fun quaternionDot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

    fun relativeRotation(current: FloatArray, reference: FloatArray?): FloatArray {
        require(current.size >= MATRIX_SIZE) { "Current rotation must contain 9 values." }
        if (reference == null) return current.copyOfRange(0, MATRIX_SIZE)
        require(reference.size >= MATRIX_SIZE) { "Reference rotation must contain 9 values." }

        // Rotation matrices are orthonormal, so inverse(reference) is transpose(reference).
        return multiply(transpose(reference), current)
    }

    private fun transpose(matrix: FloatArray): FloatArray = floatArrayOf(
        matrix[0], matrix[3], matrix[6],
        matrix[1], matrix[4], matrix[7],
        matrix[2], matrix[5], matrix[8]
    )

    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val result = FloatArray(MATRIX_SIZE)
        for (row in 0..2) {
            for (column in 0..2) {
                var value = 0f
                for (index in 0..2) {
                    value += left[row * 3 + index] * right[index * 3 + column]
                }
                result[row * 3 + column] = value
            }
        }
        return result
    }

    private const val MATRIX_SIZE = 9
    private const val QUATERNION_DOT_THRESHOLD = 0.9995f
    private const val QUATERNION_EPSILON = 1e-8f
}
