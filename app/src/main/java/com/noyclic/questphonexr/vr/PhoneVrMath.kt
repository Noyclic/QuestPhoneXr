package com.noyclic.questphonexr.vr

/** Small row-major 3x3 matrix helpers used to keep the VR preview independent of Android APIs. */
internal object PhoneVrMath {
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
}
