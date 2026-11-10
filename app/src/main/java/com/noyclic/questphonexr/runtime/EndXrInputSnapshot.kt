package com.noyclic.questphonexr.runtime

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Orientation-only phone rotation-vector sample; it contains no absolute 6DoF position. */
internal data class EndXrHeadOrientationSample(
    val active: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val w: Float = 1f
) {
    companion object {
        val EMPTY = EndXrHeadOrientationSample()

        fun fromQuaternion(x: Float, y: Float, z: Float, w: Float): EndXrHeadOrientationSample {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return EMPTY
            val magnitude = sqrt(
                x.toDouble() * x + y.toDouble() * y +
                    z.toDouble() * z + w.toDouble() * w
            )
            if (!magnitude.isFinite() || magnitude < 0.5 || magnitude > 1.5) return EMPTY
            return EndXrHeadOrientationSample(
                active = true,
                x = (x / magnitude).toFloat(),
                y = (y / magnitude).toFloat(),
                z = (z / magnitude).toFloat(),
                w = (w / magnitude).toFloat()
            )
        }
    }
}

/** A short-lived controller sample exposed to the external OpenXR process. */
internal data class EndXrControllerSample(
    val active: Boolean = false,
    val trigger: Float = 0f,
    val grip: Float = 0f,
    val stickX: Float = 0f,
    val stickY: Float = 0f,
    val buttons: Int = 0
) {
    companion object {
        fun fromNativeRow(row: FloatArray): EndXrControllerSample {
            require(row.size >= 6) { "EndXR controller row must contain six values." }
            fun finite(value: Float): Float = if (value.isFinite()) value else 0f
            return EndXrControllerSample(
                active = finite(row[0]) >= 0.5f,
                trigger = finite(row[1]).coerceIn(0f, 1f),
                grip = finite(row[2]).coerceIn(0f, 1f),
                stickX = finite(row[3]).coerceIn(-1f, 1f),
                stickY = finite(row[4]).coerceIn(-1f, 1f),
                buttons = finite(row[5]).coerceAtLeast(0f).roundToInt()
            ).let { sample -> if (sample.active) sample else EMPTY }
        }

        val EMPTY = EndXrControllerSample()
    }
}

/**
 * Camera-derived palm pose. Its depth is monocular and estimated; it is not
 * metric 6DoF tracking and therefore never claims OpenXR TRACKED location flags.
 */
internal data class EndXrHandPoseSample(
    val active: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f
) {
    companion object {
        val EMPTY = EndXrHandPoseSample()

        fun fromCameraPose(
            x: Float, y: Float, z: Float,
            qx: Float, qy: Float, qz: Float, qw: Float
        ): EndXrHandPoseSample {
            val values = floatArrayOf(x, y, z, qx, qy, qz, qw)
            if (!values.all(Float::isFinite)) return EMPTY
            if (kotlin.math.abs(x) > 8f || kotlin.math.abs(y) > 6f ||
                z > -0.25f || z < -6f
            ) return EMPTY
            val magnitude = sqrt(
                qx.toDouble() * qx + qy.toDouble() * qy +
                    qz.toDouble() * qz + qw.toDouble() * qw
            )
            if (!magnitude.isFinite() || magnitude < 0.5 || magnitude > 1.5) return EMPTY
            return EndXrHandPoseSample(
                active = true, x = x, y = y, z = z,
                qx = (qx / magnitude).toFloat(),
                qy = (qy / magnitude).toFloat(),
                qz = (qz / magnitude).toFloat(),
                qw = (qw / magnitude).toFloat()
            )
        }
    }
}

/**
 * Compact OpenXR joint buffer. Four floats per joint: palm-local position XYZ
 * and collision radius. Native runtime composes each point with the current palm
 * pose and supplies its orientation. The payload is fixed-size for cheap IPC.
 */
