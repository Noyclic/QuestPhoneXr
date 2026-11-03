package com.noyclic.questphonexr.runtime

import org.json.JSONObject
import java.util.Locale

/** Structured result from the isolated native OpenXR GLES probe. */
data class OpenXrProbeReport(
    val status: String,
    val xrResult: Int?,
    val message: String,
    val framesSubmitted: Long? = null,
    val sessionState: Int? = null,
    val poseSamples: Long? = null,
    val orientationValidSamples: Long? = null,
    val positionValidSamples: Long? = null,
    val fullTrackingSamples: Long? = null,
    val orientationTrackedSamples: Long? = null,
    val positionTrackedSamples: Long? = null,
    val fullTrackedSamples: Long? = null,
    val lastViewStateFlags: Long? = null,
    val maxHeadPositionDeltaMeters: Double? = null,
    val maxHeadRotationDeltaDegrees: Double? = null
) {
    val isSuccessful: Boolean
        get() = status == SUCCESS_STATUS ||
            status == FIRST_FRAME_SUCCESS_STATUS ||
            status == PERSISTENT_FRAME_SUCCESS_STATUS

    fun toDisplayText(): String = buildString {
        append(status)
        xrResult?.let { append(" · XrResult ").append(it) }
        framesSubmitted?.let { append(" · стереокадров ").append(it) }
        sessionState?.let {
            append("\nСостояние OpenXR: ").append(sessionStateLabel(it))
        }
        if (poseSamples != null) {
            append("\nОтслеживание головы: ориентация ")
                .append(orientationValidSamples ?: 0)
                .append("/")
                .append(poseSamples)
                .append(" · позиция ")
                .append(positionValidSamples ?: 0)
                .append("/")
                .append(poseSamples)
                .append(" · оба канала ")
                .append(fullTrackingSamples ?: 0)
                .append("/")
                .append(poseSamples)
        }
        if (poseSamples != null) {
            append("\nАктивное tracking: ориентация ")
                .append(orientationTrackedSamples ?: 0)
                .append("/")
                .append(poseSamples)
                .append(" · позиция ")
                .append(positionTrackedSamples ?: 0)
                .append("/")
                .append(poseSamples)
                .append(" · оба канала ")
                .append(fullTrackedSamples ?: 0)
                .append("/")
                .append(poseSamples)
        }
        maxHeadPositionDeltaMeters?.let {
            append("\nМаксимальное смещение головы: ")
                .append(String.format(Locale.US, "%.3f м", it))
        }
        maxHeadRotationDeltaDegrees?.let {
            append(" · поворот: ")
                .append(String.format(Locale.US, "%.1f°", it))
        }
        append("\n").append(message)
    }

    companion object {
        private fun JSONObject.optionalLong(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null

        private fun JSONObject.optionalInt(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        private fun JSONObject.optionalDouble(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

        private fun sessionStateLabel(state: Int): String = when (state) {
            1 -> "IDLE"
            2 -> "READY"
            3 -> "SYNCHRONIZED"
            4 -> "VISIBLE"
            5 -> "FOCUSED"
            6 -> "STOPPING"
            7 -> "LOSS_PENDING"
            8 -> "EXITING"
            else -> "UNKNOWN ($state)"
        }

        const val SUCCESS_STATUS = "SUSTAINED_FRAMES_SUBMITTED_GLES"
        const val FIRST_FRAME_SUCCESS_STATUS = "FIRST_FRAME_SUBMITTED_GLES"
        const val PERSISTENT_FRAME_SUCCESS_STATUS = "FRAME_SUBMITTED"

        fun parse(json: String): OpenXrProbeReport = runCatching {
            val root = JSONObject(json)
            OpenXrProbeReport(
                status = root.optString("status", "UNKNOWN").ifBlank { "UNKNOWN" },
                xrResult = if (root.has("xrResult") && !root.isNull("xrResult")) {
                    root.optInt("xrResult")
                } else {
                    null
                },
                message = root.optString("message", "No diagnostic message."),
                framesSubmitted = root.optionalLong("framesSubmitted"),
                sessionState = root.optionalInt("sessionState"),
                poseSamples = root.optionalLong("poseSamples"),
                orientationValidSamples = root.optionalLong("orientationValidSamples"),
                positionValidSamples = root.optionalLong("positionValidSamples"),
                fullTrackingSamples = root.optionalLong("fullTrackingSamples"),
                orientationTrackedSamples = root.optionalLong("orientationTrackedSamples"),
                positionTrackedSamples = root.optionalLong("positionTrackedSamples"),
                fullTrackedSamples = root.optionalLong("fullTrackedSamples"),
                lastViewStateFlags = root.optionalLong("lastViewStateFlags"),
                maxHeadPositionDeltaMeters = root.optionalDouble("maxHeadPositionDeltaMeters"),
                maxHeadRotationDeltaDegrees = root.optionalDouble("maxHeadRotationDeltaDegrees")
            )
        }.getOrElse { error ->
            OpenXrProbeReport(
                status = "INVALID_REPORT",
                xrResult = null,
                message = "Native OpenXR probe returned invalid JSON: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }
}