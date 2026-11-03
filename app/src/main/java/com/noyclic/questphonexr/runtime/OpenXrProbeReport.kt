package com.noyclic.questphonexr.runtime

import org.json.JSONObject

/** Structured result from the isolated native OpenXR GLES probe. */
data class OpenXrProbeReport(
    val status: String,
    val xrResult: Int?,
    val message: String,
    val framesSubmitted: Long? = null
) {
    val isSuccessful: Boolean
        get() = status == SUCCESS_STATUS ||
            status == FIRST_FRAME_SUCCESS_STATUS ||
            status == PERSISTENT_FRAME_SUCCESS_STATUS

    fun toDisplayText(): String = buildString {
        append(status)
        xrResult?.let { append(" · XrResult ").append(it) }
        framesSubmitted?.let { append(" · стереокадров ").append(it) }
        append("\n").append(message)
    }

    companion object {
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
                framesSubmitted = if (root.has("framesSubmitted") && !root.isNull("framesSubmitted")) {
                    root.optLong("framesSubmitted")
                } else {
                    null
                }
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