internal data class EndXrHandJointSample(
    val active: Boolean = false,
    val values: FloatArray = FloatArray(FLOAT_COUNT)
) {
    fun toBlob(): ByteArray {
        val buffer = ByteBuffer.allocate(BLOB_SIZE).order(ByteOrder.nativeOrder())
        val source = if (active && values.size == FLOAT_COUNT) values else FloatArray(FLOAT_COUNT)
        source.forEach { buffer.putFloat(if (it.isFinite()) it else 0f) }
        return buffer.array()
    }

    companion object {
        const val JOINT_COUNT = 26
        const val FLOATS_PER_JOINT = 8
        const val FLOAT_COUNT = JOINT_COUNT * FLOATS_PER_JOINT
        const val BLOB_SIZE = FLOAT_COUNT * Float.SIZE_BYTES

        val EMPTY = EndXrHandJointSample()

        fun fromValues(values: FloatArray): EndXrHandJointSample {
            if (values.size != FLOAT_COUNT || !values.all(Float::isFinite)) return EMPTY
            for (index in 0 until JOINT_COUNT) {
                val offset = index * FLOATS_PER_JOINT
                if (kotlin.math.abs(values[offset]) > 0.24f ||
                    kotlin.math.abs(values[offset + 1]) > 0.24f ||
                    kotlin.math.abs(values[offset + 2]) > 0.24f ||
                    values[offset + 3] !in 0.001f..0.04f
                ) return EMPTY
                val qx = values[offset + 4]
                val qy = values[offset + 5]
                val qz = values[offset + 6]
                val qw = values[offset + 7]
                val magnitude = sqrt(
                    qx.toDouble() * qx + qy.toDouble() * qy +
                        qz.toDouble() * qz + qw.toDouble() * qw
                )
                if (!magnitude.isFinite() || magnitude < 0.5 || magnitude > 1.5) return EMPTY
                values[offset + 4] = (qx / magnitude).toFloat()
                values[offset + 5] = (qy / magnitude).toFloat()
                values[offset + 6] = (qz / magnitude).toFloat()
                values[offset + 7] = (qw / magnitude).toFloat()
            }
            return EndXrHandJointSample(true, values.copyOf())
        }

        fun fromBlob(bytes: ByteArray?): EndXrHandJointSample {
            if (bytes == null || bytes.size != BLOB_SIZE) return EMPTY
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val values = FloatArray(FLOAT_COUNT) { buffer.float }
            return fromValues(values)
        }
    }
}

internal data class EndXrInputSnapshot(
    val sequence: Long,
    val updatedAtElapsedRealtimeNanos: Long,
    val left: EndXrControllerSample,
    val right: EndXrControllerSample,
    val stale: Boolean,
    val headOrientation: EndXrHeadOrientationSample,
    val headOrientationUpdatedAtElapsedRealtimeNanos: Long,
    val headOrientationStale: Boolean,
    val leftHandPose: EndXrHandPoseSample,
    val rightHandPose: EndXrHandPoseSample,
    val leftHandJoints: EndXrHandJointSample,
    val rightHandJoints: EndXrHandJointSample,
    val handPoseUpdatedAtElapsedRealtimeNanos: Long,
    val handPoseStale: Boolean
)

/**
 * In-memory only: input never survives a process restart. Independent callback
 * producers use CAS updates so camera and sensor publication cannot overwrite each other.
 */
internal object EndXrInputSnapshotStore {
    const val MAX_SAMPLE_AGE_NANOS = 300_000_000L
    const val MAX_HEAD_ORIENTATION_AGE_NANOS = 500_000_000L

    private val sequence = AtomicLong(0L)
    private val state = AtomicReference(
        EndXrInputSnapshot(
            sequence = 0L,
            updatedAtElapsedRealtimeNanos = 0L,
            left = EndXrControllerSample.EMPTY,
            right = EndXrControllerSample.EMPTY,
            stale = true,
            headOrientation = EndXrHeadOrientationSample.EMPTY,
            headOrientationUpdatedAtElapsedRealtimeNanos = 0L,
            headOrientationStale = true,
            leftHandPose = EndXrHandPoseSample.EMPTY,
            rightHandPose = EndXrHandPoseSample.EMPTY,
            leftHandJoints = EndXrHandJointSample.EMPTY,
            rightHandJoints = EndXrHandJointSample.EMPTY,
            handPoseUpdatedAtElapsedRealtimeNanos = 0L,
            handPoseStale = true
        )
    )

    private inline fun update(transform: (EndXrInputSnapshot) -> EndXrInputSnapshot) {
        while (true) {
            val current = state.get()
            val next = transform(current).copy(sequence = sequence.incrementAndGet())
            if (state.compareAndSet(current, next)) return
        }
    }

    fun publish(
        leftRow: FloatArray,
        rightRow: FloatArray,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val left = EndXrControllerSample.fromNativeRow(leftRow)
        val right = EndXrControllerSample.fromNativeRow(rightRow)
        update { current ->
            current.copy(
                updatedAtElapsedRealtimeNanos = timestamp,
                left = left,
                right = right,
                stale = false
            )
        }
    }

    fun publishHandPoses(
        left: EndXrHandPoseSample,
        right: EndXrHandPoseSample,
        leftJoints: EndXrHandJointSample = EndXrHandJointSample.EMPTY,
        rightJoints: EndXrHandJointSample = EndXrHandJointSample.EMPTY,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        update { current ->
            current.copy(
                leftHandPose = left,
                rightHandPose = right,
                leftHandJoints = if (left.active) leftJoints else EndXrHandJointSample.EMPTY,
                rightHandJoints = if (right.active) rightJoints else EndXrHandJointSample.EMPTY,
                handPoseUpdatedAtElapsedRealtimeNanos = timestamp,
                handPoseStale = false
            )
        }
    }

    fun publishTrackingFrame(
        leftRow: FloatArray,
        rightRow: FloatArray,
        leftPose: EndXrHandPoseSample,
        rightPose: EndXrHandPoseSample,
        leftJoints: EndXrHandJointSample,
        rightJoints: EndXrHandJointSample,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val left = EndXrControllerSample.fromNativeRow(leftRow)
        val right = EndXrControllerSample.fromNativeRow(rightRow)
        update { current ->
            current.copy(
                updatedAtElapsedRealtimeNanos = timestamp,
                left = left,
                right = right,
                stale = false,
                leftHandPose = leftPose,
                rightHandPose = rightPose,
                leftHandJoints = if (leftPose.active) leftJoints else EndXrHandJointSample.EMPTY,
                rightHandJoints = if (rightPose.active) rightJoints else EndXrHandJointSample.EMPTY,
                handPoseUpdatedAtElapsedRealtimeNanos = timestamp,
                handPoseStale = false
            )
        }
    }

    fun publishHeadOrientation(
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val orientation = EndXrHeadOrientationSample.fromQuaternion(x, y, z, w)
        update { current ->
            current.copy(
                headOrientation = orientation,
                headOrientationUpdatedAtElapsedRealtimeNanos = timestamp,
                headOrientationStale = !orientation.active
            )
        }
    }

    fun clear(nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        val timestamp = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        update {
            it.copy(
                updatedAtElapsedRealtimeNanos = timestamp,
                left = EndXrControllerSample.EMPTY,
                right = EndXrControllerSample.EMPTY,
                stale = false,
                headOrientation = EndXrHeadOrientationSample.EMPTY,
                headOrientationUpdatedAtElapsedRealtimeNanos = timestamp,
                headOrientationStale = true,
                leftHandPose = EndXrHandPoseSample.EMPTY,
                rightHandPose = EndXrHandPoseSample.EMPTY,
                leftHandJoints = EndXrHandJointSample.EMPTY,
                rightHandJoints = EndXrHandJointSample.EMPTY,
                handPoseUpdatedAtElapsedRealtimeNanos = timestamp,
                handPoseStale = true
            )
        }
    }

    fun snapshotForIpc(
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): EndXrInputSnapshot {
        val current = state.get()
        val now = nowElapsedRealtimeNanos.coerceAtLeast(0L)
        val controllerAge = (now - current.updatedAtElapsedRealtimeNanos).coerceAtLeast(0L)
        val headAge = (now - current.headOrientationUpdatedAtElapsedRealtimeNanos).coerceAtLeast(0L)
        val controllerStale = controllerAge > MAX_SAMPLE_AGE_NANOS
        val headStale = current.headOrientationStale ||
            headAge > MAX_HEAD_ORIENTATION_AGE_NANOS
        val poseAge = (now - current.handPoseUpdatedAtElapsedRealtimeNanos).coerceAtLeast(0L)
        val poseStale = current.handPoseStale || poseAge > MAX_SAMPLE_AGE_NANOS
        return current.copy(
            left = if (controllerStale) EndXrControllerSample.EMPTY else current.left,
            right = if (controllerStale) EndXrControllerSample.EMPTY else current.right,
            stale = controllerStale,
            headOrientation = if (headStale) EndXrHeadOrientationSample.EMPTY else current.headOrientation,
            headOrientationStale = headStale,
            leftHandPose = if (poseStale) EndXrHandPoseSample.EMPTY else current.leftHandPose,
            rightHandPose = if (poseStale) EndXrHandPoseSample.EMPTY else current.rightHandPose,
            leftHandJoints = if (poseStale) EndXrHandJointSample.EMPTY else current.leftHandJoints,
            rightHandJoints = if (poseStale) EndXrHandJointSample.EMPTY else current.rightHandJoints,
            handPoseStale = poseStale
        )
    }
}